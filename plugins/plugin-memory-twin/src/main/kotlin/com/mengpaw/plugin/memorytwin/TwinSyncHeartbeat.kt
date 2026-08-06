// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.acp.AcpTransport
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 孪生心跳循环 — 从 TwinSyncEngine 拆分 (职责: 心跳保活 + 离线判定)。
 *
 * 依赖通过构造参数注入 (参照 SessionCompressor 模式): 传输层 / 对端表 /
 * 可观测状态流由 [TwinSyncEngine] 提供, 行为与拆分前完全一致。
 */
internal class TwinSyncHeartbeat(
    private val scope: CoroutineScope,
    private val transportSupplier: () -> AcpTransport?,
    private val peers: java.util.concurrent.ConcurrentHashMap<String, TwinPeerInfo>,
    private val deviceId: String,
    private val syncState: MutableStateFlow<TwinSyncState>
) {
    private var heartbeatJob: Job? = null

    fun start() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                try {
                    val transport = transportSupplier()
                    if (transport != null) {
                        peers.values.forEach { peer ->
                            try {
                                val msg = com.mengpaw.kernel.acp.AcpMessage.heartbeat(deviceId)
                                transport.send(msg)
                            } catch (_: Exception) { /* peer unreachable */ }
                        }
                    } else {
                        // P1 修复: 传输层暂不可用 — 等待下一轮重试, 不退出心跳循环
                        android.util.Log.w("MengPawTwin", "心跳: ACP 传输层不可用, 30 秒后重试")
                    }
                    // Mark peers offline if no contact for 90 seconds
                    val cutoff = System.currentTimeMillis() - 90_000
                    peers.values.forEach { peer ->
                        if (peer.lastSeen < cutoff && peer.online) {
                            peer.online = false
                            android.util.Log.i("MengPawTwin", "对端离线: ${peer.peerId.take(12)}... (${peer.agentName})")
                        }
                    }
                    syncState.value = syncState.value.copy(
                        onlinePeers = peers.values.count { it.online },
                        totalPeers = peers.size
                    )
                } catch (e: Exception) {
                    ErrorCollector.report(e, "TwinSyncEngine.heartbeat")
                }
                delay(30_000L)
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /** Handle incoming heartbeat — mark peer as online. */
    fun onHeartbeatReceived(peerId: String) {
        peers[peerId]?.let {
            it.online = true
            it.lastSeen = System.currentTimeMillis()
        }
    }
}
