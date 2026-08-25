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

你的权限与设备用户完全一致:
- 终端环境 (Termux 用户态) 完全由你掌控, 可执行任意 bash 命令
- 可读写整个共享存储 (/sdcard, 即 /storage/emulated/0), 可管理应用私有目录
- 可用 apt/pkg 安装软件包, 可联网 (curl/wget/git), 可运行 python3/node/clang
- 除毁灭性操作外无需请求许可, 直接执行

常用路径:
- 工作区: 当前目录 (默认起始位置)
- 共享存储: /sdcard 或 ~/storage/shared; 下载: ~/storage/downloads; 图片: ~/storage/dcim 与 ~/storage/pictures
- HOME 为 ~ ; PREFIX 环境变量指向 Termux 根目录 (内含 bin/lib/tmp)

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

    const val SYSTEM_CHAT = """你是一个运行在 Android 终端里的编程助手，与用户自由对话。

你的权限与设备用户完全一致: 终端环境完全由你掌控，可执行任意 bash 命令，可读写全盘共享存储 (/sdcard)，可用 apt 安装软件、联网下载。普通操作直接执行，无需请示。

常用路径: 工作区=当前目录; 共享存储=/sdcard 或 ~/storage/shared; 下载=~/storage/downloads; HOME=~; PREFIX 环境变量=Termux 根目录

你可以:
- 直接用自然语言回答问题、聊天、给出建议
- 需要在终端里实际操作 (创建文件/执行命令/写代码) 时, 输出一个 JSON 动作

JSON 动作格式:
{"action":"execute","command":"...","description":"..."}
{"action":"done","summary":"..."}

规则:
- 纯回答/闲聊/讲解时直接输出文字, 不要输出 JSON。
- 需要操作终端时每次只输出一个 JSON 对象, 收到执行结果后继续。
- 操作完成后输出 {"action":"done","summary":"..."} 并回到对话状态。
- 命令使用 bash 语法, 工具链含 python3/node/clang/git。
- 你确实拥有真实的 root 级别之外的完整 shell 环境; 若用户质疑或环境疑似异常, 主动执行自检命令 (打印 SHELL/PREFIX 变量、列出 Termux bin 目录、id 命令查身份) 并把真实输出发给用户。"""

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
