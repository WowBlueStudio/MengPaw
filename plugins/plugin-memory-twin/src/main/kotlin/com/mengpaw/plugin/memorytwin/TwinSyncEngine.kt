// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.acp.*
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.ports.Ports
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File

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
    private var heartbeatJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    /** Known peers. */
    private val peers = mutableMapOf<String, TwinPeerInfo>()

    /** QoS level: WIFI (full), MOBILE (key only), METERED (manual only). */
    @Volatile var qosLevel: QosLevel = QosLevel.WIFI
    enum class QosLevel { WIFI, MOBILE, METERED }

    // ── P1.5: Runtime state (injected from AgentEngine) ────────────────

    /** Current session ID (set by MemoryTwinPlugin when a task starts). */
    @Volatile var currentSessionId: String? = null
    /** Whether this agent is currently executing a task. */
    @Volatile var isBusy: Boolean = false

    // ── Public API ──────────────────────────────────────────────────

    /** Start auto-sync in background. Interval varies by QoS. */
    fun startAutoSync() {
        if (autoSyncJob?.isActive == true) return
        autoSyncJob = scope.launch {
            while (isActive) {
                try {
                    autoDetectQos()
                    syncWithAllPeers()
                } catch (e: Exception) {
                    ErrorCollector.report(e, "TwinSyncEngine.autoSync")
                }
                delay(syncIntervalMs())
            }
        }
        startHeartbeat()
    }

    /** Stop auto-sync. */
    fun stopAutoSync() {
        autoSyncJob?.cancel()
        autoSyncJob = null
        stopHeartbeat()
    }

    // ── Heartbeat ──────────────────────────────────────────────────

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                try {
                    val transport = transportSupplier() ?: return@launch
                    peers.values.forEach { peer ->
                        try {
                            val msg = com.mengpaw.kernel.acp.AcpMessage.heartbeat(deviceId)
                            transport.send(msg)
                        } catch (_: Exception) { /* peer unreachable */ }
                    }
                    // Mark peers offline if no contact for 90 seconds
                    val cutoff = System.currentTimeMillis() - 90_000
                    peers.values.forEach { peer ->
                        if (peer.lastSeen < cutoff && peer.online) {
                            peer.online = false
                            android.util.Log.i("MengPawTwin", "对端离线: ${peer.peerId.take(12)}... (${peer.agentName})")
                        }
                    }
                    _syncState.value = _syncState.value.copy(
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

    private fun stopHeartbeat() {
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

    /** Update the peer list from discovery. */
    fun updatePeers(discovered: List<TwinPeerInfo>) {
        discovered.forEach { peer ->
            val existing = peers[peer.peerId]
            if (existing == null || existing.address != peer.address) {
                peers[peer.peerId] = peer.copy(lastSeen = System.currentTimeMillis(), online = true)
            } else {
                existing.lastSeen = System.currentTimeMillis()
                existing.online = true
                if (existing.address != peer.address || existing.port != peer.port) {
                    existing.address = peer.address
                    existing.port = peer.port
                }
            }
        }
        _syncState.value = _syncState.value.copy(
            onlinePeers = peers.values.count { it.online },
            totalPeers = peers.size
        )
    }

    /** Manually add a peer by IP address (for networks where mDNS fails). */
    fun addManualPeer(address: String, port: Int = Ports.ACP, name: String? = null): TwinPeerInfo {
        val peerId = name ?: "manual-${address.replace(".", "-")}"
        // Clean up old manual entry for this address
        peers.values.removeAll { it.address == address && it.peerId.startsWith("manual-") }
        val peer = TwinPeerInfo(
            peerId = peerId, agentName = peerId,
            address = address, port = port,
            lastSeen = System.currentTimeMillis(), online = true
        )
        peers[peerId] = peer
        return peer
    }

    /** Get known peers (defensive copy). */
    fun getPeers(): List<TwinPeerInfo> = peers.values.toList().map { it.copy() }

    /** Get the ACP transport (for use by pairing engine). */
    fun getTransport(): AcpTransport? = transportSupplier()

    /** Called after pairing is established with a peer. */
    fun onPairingEstablished(peerId: String) {
        peers[peerId]?.let {
            it.lastSeen = System.currentTimeMillis()
            it.lastSyncAt = System.currentTimeMillis()
            it.online = true
        }
        // Inject post-pairing guidance into agent workspace
        scope.launch { injectPairingGuidance(peerId) }
    }

    /**
     * Execute a full sync cycle with a specific peer (v0.22.0 工作区文件同步):
     * 1. WS_MANIFEST: 发本机清单, 响应 = 对端给我们的文件 (send) + 对端缺的文件列表 (request)
     * 2. WS_PULL: 请求我们缺的文件内容
     * 3. TwinWorkspace LWW 落盘 (冲突 → .conflict 备份)
     */
    suspend fun syncWithPeer(peerId: String): TwinSyncResult {
        val server = serverSupplier()
        if (server == null) return TwinSyncResult(0, 0, 0, "ACP 服务未启动", "请先执行 self.acp start")
        val transport = transportSupplier()
        if (transport == null) return TwinSyncResult(0, 0, 0, "ACP 传输层未初始化", null)

        return try {
            val peer = peers[peerId]
            if (peer == null) return TwinSyncResult(0, 0, 0, "节点未发现",
                "请确认对端设备在同一网络且已启动孪生服务。也可用 twin.peer.add <ip> 手动添加。")

            // Register peer in ACP server so transport can reach it
            server.registerPeer(PeerAgent(
                agentId = peerId, agentName = peer.agentName,
                address = peer.address, port = peer.port,
                capabilities = listOf("memory-twin/0.1")
            ))

            // Step 1: 交换清单 — 请求-响应一轮完成 (响应体解析修复见 AcpTransport.sendForResult)
            val myManifest = TwinWorkspace.buildManifest(agentName)
            val manifestMsg = AcpMessage.wsManifest(deviceId, peerId, buildJsonObject {
                put("files", buildJsonObject {
                    myManifest.forEach { (relPath, entry) ->
                        put(relPath, buildJsonObject {
                            put("hash", JsonPrimitive(entry.hash.take(16)))
                            put("mtime", JsonPrimitive(entry.mtime))
                        })
                    }
                })
            }.toString())
            val manifestResp = transport.sendForResult(manifestMsg, peerId, 20_000)
            if (manifestResp == null) {
                return TwinSyncResult(0, 0, 0, "同步超时 (20s)",
                    "对端未在规定时间内响应。检查: 1) 对端是否在线 2) ACP 端口 ${Ports.ACP} 是否互通 3) 防火墙是否拦截")
            }
            if (!manifestResp.success) {
                return TwinSyncResult(0, 0, 0, manifestResp.message, null)
            }

            val respObj = try { json.parseToJsonElement(manifestResp.data).jsonObject } catch (e: Exception) {
                return TwinSyncResult(0, 0, 0, "清单响应解析失败: ${e.message}", null)
            }

            // 接收对端文件 (对端 → 本机)
            var received = 0; var conflicts = 0
            respObj["send"]?.jsonObject?.forEach { (relPath, value) ->
                val fileObj = value.jsonObject
                val content = fileObj["content"]?.jsonPrimitive?.content ?: ""
                val mtime = fileObj["mtime"]?.jsonPrimitive?.long ?: System.currentTimeMillis()
                val r = TwinWorkspace.applyWorkspaceFile(agentName, relPath, content, peer.agentName, mtime)
                when (r) {
                    "applied" -> received++
                    "conflict" -> conflicts++
                }
            }

            // Step 2: 拉取我们缺的文件 (本机 → 对端)
            val requestPaths = respObj["request"]?.jsonArray?.mapNotNull { it.jsonPrimitive?.content } ?: emptyList()
            if (requestPaths.isNotEmpty()) {
                val pullMsg = AcpMessage.wsPull(deviceId, peerId, buildJsonObject {
                    put("paths", buildJsonArray { requestPaths.forEach { add(JsonPrimitive(it)) } })
                }.toString())
                val pullResp = transport.sendForResult(pullMsg, peerId, 20_000)
                if (pullResp != null && pullResp.success) {
                    try {
                        val filesObj = json.parseToJsonElement(pullResp.data).jsonObject["files"]?.jsonObject
                        filesObj?.forEach { (relPath, content) ->
                            val r = TwinWorkspace.applyWorkspaceFile(agentName, relPath, content.jsonPrimitive.content,
                                peer.agentName, System.currentTimeMillis())
                            when (r) {
                                "applied" -> received++
                                "conflict" -> conflicts++
                            }
                        }
                    } catch (e: Exception) {
                        ErrorCollector.report(e, "TwinSyncEngine.wsPull.parse")
                    }
                }
            }

            // 更新对端状态
            peers[peerId]?.let {
                it.lastSyncAt = System.currentTimeMillis()
                it.online = true
            }
            _syncState.value = _syncState.value.copy(
                lastFilesReceived = received,
                lastConflicts = conflicts,
                lastSyncAt = System.currentTimeMillis()
            )
            TwinSyncResult(received, requestPaths.size, conflicts, null, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinSyncEngine.syncWithPeer($peerId)")
            TwinSyncResult(0, 0, 0, "同步异常: ${e.message}", "请重试。若持续失败，检查对端 ACP 状态和网络连通性。")
        }
    }

    /** Sync with all known online peers. */
    suspend fun syncWithAllPeers(): List<TwinSyncResult> {
        if (qosLevel == QosLevel.METERED) {
            return listOf(TwinSyncResult(0, 0, 0, "按流量计费模式下已暂停自动同步", "使用 twin.sync 手动触发"))
        }
        val online = peers.values.filter { it.online }
        _syncState.value = _syncState.value.copy(
            phase = SyncPhase.SYNCING, totalPeers = online.size, completedPeers = 0
        )
        val results = mutableListOf<TwinSyncResult>()
        online.forEach { peer ->
            val result = syncWithPeer(peer.peerId)
            results.add(result)
            if (result.filesReceived > 0 || result.conflicts > 0) {
                _syncState.value = _syncState.value.copy(
                    completedPeers = _syncState.value.completedPeers + 1
                )
            }
        }
        _syncState.value = _syncState.value.copy(
            phase = SyncPhase.IDLE,
            completedPeers = _syncState.value.completedPeers,
            lastSyncAt = System.currentTimeMillis()
        )
        return results
    }

    /** Announce our capability card to a peer. */
    suspend fun announceCapability(peerId: String, card: String): Boolean {
        val transport = transportSupplier() ?: return false
        return try {
            val msg = AcpMessage.capabilityAnnounce(deviceId, peerId, card)
            transport.send(msg)
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinSyncEngine.announceCapability")
            false
        }
    }

    /** Announce capability to all known peers. */
    suspend fun broadcastCapability(card: String) {
        val transport = transportSupplier() ?: return
        peers.keys.forEach { peerId ->
            try {
                val msg = AcpMessage.capabilityAnnounce(deviceId, peerId, card)
                transport.send(msg)
            } catch (_: Exception) { /* best effort */ }
        }
    }

    // ── Event hooks (called by TwinAcpHandler) ─────────────────────

    // v0.22.0: 同步结果由 syncWithPeer 请求-响应直接返回 — 无需异步事件钩子

    // ── P1.2/P2.3: Revoke handling ─────────────────────────────────

    /**
     * Called when a revoke command is received from a peer.
     * Removes trust, marks entries as compromised, writes audit log.
     */
    fun onRevokeReceived(peerId: String) {
        // 1. Remove trust
        com.mengpaw.kernel.security.PromptFirewall.untrust(peerId)
        // 2. Remove the key material
        try {
            val keyFile = java.io.File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED, "$peerId.key")
            if (keyFile.exists()) keyFile.delete()
        } catch (_: Exception) {}
        // 3. (v0.22.0) 账本已移除 — 工作区文档由各设备本地持有, 解绑不标记
        android.util.Log.w("MengPawTwin", "孪生解绑: peer=$peerId")
        // 4. Write audit log
        try {
            val auditFile = java.io.File(com.mengpaw.kernel.DataPaths.TWIN_AUDIT)
            auditFile.parentFile?.mkdirs()
            auditFile.appendText(
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date()) +
                " | REVOKE | peer=$peerId\n"
            )
        } catch (_: Exception) {}
        // 5. Remove peer from peer list
        peers.remove(peerId)
        // 6. Update state
        _syncState.value = _syncState.value.copy(
            onlinePeers = peers.values.count { it.online },
            totalPeers = peers.size
        )
    }

    /**
     * Send a revoke command to all online peers.
     * Used by twin.lost CLI to broadcast device loss.
     */
    suspend fun broadcastRevoke(targetPeerId: String) {
        val transport = transportSupplier() ?: return
        val peersToNotify = peers.values.filter { it.online && it.peerId != targetPeerId }
        peersToNotify.forEach { peer ->
            try {
                val msg = com.mengpaw.kernel.acp.AcpMessage.revoke(deviceId, peer.peerId, targetPeerId)
                transport.send(msg)
                android.util.Log.i("MengPawTwin", "已发送解绑广播到 ${peer.peerId}")
            } catch (e: Exception) {
                android.util.Log.w("MengPawTwin", "发送解绑广播到 ${peer.peerId} 失败: ${e.message}")
            }
        }
    }

    // ── Rate limiting for capability announces ──────────────────────
    private val lastCapabilityAnnounce = mutableMapOf<String, Long>()

    /** Called when a peer announces its capabilities. */
    fun onCapabilityReceived(peerId: String, cardJson: String) {
        peers[peerId]?.let {
            it.capabilityCard = cardJson
            it.lastSeen = System.currentTimeMillis()
            it.online = true
        } ?: run {
            // New peer discovered via capability announce
            val card = try { CapabilityCard.fromJson(cardJson) } catch (_: Exception) { null }
            peers[peerId] = TwinPeerInfo(
                peerId = peerId,
                agentName = card?.deviceName ?: peerId.take(12),
                address = "", port = Ports.ACP,
                lastSeen = System.currentTimeMillis(), online = true,
                capabilityCard = cardJson
            )
        }

        // Rate limit: max 1 inbox entry per peer per 30 seconds
        val lastTime = lastCapabilityAnnounce[peerId] ?: 0L
        if (System.currentTimeMillis() - lastTime < 30_000) return
        lastCapabilityAnnounce[peerId] = System.currentTimeMillis()

        // P0.1 FIX: Only write pairing request to inbox if peer is trusted.
        // Unpaired devices are still registered (for discovery) but won't
        // appear as pending pair requests in the UI.
        val isTrusted = com.mengpaw.kernel.security.PromptFirewall.isTrusted(peerId)
        if (!isTrusted) {
            android.util.Log.i("MengPawTwin", "跳过未配对设备配对请求: $peerId")
            return
        }

        // Write pairing request to inbox file — UI polls this
        val card = try { CapabilityCard.fromJson(cardJson) } catch (_: Exception) { null }
        try {
            val inbox = File(DataPaths.AGENT_INBOX)
            inbox.mkdirs()
            val file = File(inbox, "twin_pair_${peerId.take(16)}.json")
            // P3.4 FIX: Use kotlinx.serialization instead of org.json
            val jsonStr = buildJsonObject {
                put("peerId", peerId)
                put("deviceName", card?.deviceName ?: peerId.take(12))
                put("deviceModel", card?.deviceModel ?: "")
                put("agentName", card?.deviceName ?: "")
                put("receivedAt", System.currentTimeMillis())
                put("capabilityCard", cardJson)
                put("protocolVersion", card?.protocolVersion ?: "0.1")
            }.toString()
            val tmp = File(inbox, "twin_pair_${peerId.take(16)}.tmp")
            tmp.writeText(jsonStr)
            tmp.renameTo(file)
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinSyncEngine.onCapability")
        }
    }

    /** Called when a peer delegates a task. */
    fun onTwinDelegateReceived(fromPeerId: String, task: String, requirements: String) {
        if (!com.mengpaw.kernel.security.PromptFirewall.isTrusted(fromPeerId)) {
            android.util.Log.w("MengPawTwin", "拒绝未配对设备的委派任务: $fromPeerId")
            return
        }
        scope.launch {
            val inboxDir = File(DataPaths.AGENTS, "$agentName/inbox")
            if (!inboxDir.exists()) inboxDir.mkdirs()
            val taskFile = File(inboxDir, "twin_delegate_${System.currentTimeMillis()}.md")
            taskFile.writeText(
                buildString {
                    appendLine("# 孪生任务委派")
                    appendLine("> 来自: $fromPeerId")
                    appendLine("> 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
                    if (requirements.isNotBlank()) appendLine("> 能力需求: $requirements")
                    appendLine()
                    appendLine(task)
                }
            )
        }
    }

    // ── Internal ────────────────────────────────────────────────────

    /** Inject post-pairing guidance into the agent workspace. */
    private fun injectPairingGuidance(peerId: String) {
        try {
            val peer = peers[peerId] ?: return
            val guidanceFile = File(DataPaths.AGENTS, "$agentName/inbox/twin_paired_${System.currentTimeMillis()}.md")
            guidanceFile.parentFile?.mkdirs()
            val tmp = File(guidanceFile.parentFile, "twin_paired.tmp")
            tmp.writeText(buildString {
                appendLine("# 🧠 记忆孪生配对完成")
                appendLine()
                appendLine("## 配对节点")
                appendLine("- 设备: ${peer.agentName}")
                appendLine("- 地址: ${peer.address}:${peer.port}")
                appendLine("- 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
                appendLine()
                appendLine("## 自动同步")
                appendLine("- 同步周期: 每 ${syncIntervalMs() / 1000} 秒")
                appendLine("- 同步内容: 记忆 | 身份文档 | 梦境记录")
                appendLine("- 验证方式: SHA-256 哈希链")
                appendLine()
                appendLine("## 常用命令")
                appendLine("- `twin.status` — 查看孪生状态和同步阶段")
                appendLine("- `twin.peers` — 查看所有已发现节点")
                appendLine("- `twin.sync` — 手动触发工作区同步 (接收/发送/冲突数)")
                appendLine("- `twin.delegate <peer> <task>` — 委派任务到对端")
                appendLine("- `twin.capabilities --all` — 对比所有节点能力")
                appendLine("- `twin.sync` — 手动触发全量同步")
                appendLine()
                appendLine("## 安全提示")
                appendLine("- 仅在受信任的个人设备间使用孪生配对")
                appendLine("- 在侧边栏框架名片中可随时解除孪生")
                appendLine("- 所有记忆通过 AES-256 加密通道传输")
            })
            tmp.renameTo(guidanceFile)
            if (tmp.exists()) try { tmp.delete() } catch (_: Exception) {}
            android.util.Log.i("MengPawTwin", "已注入配对指引到工作区")
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinSyncEngine.injectPairingGuidance")
        }
    }

}

// ── Supporting types ──────────────────────────────────────────────────

/** Information about a twin peer known on the LAN. */
data class TwinPeerInfo(
    val peerId: String,
    var agentName: String,
    var address: String,
    var port: Int = Ports.ACP,
    var lastSeen: Long = System.currentTimeMillis(),
    var lastSyncAt: Long = 0L,
    var capabilityCard: String? = null,
    var online: Boolean = true
)

/** Sync phase for UI status display. */
enum class SyncPhase { IDLE, DISCOVERING, SYNCING, MERGING, ERROR }

/** Observable sync state. */
data class TwinSyncState(
    val phase: SyncPhase = SyncPhase.IDLE,
    val totalPeers: Int = 0,
    val onlinePeers: Int = 0,
    val completedPeers: Int = 0,
    val lastFilesReceived: Int = 0,
    val lastConflicts: Int = 0,
    val lastSyncAt: Long = 0L
)

/** Concrete sync result — no more silent "return 0". */
data class TwinSyncResult(
    val filesReceived: Int,
    val filesSent: Int,
    val conflicts: Int,
    val error: String?,
    val suggestion: String?
)
