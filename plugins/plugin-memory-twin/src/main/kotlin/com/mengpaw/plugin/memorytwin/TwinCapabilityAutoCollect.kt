// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import android.content.Context

/**
 * 能力卡系统状态自动重采 (P1.4, 自 [TwinCapabilityCollector] 拆出 — 400 行文件限制)。
 *
 * 电池/网络/充电状态变化时重采一张 [CapabilityCard] 并回调 (运行时字段不进库存, 见设计 §7.3)。
 */
internal object TwinCapabilityAutoCollect {

    /**
     * 注册 Android 系统广播接收器; 调用方持有返回值以便注销。
     *
     * @param onCardChange 任一系统状态变化时的回调 (重采后的能力卡)
     */
    fun register(context: Context, onCardChange: (CapabilityCard) -> Unit): android.content.BroadcastReceiver {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: android.content.Intent?) {
                try {
                    val deviceId = try {
                        com.mengpaw.kernel.acp.AcpCrypto.myFingerprint()
                    } catch (_: Exception) {
                        "device-${System.currentTimeMillis()}"
                    }
                    val deviceName = try { android.os.Build.MODEL } catch (_: Exception) { "Android" }
                    val collector = TwinCapabilityCollector(
                        ctx, deviceId, deviceName,
                        agentName = MemoryTwinPlugin.agentName
                    )
                    onCardChange(collector.collect())
                } catch (e: Exception) {
                    com.mengpaw.kernel.error.ErrorCollector.report(e, "TwinCapabilityAutoCollect")
                }
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_BATTERY_CHANGED)
            addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(android.content.Intent.ACTION_POWER_CONNECTED)
            addAction(android.content.Intent.ACTION_POWER_DISCONNECTED)
        }
        context.registerReceiver(receiver, filter)
        return receiver
    }
}
