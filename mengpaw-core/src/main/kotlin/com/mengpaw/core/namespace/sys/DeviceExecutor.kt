// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import com.mengpaw.core.namespace.SysExecutor
import com.mengpaw.core.namespace.checkSelf
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult

/** Device info, display, and screen control. */
internal object DeviceExecutor {

    suspend fun device(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        return ExecutionResult.ok(buildString {
            appendLine("Model: ${Build.MODEL}")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Brand: ${Build.BRAND}")
            appendLine("Product: ${Build.PRODUCT}")
            appendLine("SDK: ${Build.VERSION.SDK_INT}")
            appendLine("Release: ${Build.VERSION.RELEASE}")
            appendLine("Security Patch: ${Build.VERSION.SECURITY_PATCH}")
            appendLine("Arch: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("Serial: ${if (app.checkSelf("android.permission.READ_PHONE_STATE")) Build.getSerial() else "(需 READ_PHONE_STATE 权限)"}")
        })
    }

    suspend fun display(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return ExecutionResult.fail("WindowManager unavailable")
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val brightness = try {
            Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) { -1 }
        val timeout = try {
            Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) / 1000
        } catch (_: Exception) { -1 }
        return ExecutionResult.ok(buildString {
            appendLine("Resolution: ${metrics.widthPixels}x${metrics.heightPixels}")
            appendLine("Density: ${metrics.densityDpi}dpi (${metrics.density}x)")
            appendLine("Brightness: ${if (brightness >= 0) "${brightness}/255" else "auto"}")
            appendLine("Timeout: ${if (timeout >= 0) "${timeout}s" else "unknown"}")
        })
    }

    suspend fun screenOn(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "mengpaw:screen_on"
        )
        wl.acquire(3000)
        wl.release()
        return ExecutionResult.ok("Screen turned on (wake lock released after 3s)")
    }

    suspend fun screenOff(args: List<String>, ec: ExecutionContext): ExecutionResult {
        return ExecutionResult.fail("熄屏需要 DEVICE_POWER 或辅助功能权限。请手动锁屏。", errorCode = com.mengpaw.kernel.cli.ErrorCodes.ERR_PERMISSION_DENIED)
    }

    suspend fun screenBrightness(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val level = args.firstOrNull()?.toIntOrNull()
        if (level == null || level !in 0..255) return ExecutionResult.fail("Usage: sys.screen.brightness <0-255>")
        if (!Settings.System.canWrite(app)) {
            return ExecutionResult.fail("需要 WRITE_SETTINGS 权限。使用 sys.permission.request WRITE_SETTINGS 申请。", errorCode = com.mengpaw.kernel.cli.ErrorCodes.ERR_PERMISSION_DENIED)
        }
        Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
        return ExecutionResult.ok("Brightness set to $level/255")
    }
}
