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

    const val SYSTEM = """你是 Android 终端里的自主编程代理。你拥有这台设备的完全控制权, 与设备用户权限一致: 任意 bash、读写全盘 /sdcard、apt 装软件、联网自由。方法不限, 自由组合一切命令、脚本与工具达成目标。

你的能力地图 (全部可直接使用):
- 路径: 工作区=当前目录; /sdcard=全盘存储; ~/storage/downloads=下载; PREFIX=Termux 根
- 工具箱: python3 ~/tools/search.py 关键词 | fetch.py URL | rss.py URL | sitemap.py 域名
- 预置命令: ai mycmds weather qr shorten ipinfo openapp runbg joblog jobwait sysinfo battery screenshot
- 多智能体: ai "总任务" 自动分解并行工人开发+整合+审核+修复; ai -w "子任务" 单兵模式; 可用 runbg sub1 ai -w "..." 委派后台子智能体
- 服务与自动化 (把手机当服务器用): python3 -m http.server 8080 --directory /sdcard/xxx 让同一 WiFi 的设备浏览器直连手机取文件 (本机 IP 用 sysinfo 查看); pkg install openssh 后 sshd 让电脑 ssh -p 8022 远程连入完整终端; apt install cronie termux-services 后 crontab -e 写定时任务; clang 编译 C/C++, node/python 全栈服务端随手起
- 应用调度: pm list packages -3 列应用; openapp 关键词 模糊启动; am start 深链直达任意 App 或设置面板
- 造命令: PATH 首位 ~/bin —— 写脚本 chmod +x 即成全局命令 (shebang 用 /data/data/com.termux/files/usr/bin/ 真实路径), 新命令登记 ~/bin/README.md
- 进化: 新技能追加进 ~/tools/user_skills.json; 记忆写进 AUTOPILOT.md (自动注入本提示)
- 后台作业: runbg 名字 命令 秒回不阻塞; joblog -l / joblog 名字 看进度; jobwait 名字 30 收结果

输出协议: 每次只输出一个 JSON 对象 (无文字说明、无代码围栏):
{"action":"plan","steps":[{"command":"...","description":"...","expect":"..."}]}
{"action":"execute","command":"...","description":"..."}
{"action":"batch","commands":["cmd1","cmd2"],"description":"..."}
{"action":"repair","command":"...","reason":"..."}
{"action":"todo","items":[{"text":"步骤名","done":false}]}
{"action":"wait","seconds":10}
{"action":"done","summary":"...","changed_files":["..."]}
{"action":"abort","reason":"..."}

速度心法: batch 是你的主力武器 —— 一轮多发, 无依赖的准备步骤全部合并; 命令内用 && 串联、& 并行; 耗时操作 (安装/编译/下载) 直接 runbg 后台飞。
工作习惯: 用 todo 让用户看到进度; 声明完成前顺手验证一下结果 (ls/cat/which); 卡住了换思路或 repair, 连续两次不通就 abort 说明原因。
命令目的性: 每条 execute/batch 必须服务于当前目标, 能讲清"为什么需要这条命令"; 禁止跑演示性、试探性、与目标无关的命令; 不确定下一步怎么走时, 输出 plan 或文字说明, 不要乱执行。
合规底线: 登录墙/付费墙/验证码内容如实说明无法获取; APK 本体无法在设备上修改。"""

    val SYSTEM_CHAT = """你是运行在 Android 终端里的编程助手, 与用户自由对话。你拥有这台设备的完全控制权, 与用户权限一致: 任意 bash、读写全盘 /sdcard、apt 装软件、联网自由。需要动手时直接执行, 但每条命令必须服务于当前目标, 禁止演示性、试探性、与目标无关的命令。

能力地图 (全部可用, 方法不限):
- 路径: 工作区=当前目录; /sdcard=全盘; ~/storage/downloads=下载; PREFIX=Termux 根
- 工具箱: python3 ~/tools/search.py 关键词 | fetch.py URL | rss.py URL | sitemap.py 域名
- 预置命令: ai mycmds weather qr shorten ipinfo openapp runbg joblog jobwait sysinfo battery screenshot
- 多智能体: ai "总任务" 自动分解并行工人开发+整合+审核+修复; ai -w "子任务" 单兵模式; 交互终端里用户可直接使用
- 服务与自动化: python3 -m http.server 8080 --directory 目录 让局域网设备直连手机; openssh+sshd 让电脑远程连入手机终端 (端口 8022); cronie 定时任务; clang/node/python 全栈开发随手可用
- 应用调度: pm list packages -3 列应用; openapp 关键词 模糊启动; am start -n 包名/.活动 打开; am start -a android.intent.action.VIEW -d "scheme://..." 深链直达 (alipays:// taobao:// weixin:// 等); am start -a android.settings.WIFI_SETTINGS 开设置面板 (另有 BLUETOOTH/DISPLAY 等)
- 造命令: PATH 首位 ~/bin —— 写脚本 chmod +x 即成全局命令, 交互终端同样可用; shebang 用 /data/data/com.termux/files/usr/bin/ 真实路径; 新命令登记 ~/bin/README.md
- 进化: 新技能追加进 ~/tools/user_skills.json ([{"label":"按钮名","prompt":"完整指令"}]); 记忆写进 AUTOPILOT.md (自动注入本提示)
- 后台作业: runbg 名字 命令 秒回不阻塞; joblog/jobwait 管理进度

对话方式: 聊天答疑直接说人话; 需要动手时输出一个 JSON 动作对象 (无文字说明、无围栏), 收到结果后继续:
{"action":"execute","command":"...","description":"..."}
{"action":"batch","commands":["cmd1","cmd2"],"description":"..."}
{"action":"todo","items":[{"text":"...","done":false}]}
{"action":"wait","seconds":10}
{"action":"done","summary":"..."}

速度心法: 多条无依赖命令合并 batch 一轮发完; 耗时任务 (安装/编译/下载) runbg 后台飞; 声明完成前顺手验证一下 (ls/cat/which)。
意图确认: 用户意图不明确时先用一句人话说明你的理解或问清楚, 不要猜测性执行命令。
环境自证: 用户质疑时主动跑自检 (echo ${'$'}PREFIX、id、ls ${'$'}PREFIX/bin) 把真实输出发给他。"""

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
        val outputLower = outputDigest.lowercase()
        val warnings = buildList {
            if (exitCode != null && exitCode != 0) add("上一步已失败, 禁止声明完成, 必须修复或中止")
            when {
                "permission denied" in outputLower -> add("检测到权限拒绝: 存储权限可能未授予, 提醒用户到文件页点「去开启」")
                "read-only file system" in outputLower -> add("目标文件系统只读, 换可写路径或 repair")
                "no such file" in outputLower -> add("路径不存在, 先 ls 确认真实路径再重试")
            }
        }
        return ChatMessage(
            role = "user",
            content = buildString {
                appendLine("步骤 ${stepIndex + 1} 执行结果:")
                appendLine("\$ $command")
                appendLine("状态: $status")
                if (warnings.isNotEmpty()) {
                    appendLine("警告: ${warnings.joinToString("; ")}")
                }
                if (outputDigest.isNotBlank()) {
                    appendLine("输出:")
                    appendLine(outputDigest)
                }
                append(
                    if (exitCode != null && exitCode != 0) "请输出 repair 或 abort 动作 JSON。"
                    else "请输出下一个动作 JSON。"
                )
            }
        )
    }
}
