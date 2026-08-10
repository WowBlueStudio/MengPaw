// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.mengpaw.kernel.acp.AcpServer
import com.mengpaw.kernel.acp.AcpTransport
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Memory Twin sync engine — the central orchestrator for twin synchronization.
 *
 * Manages the full sync lifecycle (v0.22.0, 账本 → 工作区文件同步):
 *   1. Discovery (via TwinDiscovery, which feeds peer addresses)
 *   2. WS_MANIFEST exchange (工作区文件清单 {relPath: hash})
 *   3. Diff → WS_PULL (差异文件内容)
 *   4. LWW apply with .conflict backup (TwinWorkspace)
 *
 * Also handles: heartbeat monitoring, QoS enforcement, capability card
 * updates, and task delegation intake.
 *
 * ## 职责拆分 (批次3)
 * 业务实现按职责拆到同包委托对象 (构造参数传依赖), 本类保留
 * 公开 API 签名与状态聚合, 行为与拆分前完全一致:
 * - [TwinSyncHeartbeat] — 心跳循环 + 离线判定
 * - [TwinSyncPeerRegistry] — 对端增删查 + 能力卡/委派摄入 + 配对指引
 * - [TwinSyncProtocol] — 清单交换 + 差异拉取 + 能力广播
 * - [TwinSyncSecurity] — REVOKE 处理与广播
 * - [TwinSyncTypes] — 对端信息/同步阶段/状态/结果 数据模型
 */
class TwinSyncEngine(
    private val serverSupplier: () -> AcpServer?,
    private val transportSupplier: () -> AcpTransport?,
    val agentName: String,
    private val deviceId: String,
    private val deviceName: String
) {
    // ── State ──────────────────────────────────────────────────────

    private val _syncState = MutableStateFlow(TwinSyncState())
    val syncState: StateFlow<TwinSyncState> = _syncState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var autoSyncJob: Job? = null

    /** Known peers. P1 修复: 并发集合 — 心跳/同步协程与 ACP handler 线程共享。 */
    private val peers = java.util.concurrent.ConcurrentHashMap<String, TwinPeerInfo>()

    /** QoS level: WIFI (full), MOBILE (key only), METERED (manual only). */
    @Volatile var qosLevel: QosLevel = QosLevel.WIFI
    enum class QosLevel { WIFI, MOBILE, METERED }

    // ── P1.5: Runtime state (injected from AgentEngine) ────────────────

    /** Current session ID (set by MemoryTwinPlugin when a task starts). */
    @Volatile var currentSessionId: String? = null
    /** Whether this agent is currently executing a task. */
    @Volatile var isBusy: Boolean = false

    // ── 职责委托 (批次3 拆分) ─────────────────────────────────────────

    private val heartbeat = TwinSyncHeartbeat(scope, transportSupplier, peers, deviceId, _syncState)
    private val peerRegistry = TwinSyncPeerRegistry(scope, peers, _syncState, agentName, ::syncIntervalMs)
    private val protocol = TwinSyncProtocol(serverSupplier, transportSupplier, peers, _syncState, deviceId, agentName) { qosLevel }
    private val security = TwinSyncSecurity(peers, _syncState, transportSupplier, deviceId)

    // ── Public API ──────────────────────────────────────────────────

    /** Start auto-sync in background. Interval varies by QoS. */
    fun startAutoSync() {
        if (autoSyncJob?.isActive == true) return
        autoSyncJob = scope.launch {
            while (isActive) {
                try {
                    autoDetectQos()
                    protocol.syncWithAllPeers()
                } catch (e: Exception) {
                    ErrorCollector.report(e, "TwinSyncEngine.autoSync")
                }
                delay(syncIntervalMs())
            }
        }
        heartbeat.start()
    }

    /** Stop auto-sync. */
    fun stopAutoSync() {
        autoSyncJob?.cancel()
        autoSyncJob = null
        heartbeat.stop()
    }

    /** Handle incoming heartbeat — mark peer as online. */
    fun onHeartbeatReceived(peerId: String) = heartbeat.onHeartbeatReceived(peerId)

    // ── QoS ────────────────────────────────────────────────────────

    private fun syncIntervalMs(): Long = when (qosLevel) {
        QosLevel.WIFI -> 60_000L
        QosLevel.MOBILE -> 300_000L
        QosLevel.METERED -> Long.MAX_VALUE // never auto-sync
    }

    private fun autoDetectQos() {
        try {
            val ctx = MemoryTwinPlugin.appContext ?: return
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val nc = cm.activeNetwork ?: return
            val caps = cm.getNetworkCapabilities(nc) ?: return
            qosLevel = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> QosLevel.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) QosLevel.METERED
                    else QosLevel.MOBILE
                }
                else -> QosLevel.WIFI // default
            }
        } catch (_: Exception) { /* keep current qosLevel */ }
    }

    // ── Peer registry (delegated) ──────────────────────────────────

    /** Update the peer list from discovery. */
    fun updatePeers(discovered: List<TwinPeerInfo>) = peerRegistry.updatePeers(discovered)

    /** Manually add a peer by IP address (for networks where mDNS fails). */
    fun addManualPeer(address: String, port: Int = com.mengpaw.kernel.ports.Ports.ACP, name: String? = null): TwinPeerInfo =
        peerRegistry.addManualPeer(address, port, name)

    /** Get known peers (defensive copy). */
    fun getPeers(): List<TwinPeerInfo> = peerRegistry.getPeers()

    /** Get the ACP transport (for use by pairing engine). */
    fun getTransport(): AcpTransport? = transportSupplier()

    /** Called after pairing is established with a peer. */
    fun onPairingEstablished(peerId: String) = peerRegistry.onPairingEstablished(peerId)

    /** Called when a peer announces its capabilities. */
    fun onCapabilityReceived(peerId: String, cardJson: String) = peerRegistry.onCapabilityReceived(peerId, cardJson)

    /** Called when a peer delegates a task. */
    fun onTwinDelegateReceived(
        fromPeerId: String, task: String, requirements: String,
        delegateId: String = "", callbackAddress: String = "", callbackPort: Int = 0
    ) = peerRegistry.onTwinDelegateReceived(fromPeerId, task, requirements, delegateId, callbackAddress, callbackPort)

    // ── Sync protocol (delegated) ──────────────────────────────────

    /**
     * Execute a full sync cycle with a specific peer (v0.22.0 工作区文件同步):
     * 1. WS_MANIFEST: 发本机清单, 响应 = 对端给我们的文件 (send) + 对端缺的文件列表 (request)
     * 2. WS_PULL: 请求我们缺的文件内容
     * 3. TwinWorkspace LWW 落盘 (冲突 → .conflict 备份)
     */
    suspend fun syncWithPeer(peerId: String): TwinSyncResult = protocol.syncWithPeer(peerId)

    /** Sync with all known online peers. */
    suspend fun syncWithAllPeers(): List<TwinSyncResult> = protocol.syncWithAllPeers()

    /** Announce our capability card to a peer. */
    suspend fun announceCapability(peerId: String, card: String): Boolean = protocol.announceCapability(peerId, card)

    /** Announce capability to all known peers. */
    suspend fun broadcastCapability(card: String) = protocol.broadcastCapability(card)

    // ── Revoke handling (delegated) ────────────────────────────────

    /**
     * Called when a revoke command is received from a peer.
     * Removes trust, marks entries as compromised, writes audit log.
     */
    fun onRevokeReceived(peerId: String) = security.onRevokeReceived(peerId)

    /**
     * Send a revoke command to all online peers.
     * Used by twin.lost CLI to broadcast device loss.
     */
    suspend fun broadcastRevoke(targetPeerId: String) = security.broadcastRevoke(targetPeerId)

    // ── Event hooks (called by TwinAcpHandler) ─────────────────────

    // v0.22.0: 同步结果由 syncWithPeer 请求-响应直接返回 — 无需异步事件钩子
}
