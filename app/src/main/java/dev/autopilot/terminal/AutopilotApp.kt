package dev.autopilot.terminal

import android.app.Application
import java.io.File

class AutopilotApp : Application() {

    lateinit var workspaceRoot: File
        private set

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
