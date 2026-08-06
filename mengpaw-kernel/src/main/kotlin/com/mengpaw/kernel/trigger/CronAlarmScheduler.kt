// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.trigger

import com.mengpaw.kernel.KernelLog

/**
 * Android AlarmManager 反射注册（自 TriggerEngine 拆出 — 400 行文件拆分批次 1）。
 *
 * kernel 纯 JVM 零 Android 依赖铁律: 通过 Class.forName 反射调用,
 * 非 Android 环境 (桌面/JVM 测试) 静默降级, 不崩溃。
 *
 * 职责: 一次性 (setExact/set) 定时唤醒 + 周期 (setInexactRepeating) 系统唤醒。
 * 调度时刻的计算 (CRON 表达式解析/模糊窗口) 留在 TriggerEngine。
 */
internal object CronAlarmScheduler {

    /** 触发一次定时唤醒 (RTC_WAKEUP)。[wakeReason] 随 Intent extra 下发, 接收方区分 cron/schedule。 */
    fun scheduleExact(context: Any?, timeInMillis: Long, wakeReason: String, requestCode: Int = 1001) {
        if (context == null) return
        try {
            val ctxClass = Class.forName("android.content.Context")
            val alarmClass = Class.forName("android.app.AlarmManager")
            val intentClass = Class.forName("android.content.Intent")
            val pendingIntentClass = Class.forName("android.app.PendingIntent")

            val alarmManager = ctxClass.getMethod("getSystemService", String::class.java).invoke(context, "alarm")
            val intent = intentClass.getConstructor(ctxClass, Class.forName("java.lang.Class"))
                .newInstance(context, Class.forName("com.mengpaw.shell.service.WakeReceiver"))
            intent.javaClass.getMethod("putExtra", String::class.java, String::class.java)
                .invoke(intent, "wake_reason", wakeReason)

            val pendingIntent = pendingIntentClass.getMethod("getBroadcast",
                ctxClass, Int::class.javaPrimitiveType, intentClass, Int::class.javaPrimitiveType)
                .invoke(null, context, requestCode, intent,
                    pendingIntentClass.getField("FLAG_IMMUTABLE").getInt(null) or
                    pendingIntentClass.getField("FLAG_UPDATE_CURRENT").getInt(null))

            try {
                alarmManager?.javaClass?.getMethod("setExact",
                    Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, pendingIntentClass)
                    ?.invoke(alarmManager,
                        alarmClass.getField("RTC_WAKEUP").getInt(null), timeInMillis, pendingIntent)
            } catch (_: Exception) {
                // setExact 在部分国产 ROM 受限 → 退回 set (近似)
                alarmManager?.javaClass?.getMethod("set",
                    Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, pendingIntentClass)
                    ?.invoke(alarmManager,
                        alarmClass.getField("RTC_WAKEUP").getInt(null), timeInMillis, pendingIntent)
            }

            KernelLog.d("TriggerEngine",
                "Next Cron wake: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(timeInMillis))}")
        } catch (e: Exception) {
            KernelLog.w("TriggerEngine", "Cron alarm registration failed: ${e.message}")
        }
    }

    /** 注册周期系统唤醒 (ELAPSED_REALTIME_WAKEUP, inexact) — 设备被闹钟定时拉起检查 CRON。 */
    fun registerSystemWake(context: Any?, intervalMinutes: Int = 10) {
        if (context == null) return
        try {
            val ctxClass = Class.forName("android.content.Context")
            val alarmClass = Class.forName("android.app.AlarmManager")
            val intentClass = Class.forName("android.content.Intent")
            val pendingIntentClass = Class.forName("android.app.PendingIntent")

            val alarmManager = ctxClass.getMethod("getSystemService", String::class.java)
                .invoke(context, "alarm")

            val intent = intentClass.getConstructor(ctxClass, Class.forName("java.lang.Class"))
                .newInstance(context, Class.forName("com.mengpaw.shell.service.WakeReceiver"))

            val pendingIntent = pendingIntentClass.getMethod("getBroadcast",
                ctxClass, Int::class.javaPrimitiveType, intentClass, Int::class.javaPrimitiveType)
                .invoke(null, context, 0, intent,
                    pendingIntentClass.getField("FLAG_IMMUTABLE").getInt(null) or
                    pendingIntentClass.getField("FLAG_UPDATE_CURRENT").getInt(null))

            val intervalMs = (intervalMinutes.coerceAtLeast(5) * 60 * 1000).toLong()
            alarmManager?.javaClass?.getMethod("setInexactRepeating",
                Int::class.javaPrimitiveType, Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType, pendingIntentClass)
                ?.invoke(alarmManager,
                    alarmClass.getField("ELAPSED_REALTIME_WAKEUP").getInt(null),
                    intervalMs, intervalMs, pendingIntent)

            KernelLog.d("TriggerEngine", "System wake registered every ${intervalMinutes}min")
        } catch (e: Exception) {
            KernelLog.w("TriggerEngine", "AlarmManager not available: ${e.message}")
        }
    }
}
