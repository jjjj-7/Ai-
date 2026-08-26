package dev.autopilot.terminal

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class AutopilotApp : Application() {

    lateinit var workspaceRoot: File
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        workspaceRoot = try {
            File(filesDir, "workspace").apply { if (!exists()) mkdirs() }
        } catch (t: Throwable) {
            cacheDir.apply { mkdirs() }
        }
    }
}
