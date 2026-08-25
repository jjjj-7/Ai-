package dev.autopilot.terminal.perms

import dev.autopilot.terminal.data.ChannelLevel
import dev.autopilot.terminal.llm.ChatMessage

enum class ChannelKind { PTY, SHIZUKU }

data class ExecResult(
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean
)

interface CommandChannel {
    val kind: ChannelKind
    val level: ChannelLevel
    suspend fun exec(command: String, timeoutMs: Long): ExecResult
    fun killCurrent() {}
    fun close()
}

class OutputCollector {
    private val sb = StringBuilder()
    private var exitCode: Int? = null

    @Synchronized
    fun onChunk(bytes: ByteArray) {
        sb.append(String(bytes, Charsets.UTF_8))
        if (sb.length > 200_000) sb.delete(0, sb.length - 100_000)
        MARKER_REGEX.find(sb)?.let { m ->
            if (exitCode == null) exitCode = m.groupValues[1].toIntOrNull()
        }
    }

    @Synchronized
    fun pollExitCode(): Int? = exitCode

    @Synchronized
    fun text(): String = sb.toString()

    companion object {
        const val EXIT_MARKER = "__EXIT_CODE:"
        val MARKER_REGEX = Regex("""${Regex.escape(EXIT_MARKER)}(-?\d+)__""")
    }
}

class CommandRunner {
    internal fun digest(text: String): String {
        val clean = text.replace(OutputCollector.MARKER_REGEX, "").trim()
        if (clean.length <= MAX_OUTPUT_CHARS) return clean
        val half = MAX_OUTPUT_CHARS / 2
        return "${clean.take(half)}\n\n[... 输出过长已截断 ...]\n\n${clean.takeLast(half)}"
    }

    companion object {
        const val MAX_OUTPUT_CHARS = 6000
    }
}
