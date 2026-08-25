package dev.autopilot.terminal.perms

import android.content.Context
import android.os.Build
import android.os.Environment
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.autopilot.terminal.data.ChannelLevel

class PermissionManager(private val context: Context) {

    fun storageGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun shizukuBinderAlive(): Boolean = runCatching { ShizukuGate.isAvailable() }.getOrDefault(false)

    fun shizukuGranted(): Boolean = runCatching { ShizukuGate.hasPermission() }.getOrDefault(false)

    fun bestChannelLevel(): ChannelLevel = when {
        shizukuBinderAlive() && shizukuGranted() -> ChannelLevel.SHELL
        else -> ChannelLevel.SANDBOX
    }

    fun channelDescription(): String {
        val storage = if (runCatching { storageGranted() }.getOrDefault(false)) "存储:已授权" else "存储:未授权"
        val shizuku = when {
            !shizukuBinderAlive() -> "Shizuku:服务未运行"
            !shizukuGranted() -> "Shizuku:待授权"
            else -> "Shizuku:已就绪"
        }
        return "$storage / $shizuku"
    }

    companion object {
        const val REQUEST_SHIZUKU = 7001

        fun safeDescription(pm: PermissionManager?): String =
            try { pm?.channelDescription() ?: "通道检测中" } catch (t: Throwable) { "通道检测中" }
    }
}
