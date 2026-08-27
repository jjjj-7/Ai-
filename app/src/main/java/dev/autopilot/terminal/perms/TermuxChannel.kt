package dev.autopilot.terminal.perms

import com.termux.terminal.TerminalSession
import dev.autopilot.terminal.data.ChannelLevel
import dev.autopilot.terminal.terminal.SessionRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class TermuxChannel(
    private val registry: SessionRegistry,
    private val scope: CoroutineScope
) : CommandChannel {

    override val kind: ChannelKind = ChannelKind.PTY
    override val level: ChannelLevel = ChannelLevel.SANDBOX

    private val counter = AtomicLong(0)

    @Volatile private var activeSession: TerminalSession? = null
    @Volatile private var activeProcess: Process? = null

    override fun killCurrent() {
        activeSession?.let { s -> runCatching { s.finishIfRunning() } }
        activeSession = null
        activeProcess?.let { p -> runCatching { p.destroy() } }
        activeProcess = null
    }

    override suspend fun exec(command: String, timeoutMs: Long): ExecResult {
        val cwd = registryWorkspace()
        fastExec(command, cwd, timeoutMs)?.let { return it }
        return ptyExec(command, cwd, timeoutMs)
    }

    private suspend fun fastExec(command: String, cwd: String, timeoutMs: Long): ExecResult? {
        val sh = registry.directShellPath() ?: return null
        return withContext(Dispatchers.IO) {
            val pb = ProcessBuilder(sh, "-c", command)
                .directory(File(cwd))
                .redirectErrorStream(true)
            registry.directEnv(cwd).forEach { spec ->
                val idx = spec.indexOf('=')
                if (idx > 0) pb.environment()[spec.substring(0, idx)] = spec.substring(idx + 1)
            }
            val process = runCatching { pb.start() }.getOrNull() ?: return@withContext null
            activeProcess = process
            val text = StringBuilder()
            try {
                val finished = CompletableDeferred<Int>()
                scope.launch {
                    try {
                        val reader = process.inputStream.bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (text.length < 512_000) {
                                text.append(line).append('\n')
                            }
                        }
                        finished.complete(process.waitFor())
                    } catch (t: Throwable) {
                        finished.complete(-1)
                    }
                }
                val code = withTimeoutOrNull(timeoutMs) { finished.await() }
                if (code == null) {
                    runCatching { process.destroy() }
                    return@withContext ExecResult(null, digest(text.toString()), true)
                }
                ExecResult(code, digest(text.toString()), false)
            } finally {
                if (activeProcess === process) activeProcess = null
            }
        }
    }

    private suspend fun ptyExec(command: String, cwd: String, timeoutMs: Long): ExecResult {
        val session = registry.createOnce(
            "ai-${counter.incrementAndGet()}",
            arrayOf("sh", "-c", command),
            cwd
        ) ?: return ExecResult(null, "终端环境未就绪，无法执行命令", false)
        activeSession = session

        try {
            val finished = CompletableDeferred<Int>()
            scope.launch {
                while (!session.isRunning()) {
                    delay(5)
                }
                while (session.isRunning()) {
                    delay(10)
                }
                finished.complete(session.exitStatus)
            }

            val code = withTimeoutOrNull(timeoutMs) { finished.await() }
            if (code == null) {
                runCatching { session.finishIfRunning() }
                return ExecResult(null, digest(transcript(session)), true)
            }

            return ExecResult(code, digest(transcript(session)), false)
        } finally {
            if (activeSession === session) activeSession = null
        }
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
