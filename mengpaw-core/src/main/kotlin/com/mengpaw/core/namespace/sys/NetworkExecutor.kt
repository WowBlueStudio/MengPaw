// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.mengpaw.core.namespace.SysExecutor
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import java.net.NetworkInterface

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
}
