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
internal data class AgentAction(
    val action: String,
    val command: String? = null,
    val description: String? = null,
    val reason: String? = null,
    val steps: List<PlanStep>? = null,
    val summary: String? = null,
    val changed_files: List<String>? = null
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

    @Volatile private var pendingConfirmCommand: String? = null
    private var loopJob: Job? = null

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

        loopJob = scope.launch {
            val task = TaskEntity(goal = goal, criteriaJson = json.encodeToString(criteria))
            val taskId = db.taskDao().insert(task)
            _uiState.value = AgentUiState.Planning(taskId)
            say(ChatRole.SYSTEM, "正在制定执行计划...")
            val startedAt = System.currentTimeMillis()

            val messages = mutableListOf(
                ChatMessage("system", Prompts.SYSTEM),
                ChatMessage("user", Prompts.userTask(goal, criteria, channelDescProvider()))
            )

            var plan: Plan? = null
            var planRetried = false
            var iteration = 0
            var stepCounter = 0

            while (iteration < maxIterations) {
                iteration++
                db.taskDao().byId(taskId)?.let { db.taskDao().update(it.copy(iterations = iteration)) }

                var fullText = ""
                var llmError: String? = null
                llm.chat(messages).collect { ev ->
                    when (ev) {
                        is LlmEvent.Completed -> fullText = ev.fullText
                        is LlmEvent.Failed -> llmError = ev.error
                        is LlmEvent.Delta -> Unit
                    }
                }
                if (llmError != null) {
                    failTask(taskId, "模型调用失败: $llmError")
                    return@launch
                }

                val actionObj = parseAction(fullText)
                if (actionObj == null) {
                    if (!planRetried && plan == null) {
                        planRetried = true
                        messages += ChatMessage("assistant", fullText)
                        messages += ChatMessage("user", "JSON 解析失败。请只输出一个符合格式的 JSON 对象。")
                        continue
                    }
                    failTask(taskId, "模型响应无法解析: ${fullText.take(200)}")
                    return@launch
                }

                val action = actionObj!!
                when (action.action) {
                    "plan" -> {
                        val p = Plan(action.steps ?: emptyList())
                        if (p.steps.isEmpty()) { failTask(taskId, "计划为空"); return@launch }
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
                        val cmd = action.command ?: run { failTask(taskId, "动作缺少 command 字段"); return@launch }
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
                            if (_uiState.value is AgentUiState.Stopped || _uiState.value is AgentUiState.Failed) return@launch
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
                    }
                    "done" -> {
                        say(ChatRole.AI, "任务完成: ${action.summary ?: ""}")
                        finishTask(taskId, action.summary ?: "任务完成", action.changed_files ?: emptyList(), startedAt, channel.level)
                        return@launch
                    }
                    "abort" -> {
                        say(ChatRole.AI, "主动中止: ${action.reason ?: "未说明"}")
                        stopTask(taskId, "AI 主动中止: ${action.reason ?: "未说明"}")
                        return@launch
                    }
                    else -> {
                        failTask(taskId, "未知动作类型: ${action.action}")
                        return@launch
                    }
                }
            }
            db.taskDao().byId(taskId)?.let { t -> db.taskDao().update(t.copy(status = TaskStatus.PAUSED_LIMIT)) }
            _uiState.value = AgentUiState.PausedLimit
        }
    }

    private val chatHistory = mutableListOf<ChatMessage>()

    fun chat(message: String) {
        if (loopJob?.isActive == true) {
            say(ChatRole.SYSTEM, "有任务正在执行中，请先等待完成或点击停止")
            return
        }
        say(ChatRole.USER, message)

        loopJob = scope.launch {
            if (chatHistory.isEmpty()) {
                chatHistory += ChatMessage("system", Prompts.SYSTEM_CHAT)
            }
            chatHistory += ChatMessage("user", message)

            var turns = 0
            while (turns < CHAT_MAX_TURNS) {
                turns++

                var fullText = ""
                var llmError: String? = null
                llm.chat(chatHistory).collect { ev ->
                    when (ev) {
                        is LlmEvent.Completed -> fullText = ev.fullText
                        is LlmEvent.Failed -> llmError = ev.error
                        is LlmEvent.Delta -> Unit
                    }
                }
                if (llmError != null) {
                    say(ChatRole.SYSTEM, "模型调用失败: $llmError")
                    return@launch
                }

                val actionObj = parseAction(fullText)
                if (actionObj == null) {
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
                        _uiState.value = AgentUiState.Idle
                    }
                    "done" -> {
                        chatHistory += ChatMessage("assistant", fullText)
                        say(ChatRole.AI, actionObj.summary ?: "完成")
                        _uiState.value = AgentUiState.Idle
                        return@launch
                    }
                    else -> {
                        chatHistory += ChatMessage("assistant", fullText)
                        say(ChatRole.AI, fullText.trim().take(2000))
                        _uiState.value = AgentUiState.Idle
                        return@launch
                    }
                }
            }
            say(ChatRole.SYSTEM, "本轮连续操作步数已达上限，已回到对话状态")
            _uiState.value = AgentUiState.Idle
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
        loopJob?.cancel()
        loopJob = null
        confirmChannel.trySend(false)
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
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(text)?.groupValues?.get(1)?.trim() ?: text
        val start = fenced.indexOf('{')
        val end = fenced.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.decodeFromString<AgentAction>(fenced.substring(start, end + 1)) }.getOrNull()
    }

    companion object {
        const val COMMAND_TIMEOUT_MS = 120_000L
        const val CONFIRM_TIMEOUT_MS = 10 * 60_000L
        const val CHAT_MAX_TURNS = 15
    }
}
