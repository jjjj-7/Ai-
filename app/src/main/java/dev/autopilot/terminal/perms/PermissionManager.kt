package dev.autopilot.terminal.perms

import android.content.Context
import android.os.Environment
import dev.autopilot.terminal.data.ChannelLevel

class PermissionManager(private val context: Context) {

    fun storageGranted(): Boolean =
        Environment.isExternalStorageManager()

    fun shizukuBinderAlive(): Boolean = ShizukuGate.isAvailable()

    fun shizukuGranted(): Boolean = ShizukuGate.hasPermission()

    fun bestChannelLevel(): ChannelLevel = when {
        shizukuBinderAlive() && shizukuGranted() -> ChannelLevel.SHELL
        else -> ChannelLevel.SANDBOX
    }

    fun channelDescription(): String {
        val storage = if (storageGranted()) "存储:已授权" else "存储:未授权"
        val shizuku = when {
            !shizukuBinderAlive() -> "Shizuku:服务未运行"
            !shizukuGranted() -> "Shizuku:待授权"
            else -> "Shizuku:已就绪"
        }
        return "$storage / $shizuku"
    }

    companion object {
        const val REQUEST_SHIZUKU = 7001
    }
}
