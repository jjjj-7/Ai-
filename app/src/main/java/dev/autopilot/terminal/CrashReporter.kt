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
    private const val SAFE_MODE_PREF = "crash_meta"
    private const val KEY_COUNT = "consecutive_crashes"
    private const val KEY_TS = "last_crash_ts"
    private const val SAFE_THRESHOLD = 3

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeLog(appContext, thread, throwable) }
            runCatching { bumpCrashCount(appContext) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun isSafeMode(context: Context): Boolean {
        val p = context.getSharedPreferences(SAFE_MODE_PREF, Context.MODE_PRIVATE)
        val ts = p.getLong(KEY_TS, 0L)
        val stale = System.currentTimeMillis() - ts > 5 * 60 * 1000L
        return !stale && p.getInt(KEY_COUNT, 0) >= SAFE_THRESHOLD
    }

    fun markLaunchHealthy(context: Context) {
        context.getSharedPreferences(SAFE_MODE_PREF, Context.MODE_PRIVATE)
            .edit().putInt(KEY_COUNT, 0).apply()
    }

    fun clearSafeMode(context: Context) {
        context.getSharedPreferences(SAFE_MODE_PREF, Context.MODE_PRIVATE)
            .edit().clear().apply()
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

    private fun bumpCrashCount(ctx: Context) {
        val p = ctx.getSharedPreferences(SAFE_MODE_PREF, Context.MODE_PRIVATE)
        val last = p.getLong(KEY_TS, 0L)
        val fresh = System.currentTimeMillis() - last < 5 * 60 * 1000L
        val next = if (fresh) p.getInt(KEY_COUNT, 0) + 1 else 1
        p.edit().putInt(KEY_COUNT, next).putLong(KEY_TS, System.currentTimeMillis()).apply()
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
