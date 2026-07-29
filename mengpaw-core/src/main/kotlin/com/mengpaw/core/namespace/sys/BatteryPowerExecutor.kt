// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.namespace.sys

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.mengpaw.core.namespace.SysExecutor
import com.mengpaw.core.namespace.checkSelf
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes

/** Battery status, power info, and power-save mode. */
internal object BatteryPowerExecutor {

    suspend fun battery(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (scale > 0) level * 100 / scale else -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val health = when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            else -> "Unknown"
        }
        val charging = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Discharging"
        }
        return ExecutionResult.ok("Level: $pct% | Charging: $charging | Temp: ${temp}°C | Health: $health")
    }

    suspend fun power(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        return ExecutionResult.ok(buildString {
            appendLine("Power Save: ${pm.isPowerSaveMode}")
            @Suppress("DEPRECATION")
            appendLine("Interactive: ${pm.isInteractive}")
            if (Build.VERSION.SDK_INT >= 23) appendLine("Doze: ${pm.isDeviceIdleMode}")
        })
    }

    suspend fun powerSave(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val enable = args.firstOrNull()?.lowercase() != "false" && args.firstOrNull()?.lowercase() != "off"
        if (!app.checkSelf(android.Manifest.permission.WRITE_SETTINGS)) {
            val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            return ExecutionResult.ok("Opened battery saver settings. Toggle manually or grant WRITE_SETTINGS permission.")
        }
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
        return ExecutionResult.ok("Opened battery saver settings (direct toggle requires system app).")
    }
}
