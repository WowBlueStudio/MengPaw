// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * MediaProjection 前台服务令牌 (P1 修复, 9d 审查)。
 *
 * 背景: 原 sys.screenshot/screenrecord 依赖 `screencap`/`screenrecord` shell 二进制,
 * 普通 App 进程 (非 root/ADB) 必失败。MediaProjection 是唯一免 root 路径, 且
 * Android 14+ 强制屏幕捕获必须在 mediaProjection 类型前台服务上下文中创建
 * VirtualDisplay, 否则抛 SecurityException。本服务只提供前台服务令牌 + 常驻通知,
 * 实际捕获逻辑在 [ScreenCaptureExecutor] (同进程)。
 */
class MediaProjectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "屏幕捕获", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("MengPaw 屏幕捕获")
            .setContentText("截图/录屏进行中")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "media_projection"
        private const val NOTIFICATION_ID = 2002

        /** 启动前台服务令牌 — 失败不阻塞 (通知权限缺失等场景仅无通知, 捕获仍可进行). */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, MediaProjectionService::class.java))
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            try { context.stopService(Intent(context, MediaProjectionService::class.java)) } catch (_: Exception) {}
        }
    }
}
