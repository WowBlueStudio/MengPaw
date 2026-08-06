// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.errorreport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * WiFi 连接监视器 — 从 ErrorReportPlugin 拆分 (职责: WiFi/Ethernet 连通性跟踪)。
 *
 * 依赖通过构造参数注入 (参照 SessionCompressor 模式): Context 供给 /
 * 连通性写回 / 连上时的上传触发闭包, 行为与拆分前完全一致。
 */
internal class ErrorReportWifiMonitor(
    private val appContextProvider: () -> Context?,
    private val setWifiConnected: (Boolean) -> Unit,
    private val onWifiConnected: () -> Unit
) {
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ── WiFi Monitor ────────────────────────────────────────────────────

    fun register() {
        val ctx = appContextProvider() ?: return
        connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = connectivityManager?.getNetworkCapabilities(network) ?: return
                val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                // Also treat Ethernet as upload-safe
                val isEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (isWifi || isEthernet) {
                    setWifiConnected(true)
                    onWifiConnected()
                }
            }

            override fun onLost(network: Network) {
                // Check if any other network is still WiFi
                val allNetworks = connectivityManager?.allNetworks ?: emptyArray()
                val stillWifi = allNetworks.any { net ->
                    connectivityManager?.getNetworkCapabilities(net)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }
                if (!stillWifi) setWifiConnected(false)
            }
        }
        networkCallback = callback

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        try {
            connectivityManager?.registerNetworkCallback(request, callback)
        } catch (_: Exception) { }

        // Check initial state
        try {
            val activeNetwork = connectivityManager?.activeNetwork
            val caps = activeNetwork?.let { connectivityManager?.getNetworkCapabilities(it) }
            setWifiConnected(caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true)
        } catch (_: Exception) { }
    }

    fun unregister() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) { }
        networkCallback = null
    }
}
