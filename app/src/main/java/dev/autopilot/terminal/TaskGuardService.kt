package dev.autopilot.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

class TaskGuardService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notification = runCatching { buildNotification() }
            .getOrElse { fallbackNotification() }
        startForeground(NOTIF_ID, notification)

        wakeLock = runCatching {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autopilot:taskguard")
                .apply { acquire(WAKELOCK_TIMEOUT_MS) }
        }.getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun ensureChannel(): NotificationManager {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "任务执行保护", NotificationManager.IMPORTANCE_LOW)
        channel.description = "AI 任务在后台执行时保持进程存活"
        manager.createNotificationChannel(channel)
        return manager
    }

    private fun buildNotification(): Notification {
        val manager = ensureChannel()
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pending = PendingIntent.getActivity(
            this, 0, intent ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Autopilot AI 执行中")
            .setContentText("终端任务正在后台运行，点按返回查看")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .build()
        runCatching { manager.notify(NOTIF_ID, notification) }
        return notification
    }

    private fun fallbackNotification(): Notification {
        return runCatching {
            ensureChannel()
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("Autopilot AI")
                .build()
        }.getOrElse {
            Notification.Builder(this).setSmallIcon(android.R.drawable.ic_menu_info_details).build()
        }
    }

    companion object {
        private const val CHANNEL_ID = "task_guard"
        private const val NOTIF_ID = 161
        private const val WAKELOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, TaskGuardService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TaskGuardService::class.java)) }
        }
    }
}
