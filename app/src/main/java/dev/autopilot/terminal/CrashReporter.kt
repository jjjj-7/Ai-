package dev.autopilot.terminal

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashReporter {

    private const val MAX_LOGS = 5

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeLog(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun latestLogs(context: Context): List<File> =
        crashDir(context.applicationContext).listFiles()?.sortedByDescending { it.name } ?: emptyList()

    fun readableSummary(context: Context): String {
        val files = latestLogs(context)
        if (files.isEmpty()) return "暂无崩溃记录"
        val sb = StringBuilder()
        files.take(3).forEach { f ->
            sb.appendLine("=== ${f.name} ===")
            sb.appendLine(f.readText().take(4000))
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun crashDir(ctx: Context): File = File(ctx.filesDir, "crash").apply { mkdirs() }

    private fun writeLog(ctx: Context, thread: Thread, t: Throwable) {
        val dir = crashDir(ctx)
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val content = buildString {
            appendLine("time: ${Date()}")
            appendLine("thread: ${thread.name}")
            appendLine(sw.toString())
        }
        File(dir, "crash-$ts.log").writeText(content)
        dir.listFiles()?.sortedBy { it.name }?.let { logs ->
            if (logs.size > MAX_LOGS) logs.take(logs.size - MAX_LOGS).forEach { it.delete() }
        }
    }
}
