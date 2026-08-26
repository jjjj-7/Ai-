package dev.autopilot.terminal.agent

object RiskFilter {

    sealed class Verdict {
        data object Allow : Verdict()
        data class Confirm(val reason: String) : Verdict()
    }

    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("""mkfs(\.\w+)?(\s|$)""") to "文件系统格式化",
        Regex("""dd\s+[^\n]*of=/dev/(sd|hd|mmcblk|nvme|block)""") to "向块设备写入",
        Regex("""(>\s*)?/dev/(sd[a-z]|block/mmcblk)\d?\s*$""") to "直接写物理设备",
        Regex(""":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:""") to "fork 炸弹",
        Regex("""rm\s+-[a-zA-Z]*r[a-zA-Z]*(\s+-[a-zA-Z]+)*\s+(/usr|/etc|/bin|/sbin|/lib|/boot|/dev|/system|/data|/vendor|~\s*$|\$\{?HOME\}?/?\s*$|/\*?\s*$)""") to "递归删除系统目录或主目录本体",
        Regex("""chmod\s+-R\s+(777|666|000)\s+/(usr|etc|system|data)\b""") to "批量改写系统目录权限"
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
