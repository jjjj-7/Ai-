package dev.autopilot.terminal.agent

object RiskFilter {

    sealed class Verdict {
        data object Allow : Verdict()
        data class Confirm(val reason: String) : Verdict()
    }

    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("""rm\s+(-[a-zA-Z]*[rf][a-zA-Z]*\s+)*(/|~|\${'$'}HOME|/\*|\.\.)""") to "递归删除根目录或主目录",
        Regex("""rm\s+-[a-zA-Z]*r[a-zA-Z]*f?[a-zA-Z]*\s+/(usr|etc|bin|sbin|lib|boot|dev|system|data|vendor)""") to "删除系统目录",
        Regex("""mkfs(\.\w+)?\s""") to "文件系统格式化",
        Regex("""dd\s+[^\n]*of=/dev/(sd|hd|mmcblk|nvme|block)""") to "向块设备写入",
        Regex("""chmod\s+-R\s+(777|666|000)\s+/""") to "批量修改系统目录权限",
        Regex("""chown\s+-R\s+\S+\s+/(usr|etc|system|data)""") to "批量修改系统目录属主",
        Regex("""\b(shutdown|reboot|halt|poweroff)\b""") to "电源控制命令",
        Regex(""":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:""") to "fork 炸弹",
        Regex("""(>\s*)?/dev/sd[a-z]\s*$""") to "直接写物理设备",
        Regex("""git\s+push\s+.*--force\b""") to "强推远程分支",
        Regex("""DROP\s+(TABLE|DATABASE)\b""", RegexOption.IGNORE_CASE) to "数据库删除操作",
        Regex("""curl\s+[^\n]*\|\s*(ba)?sh""") to "执行远程未审查脚本"
    )

    fun evaluate(command: String): Verdict {
        val normalized = command.trim().replace("\\\n", " ")
        for ((regex, reason) in rules) {
            if (regex.containsMatchIn(normalized)) return Verdict.Confirm(reason)
        }
        return Verdict.Allow
    }

    fun isHighRisk(command: String): Boolean = evaluate(command) is Verdict.Confirm
}
