package dev.autopilot.terminal.agent

import dev.autopilot.terminal.llm.ChatMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PlanStep(
    val command: String,
    val description: String = "",
    val expect: String = ""
)

@Serializable
data class Plan(val steps: List<PlanStep>)

class PlanParser(private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }) {

    fun parse(rawText: String): Result<Plan> {
        val candidate = extractJsonBlock(rawText) ?: return Result.failure(IllegalArgumentException("响应中未找到 JSON"))
        return runCatching { json.decodeFromString<Plan>(candidate) }
            .recoverCatching { fallback ->
                val obj = json.parseToJsonElement(candidate).toString()
                json.decodeFromString<Plan>(obj)
            }
            .recoverCatching {
                val single = json.parseToJsonElement(candidate)
                val arr = when {
                    single.toString().trimStart().startsWith("[") -> candidate
                    else -> throw IllegalArgumentException("JSON 既非对象也非数组")
                }
                json.decodeFromString<List<PlanStep>>(arr).let { Plan(it) }
            }
    }

    private fun extractJsonBlock(text: String): String? {
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(text)?.groupValues?.get(1)?.trim()
        val source = fenced ?: text
        val objStart = source.indexOf('{')
        val objEnd = source.lastIndexOf('}')
        if (objStart >= 0 && objEnd > objStart) return source.substring(objStart, objEnd + 1)
        val arrStart = source.indexOf('[')
        val arrEnd = source.lastIndexOf(']')
        if (arrStart >= 0 && arrEnd > arrStart) return source.substring(arrStart, arrEnd + 1)
        return null
    }
}

object Prompts {

    const val SYSTEM = """你是一个在 Android 设备终端中自主工作的编程代理。你的唯一执行通道是 shell 终端。

规则:
1. 只能通过执行终端命令完成任务。
2. 每次只输出一个 JSON 对象，不要输出其他文本。
3. 文件读写使用可用命令工具 (cat/echo/python/node 等) 完成。

输出格式 (制定计划时):
{"action":"plan","steps":[{"command":"...","description":"...","expect":"..."}]}

输出格式 (观察结果后的每一步):
{"action":"execute","command":"...","description":"..."}
{"action":"repair","command":"...","reason":"..."}
{"action":"done","summary":"...","changed_files":["..."]}
{"action":"abort","reason":"..."}

注意:
- expect 字段描述该步骤成功的可验证标准。
- 失败时优先 repair，同一问题连续修复超过 2 次应 abort。
- 完成时必须用 done 动作并附变更文件清单。"""

    fun userTask(goal: String, criteria: List<String>, channelDesc: String): String =
        buildString {
            appendLine("任务目标: $goal")
            if (criteria.isNotEmpty()) {
                appendLine("验收标准:")
                criteria.forEachIndexed { i, c -> appendLine("${i + 1}. $c") }
            }
            appendLine()
            appendLine("执行环境: $channelDesc")
            append("请先输出 plan。")
        }

    fun observation(stepIndex: Int, command: String, exitCode: Int?, outputDigest: String): ChatMessage {
        val status = when (exitCode) {
            null -> "超时未返回"
            0 -> "成功"
            else -> "失败 exit=$exitCode"
        }
        return ChatMessage(
            role = "user",
            content = buildString {
                appendLine("步骤 ${stepIndex + 1} 执行结果:")
                appendLine("\$ $command")
                appendLine("状态: $status")
                if (outputDigest.isNotBlank()) {
                    appendLine("输出:")
                    appendLine(outputDigest)
                }
                append("请输出下一个动作 JSON。")
            }
        )
    }
}
