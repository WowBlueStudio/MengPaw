// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.work.*
import com.mengpaw.kernel.agent.DreamEngine
import com.mengpaw.kernel.agent.ScrollContextManager
import com.mengpaw.kernel.llm.AdaptiveLlmProvider
import com.mengpaw.core.security.Vault
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WorkManager-based Dream scheduler.
 * Triggers only when device is charging + idle (Doze), then runs a single
 * dream pass for each agent that has activity since the last dream.
 */
class DreamWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "mengpaw-dream-pass"
        private const val MIN_IDLE_MINUTES = 30L

        /** Schedule the next dream pass. Safe to call repeatedly (idempotent). */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .build()

            val request = OneTimeWorkRequestBuilder<DreamWorker>()
                .setConstraints(constraints)
                .setInitialDelay(MIN_IDLE_MINUTES, TimeUnit.MINUTES)
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** Cancel pending dream. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        // SECURITY: Use encrypted Vault instead of plain SharedPreferences
        val vault = Vault(applicationContext)
        val endpoint = vault.retrieve("api_endpoint") ?: ""
        val apiKey = vault.retrieve("api_key") ?: ""
        val model = vault.retrieve("model_name") ?: ""
        // Only run if we have real API config
        if (apiKey.isBlank()) return Result.success()

        val llmProvider = try {
            AdaptiveLlmProvider(endpoint, apiKey, model)
        } catch (_: Exception) {
            return Result.failure()
        }

        // Find all agent directories using DataPaths
        val agentsDir = File(com.mengpaw.kernel.DataPaths.AGENTS)
        if (!agentsDir.exists()) return Result.success()

        val agentDirs = agentsDir.listFiles()?.filter {
            it.isDirectory && File(it, "AGENTS.md").exists()
        } ?: return Result.success()

        var ran = 0
        for (dir in agentDirs) {
            val name = dir.name
            // Skip if dream ran within last 6 hours
            val dreamFile = File(dir, "DREAM.md")
            if (dreamFile.exists() &&
                System.currentTimeMillis() - dreamFile.lastModified() < 6 * 3600 * 1000L) continue

            val scroll = ScrollContextManager(name)
            val result = DreamEngine.dreamPass(llmProvider, name, scroll)
            if (result != null) ran++
        }

        // ── Cleanup + storage report ──
        val cleanup = DreamEngine.cleanupWorkspace()
        val storage = DreamEngine.storageReport()

        // Persist to dream log for agent inspection
        val logFile = File(com.mengpaw.kernel.DataPaths.AGENTS, "dream.log")
        logFile.parentFile?.mkdirs()
        logFile.appendText(
            "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} " +
            "dream_ran=$ran | cleanup=${cleanup.filesDeleted} files, ${cleanup.bytesFreed / 1024}KB freed | ${storage}\n"
        )

        return if (ran > 0 || cleanup.filesDeleted > 0) Result.success() else Result.success()
    }
}

/**
 * Register a charging broadcast receiver that schedules dream passes when
 * the device connects to power. The actual work is deferred to WorkManager
 * with idle constraints.
 */
class PowerConnectionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        if (plugged == BatteryManager.BATTERY_PLUGGED_AC ||
            plugged == BatteryManager.BATTERY_PLUGGED_USB ||
            plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
            DreamWorker.schedule(context)
        }
    }

    companion object {
        fun register(context: Context): android.content.BroadcastReceiver {
            val receiver = PowerConnectionReceiver()
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_POWER_CONNECTED))
            return receiver
        }
    }
}
