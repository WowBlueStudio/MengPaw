// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mengpaw.core.namespace.SysExecutor
import com.mengpaw.core.namespace.checkSelf
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes

/** Notification ID lookup, send, and cancel. */
internal object NotificationExecutor {

    /** 通知 ID 单一事实源 — 查询/发送/取消共用，避免错位（此前查询返回 1001 而实际发送 1002）。 */
    private const val NOTIFICATION_ID = 1002

    /**
     * 闹钟广播 action — 与 shell 模块 WakeReceiver 的 intent-filter 配对
     * (9d 审查 P2 修复: 原 Class.forName 反射字符串耦合, ProGuard 混淆/类改名即崩;
     * 改 action + setPackage 显式路由, core 不再依赖 shell 类名)。
     */
    const val ALARM_ACTION = "com.mengpaw.action.ALARM"

    suspend fun notificationId(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val channel = args.firstOrNull() ?: "mengpaw_agent"
        return ExecutionResult.ok("Notification channel: $channel (id: $NOTIFICATION_ID)")
    }

    suspend fun notificationSend(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        var priority = NotificationCompat.PRIORITY_DEFAULT
        val parts = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            if (args[i] == "--priority" && i + 1 < args.size) {
                priority = args[i + 1].toIntOrNull() ?: NotificationCompat.PRIORITY_DEFAULT
                i += 2
            } else {
                parts.add(args[i])
                i++
            }
        }
        if (parts.size < 2) return ExecutionResult.fail("Usage: sys.notification.send <title> <text> [--priority N]")

        val title = parts[0]
        val text = parts.drop(1).joinToString(" ")
        val channelId = "mengpaw_agent"

        if (Build.VERSION.SDK_INT >= 33 && !app.checkSelf(Manifest.permission.POST_NOTIFICATIONS)) {
            return ExecutionResult.fail(
                "⛔ 需要 POST_NOTIFICATIONS 权限（Android 13+ 通知权限）\n" +
                "当前状态: 未授予\n" +
                "操作: sys.permission.request POST_NOTIFICATIONS\n" +
                "说明: 将弹出系统权限对话框，请用户在弹窗中选择'允许'",
                errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "MengPaw Agent",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(app, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(priority)
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification)
            return ExecutionResult.ok("Notification sent: $title")
        } catch (e: SecurityException) {
            return ExecutionResult.fail("需要 POST_NOTIFICATIONS 权限 (Android 13+)", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        } catch (e: Exception) {
            return ExecutionResult.fail("Notification failed: ${e.message}")
        }
    }

    suspend fun notificationCancel(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val id = args.firstOrNull()?.toIntOrNull() ?: NOTIFICATION_ID
        NotificationManagerCompat.from(app).cancel(id)
        return ExecutionResult.ok("Notification #$id cancelled")
    }

    /** 读取系统可见通知 — 数据来自 MengPawNotificationListener (需用户开启『通知使用权』)。 */
    suspend fun notificationList(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        if (!MengPawNotificationListener.isAuthorized(app)) {
            return ExecutionResult.fail(
                "需要『通知使用权』(非运行时权限, 无法自动申请)。请引导用户: 设置 → 通知使用权 → 开启 MengPaw。" +
                    "开启后 Agent 可读取系统通知。",
                errorCode = ErrorCodes.ERR_PERMISSION_DENIED
            )
        }
        val list = MengPawNotificationListener.snapshot()
        return ExecutionResult.ok(
            if (list.isEmpty()) "(当前无可见通知)" else list.joinToString("\n\n")
        )
    }

    suspend fun alarmSet(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        if (args.size < 2) return ExecutionResult.fail("Usage: sys.alarm.set <seconds> <message>")
        val secs = args[0].toLongOrNull() ?: return ExecutionResult.fail("Seconds must be a number")
        val msg = args.drop(1).joinToString(" ")
        return try {
            val am = app.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = android.content.Intent(ALARM_ACTION).setPackage(app.packageName)
            intent.putExtra("wake_reason", "alarm")
            intent.putExtra("message", msg)
            val pi = android.app.PendingIntent.getBroadcast(
                app, 2001, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            am.setExact(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + secs * 1000, pi)
            ExecutionResult.ok("Alarm set: ${secs}s → '$msg'")
        } catch (e: Exception) {
            ExecutionResult.fail("Alarm failed: ${e.message}")
        }
    }
}
