// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

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

    /**
     * 切换省电模式。
     * 用法: sys.power.save [true|false]（省略参数视为开启）
     *
     * 修复: 原实现解析 enable 后完全不用 — 无论开还是关都只打开设置页并返回成功，
     * 语义与声明不符。Android 无公共 API 直接切换省电模式，故采用两级策略:
     * - 无 WRITE_SETTINGS 权限: 打开系统省电设置页引导手动切换，如实报告当前状态。
     * - 有 WRITE_SETTINGS: 写入全局设置 low_power（社区通行方案），写后回读
     *   isPowerSaveMode 验证；OEM 不生效时如实告知并引导手动切换。
     */
    suspend fun powerSave(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val enable = args.firstOrNull()?.lowercase() != "false" && args.firstOrNull()?.lowercase() != "off"
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val current = pm.isPowerSaveMode

        if (!app.checkSelf(android.Manifest.permission.WRITE_SETTINGS)) {
            val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            return ExecutionResult.ok(buildString {
                appendLine("省电模式当前状态: ${if (current) "开启" else "关闭"}")
                appendLine("目标: ${if (enable) "开启" else "关闭"} — Android 无公共 API 直接切换，需 WRITE_SETTINGS 权限")
                appendLine("已打开系统省电设置页，请手动${if (enable) "开启" else "关闭"}。")
                appendLine("手动授权: 设置 → 应用 → MengPaw → 修改系统设置 → 开启 (清单未声明 WRITE_SETTINGS，无法自动申请)")
            })
        }

        return try {
            Settings.Global.putInt(app.contentResolver, "low_power", if (enable) 1 else 0)
            // 等待 PowerManagerService 消费设置变更后再回读验证
            kotlinx.coroutines.delay(300)
            val actual = pm.isPowerSaveMode
            if (actual == enable) {
                ExecutionResult.ok("省电模式已${if (enable) "开启" else "关闭"}（验证通过）")
            } else {
                val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(intent)
                ExecutionResult.ok(buildString {
                    appendLine("已写入设置但系统未生效（部分 OEM 设备不支持应用直接切换）")
                    appendLine("当前状态: ${if (pm.isPowerSaveMode) "开启" else "关闭"}")
                    appendLine("已打开系统省电设置页，请手动${if (enable) "开启" else "关闭"}。")
                })
            }
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            ExecutionResult.fail("省电模式切换失败: ${e.message}。已打开系统省电设置页，请手动切换。", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }
}
