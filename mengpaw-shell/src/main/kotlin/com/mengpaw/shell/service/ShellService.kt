// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Foreground service to keep Agent running in background.
 * Uses foreground notification + WakeLock to prevent Android from killing
 * the process during long-running tasks.
 *
 * Android 12+ (API 31+) restricts foreground service launch from background;
 * we handle this gracefully. On OEM devices (Xiaomi, Huawei, OPPO, vivo),
 * users should also disable battery optimization for MengPaw.
 */
class ShellService : Service() {

    companion object {
        private const val CHANNEL_ID = "mengpaw_agent"
        private const val NOTIFICATION_ID = 1001
        private const val WAKELOCK_TAG = "mengpaw:shell-service"
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L // 1 hour max

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ShellService::class.java))
            } catch (e: Exception) {
                // Android 13+ (API 33): may throw ForegroundServiceStartNotAllowedException
                // if called from background (e.g. WakeReceiver). Service will retry on next wake.
                android.util.Log.w("ShellService", "Cannot start from background: ${e.message}")
            }
        }
    }

    private var powerReceiver: android.content.BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, createNotification())
            foregroundStarted = true
            android.util.Log.d("ShellService", "Foreground service started successfully")
        } catch (e: Exception) {
            // Android 12+ may reject foreground service start from background.
            // If startForeground() fails, we MUST stopSelf() — otherwise the system
            // throws ForegroundServiceDidNotStartInTimeException after 5s → crash.
            android.util.Log.e("ShellService", "FATAL: startForeground failed: ${e.message}")
            try { stopSelf() } catch (_: Exception) {}
            return
        }

        // Acquire partial WakeLock to keep CPU running during agent tasks.
        // Released in onDestroy(). Timeout prevents battery drain if something goes wrong.
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKELOCK_TAG
            )?.apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT_MS)
            }
            android.util.Log.d("ShellService", "WakeLock acquired (partial, ${WAKELOCK_TIMEOUT_MS}ms timeout)")
        } catch (e: Exception) {
            android.util.Log.w("ShellService", "WakeLock acquisition failed: ${e.message}")
        }

        // Register dream mode charging trigger
        try { powerReceiver = PowerConnectionReceiver.register(this) } catch (_: Exception) { }
        try { DreamWorker.schedule(this) } catch (_: Exception) { }
    }

    override fun onDestroy() {
        // Release WakeLock cleanly
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            android.util.Log.d("ShellService", "WakeLock released")
        } catch (e: Exception) {
            android.util.Log.w("ShellService", "WakeLock release failed: ${e.message}")
        }
        wakeLock = null

        powerReceiver?.let { unregisterReceiver(it) }
        EventReceiver.unregister(this)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            // Foreground notification failed — cannot run as foreground service.
            // Stop immediately to avoid ForegroundServiceDidNotStartInTimeException.
            try { stopSelf() } catch (_: Exception) {}
            return START_NOT_STICKY
        }
        // Re-acquire existing WakeLock if it timed out; create one only if null
        if (wakeLock?.isHeld != true) {
            try {
                if (wakeLock != null) {
                    // Re-acquire the existing lock (timeout doesn't invalidate the object)
                    wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
                } else {
                    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                    wakeLock = pm?.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        WAKELOCK_TAG
                    )?.apply {
                        setReferenceCounted(false)
                        acquire(WAKELOCK_TIMEOUT_MS)
                    }
                }
            } catch (_: Exception) { }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            // 删除旧渠道 — 通知渠道设置是粘性的，改代码不会自动更新已有渠道
            manager.deleteNotificationChannel(CHANNEL_ID)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MengPaw 后台运行",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "MengPaw is running in the background"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MengPaw 智能助手")
            .setContentText("后台运行中，智能体随时响应")
            .setSmallIcon(com.mengpaw.shell.R.drawable.ic_wowblue_icon)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }
}
