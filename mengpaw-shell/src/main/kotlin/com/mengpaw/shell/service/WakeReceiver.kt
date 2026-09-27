// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.trigger.TriggerEngine

/**
 * System wake receiver — woken by AlarmManager every N minutes.
 * Survives Doze mode. Keeps MengPaw Agent reachable even when
 * Android has killed the app process.
 *
 * 两条唤醒通道 (同一接收器, 按 action 分流):
 * 1. kernel CronAlarmScheduler (无 action 的显式 Intent / `wake_reason` extra):
 *    TriggerEngine 触发 Lifetime/CRON 触发器 — 原行为不变;
 * 2. 保活看门狗 ([KeepAliveController.ACTION_KEEPALIVE_WATCHDOG], 精确闹钟可穿透 Doze):
 *    只做"是否还需要保活"的判定 — 有未完成事项 (inbox 待办) 或任务仍活跃则补租 WakeLock
 *    并重布看门狗, 否则释放并撤销, 不做长期空转唤醒。不重复触发 TriggerEngine。
 *
 * On wake:
 * 1. TriggerEngine fires due Lifetime triggers
 * 2. Agent checks ACP inbox for pending tasks
 * 3. If tasks exist, Agent processes them
 * 4. Returns to sleep
 */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val reason = intent?.getStringExtra("wake_reason") ?: "check"
        val isKeepAliveWatchdog = intent?.action == KeepAliveController.ACTION_KEEPALIVE_WATCHDOG

        // 有未完成事项才补租 WakeLock — 无待办不空转
        val pending = firstPendingInboxFile()

        context?.let { ctx ->
            try { ShellService.start(ctx) } catch (_: Exception) {}
        }

        if (isKeepAliveWatchdog) {
            context?.let { ctx ->
                try { KeepAliveController.onWatchdog(ctx, pendingWork = pending != null) } catch (_: Exception) {}
            }
            return
        }

        // Fire all trigger types
        TriggerEngine.onSystemWake()

        // After Cron fires, re-register next Cron alarm
        if (reason == "cron") {
            TriggerEngine.refreshCronAlarm()
        }

        // Check ACP inbox
        if (pending != null) {
            android.util.Log.d("WakeReceiver", "ACP task pending: ${pending.name}")
        }
    }

    /** inbox 首个待办文件; 目录缺失或不可读时返回 null (禁止未捕获 IO 异常)。 */
    private fun firstPendingInboxFile(): java.io.File? = try {
        java.io.File(DataPaths.TEAM_INBOX).listFiles()?.firstOrNull()
    } catch (e: Exception) {
        android.util.Log.w("WakeReceiver", "inbox 检查失败: ${e.message}")
        null
    }
}
