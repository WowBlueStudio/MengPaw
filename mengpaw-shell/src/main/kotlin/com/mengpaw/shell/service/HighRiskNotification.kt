// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import com.mengpaw.kernel.security.UserConfirmBus

/**
 * 高危操作确认横幅通知 (v0.37.3) — 取代应用内 AlertDialog 弹窗。
 *
 * 背景: 原弹窗只在主聊天界面可见, 后台任务/其他页面触发高危命令时用户看不到,
 * 30 秒静默超时被拒 (用户无感知)。现改为系统通知栏高优先级横幅, 带「允许/拒绝」
 * 操作按钮 — 无论应用前台/后台都能看到, 通知栏直接确认。
 *
 * 生命周期: AppInitializer 调 [init] (创建渠道 + 动态注册接收器, 幂等);
 * 通知在 [UserConfirmBus.request] 30 秒超时后由发起方自动取消。
 */
object HighRiskNotification {

    private const val CHANNEL_ID = "mengpaw_high_risk_confirm"
    private const val ACTION_CONFIRM = "com.mengpaw.shell.action.HIGH_RISK_CONFIRM"
    private const val EXTRA_REQ_ID = "req_id"
    private const val EXTRA_ALLOW = "allow"

    @Volatile private var appContext: Context? = null
    @Volatile private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val reqId = intent.getLongExtra(EXTRA_REQ_ID, -1L)
            val allow = intent.getBooleanExtra(EXTRA_ALLOW, false)
            if (reqId >= 0) {
                UserConfirmBus.respond(reqId, allow)
                cancel(reqId)
            }
        }
    }

    /** 初始化: 创建通知渠道 + 动态注册确认接收器 (幂等, AppInitializer 调用)。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (registered) return
        try {
            val ctx = appContext ?: return
            val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (Build.VERSION.SDK_INT >= 26 && manager != null) {
                if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                    manager.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID, "高危操作确认", NotificationManager.IMPORTANCE_HIGH
                        ).apply {
                            description = "Agent 请求执行高危操作时的确认横幅"
                            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                        }
                    )
                }
            }
            val filter = IntentFilter(ACTION_CONFIRM)
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                ctx.registerReceiver(receiver, filter)
            }
            registered = true
        } catch (_: Exception) {}
    }

    /** 展示高危确认横幅通知 (带允许/拒绝按钮)。 */
    fun show(req: UserConfirmBus.ConfirmRequest) {
        val ctx = appContext ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // 通知权限未授予 — 不展示, UserConfirmBus 超时后按安全默认拒绝
            return
        }
        try {
            val notifyId = req.id.toInt()
            val allowPi = PendingIntent.getBroadcast(
                ctx, notifyId * 2,
                Intent(ACTION_CONFIRM).setPackage(ctx.packageName)
                    .putExtra(EXTRA_REQ_ID, req.id).putExtra(EXTRA_ALLOW, true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val denyPi = PendingIntent.getBroadcast(
                ctx, notifyId * 2 + 1,
                Intent(ACTION_CONFIRM).setPackage(ctx.packageName)
                    .putExtra(EXTRA_REQ_ID, req.id).putExtra(EXTRA_ALLOW, false),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val contentPi = PendingIntent.getActivity(
                ctx, notifyId * 2 + 2,
                ctx.packageManager.getLaunchIntentForPackage(ctx.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val detail = buildString {
                append(req.command)
                if (!req.reason.isNullOrBlank()) append("\n原因: ").append(req.reason)
            }
            val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(ctx.applicationInfo.icon)
                .setContentTitle("⚠️ 高危操作确认")
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(false)
                .setContentIntent(contentPi)
                .addAction(0, "允许", allowPi)
                .addAction(0, "拒绝", denyPi)
                .build()
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.notify(notifyId, notification)
        } catch (_: Exception) {}
    }

    /** 取消指定请求的确认通知 (超时/已响应)。 */
    fun cancel(reqId: Long) {
        try {
            (appContext?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(reqId.toInt())
        } catch (_: Exception) {}
    }
}
