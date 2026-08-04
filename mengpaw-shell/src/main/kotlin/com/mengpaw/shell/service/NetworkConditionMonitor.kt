// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.mengpaw.kernel.llm.NetworkConditionGate

/**
 * Android 网络状况门卫实现 (v0.29.2, 用户提议 — 信号强度报告 → 内核重试策略)。
 *
 * 用免危险权限的信号源 (仅 ACCESS_NETWORK_STATE, manifest 已有):
 *   - 在线/离线: registerDefaultNetworkCallback 的 onAvailable/onLost
 *   - 质量档位: NET_CAPABILITY_VALIDATED + 下行带宽估算 (linkDownstreamBandwidthKbps)
 *
 * 真实蜂窝 dBm 需 READ_PHONE_STATE (危险权限, 运行时弹窗) — 本实现刻意不用;
 * 对"断网快返 / 弱网放慢退避"的目的, VALIDATED + 带宽已足够。
 * 升级路径 (如未来需要): WifiManager.getConnectionInfo().rssi (ACCESS_WIFI_STATE, 普通权限)
 * 可细化 WiFi 侧档位。
 *
 * 注入点: MainActivity.onCreate 调用 [attach]; kernel 消费方 AdaptiveLlmProvider.
 */
object NetworkConditionMonitor : NetworkConditionGate {

    @Volatile private var attached = false
    @Volatile private var online = true
    @Volatile private var qualityLevel = 2

    @Synchronized
    fun attach(context: Context) {
        if (attached) return
        attached = true
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online = true
            }

            override fun onLost(network: Network) {
                online = false
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                qualityLevel = when {
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> 0
                    caps.linkDownstreamBandwidthKbps >= 2000 -> 2
                    caps.linkDownstreamBandwidthKbps >= 200 -> 1
                    else -> 0
                }
            }
        })
    }

    override fun isOnline(): Boolean = online

    override fun quality(): Int = qualityLevel
}
