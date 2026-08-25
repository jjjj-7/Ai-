package dev.autopilot.terminal.perms

import com.termux.terminal.TerminalSession
import dev.autopilot.terminal.data.ChannelLevel
import dev.autopilot.terminal.terminal.SessionRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

class TermuxChannel(
    private val registry: SessionRegistry,
    private val scope: CoroutineScope
) : CommandChannel {

    override val kind: ChannelKind = ChannelKind.PTY
    override val level: ChannelLevel = ChannelLevel.SANDBOX

    private val counter = AtomicLong(0)

    override suspend fun exec(command: String, timeoutMs: Long): ExecResult {
        val cwd = registryWorkspace()
        val session = registry.createOnce(
            "ai-${counter.incrementAndGet()}",
            arrayOf("-c", command),
            cwd
        ) ?: return ExecResult(null, "终端环境未就绪，无法执行命令", false)

        val finished = CompletableDeferred<Int>()
        scope.launch {
            while (!session.isRunning()) {
                delay(20)
            }
            while (session.isRunning()) {
                delay(60)
            }
            finished.complete(session.exitStatus)
        }

        val code = withTimeoutOrNull(timeoutMs) { finished.await() }
        if (code == null) {
            runCatching { session.finishIfRunning() }
            return ExecResult(null, digest(transcript(session)), true)
        }

        delay(120)
        return ExecResult(code, digest(transcript(session)), false)
    }

    private fun transcript(session: TerminalSession): String =
        runCatching {
            session.emulator?.screen?.transcriptTextWithFullLinesJoined ?: ""
        }.getOrDefault("")

    private var workspaceProvider: (() -> String)? = null

    fun bindWorkspace(provider: () -> String) {
        workspaceProvider = provider
    }

    private fun registryWorkspace(): String =
        runCatching { workspaceProvider?.invoke() }.getOrNull() ?: "/data/data"

    internal fun digest(text: String): String {
        val clean = text.trim()
        return if (clean.length <= CommandRunner.MAX_OUTPUT_CHARS) clean
        else {
            val half = CommandRunner.MAX_OUTPUT_CHARS / 2
            "${clean.take(half)}\n\n[... 输出过长已截断 ...]\n\n${clean.takeLast(half)}"
        }
    }

    override fun close() {}
}
