// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service to keep Agent running in background.
 * Uses foreground notification + WakeLock to prevent Android from killing
 * the process during long-running tasks.
 *
 * 保活职责自 v0.47.x 起拆给 [KeepAliveController] — 本服务只做"前台化 + 通知渲染":
 * WakeLock 由任务状态驱动 (任务活跃才持锁, 空闲 [KeepAlivePolicy.IDLE_RELEASE_MS] 后释放),
 * 到期由 ticker/看门狗续租。见 KeepAliveController 的类注释。
 *
 * Android 12+ (API 31+) restricts foreground service launch from background;
 * we handle this gracefully. On OEM devices (Xiaomi, Huawei, OPPO, vivo),
 * users should also disable battery optimization for MengPaw
 * (一次性引导见 [BatteryOptimizationGuide])。
 */
class ShellService : Service() {

    companion object {
        private const val CHANNEL_ID = "mengpaw_bg_v2"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ShellService::class.java))
            } catch (e: Exception) {
                android.util.Log.w("ShellService", "Cannot start from background: ${e.message}")
            }
        }

        /**
         * 更新常驻前台通知文案 (任务进度/空闲降级) — 由 [KeepAliveController] 调用。
         * 通知 ID 与 startForeground 一致, 因此只改内容, 不会产生第二条通知。
         */
        fun updateNotification(context: Context, title: String, text: String) {
            try {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return
                manager.notify(NOTIFICATION_ID, buildNotification(context, title, text))
            } catch (e: Exception) {
                android.util.Log.w("ShellService", "Notification update failed: ${e.message}")
            }
        }

        private fun buildNotification(context: Context, title: String, text: String): Notification {
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(com.mengpaw.shell.R.drawable.ic_wowblue_icon)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOngoing(true)
                .setShowWhen(false)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .build()
        }

        /** 通知可见性变更后重启服务以生效 */
        fun refreshNotification(context: Context) {
            try {
                context.stopService(Intent(context, ShellService::class.java))
                android.os.Handler(context.mainLooper).postDelayed({
                    start(context)
                }, 500)
            } catch (_: Exception) {}
        }
    }

    private var powerReceiver: android.content.BroadcastReceiver? = null

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

        // Browser 回传监视 (幂等; UI 存活期间由 MainActivity 驱动, 服务兜底)
        try { BrowserReturnWatcher.start(this) } catch (_: Exception) {}

        // 任务级保活接入: 冷启动不预持 WakeLock (无任务零耗电),
        // 任务开始/活跃才补租 — 判定与续租全在 KeepAliveController。
        try { KeepAliveController.attach(this) } catch (_: Exception) { }

        // Register dream mode charging trigger
        try { powerReceiver = PowerConnectionReceiver.register(this) } catch (_: Exception) { }
        try { DreamWorker.schedule(this) } catch (_: Exception) { }

        // P1 修复: onDestroy 中 EventReceiver.unregister 后永不重注册 → 广播事件全部丢失。
        // 服务每次创建时重新注册 (register 内部幂等: 已注册则直接返回)。
        try { EventReceiver.register(this) } catch (_: Exception) { }
    }

    override fun onDestroy() {
        // Release WakeLock / 撤销看门狗 / 停 ticker (任务状态保留, 服务重启后可续租)
        try {
            KeepAliveController.detach(this)
            android.util.Log.d("ShellService", "KeepAlive detached (wakelock released)")
        } catch (e: Exception) {
            android.util.Log.w("ShellService", "KeepAlive detach failed: ${e.message}")
        }

        BrowserReturnWatcher.stop()
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
        // 再次被启动 (含 START_STICKY 重建): 按当前任务状态重新判定补租
        try { KeepAliveController.attach(this) } catch (_: Exception) { }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun readBackgroundMode(): String {
        return try {
            val file = java.io.File(com.mengpaw.kernel.DataPaths.CONFIG, "background_mode")
            if (file.exists()) file.readText().trim() else "NOTIFICATION"
        } catch (_: Exception) { "NOTIFICATION" }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val mode = readBackgroundMode()
            val importance = when (mode) {
                "SILENT" -> NotificationManager.IMPORTANCE_MIN
                else -> NotificationManager.IMPORTANCE_DEFAULT
            }
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            // 渠道不匹配才重建；前台服务运行时 deleteChannel 会抛 SecurityException
            if (existing == null || existing.importance != importance) {
                try { manager.deleteNotificationChannel(CHANNEL_ID) }
                catch (_: SecurityException) { return } // 前台服务运行中，保留旧渠道
                val channel = NotificationChannel(
                    CHANNEL_ID, "MengPaw 后台运行", importance
                ).apply {
                    description = "MengPaw is running in the background"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    /** 启动时通知文案 = 空闲态 (任务开始后由 KeepAliveController 改为"执行中 + 步骤/耗时")。 */
    private fun createNotification(): Notification =
        buildNotification(this, KeepAlivePolicy.TITLE_IDLE, KeepAlivePolicy.TEXT_IDLE)
}
