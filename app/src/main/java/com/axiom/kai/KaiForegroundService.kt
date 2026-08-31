package com.axiom.kai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class KaiForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                releaseWakeLock()
                return START_NOT_STICKY
            }
            else -> {
                acquireWakeLock()
                startForeground(NOTIF_ID, buildNotification(intent?.getStringExtra(EXTRA_TEXT)?.take(80) ?: "Kai is thinking…"))
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Kai thinking", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps Kai generating when app is in background or screen is locked"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(subText: String): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.let { base ->
            PendingIntent.getActivity(this, 0, base.apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Kai is thinking…")
            .setContentText(subText)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(NotificationCompat.Action(0, "Stop", PendingIntent.getService(this, 1, Intent(this, KaiForegroundService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kai:GenerationWakeLock").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L) // 10 min max, released on destroy/stop
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        const val CHANNEL_ID = "kai_generation"
        const val NOTIF_ID = 1701
        const val ACTION_STOP = "kai_stop"
        const val EXTRA_TEXT = "text"

        fun start(ctx: Context, text: String = "Kai is thinking…") {
            val i = Intent(ctx, KaiForegroundService::class.java).putExtra(EXTRA_TEXT, text)
            try {
                androidx.core.content.ContextCompat.startForegroundService(ctx, i)
            } catch (_: Throwable) {
                try { ctx.startService(i) } catch (_: Throwable) {}
            }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, KaiForegroundService::class.java)) } catch (_: Throwable) {}
            // also send stop action to ensure foreground removed
            try {
                ctx.startService(Intent(ctx, KaiForegroundService::class.java).apply { action = ACTION_STOP })
            } catch (_: Throwable) {}
        }
    }
}
