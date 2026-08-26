package dev.autopilot.terminal.agent

import dev.autopilot.terminal.data.ChannelLevel
import dev.autopilot.terminal.data.TaskEntity
import dev.autopilot.terminal.data.TaskStatus
import dev.autopilot.terminal.data.AuditEntryEntity
import dev.autopilot.terminal.data.AppDatabase
import dev.autopilot.terminal.llm.ChatMessage
import dev.autopilot.terminal.llm.LlmEvent
import dev.autopilot.terminal.llm.LlmClient
import dev.autopilot.terminal.perms.CommandChannel
import dev.autopilot.terminal.perms.CommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed class AgentUiState {
    data object Idle : AgentUiState()
    data class Planning(val taskId: Long) : AgentUiState()
    data class Executing(
        val taskId: Long,
        val stepIndex: Int,
        val totalSteps: Int,
        val command: String,
        val iteration: Int
    ) : AgentUiState()
    data class AwaitConfirm(val taskId: Long, val command: String, val reason: String) : AgentUiState()
    data object PausedLimit : AgentUiState()
    data class Done(val summary: String, val changedFiles: List<String>, val elapsedMs: Long, val degraded: Boolean) : AgentUiState()
    data class Stopped(val message: String) : AgentUiState()
    data class Failed(val reason: String) : AgentUiState()
}

enum class ChatRole { USER, AI, CMD, OUTPUT, SYSTEM }

data class ChatEntry(
    val role: ChatRole,
    val text: String,
    val ts: Long = System.currentTimeMillis()
)

@Serializable
internal data class ActionTodoItem(val text: String, val done: Boolean = false)

@Serializable
internal data class AgentAction(
    val action: String,
    val command: String? = null,
    val commands: List<String>? = null,
    val description: String? = null,
    val reason: String? = null,
    val seconds: Long? = null,
    val steps: List<PlanStep>? = null,
    val summary: String? = null,
    val changed_files: List<String>? = null,
    val items: List<ActionTodoItem>? = null
)

class AgentEngine(
    private val scope: CoroutineScope,
    private val llm: LlmClient,
    private val db: AppDatabase,
    private val channelProvider: () -> CommandChannel?,
    private val channelDescProvider: () -> String
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val planParser = PlanParser()

    private val _uiState = MutableStateFlow<AgentUiState>(AgentUiState.Idle)
    val uiState: StateFlow<AgentUiState> = _uiState

    private val _chat = MutableStateFlow<List<ChatEntry>>(emptyList())
    val chat: StateFlow<List<ChatEntry>> = _chat

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    data class TodoItem(val text: String, val done: Boolean)
    private val _todos = MutableStateFlow<List<TodoItem>>(emptyList())
    val todos: StateFlow<List<TodoItem>> = _todos

    @Volatile var memoryProvider: () -> String = { "" }

    @Volatile private var pendingConfirmCommand: String? = null
    private var loopJob: Job? = null

    private fun systemMessage(base: String): ChatMessage {
        val extra = buildString {
            val memory = runCatching { memoryProvider() }.getOrDefault("")
            if (memory.isNotBlank()) append("\n\n").append(memory)
        }
        return ChatMessage("system", base + extra)
    }

    private fun say(role: ChatRole, text: String) {
        _chat.value = (_chat.value + ChatEntry(role, text)).takeLast(300)
    }

    fun submit(goal: String, criteria: List<String>, maxIterations: Int) {
        if (loopJob?.isActive == true) {
            say(ChatRole.SYSTEM, "有任务正在执行中，请先等待完成或点击停止")
            return
        }
        val channel = channelProvider() ?: run {
            _uiState.value = AgentUiState.Failed("终端会话未就绪，请先完成环境安装")
            say(ChatRole.SYSTEM, "终端环境未就绪，任务未启动")
            return
        }
        say(ChatRole.USER, goal)
        _busy.value = true

        loopJob = scope.launch {
            try {
                val task = TaskEntity(goal = goal, criteriaJson = json.encodeToString(criteria))
                val taskId = db.taskDao().insert(task)
                val startedAt = System.currentTimeMillis()
                runAgentLoop(taskId, goal, criteria, channel, maxIterations, startedAt)
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                say(ChatRole.SYSTEM, "任务异常: ${t.message ?: t.javaClass.simpleName}")
                _uiState.value = AgentUiState.Failed(t.message ?: "未知错误")
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun runAgentLoop(
        taskId: Long,
        goal: String,
        criteria: List<String>,
        channel: CommandChannel,
        maxIterations: Int,
        startedAt: Long
    ) {
        _uiState.value = AgentUiState.Planning(taskId)
        say(ChatRole.SYSTEM, "正在制定执行计划...")

            val messages = mutableListOf(
                systemMessage(Prompts.SYSTEM),
                ChatMessage("user", Prompts.userTask(goal, criteria, channelDescProvider()))
            )

            var plan: Plan? = null
            var formatRetried = false
            var iteration = 0
            var stepCounter = 0
            var lastFailCmd: String? = null
            var failStreak = 0

            while (iteration < maxIterations) {
                iteration++
                db.taskDao().byId(taskId)?.let { db.taskDao().update(it.copy(iterations = iteration)) }
                compactIfNeeded(messages, systemCount = 2)
                trimWindow(messages, systemCount = 2, keep = WINDOW_KEEP)

                val (fullText0, llmError) = awaitLlm(messages)
                if (llmError != null) {
                    failTask(taskId, "模型调用失败: $llmError")
                    return
                }
                val fullText = fullText0

                val actionObj = parseAction(fullText)
                if (actionObj == null) {
                    if (!formatRetried) {
                        formatRetried = true
                        messages += ChatMessage("assistant", fullText)
                        messages += ChatMessage(
                            "user",
                            "你的上一条输出无法解析为动作。请只输出一个合法的 JSON 动作对象 (execute/batch/repair/todo/done), 不要附加任何说明文字或多余 JSON, 继续当前任务。"
                        )
                        continue
                    }
                    failTask(taskId, "模型响应无法解析: ${fullText.take(200)}")
                    return
                }
                formatRetried = false

                val action = actionObj!!
                when (action.action) {
                    "plan" -> {
                        val p = Plan(action.steps ?: emptyList())
                        if (p.steps.isEmpty()) { failTask(taskId, "计划为空"); return }
                        plan = p
                        _uiState.value = AgentUiState.Executing(taskId, 0, p.steps.size, "", iteration)
                        say(ChatRole.AI, "已制定 ${p.steps.size} 步执行计划:")
                        p.steps.take(8).forEachIndexed { i, s ->
                            say(ChatRole.AI, "${i + 1}. ${s.description.ifBlank { s.command }}")
                        }
                        if (p.steps.size > 8) say(ChatRole.AI, "... 共 ${p.steps.size} 步")
                        messages += ChatMessage("assistant", fullText)
                    }
                    "execute", "repair" -> {
                        val cmd = action.command ?: run { failTask(taskId, "动作缺少 command 字段"); return }
                        messages += ChatMessage("assistant", fullText)
                        say(
                            if (action.action == "repair") ChatRole.AI else ChatRole.CMD,
                            if (action.action == "repair")
                                "修复: $cmd\n原因: ${action.reason ?: ""}"
                            else "\$ $cmd"
                        )

                        val verdict = RiskFilter.evaluate(cmd)
                        if (verdict is RiskFilter.Verdict.Confirm) {
                            pendingConfirmCommand = cmd
                            db.auditDao().insert(AuditEntryEntity(taskId = taskId, channelLevel = channel.level, command = cmd, exitCode = null))
                            _uiState.value = AgentUiState.AwaitConfirm(taskId, cmd, verdict.reason)
                            awaitConfirmDecision()
                            if (_uiState.value is AgentUiState.Stopped || _uiState.value is AgentUiState.Failed) return
                            pendingConfirmCommand = null
                        }

                        stepCounter++
                        _uiState.value = AgentUiState.Executing(
                            taskId, stepCounter - 1, plan?.steps?.size ?: stepCounter, cmd, iteration
                        )
                        val result = channel.exec(cmd, COMMAND_TIMEOUT_MS)
                        db.auditDao().insert(
                            AuditEntryEntity(taskId = taskId, channelLevel = channel.level, command = cmd, exitCode = result.exitCode)
                        )
                        say(
                            ChatRole.OUTPUT,
                            "[exit=${result.exitCode ?: "超时"}] ${result.output.take(600)}"
                        )
                        messages += Prompts.observation(stepCounter - 1, cmd, result.exitCode, result.output)
                        if (result.exitCode != null && result.exitCode != 0) {
                            failStreak = if (lastFailCmd == cmd) failStreak + 1 else 1
                            lastFailCmd = cmd
                        } else {
                            lastFailCmd = null
                            failStreak = 0
                        }
                        if (failStreak >= 2) {
                            messages += ChatMessage(
                                "user",
                                "同一命令已连续失败 $failStreak 次。禁止原样重试: 先用诊断命令定位根因 (查看完整错误、检查依赖与路径), 再换一种方法或修复环境后继续。"
                            )
                        }
                    }
                    "todo" -> {
                        _todos.value = (action.items ?: emptyList()).map { TodoItem(it.text, it.done) }
                        val doneN = _todos.value.count { it.done }
                        say(ChatRole.AI, "任务清单更新: ${doneN}/${_todos.value.size} 已完成")
                        messages += ChatMessage("assistant", fullText)
                    }
                    "done" -> {
                        say(ChatRole.AI, "任务完成: ${action.summary ?: ""}")
                        finishTask(taskId, action.summary ?: "任务完成", action.changed_files ?: emptyList(), startedAt, channel.level)
                        return
                    }
                    "abort" -> {
                        say(ChatRole.AI, "主动中止: ${action.reason ?: "未说明"}")
                        stopTask(taskId, "AI 主动中止: ${action.reason ?: "未说明"}")
                        return
                    }
                    "wait" -> {
                        val sec = (action.seconds ?: 5L).coerceIn(1L, 60L)
                        say(ChatRole.SYSTEM, "等待 ${sec}s...")
                        delay(sec * 1000)
                        messages += ChatMessage("assistant", fullText)
                        messages += ChatMessage("user", "已等待 ${sec}秒。请查看后台任务状态 (joblog) 或继续下一步动作。")
                    }
                    else -> {
                        if (!formatRetried) {
                            formatRetried = true
                            messages += ChatMessage("assistant", fullText)
                            messages += ChatMessage(
                                "user",
                                "动作 \"${action.action}\" 暂不支持。标准动作: plan/execute/batch/repair/todo/wait/done/abort。想执行 shell 就用 execute 或 batch, 然后继续任务。"
                            )
                        } else {
                            formatRetried = false
                            messages += ChatMessage("user", "请用标准动作 (execute/batch/done) 继续, 不要自创动作名。")
                        }
                    }
                }
            }
            db.taskDao().byId(taskId)?.let { t -> db.taskDao().update(t.copy(status = TaskStatus.PAUSED_LIMIT)) }
            _uiState.value = AgentUiState.PausedLimit
    }

    private val chatHistory = mutableListOf<ChatMessage>()

    fun chat(message: String) {
        if (loopJob?.isActive == true) {
            say(ChatRole.SYSTEM, "有任务正在执行中，请先等待完成或点击停止")
            return
        }
        say(ChatRole.USER, message)
        _busy.value = true

        loopJob = scope.launch {
            try {
                if (chatHistory.isEmpty()) {
                    chatHistory += systemMessage(Prompts.SYSTEM_CHAT)
                } else if (chatHistory.first().role == "system") {
                    chatHistory[0] = systemMessage(Prompts.SYSTEM_CHAT)
                }
                chatHistory += ChatMessage("user", message)

                var turns = 0
                var lastFailCmd: String? = null
                var failStreak = 0
                while (turns < CHAT_MAX_TURNS) {
                    turns++
                    compactIfNeeded(chatHistory, systemCount = 1)
                    trimWindow(chatHistory, systemCount = 1, keep = CHAT_WINDOW_KEEP)

                    val (fullText, llmError) = awaitLlm(chatHistory)
                    if (llmError != null) {
                        say(ChatRole.SYSTEM, "模型调用失败: $llmError")
                        _uiState.value = AgentUiState.Idle
                        return@launch
                    }

                    val actionObj = parseAction(fullText)
                    if (actionObj == null) {
                        val looksLikeBrokenJson =
                            fullText.contains("\"action\"") || fullText.contains("{\"") || fullText.contains("```json")
                        if (looksLikeBrokenJson) {
                            chatHistory += ChatMessage("assistant", fullText)
                            chatHistory += ChatMessage(
                                "user",
                                "你的输出包含不完整的动作 JSON。请只重新输出一个完整合法的 JSON 动作对象, 不附加任何说明文字, 继续执行。"
                            )
                            continue
                        }
                        chatHistory += ChatMessage("assistant", fullText)
                        say(ChatRole.AI, fullText.trim().take(2000))
                        _uiState.value = AgentUiState.Idle
                        return@launch
                    }

                    when (actionObj.action) {
                        "execute", "repair" -> {
                            val cmd = actionObj.command
                            if (cmd.isNullOrBlank()) {
                                chatHistory += ChatMessage("assistant", fullText)
                                say(ChatRole.AI, fullText.trim().take(2000))
                                _uiState.value = AgentUiState.Idle
                                return@launch
                            }
                            chatHistory += ChatMessage("assistant", fullText)
                            val desc = actionObj.description?.take(80) ?: ""
                            say(ChatRole.CMD, "$cmd" + if (desc.isNotBlank()) "\n# $desc" else "")

                            val channel = channelProvider()
                            if (channel == null) {
                                say(ChatRole.SYSTEM, "终端环境未就绪，无法执行命令")
                                return@launch
                            }
                            val verdict = RiskFilter.evaluate(cmd)
                            if (verdict is RiskFilter.Verdict.Confirm) {
                                pendingConfirmCommand = cmd
                                db.auditDao().insert(AuditEntryEntity(taskId = 0L, channelLevel = channel.level, command = cmd, exitCode = null))
                                _uiState.value = AgentUiState.AwaitConfirm(0L, cmd, verdict.reason)
                                awaitConfirmDecision()
                                pendingConfirmCommand = null
                                if (_uiState.value is AgentUiState.Stopped || _uiState.value is AgentUiState.Failed) return@launch
                            }

                            _uiState.value = AgentUiState.Executing(0L, 0, 1, cmd, 0)
                            val result = channel.exec(cmd, COMMAND_TIMEOUT_MS)
                            db.auditDao().insert(
                                AuditEntryEntity(taskId = 0L, channelLevel = channel.level, command = cmd, exitCode = result.exitCode)
                            )
                            say(ChatRole.OUTPUT, "[exit=${result.exitCode ?: "超时"}] ${result.output.take(600)}")
                            chatHistory += Prompts.observation(turns, cmd, result.exitCode, result.output)
                            if (result.exitCode != null && result.exitCode != 0) {
                                failStreak = if (lastFailCmd == cmd) failStreak + 1 else 1
                                lastFailCmd = cmd
                            } else {
                                lastFailCmd = null
                                failStreak = 0
                            }
                            if (failStreak >= 2) {
                                chatHistory += ChatMessage(
                                    "user",
                                    "同一命令已连续失败 $failStreak 次。禁止原样重试: 先诊断根因再换方法。"
                                )
                            }
                            _uiState.value = AgentUiState.Idle
                        }
                    "batch" -> {
                        val cmds = actionObj.commands?.filter { it.isNotBlank() } ?: emptyList()
                        if (cmds.isEmpty()) {
                            chatHistory += ChatMessage("assistant", fullText)
                            say(ChatRole.AI, fullText.trim().take(2000))
                            _uiState.value = AgentUiState.Idle
                            return@launch
                        }
                        chatHistory += ChatMessage("assistant", fullText)
                        say(ChatRole.CMD, cmds.joinToString("\n") { "\$ $it" })
                        val channel = channelProvider()
                        if (channel == null) {
                            say(ChatRole.SYSTEM, "终端环境未就绪，无法执行命令")
                            return@launch
                        }
                        var combined = ""
                        var lastExit: Int? = 0
                        for ((i, c) in cmds.withIndex()) {
                            val verdict = RiskFilter.evaluate(c)
                            if (verdict is RiskFilter.Verdict.Confirm) {
                                pendingConfirmCommand = c
                                db.auditDao().insert(AuditEntryEntity(taskId = 0L, channelLevel = channel.level, command = c, exitCode = null))
                                _uiState.value = AgentUiState.AwaitConfirm(0L, c, verdict.reason)
                                awaitConfirmDecision()
                                pendingConfirmCommand = null
                                if (_uiState.value is AgentUiState.Stopped || _uiState.value is AgentUiState.Failed) return@launch
                            }
                            _uiState.value = AgentUiState.Executing(0L, i, cmds.size, c, turns)
                            val r = channel.exec(c, COMMAND_TIMEOUT_MS)
                            db.auditDao().insert(AuditEntryEntity(taskId = 0L, channelLevel = channel.level, command = c, exitCode = r.exitCode))
                            lastExit = r.exitCode
                            combined += "[${i + 1}/${cmds.size} exit=${r.exitCode ?: "超时"}] \$ ${c.take(80)}\n${r.output.take(400)}\n"
                            if (r.exitCode != null && r.exitCode != 0) break
                        }
                        say(ChatRole.OUTPUT, combined.take(1400))
                        chatHistory += Prompts.observation(turns, actionObj.description ?: "batch", lastExit, combined.take(3200))
                        _uiState.value = AgentUiState.Idle
                    }
                    "todo" -> {
                        chatHistory += ChatMessage("assistant", fullText)
                        _todos.value = (actionObj.items ?: emptyList()).map { TodoItem(it.text, it.done) }
                        val doneN = _todos.value.count { it.done }
                        say(ChatRole.AI, "任务清单更新: ${doneN}/${_todos.value.size} 已完成")
                    }
                        "wait" -> {
                            val sec = (actionObj.seconds ?: 5L).coerceIn(1L, 60L)
                            say(ChatRole.SYSTEM, "等待 ${sec}s...")
                            delay(sec * 1000)
                            chatHistory += ChatMessage("assistant", fullText)
                            chatHistory += ChatMessage("user", "已等待 ${sec}秒。请继续: 查看 joblog 或执行下一步。")
                        }
                        "done" -> {
                            chatHistory += ChatMessage("assistant", fullText)
                            say(ChatRole.AI, actionObj.summary ?: "完成")
                            _uiState.value = AgentUiState.Idle
                            return@launch
                        }
                        else -> {
                            chatHistory += ChatMessage("assistant", fullText)
                            chatHistory += ChatMessage(
                                "user",
                                "动作 \"${actionObj.action}\" 暂不支持。标准动作: execute/batch/todo/wait/done。想执行命令就输出 execute 或 batch JSON, 不要用其他动作名。"
                            )
                            continue
                        }
                    }
                }
                say(ChatRole.SYSTEM, "本轮连续操作步数已达上限，已回到对话状态")
                _uiState.value = AgentUiState.Idle
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                say(ChatRole.SYSTEM, "对话处理异常: ${t.message ?: t.javaClass.simpleName}")
                _uiState.value = AgentUiState.Idle
            } finally {
                _busy.value = false
            }
        }
    }

    fun clearChat() {
        chatHistory.clear()
        _chat.value = emptyList()
    }

    private suspend fun awaitConfirmDecision() {
        val decision = kotlinx.coroutines.withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { confirmChannel.receive() } ?: false
        if (!decision) {
            _uiState.value = AgentUiState.Stopped("用户拒绝了高危命令执行")
        }
    }

    private val confirmChannel = kotlinx.coroutines.channels.Channel<Boolean>(capacity = 1)

    fun confirm() {
        pendingConfirmCommand = null
        confirmChannel.trySend(true)
    }

    fun reject() {
        pendingConfirmCommand = null
        confirmChannel.trySend(false)
    }

    fun stop(reason: String = "用户手动停止") {
        _busy.value = false
        loopJob?.cancel()
        loopJob = null
        confirmChannel.trySend(false)
        runCatching { channelProvider()?.killCurrent() }
        say(ChatRole.SYSTEM, "已停止: $reason")
        _uiState.value = AgentUiState.Stopped(reason)
    }

    fun reset() {
        loopJob?.cancel()
        loopJob = null
        pendingConfirmCommand = null
        _uiState.value = AgentUiState.Idle
    }

    private suspend fun finishTask(taskId: Long, summary: String, files: List<String>, startedAt: Long, level: ChannelLevel) {
        db.taskDao().byId(taskId)?.let { t ->
            db.taskDao().update(
                t.copy(
                    status = TaskStatus.DONE,
                    reportSummary = summary,
                    changedFiles = json.encodeToString(files),
                    finishedAt = System.currentTimeMillis(),
                    degraded = level == ChannelLevel.SANDBOX
                )
            )
        }
        _uiState.value = AgentUiState.Done(summary, files, System.currentTimeMillis() - startedAt, level == ChannelLevel.SANDBOX)
    }

    private suspend fun stopTask(taskId: Long, message: String) {
        say(ChatRole.SYSTEM, message)
        db.taskDao().byId(taskId)?.let { t ->
            db.taskDao().update(t.copy(status = TaskStatus.STOPPED, reportSummary = message, finishedAt = System.currentTimeMillis()))
        }
        _uiState.value = AgentUiState.Stopped(message)
    }

    private suspend fun failTask(taskId: Long, reason: String) {
        say(ChatRole.SYSTEM, "任务失败: $reason")
        db.taskDao().byId(taskId)?.let { t ->
            db.taskDao().update(t.copy(status = TaskStatus.FAILED, reportSummary = reason, finishedAt = System.currentTimeMillis()))
        }
        _uiState.value = AgentUiState.Failed(reason)
    }

    private fun parseAction(text: String): AgentAction? {
        val cleaned = Regex("```(?:json)?").replace(text, "").replace("```", "")
        var depth = 0
        var start = -1
        var inStr = false
        var esc = false
        for (i in cleaned.indices) {
            val ch = cleaned[i]
            if (inStr) {
                if (esc) esc = false
                else if (ch == '\\') esc = true
                else if (ch == '"') inStr = false
                continue
            }
            when (ch) {
                '"' -> inStr = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        val cand = cleaned.substring(start, i + 1)
                        val parsed = runCatching { json.decodeFromString<AgentAction>(cand) }.getOrNull()
                        if (parsed != null && parsed.action.isNotBlank()) return parsed
                        start = -1
                    }
                    if (depth < 0) depth = 0
                }
            }
        }
        return null
    }

    companion object {
        const val COMMAND_TIMEOUT_MS = 120_000L
        const val CONFIRM_TIMEOUT_MS = 10 * 60_000L
        const val CHAT_MAX_TURNS = 15
        const val LLM_TIMEOUT_MS = 120_000L
        private const val WINDOW_KEEP = 12
        private const val CHAT_WINDOW_KEEP = 14
        private const val COMPACT_THRESHOLD = 22
    }

    private fun trimWindow(messages: MutableList<ChatMessage>, systemCount: Int, keep: Int) {
        if (messages.size <= systemCount + keep) return
        val head = messages.take(systemCount)
        val tail = messages.takeLast(keep)
        messages.clear()
        messages.addAll(head + tail)
    }

    private suspend fun compactIfNeeded(messages: MutableList<ChatMessage>, systemCount: Int) {
        if (messages.size < COMPACT_THRESHOLD) return
        val keepTail = CHAT_WINDOW_KEEP / 2
        val middleEnd = messages.size - keepTail
        if (middleEnd - systemCount < 4) return
        val middle = messages.subList(systemCount, middleEnd).toList()
        say(ChatRole.SYSTEM, "对话较长, 正在压缩历史上下文...")
        val req = listOf(
            ChatMessage(
                "system",
                "你是对话压缩器。把给出的多轮历史压缩为要点摘要: 保留关键事实、文件路径、命令执行结果、已做决定与未完成事项。400 字以内, 直接输出摘要正文。"
            ),
            ChatMessage("user", middle.joinToString("\n\n") { m -> "[${m.role}] ${m.content.take(500)}" }.take(12000))
        )
        val (summary, err) = awaitLlm(req)
        if (!err.isNullOrEmpty() || summary.isBlank()) return
        val rebuilt = mutableListOf<ChatMessage>()
        rebuilt += messages.take(systemCount)
        rebuilt += ChatMessage("user", "[早前对话摘要]\n${summary.trim()}")
        rebuilt += ChatMessage("assistant", "已了解此前进展, 继续当前任务。")
        rebuilt += messages.takeLast(keepTail)
        messages.clear()
        messages.addAll(rebuilt)
        say(ChatRole.SYSTEM, "历史已压缩 (${middle.size} 条消息 → 摘要)")
    }

    private suspend fun awaitLlm(messages: List<ChatMessage>): Pair<String, String?> {
        var last: Pair<String, String?> = Pair("", "未知错误")
        repeat(2) { attempt ->
            val done = kotlinx.coroutines.withTimeoutOrNull(LLM_TIMEOUT_MS) {
                var text = ""
                var err: String? = null
                llm.chat(messages).collect { ev ->
                    when (ev) {
                        is LlmEvent.Completed -> text = ev.fullText
                        is LlmEvent.Failed -> err = ev.error
                        is LlmEvent.Delta -> Unit
                    }
                }
                Pair(text, err)
            }
            last = done ?: Pair("", "模型响应超时 (${LLM_TIMEOUT_MS / 1000}s)")
            if (last.second == null) return last
            if (attempt == 0) {
                say(ChatRole.SYSTEM, "模型响应异常 (${last.second?.take(60)}), 自动重试...")
                delay(600)
            }
        }
        return last
    }
}
