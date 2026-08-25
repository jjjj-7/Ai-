package dev.autopilot.terminal.perms

import dev.autopilot.terminal.data.ChannelLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ShizukuGate {

    fun isAvailable(): Boolean = runCatching {
        rikka.shizuku.Shizuku.pingBinder()
    }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        rikka.shizuku.Shizuku.checkSelfPermission() ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun newProcess(cmd: String): Process? {
        val shizukuClass = Class.forName("rikka.shizuku.Shizuku")

        val attempt1 = runCatching {
            val m = shizukuClass.getMethod(
                "newProcess",
                Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            m.isAccessible = true
            m.invoke(null, arrayOf("/system/bin/sh", "-c", cmd), null as Array<String>?, null as String?) as Process
        }.getOrNull()
        if (attempt1 != null) return attempt1

        val attempt2 = runCatching {
            val m = shizukuClass.getMethod("newProcess", Array<String>::class.java)
            m.isAccessible = true
            m.invoke(null, arrayOf("/system/bin/sh", "-c", cmd)) as Process
        }.getOrNull()
        if (attempt2 != null) return attempt2

        return null
    }
}

class ShizukuChannel(
    private val scope: kotlinx.coroutines.CoroutineScope
) : CommandChannel {

    override val kind: ChannelKind = ChannelKind.SHIZUKU
    override val level: ChannelLevel = ChannelLevel.SHELL

    override suspend fun exec(command: String, timeoutMs: Long): ExecResult {
        if (!ShizukuGate.isAvailable()) return ExecResult(null, "Shizuku 服务不可用", false)
        if (!ShizukuGate.hasPermission()) return ExecResult(null, "Shizuku 未授权", false)
        return withContext(Dispatchers.IO) {
            runCatching {
                val process = ShizukuGate.newProcess(command)
                    ?: return@withContext ExecResult(null, "Shizuku 进程创建接口不可用，已降级", false)
                readProcess(process, timeoutMs)
            }.getOrElse { ExecResult(null, "Shizuku 执行失败: ${it.message}", false) }
        }
    }

    private fun readProcess(process: Process, timeoutMs: Long): ExecResult {
        val out = StringBuilder()
        var timedOut = false
        val readerThread = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { out.appendLine(it) }
            }
        }.apply { isDaemon = true; start() }

        val done = java.util.concurrent.CountDownLatch(1)
        Thread {
            process.waitFor()
            done.countDown()
        }.apply { isDaemon = true }.start()

        if (!done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            timedOut = true
            process.destroy()
        } else {
            readerThread.join(2000)
        }

        val code = if (timedOut) null else process.exitValue()
        return ExecResult(code, truncate(out.toString()), timedOut)
    }

    private fun truncate(text: String): String {
        val clean = text.trim()
        return if (clean.length <= CommandRunner.MAX_OUTPUT_CHARS) clean
        else "${clean.take(CommandRunner.MAX_OUTPUT_CHARS / 2)}\n[...截断...]\n${clean.takeLast(CommandRunner.MAX_OUTPUT_CHARS / 2)}"
    }

    override fun close() {}
}
