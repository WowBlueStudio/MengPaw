// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

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
 * On wake:
 * 1. TriggerEngine fires due Lifetime triggers
 * 2. Agent checks ACP inbox for pending tasks
 * 3. If tasks exist, Agent processes them
 * 4. Returns to sleep
 */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val reason = intent?.getStringExtra("wake_reason") ?: "check"

        context?.let { ctx ->
            try { com.mengpaw.plugin.pad.FloatingDotService.start(ctx) } catch (_: Exception) {}
            try { ShellService.start(ctx) } catch (_: Exception) {}
        }

        // Fire all trigger types
        TriggerEngine.onSystemWake()

        // After Cron fires, re-register next Cron alarm
        if (reason == "cron") {
            context?.let { TriggerEngine.registerCronAlarm(it) }
        }

        // Check ACP inbox
        val inboxDir = java.io.File(DataPaths.TEAM_INBOX)
        val pending = inboxDir.listFiles()?.firstOrNull()
        if (pending != null) {
            android.util.Log.d("WakeReceiver", "ACP task pending: ${pending.name}")
        }
    }
}
