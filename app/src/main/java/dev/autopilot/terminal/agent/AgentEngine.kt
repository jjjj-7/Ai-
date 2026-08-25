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
    private val runner = CommandRunner(scope)

    private val _uiState = MutableStateFlow<AgentUiState>(AgentUiState.Idle)
    val uiState: StateFlow<AgentUiState> = _uiState

    @Volatile private var pendingConfirmCommand: String? = null
    private var loopJob: Job? = null

    fun submit(goal: String, criteria: List<String>, maxIterations: Int) {
        if (loopJob?.isActive == true) return
        val channel = channelProvider() ?: run {
            _uiState.value = AgentUiState.Failed("终端会话未就绪，请先完成 Bootstrap 安装")
            return
        }

        loopJob = scope.launch {
            val task = TaskEntity(goal = goal, criteriaJson = json.encodeToString(criteria))
            val taskId = db.taskDao().insert(task)
            _uiState.value = AgentUiState.Planning(taskId)
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
                        messages += ChatMessage("assistant", fullText)
                    }
                    "execute", "repair" -> {
                        val cmd = action.command ?: run { failTask(taskId, "动作缺少 command 字段"); return@launch }
                        messages += ChatMessage("assistant", fullText)

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
                        messages += Prompts.observation(stepCounter - 1, cmd, result.exitCode, result.output)
                    }
                    "done" -> {
                        finishTask(taskId, action.summary ?: "任务完成", action.changed_files ?: emptyList(), startedAt, channel.level)
                        return@launch
                    }
                    "abort" -> {
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
        db.taskDao().byId(taskId)?.let { t ->
            db.taskDao().update(t.copy(status = TaskStatus.STOPPED, reportSummary = message, finishedAt = System.currentTimeMillis()))
        }
        _uiState.value = AgentUiState.Stopped(message)
    }

    private suspend fun failTask(taskId: Long, reason: String) {
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
    }
}
