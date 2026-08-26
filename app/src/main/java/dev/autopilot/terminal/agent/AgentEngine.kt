package dev.autopilot.terminal.agent

import dev.autopilot.terminal.data.ChannelLevel
import dev.autopilot.terminal.data.TaskEntity
import dev.autopilot.terminal.data.TaskStatus
import dev.autopilot.terminal.data.AuditEntryEntity
import dev.autopilot.terminal.data.AppDatabase
import dev.autopilot.terminal.llm.ChatMessage
import dev.autopilot.terminal.llm.LlmEvent
import dev.autopilot.terminal.llm.LlmClient
import dev.autopilot.terminal.llm.ToolCall
import dev.autopilot.terminal.llm.ToolDefinition
import dev.autopilot.terminal.perms.CommandChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

sealed class AgentUiState {
    data object Idle : AgentUiState()
    data class Planning(val taskId: Long) : AgentUiState()
    data class Executing(
        val taskId: Long,
        val stepIndex: Int,
        val totalSteps: Int,
        val command: String,
        val iteration: Int,
        val toolName: String = ""
    ) : AgentUiState()
    data class AwaitConfirm(val taskId: Long, val command: String, val reason: String) : AgentUiState()
    data class Streaming(val taskId: Long, val iteration: Int) : AgentUiState()
    data object PausedLimit : AgentUiState()
    data class Done(val summary: String, val changedFiles: List<String>, val elapsedMs: Long, val degraded: Boolean) : AgentUiState()
    data class Stopped(val message: String) : AgentUiState()
    data class Failed(val reason: String) : AgentUiState()
}

enum class ChatRole { USER, AI, CMD, OUTPUT, SYSTEM, THINKING, TOOL_CALL }

data class ChatEntry(
    val role: ChatRole,
    val text: String,
    val ts: Long = System.currentTimeMillis(),
    val toolName: String? = null
)

@Serializable
internal data class ActionTodoItem(val text: String, val done: Boolean = false)

class AgentEngine(
    private val scope: CoroutineScope,
    private val llm: LlmClient,
    private val db: AppDatabase,
    private val channelProvider: () -> CommandChannel?,
    private val channelDescProvider: () -> String,
    private val workspaceRootProvider: () -> File = { File(System.getProperty("user.dir") ?: ".") }
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val planParser = PlanParser()

    private val _uiState = MutableStateFlow<AgentUiState>(AgentUiState.Idle)
    val uiState: StateFlow<AgentUiState> = _uiState

    private val _chat = MutableStateFlow<List<ChatEntry>>(emptyList())
    val chat: StateFlow<List<ChatEntry>> = _chat

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText

    private val _contextUsage = MutableStateFlow(0f)
    val contextUsage: StateFlow<Float> = _contextUsage

    data class SessionStats(
        val iterations: Int = 0,
        val totalTokens: Int = 0,
        val toolsCalled: Int = 0,
        val filesModified: Int = 0,
        val commandsRun: Int = 0
    )
    private val _sessionStats = MutableStateFlow(SessionStats())
    val sessionStats: StateFlow<SessionStats> = _sessionStats

    data class TodoItem(val text: String, val done: Boolean)
    private val _todos = MutableStateFlow<List<TodoItem>>(emptyList())
    val todos: StateFlow<List<TodoItem>> = _todos

    @Volatile var memoryProvider: () -> String = { "" }

    @Volatile private var pendingConfirmCommand: String? = null
    private var loopJob: Job? = null
    private val toolSchemas: List<ToolDefinition> = AgentTools.schemas()

    private fun systemMessage(base: String): ChatMessage {
        val extra = buildString {
            val memory = runCatching { memoryProvider() }.getOrDefault("")
            if (memory.isNotBlank()) append("\n\n").append(memory)
        }
        return ChatMessage("system", base + extra)
    }

    private fun say(role: ChatRole, text: String, toolName: String? = null) {
        _chat.value = (_chat.value + ChatEntry(role, text, System.currentTimeMillis(), toolName)).takeLast(500)
    }

    fun injectSystem(text: String) {
        say(ChatRole.SYSTEM, text)
    }

    fun submit(goal: String, criteria: List<String>, maxIterations: Int) {
        if (loopJob?.isActive == true) {
            say(ChatRole.SYSTEM, "有任务正在执行中，请先等待完成或点击停止")
            return
        }
        _sessionStats.value = SessionStats()
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
                _streamingText.value = ""
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

        val messages = mutableListOf(
            systemMessage(Prompts.SYSTEM),
            ChatMessage("user", Prompts.userTask(goal, criteria, channelDescProvider()))
        )

        var iteration = 0
        var stepCounter = 0
        var lastFailTool: String? = null
        var failStreak = 0

        while (iteration < maxIterations) {
            iteration++
            _sessionStats.value = _sessionStats.value.copy(iterations = iteration)
            db.taskDao().byId(taskId)?.let { db.taskDao().update(it.copy(iterations = iteration)) }
            compactIfNeeded(messages, systemCount = 2)
            trimWindow(messages, systemCount = 2, keep = WINDOW_KEEP)
            _contextUsage.value = estimateTokens(messages).toFloat() / (MAX_CONTEXT_CHARS / 4).toFloat().coerceAtLeast(1f)

            _uiState.value = AgentUiState.Streaming(taskId, iteration)
            val (fullText, toolCalls, llmError) = awaitLlm(messages)
            _streamingText.value = ""
            _sessionStats.value = _sessionStats.value.copy(
                totalTokens = _sessionStats.value.totalTokens + estimateTokens(messages)
            )

            if (llmError != null) {
                failTask(taskId, "模型调用失败: $llmError")
                return
            }

            messages += ChatMessage(
                role = "assistant",
                content = fullText,
                toolCalls = toolCalls
            )

            if (fullText.isNotBlank() && fullText.length > 10) {
                val role = if (toolCalls.isNotEmpty()) ChatRole.THINKING else ChatRole.AI
                say(role, fullText.trim().take(3000))
            }

            if (toolCalls.isEmpty()) {
                if (fullText.isNotBlank()) {
                    messages += ChatMessage(
                        "user",
                        "你已回复但没有调用任何工具。如果任务已完成，请调用 finish 工具。如果需要执行操作，请使用 execute/batch/read_file 等工具。如果需要更多信息，请说明。"
                    )
                    continue
                } else {
                    messages += ChatMessage("user", "请使用工具继续执行任务。")
                    continue
                }
            }

            val shouldStop = processToolCalls(
                toolCalls, messages, taskId, channel, stepCounter, iteration, startedAt,
                { stepCounter = it }, { lastFailTool = it }, { failStreak = it }
            )

            if (shouldStop == StopReason.FINISH) {
                val summary = extractFromArgs(toolCalls, AgentTools.FINISH, "summary") ?: "任务完成"
                val changedFiles = extractListFromArgs(toolCalls, AgentTools.FINISH, "changed_files")
                say(ChatRole.AI, "任务完成: $summary")
                finishTask(taskId, summary, changedFiles, startedAt, channel.level)
                return
            }
            if (shouldStop == StopReason.ABORT) {
                val reason = extractFromArgs(toolCalls, AgentTools.ABORT, "reason") ?: "未说明"
                say(ChatRole.AI, "主动中止: $reason")
                stopTask(taskId, "AI 主动中止: $reason")
                return
            }

            if (failStreak >= 2) {
                messages += ChatMessage(
                    "user",
                    "同一工具已连续失败 $failStreak 次。禁止原样重试: 先用诊断命令定位根因 (查看完整错误、检查依赖与路径), 再换一种方法或修复环境后继续。如果确实无法继续，调用 abort 说明原因。"
                )
            }
        }
        db.taskDao().byId(taskId)?.let { t -> db.taskDao().update(t.copy(status = TaskStatus.PAUSED_LIMIT)) }
        _uiState.value = AgentUiState.PausedLimit
    }

    private enum class StopReason { CONTINUE, FINISH, ABORT }

    private suspend fun processToolCalls(
        toolCalls: List<ToolCall>,
        messages: MutableList<ChatMessage>,
        taskId: Long,
        channel: CommandChannel,
        stepCounterInit: Int,
        iteration: Int,
        startedAt: Long,
        setStepCounter: (Int) -> Unit,
        setLastFailTool: (String?) -> Unit,
        setFailStreak: (Int) -> Unit
    ): StopReason {
        var stepCounter = stepCounterInit
        var lastFailTool: String? = null
        var failStreak = 0
        var anyError = false

        val todoCalls = toolCalls.filter { it.function == AgentTools.TODO }
        if (todoCalls.isNotEmpty()) {
            val firstTodo = todoCalls.first()
            val items = runCatching {
                json.parseToJsonElement(firstTodo.arguments)
                    .let { it as? kotlinx.serialization.json.JsonObject }
                    ?.get("items")
            }.getOrNull()
            if (items != null) {
                val todoItems = (items as kotlinx.serialization.json.JsonArray).mapNotNull { itemEl ->
                    val item = itemEl as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    val text = item["text"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content } ?: ""
                    val done = item["done"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content == "true" } ?: false
                    ActionTodoItem(text, done)
                }
                _todos.value = todoItems.map { TodoItem(it.text, it.done) }
                val doneN = _todos.value.count { it.done }
                say(ChatRole.AI, "任务清单: ${doneN}/${_todos.value.size} 已完成")
            }
            messages += ChatMessage("tool", "Todo updated.", toolCallId = firstTodo.id, name = AgentTools.TODO)
        }

        val actionCalls = toolCalls.filter {
            it.function !in listOf(AgentTools.TODO, AgentTools.FINISH, AgentTools.ABORT,
                AgentTools.SUBAGENT, AgentTools.LISTEN)
        }
        val subAgentCalls = toolCalls.filter { it.function == AgentTools.SUBAGENT }
        val listenCalls = toolCalls.filter { it.function == AgentTools.LISTEN }

        if (actionCalls.size + subAgentCalls.size + listenCalls.size > 1) {
            say(ChatRole.SYSTEM, "并行执行 ${actionCalls.size + subAgentCalls.size + listenCalls.size} 个工具...")
        }

        if (subAgentCalls.isNotEmpty()) {
            for (tc in subAgentCalls) {
                stepCounter++
                setStepCounter(stepCounter)
                val goal = extractFromArgsSingle(tc.arguments, "goal") ?: ""
                val maxIter = extractFromArgsSingle(tc.arguments, "max_iterations")?.toIntOrNull() ?: 15
                say(ChatRole.CMD, "subagent: ${goal.take(80)}", toolName = tc.function)
                _uiState.value = AgentUiState.Executing(taskId, stepCounter - 1, _todos.value.size.coerceAtLeast(stepCounter), goal.take(60), iteration, tc.function)
                val subResult = runSubAgent(goal, maxIter, channel)
                say(ChatRole.OUTPUT, subResult.take(1000), toolName = tc.function)
                messages += ChatMessage("tool", subResult, toolCallId = tc.id, name = tc.function)
            }
        }

        if (listenCalls.isNotEmpty()) {
            for (tc in listenCalls) {
                val msg = extractFromArgsSingle(tc.arguments, "message") ?: ""
                say(ChatRole.AI, msg)
                val userResponse = waitForUserInput()
                say(ChatRole.USER, userResponse)
                messages += ChatMessage("tool", "User response: $userResponse", toolCallId = tc.id, name = tc.function)
            }
        }

        val results: List<Pair<ToolCall, AgentTools.ToolResult>> = if (actionCalls.isNotEmpty()) {
            coroutineScope {
                actionCalls.map { tc ->
                    async {
                        val result = AgentTools.execute(
                            tc.function, tc.arguments, channel,
                            workspaceRootProvider(), COMMAND_TIMEOUT_MS
                        )
                        Pair<ToolCall, AgentTools.ToolResult>(tc, result)
                    }
                }.awaitAll()
            }
        } else emptyList()

        for ((tc, result) in results) {
            stepCounter++
            setStepCounter(stepCounter)

            val displayCmd = formatToolDisplay(tc.function, tc.arguments)
            say(ChatRole.CMD, displayCmd, toolName = tc.function)

            if (tc.function == AgentTools.EXECUTE || tc.function == AgentTools.BATCH) {
                val cmd = extractFromArgsSingle(tc.arguments, "command")
                    ?: extractFromArgsSingle(tc.arguments, "commands")
                    ?: tc.function
                val verdict = RiskFilter.evaluate(cmd)
                if (verdict is RiskFilter.Verdict.Confirm) {
                    pendingConfirmCommand = cmd
                    db.auditDao().insert(AuditEntryEntity(taskId = taskId, channelLevel = channel.level, command = cmd, exitCode = null))
                    _uiState.value = AgentUiState.AwaitConfirm(taskId, cmd, verdict.reason)
                    awaitConfirmDecision()
                    if (_uiState.value is AgentUiState.Stopped || _uiState.value is AgentUiState.Failed) return StopReason.ABORT
                    pendingConfirmCommand = null
                }
            }

            _uiState.value = AgentUiState.Executing(
                taskId, stepCounter - 1, _todos.value.size.coerceAtLeast(stepCounter),
                displayCmd.take(100), iteration, tc.function
            )

            if (tc.function == AgentTools.EXECUTE || tc.function == AgentTools.BATCH || tc.function == AgentTools.RUNBG || tc.function == AgentTools.JOBLOG) {
                val cmd = extractFromArgsSingle(tc.arguments, "command")
                    ?: extractFromArgsSingle(tc.arguments, "name")
                    ?: tc.function
                db.auditDao().insert(
                    AuditEntryEntity(taskId = taskId, channelLevel = channel.level, command = cmd, exitCode = result.exitCode)
                )
            }

            val outputDisplay = if (result.output.length > 1500) {
                result.output.take(700) + "\n\n[... 输出过长, 完整内容已发送给模型 ...]\n\n" + result.output.takeLast(300)
            } else {
                result.output
            }
            say(ChatRole.OUTPUT, outputDisplay, toolName = tc.function)

            val obs = Prompts.observation(tc.function, tc.arguments, result.output, result.isError, result.exitCode)
            messages += ChatMessage(
                role = "tool",
                content = obs.content,
                toolCallId = tc.id,
                name = tc.function
            )

            if (result.isError) {
                anyError = true
                failStreak = if (lastFailTool == tc.function) failStreak + 1 else 1
                lastFailTool = tc.function
            } else {
                lastFailTool = null
                failStreak = 0
            }
            setLastFailTool(lastFailTool)
            setFailStreak(failStreak)

            if (result.output == "__FINISH__") return StopReason.FINISH
            if (result.output == "__ABORT__") return StopReason.ABORT
        }

        val finishCall = toolCalls.firstOrNull { it.function == AgentTools.FINISH }
        if (finishCall != null) return StopReason.FINISH
        val abortCall = toolCalls.firstOrNull { it.function == AgentTools.ABORT }
        if (abortCall != null) return StopReason.ABORT

        return StopReason.CONTINUE
    }

    private fun formatToolDisplay(function: String, arguments: String): String {
        return try {
            val args = json.parseToJsonElement(arguments) as? kotlinx.serialization.json.JsonObject
            when (function) {
                AgentTools.EXECUTE -> "\$ ${args?.get("command")?.toString()?.trim('"') ?: ""}"
                AgentTools.BATCH -> {
                    val cmds = (args?.get("commands") as? kotlinx.serialization.json.JsonArray)
                        ?.map { it.toString().trim('"') } ?: emptyList()
                    cmds.joinToString("\n") { "\$ $it" }
                }
                AgentTools.READ_FILE -> "read ${args?.get("path")?.toString()?.trim('"') ?: ""}" +
                    (args?.get("offset")?.let { " from line ${it.toString().trim('"')}" } ?: "")
                AgentTools.WRITE_FILE -> "write ${args?.get("path")?.toString()?.trim('"') ?: ""}"
                AgentTools.EDIT_FILE -> "edit ${args?.get("path")?.toString()?.trim('"') ?: ""}"
                AgentTools.GLOB -> "glob ${args?.get("pattern")?.toString()?.trim('"') ?: ""}"
                AgentTools.GREP -> "grep ${args?.get("pattern")?.toString()?.trim('"') ?: ""}"
                AgentTools.RUNBG -> "bg ${args?.get("name")?.toString()?.trim('"') ?: ""}: ${args?.get("command")?.toString()?.trim('"')?.take(60) ?: ""}"
                AgentTools.JOBLOG -> "joblog ${args?.get("name")?.toString()?.trim('"') ?: ""}"
                AgentTools.WAIT -> "wait ${args?.get("seconds")?.toString()?.trim('"') ?: "5"}s"
                AgentTools.TODO -> "todo update"
                else -> "$function($arguments)"
            }
        } catch (e: Exception) {
            "$function($arguments)"
        }
    }

    private fun extractFromArgs(toolCalls: List<ToolCall>, function: String, field: String): String? {
        val tc = toolCalls.firstOrNull { it.function == function } ?: return null
        return extractFromArgsSingle(tc.arguments, field)
    }

    private fun extractListFromArgs(toolCalls: List<ToolCall>, function: String, field: String): List<String> {
        val tc = toolCalls.firstOrNull { it.function == function } ?: return emptyList()
        return try {
            val args = json.parseToJsonElement(tc.arguments) as? kotlinx.serialization.json.JsonObject ?: return emptyList()
            val arr = args[field] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
            arr.map { it.toString().trim('"') }
        } catch (e: Exception) { emptyList() }
    }

    private fun extractFromArgsSingle(arguments: String, field: String): String? {
        return try {
            val args = json.parseToJsonElement(arguments) as? kotlinx.serialization.json.JsonObject
            args?.get(field)?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            }
        } catch (e: Exception) { null }
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
                while (turns < CHAT_MAX_TURNS) {
                    turns++
                    compactIfNeeded(chatHistory, systemCount = 1)
                    trimWindow(chatHistory, systemCount = 1, keep = CHAT_WINDOW_KEEP)

                    _uiState.value = AgentUiState.Streaming(0L, turns)
                    val (fullText, toolCalls, llmError) = awaitLlm(chatHistory)
                    _streamingText.value = ""

                    if (llmError != null) {
                        say(ChatRole.SYSTEM, "模型调用失败: $llmError")
                        _uiState.value = AgentUiState.Idle
                        return@launch
                    }

                    chatHistory += ChatMessage(
                        role = "assistant",
                        content = fullText,
                        toolCalls = toolCalls
                    )

                    if (fullText.isNotBlank() && fullText.length > 10) {
                        val role = if (toolCalls.isNotEmpty()) ChatRole.THINKING else ChatRole.AI
                        say(role, fullText.trim().take(2000))
                    }

                    if (toolCalls.isEmpty()) {
                        if (fullText.isNotBlank()) {
                            _uiState.value = AgentUiState.Idle
                            return@launch
                        }
                        chatHistory += ChatMessage("user", "请使用工具执行操作，或调用 finish 结束。")
                        continue
                    }

                    val channel = channelProvider()
                    if (channel == null && toolCalls.any { it.function in listOf(AgentTools.EXECUTE, AgentTools.BATCH, AgentTools.RUNBG, AgentTools.JOBLOG) }) {
                        say(ChatRole.SYSTEM, "终端环境未就绪，无法执行命令")
                        _uiState.value = AgentUiState.Idle
                        return@launch
                    }

                    for (tc in toolCalls) {
                        if (tc.function == AgentTools.TODO) {
                            val items = runCatching {
                                (json.parseToJsonElement(tc.arguments) as? kotlinx.serialization.json.JsonObject)
                                    ?.get("items")
                            }.getOrNull()
                            if (items != null) {
                                val todoItems = (items as kotlinx.serialization.json.JsonArray).mapNotNull { itemEl ->
                                    val item = itemEl as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                                    val text = item["text"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content } ?: ""
                                    val done = item["done"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content == "true" } ?: false
                                    ActionTodoItem(text, done)
                                }
                                _todos.value = todoItems.map { TodoItem(it.text, it.done) }
                                say(ChatRole.AI, "任务清单: ${_todos.value.count { it.done }}/${_todos.value.size} 已完成")
                            }
                            chatHistory += ChatMessage("tool", "Todo updated.", toolCallId = tc.id, name = tc.function)
                            continue
                        }

                        if (tc.function == AgentTools.FINISH) {
                            val summary = extractFromArgsSingle(tc.arguments, "summary") ?: "完成"
                            say(ChatRole.AI, summary.take(500))
                            chatHistory += ChatMessage("tool", "Done.", toolCallId = tc.id, name = tc.function)
                            _uiState.value = AgentUiState.Idle
                            return@launch
                        }

                        if (tc.function == AgentTools.ABORT) {
                            val reason = extractFromArgsSingle(tc.arguments, "reason") ?: "中止"
                            say(ChatRole.AI, "中止: $reason")
                            chatHistory += ChatMessage("tool", "Aborted.", toolCallId = tc.id, name = tc.function)
                            _uiState.value = AgentUiState.Idle
                            return@launch
                        }

                        val displayCmd = formatToolDisplay(tc.function, tc.arguments)
                        say(ChatRole.CMD, displayCmd, toolName = tc.function)

                        if (tc.function == AgentTools.EXECUTE || tc.function == AgentTools.BATCH) {
                            val cmd = extractFromArgsSingle(tc.arguments, "command")
                                ?: extractFromArgsSingle(tc.arguments, "commands")
                                ?: tc.function
                            val verdict = RiskFilter.evaluate(cmd)
                            if (verdict is RiskFilter.Verdict.Confirm) {
                                pendingConfirmCommand = cmd
                                db.auditDao().insert(AuditEntryEntity(taskId = 0L, channelLevel = channel?.level ?: ChannelLevel.SANDBOX, command = cmd, exitCode = null))
                                _uiState.value = AgentUiState.AwaitConfirm(0L, cmd, verdict.reason)
                                awaitConfirmDecision()
                                pendingConfirmCommand = null
                                if (_uiState.value is AgentUiState.Stopped || _uiState.value is AgentUiState.Failed) return@launch
                            }
                        }

                        _uiState.value = AgentUiState.Executing(0L, 0, 1, displayCmd.take(100), turns, tc.function)
                        val result = AgentTools.execute(
                            tc.function, tc.arguments, channel,
                            workspaceRootProvider(), COMMAND_TIMEOUT_MS
                        )
                        val outputDisplay = if (result.output.length > 1200) {
                            result.output.take(500) + "\n\n[... truncated ...]\n\n" + result.output.takeLast(300)
                        } else result.output
            say(ChatRole.OUTPUT, outputDisplay, toolName = tc.function)

            _sessionStats.value = _sessionStats.value.copy(
                toolsCalled = _sessionStats.value.toolsCalled + 1,
                commandsRun = if (tc.function in listOf(AgentTools.EXECUTE, AgentTools.BATCH, AgentTools.RUNBG)) _sessionStats.value.commandsRun + 1 else _sessionStats.value.commandsRun,
                filesModified = if (tc.function in listOf(AgentTools.WRITE_FILE, AgentTools.EDIT_FILE, AgentTools.MULTI_EDIT, AgentTools.UNDO_EDIT)) {
                    val modified = mutableSetOf<String>()
                    AgentTools.undoStacks.keys.forEach { modified.add(it) }
                    modified.size
                } else _sessionStats.value.filesModified
            )

                        val obs = Prompts.observation(tc.function, tc.arguments, result.output, result.isError, result.exitCode)
                        chatHistory += ChatMessage(
                            role = "tool",
                            content = obs.content,
                            toolCallId = tc.id,
                            name = tc.function
                        )
                    }
                    _uiState.value = AgentUiState.Idle
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
                _streamingText.value = ""
            }
        }
    }

    fun clearChat() {
        chatHistory.clear()
        _chat.value = emptyList()
        _contextUsage.value = 0f
        _sessionStats.value = SessionStats()
        say(ChatRole.SYSTEM, "聊天历史已清空")
    }

    private suspend fun awaitConfirmDecision() {
        val decision = kotlinx.coroutines.withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { confirmChannel.receive() } ?: false
        if (!decision) {
            _uiState.value = AgentUiState.Stopped("用户拒绝了高危命令执行")
        }
    }

    private val confirmChannel = kotlinx.coroutines.channels.Channel<Boolean>(capacity = 1)
    private val inputChannel = kotlinx.coroutines.channels.Channel<String>(capacity = 1)

    fun confirm() {
        pendingConfirmCommand = null
        confirmChannel.trySend(true)
    }

    fun reject() {
        pendingConfirmCommand = null
        confirmChannel.trySend(false)
    }

    fun provideUserInput(text: String) {
        inputChannel.trySend(text)
    }

    private suspend fun waitForUserInput(): String {
        val response = kotlinx.coroutines.withTimeoutOrNull(5 * 60_000L) { inputChannel.receive() }
        return response ?: "(no response)"
    }

    private suspend fun runSubAgent(goal: String, maxIter: Int, channel: CommandChannel): String {
        val subMessages = mutableListOf(
            systemMessage(Prompts.SYSTEM),
            ChatMessage("user", "Subtask: $goal\n\nYou are a sub-agent. Complete this task autonomously and call finish with a summary of what you accomplished.")
        )
        var iteration = 0
        while (iteration < maxIter) {
            iteration++
            compactIfNeeded(subMessages, systemCount = 2)
            trimWindow(subMessages, systemCount = 2, keep = SUB_WINDOW_KEEP)

            _uiState.value = AgentUiState.Streaming(-1L, iteration)
            val (fullText, toolCalls, llmError) = awaitLlm(subMessages)
            _streamingText.value = ""

            if (llmError != null) return "Sub-agent failed: $llmError"

            subMessages += ChatMessage("assistant", fullText, toolCalls = toolCalls)

            if (toolCalls.isEmpty()) {
                if (fullText.isNotBlank()) {
                    subMessages += ChatMessage("user", "Use tools to complete the task, or call finish.")
                    continue
                }
                continue
            }

            val finishCall = toolCalls.firstOrNull { it.function == AgentTools.FINISH }
            if (finishCall != null) {
                return extractFromArgsSingle(finishCall.arguments, "summary") ?: "Sub-agent completed."
            }
            val abortCall = toolCalls.firstOrNull { it.function == AgentTools.ABORT }
            if (abortCall != null) {
                return "Sub-agent aborted: ${extractFromArgsSingle(abortCall.arguments, "reason") ?: "unknown"}"
            }

            val actionCalls = toolCalls.filter {
                it.function !in listOf(AgentTools.TODO, AgentTools.FINISH, AgentTools.ABORT,
                    AgentTools.SUBAGENT, AgentTools.LISTEN)
            }
            for (tc in actionCalls) {
                val result = AgentTools.execute(
                    tc.function, tc.arguments, channel,
                    workspaceRootProvider(), COMMAND_TIMEOUT_MS
                )
                subMessages += ChatMessage(
                    "tool",
                    Prompts.observation(tc.function, tc.arguments, result.output, result.isError, result.exitCode).content,
                    toolCallId = tc.id,
                    name = tc.function
                )
                if (result.output == "__FINISH__") return "Sub-agent completed."
                if (result.output == "__ABORT__") return "Sub-agent aborted."
            }
        }
        return "Sub-agent reached iteration limit ($maxIter)."
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

    fun undoLastEdit(): String {
        if (AgentTools.undoStacks.isEmpty()) {
            say(ChatRole.SYSTEM, "没有可撤销的编辑")
            return "没有可撤销的编辑"
        }
        val lastFile = AgentTools.undoStacks.keys.last()
        val stack = AgentTools.undoStacks[lastFile]!!
        if (stack.isEmpty()) {
            say(ChatRole.SYSTEM, "没有可撤销的编辑")
            return "没有可撤销的编辑"
        }
        val prev = stack.removeAt(stack.lastIndex)
        File(lastFile).writeText(prev)
        AgentTools.redoStacks.getOrPut(lastFile) { mutableListOf() }.add(File(lastFile).readText())
        say(ChatRole.SYSTEM, "已撤销 $lastFile")
        return "已撤销 $lastFile"
    }

    fun reset() {
        loopJob?.cancel()
        loopJob = null
        pendingConfirmCommand = null
        _streamingText.value = ""
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

    companion object {
        const val COMMAND_TIMEOUT_MS = 120_000L
        const val CONFIRM_TIMEOUT_MS = 10 * 60_000L
        const val CHAT_MAX_TURNS = 20
        const val LLM_TIMEOUT_MS = 180_000L
        const val MAX_TOKENS = 8192
        const val WINDOW_KEEP = 40
        const val CHAT_WINDOW_KEEP = 30
        const val SUB_WINDOW_KEEP = 20
        const val COMPACT_THRESHOLD = 60
        const val MAX_CONTEXT_CHARS = 80_000
    }

    private fun estimateTokens(messages: List<ChatMessage>): Int {
        var chars = 0
        for (m in messages) {
            chars += m.content.length
            for (tc in m.toolCalls) {
                chars += tc.function.length + tc.arguments.length
            }
        }
        return chars / 4
    }

    private fun trimWindow(messages: MutableList<ChatMessage>, systemCount: Int, keep: Int) {
        if (messages.size <= systemCount + keep) return
        val estimatedTokens = estimateTokens(messages)
        if (estimatedTokens <= MAX_CONTEXT_CHARS / 4) return

        val head = messages.take(systemCount)

        // Differentiated retention: keep user messages and assistant reasoning,
        // truncate old tool results (they're less valuable over time)
        val tail = messages.drop(systemCount).takeLast(keep * 2)
        // Truncate old tool result contents to save tokens
        val processedTail = tail.mapIndexed { idx, msg ->
            val age = tail.size - idx
            if (msg.role == "tool" && age > keep / 2) {
                msg.copy(content = msg.content.take(200) + "\n[... old result truncated ...]")
            } else {
                msg
            }
        }

        messages.clear()
        messages.addAll(head + processedTail)
        say(ChatRole.SYSTEM, "上下文已裁剪 (保留最近 ${keep} 条, 工具结果按时效衰减)")
    }

    private suspend fun compactIfNeeded(messages: MutableList<ChatMessage>, systemCount: Int) {
        if (messages.size < COMPACT_THRESHOLD) return
        val estimatedTokens = estimateTokens(messages)
        if (estimatedTokens < MAX_CONTEXT_CHARS / 4 * 0.8) return

        val keepTail = WINDOW_KEEP / 2
        val middleEnd = messages.size - keepTail
        if (middleEnd - systemCount < 4) return

        // Differentiated compression: preserve user messages fully,
        // compress tool results more aggressively
        val middle = messages.subList(systemCount, middleEnd).toList()

        say(ChatRole.SYSTEM, "对话较长, 正在压缩历史上下文...")
        val req = listOf(
            ChatMessage(
                "system",
                "你是对话压缩器。把给出的多轮历史压缩为要点摘要。重点保留: 用户原始需求和验收标准、关键文件路径、命令执行结果摘要、已做决定、未完成事项、错误诊断。压缩到 600 字以内, 直接输出摘要正文。"
            ),
            ChatMessage("user", middle.joinToString("\n\n") { m ->
                val content = when (m.role) {
                    "user" -> m.content.take(1000)  // Preserve user messages fully
                    "tool" -> m.content.take(300)   // Compress tool results aggressively
                    else -> m.content.take(600)     // Moderate for assistant
                }
                if (m.toolCalls.isNotEmpty()) {
                    "[${m.role} tools: ${m.toolCalls.joinToString(", ") {
                        "${it.function}(${it.arguments.take(80)})"
                    }}] $content"
                } else {
                    "[${m.role}] $content"
                }
            }.take(20000))
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
        say(ChatRole.SYSTEM, "历史已压缩 (${middle.size} 条 → 摘要, 用户消息全保留, 工具结果已压缩)")
    }

    private suspend fun awaitLlm(messages: List<ChatMessage>): Triple<String, List<ToolCall>, String?> {
        var last: Triple<String, List<ToolCall>, String?> = Triple("", emptyList(), "未知错误")
        repeat(2) { attempt ->
            val done = kotlinx.coroutines.withTimeoutOrNull(LLM_TIMEOUT_MS) {
                var text = ""
                var toolCalls = emptyList<ToolCall>()
                var err: String? = null
                llm.chat(messages, MAX_TOKENS, toolSchemas).collect { ev ->
                    when (ev) {
                        is LlmEvent.Delta -> {
                            if (ev.text.isNotEmpty()) {
                                _streamingText.value = _streamingText.value + ev.text
                            }
                        }
                        is LlmEvent.Completed -> {
                            text = ev.fullText
                            toolCalls = ev.toolCalls
                            _streamingText.value = ""
                        }
                        is LlmEvent.Failed -> err = ev.error
                    }
                }
                Triple(text, toolCalls, err)
            }
            last = done ?: Triple("", emptyList(), "模型响应超时 (${LLM_TIMEOUT_MS / 1000}s)")
            if (last.third == null) return last
            if (attempt == 0) {
                say(ChatRole.SYSTEM, "模型响应异常 (${last.third?.take(60)}), 自动重试...")
                delay(800)
            }
        }
        return last
    }
}
