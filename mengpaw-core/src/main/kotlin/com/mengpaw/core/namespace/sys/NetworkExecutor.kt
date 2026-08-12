// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.mengpaw.core.namespace.SysExecutor
import com.mengpaw.core.namespace.checkSelf
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import java.net.NetworkInterface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Network type, WiFi status, and Bluetooth info. */
internal object NetworkExecutor {

    suspend fun network(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val type = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> "Disconnected"
        }
        val ip = try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(":") != true }
                ?.hostAddress ?: "unknown"
        } catch (_: Exception) { "unknown" }
        return ExecutionResult.ok("Type: $type | IP: $ip")
    }

    suspend fun wifi(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val info = wm?.connectionInfo
        return ExecutionResult.ok(buildString {
            appendLine("WiFi: ${if (wm?.isWifiEnabled == true) "Enabled" else "Disabled"}")
            if (info != null && info.ssid != "<unknown ssid>") {
                appendLine("SSID: ${info.ssid}")
                appendLine("BSSID: ${info.bssid}")
                appendLine("RSSI: ${info.rssi} dBm")
                appendLine("Speed: ${info.linkSpeed} Mbps")
                appendLine("Freq: ${info.frequency} MHz")
            }
        })
    }

    suspend fun wifiEnable(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val enable = args.firstOrNull()?.lowercase() != "false" && args.firstOrNull()?.lowercase() != "off"
        try {
            wm?.isWifiEnabled = enable
            return ExecutionResult.ok("WiFi ${if (enable) "enabled" else "disabled"}")
        } catch (e: SecurityException) {
            return ExecutionResult.fail("需要 CHANGE_WIFI_STATE 权限", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }
    }

    suspend fun bluetooth(args: List<String>, ec: ExecutionContext): ExecutionResult {
        return try {
            val adapter = Class.forName("android.bluetooth.BluetoothAdapter")
                .getMethod("getDefaultAdapter").invoke(null)
            val enabled = adapter?.javaClass?.getMethod("isEnabled")?.invoke(adapter) as? Boolean ?: false
            val name = adapter?.javaClass?.getMethod("getName")?.invoke(adapter) as? String ?: "unknown"
            ExecutionResult.ok("Bluetooth: ${if (enabled) "Enabled" else "Disabled"} | Name: $name")
        } catch (e: Exception) {
            ExecutionResult.ok("Bluetooth: unavailable (requires BLUETOOTH permission)")
        }
    }

    /** WiFi 热点扫描 (对齐 Termux:API termux-wifi-scaninfo) — 需定位权限 + 系统定位开关。 */
    suspend fun wifiScan(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        if (!app.checkSelf(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return ExecutionResult.fail(
                "WiFi 扫描需要 ACCESS_FINE_LOCATION 权限与系统定位开关。请先执行 sys.permission.request ACCESS_FINE_LOCATION",
                errorCode = ErrorCodes.ERR_PERMISSION_DENIED
            )
        }
        val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return ExecutionResult.fail("WifiManager 不可用", errorCode = ErrorCodes.ERR_INTERNAL)
        if (wm.isWifiEnabled != true) {
            return ExecutionResult.fail("WiFi 未开启, 无法扫描", errorCode = ErrorCodes.ERR_INTERNAL)
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        val scanned = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) scanned.complete(Unit)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            return ExecutionResult.fail("注册扫描监听失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
        try {
            val started = try { wm.startScan() } catch (e: SecurityException) { false }
            if (!started) {
                return ExecutionResult.fail(
                    "扫描启动失败 (需要 CHANGE_WIFI_STATE 权限 + 系统定位服务开启)",
                    errorCode = ErrorCodes.ERR_PERMISSION_DENIED
                )
            }
            withTimeoutOrNull(15_000L) { scanned.await() }
                ?: return ExecutionResult.fail("WiFi 扫描超时", errorCode = ErrorCodes.ERR_TIMEOUT)
            val results = wm.scanResults.take(20)
            return ExecutionResult.ok(
                results.map { "${it.SSID} | ${it.BSSID} | ${it.level}dBm | ${it.capabilities}" }
                    .joinToString("\n").ifEmpty { "(未扫描到热点)" }
            )
        } finally {
            try { app.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }
}
