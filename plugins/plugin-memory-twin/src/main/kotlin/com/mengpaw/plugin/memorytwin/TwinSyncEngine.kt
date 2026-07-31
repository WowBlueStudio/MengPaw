// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.memorytwin

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.acp.*
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Memory Twin sync engine — the central orchestrator for twin synchronization.
 *
 * Manages the full sync lifecycle:
 *   1. Discovery (via TwinDiscovery, which feeds peer addresses)
 *   2. Head exchange (LEDGER_HEAD)
 *   3. Pull missing entries (LEDGER_PULL → LEDGER_BATCH)
 *   4. Verify & merge
 *   5. Ack (LEDGER_ACK)
 *
 * Also handles: heartbeat monitoring, QoS enforcement, dream event propagation,
 * identity doc sync, capability card updates, and task delegation intake.
 */
class TwinSyncEngine(
    private val serverSupplier: () -> AcpServer?,
    private val transportSupplier: () -> AcpTransport?,
    private val agentName: String,
    private val deviceId: String,
    private val deviceName: String
) {
    // ── State ──────────────────────────────────────────────────────

    private val _syncState = MutableStateFlow(TwinSyncState())
    val syncState: StateFlow<TwinSyncState> = _syncState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var autoSyncJob: Job? = null
    private var heartbeatJob: Job? = null

    /** Known peers with their latest ledger hash. */
    private val peers = mutableMapOf<String, TwinPeerInfo>()

    /** Pending sync completions — resolved when BATCH response arrives. */
    private val pendingSyncs = mutableMapOf<String, CompletableDeferred<TwinSyncResult>>()

    /** QoS level: WIFI (full), MOBILE (key only), METERED (manual only). */
    @Volatile var qosLevel: QosLevel = QosLevel.WIFI
    enum class QosLevel { WIFI, MOBILE, METERED }

    // ── P1.5: Runtime state (injected from AgentEngine) ────────────────

    /** Current session ID (set by MemoryTwinPlugin when a task starts). */
    @Volatile var currentSessionId: String? = null
    /** Whether this agent is currently executing a task. */
    @Volatile var isBusy: Boolean = false

    // ── P1.3: Dream coordinator ───────────────────────────────────────

    /** Timestamp of the most recent DREAM entry from any peer (epoch ms, 0 = never). */
    @Volatile private var lastClusterDreamTimestamp: Long = 0L
    /** Which device ran the most recent cluster dream. */
    @Volatile private var lastClusterDreamDevice: String = ""
    /** Minimum gap between cluster-triggered dreams (hours). */
    private val DREAM_COOLDOWN_HOURS = 6L

    /**
     * Check if the local device should trigger a dream pass.
     * Returns true only if no other peer has run a dream recently.
     */
    fun shouldRunLocalDream(): Boolean {
        val now = System.currentTimeMillis()
        if (lastClusterDreamTimestamp > 0) {
            val elapsedHours = (now - lastClusterDreamTimestamp) / 3_600_000
            if (elapsedHours < DREAM_COOLDOWN_HOURS) {
                android.util.Log.i("MengPawTwin",
                    "集群梦境协调: 跳过 — ${lastClusterDreamDevice} 已在 ${elapsedHours}h 前执行梦境")
                return false
            }
        }
        return true
    }

    /**
     * Called when a local dream completes — records it as the cluster's
     * latest dream so other peers don't redundantly trigger.
     */
    fun onLocalDreamCompleted(deviceName: String) {
        lastClusterDreamTimestamp = System.currentTimeMillis()
        lastClusterDreamDevice = deviceName
    }

    /** Get cluster dream status for info display. */
    fun dreamCoordinatorStatus(): String {
        if (lastClusterDreamTimestamp == 0L) return "集群尚未执行过梦境"
        val ago = (System.currentTimeMillis() - lastClusterDreamTimestamp) / 3_600_000
        return "上次梦境: ${ago}h 前 (由 $lastClusterDreamDevice)"
    }

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
    fun addManualPeer(address: String, port: Int = 9876, name: String? = null): TwinPeerInfo {
        val peerId = name ?: "manual-${address.replace(".", "-")}"
        // Clean up old manual entry for this address
        peers.values.removeAll { it.address == address && it.peerId.startsWith("manual-") }
        val peer = TwinPeerInfo(
            peerId = peerId, agentName = peerId,
            address = address, port = port,
            lastSeen = System.currentTimeMillis(), online = true
        )
        peers[peerId] = peer
        persistPeerInfo()
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
        persistPeerInfo()
        // Inject post-pairing guidance into agent workspace
        scope.launch { injectPairingGuidance(peerId) }
    }

    /**
     * Execute a full sync cycle with a specific peer.
     * Returns a [TwinSyncResult] with actual sync outcome — no more silent failures.
     */
    suspend fun syncWithPeer(peerId: String): TwinSyncResult {
        val server = serverSupplier()
        if (server == null) return TwinSyncResult(0, "ACP 服务未启动", "请先执行 self.acp start")
        val transport = transportSupplier()
        if (transport == null) return TwinSyncResult(0, "ACP 传输层未初始化", null)

        return try {
            val peer = peers[peerId]
            if (peer == null) return TwinSyncResult(0, "节点未发现", "请确认对端设备在同一网络且已启动孪生服务。也可用 twin.peer.add <ip> 手动添加。")

            // Register peer in ACP server so transport can reach it
            server.registerPeer(PeerAgent(
                agentId = peerId, agentName = peer.agentName,
                address = peer.address, port = peer.port,
                capabilities = listOf("memory-twin/0.1")
            ))

            // Step 1: Exchange ledger heads
            val localLatest = TwinLedgerStore.latest()
            val headMsg = AcpMessage.ledgerHead(
                deviceId, peerId,
                localLatest?.hash ?: "",
                TwinLedgerStore.count()
            )
            transport.send(headMsg)

            // Step 2: Request missing entries
            val localHash = localLatest?.hash ?: ""
            val pullMsg = AcpMessage.ledgerPull(deviceId, peerId, localHash, 100)
            transport.send(pullMsg)

            // Step 3: Wait for BATCH response (or timeout)
            val deferred = CompletableDeferred<TwinSyncResult>()
            pendingSyncs[peerId] = deferred
            val result = withTimeoutOrNull(15_000L) {
                deferred.await()
            } ?: run {
                // P0.2 FIX: Try-complete the deferred with timeout result so late
                // onEntriesReceived doesn't operate a stale deferred (CAS-safe).
                val timeoutResult = TwinSyncResult(0, "同步超时 (15s)",
                    "对端未在规定时间内响应。检查: 1) 对端是否在线 2) ACP 端口 9876 是否互通 3) 防火墙是否拦截")
                deferred.complete(timeoutResult) // complete() returns false if already done — safe
                timeoutResult
            }
            pendingSyncs.remove(peerId)
            result
        } catch (e: CancellationException) {
            pendingSyncs.remove(peerId)
            throw e
        } catch (e: Exception) {
            pendingSyncs.remove(peerId)
            ErrorCollector.report(e, "TwinSyncEngine.syncWithPeer($peerId)")
            TwinSyncResult(0, "同步异常: ${e.message}", "请重试。若持续失败，检查对端 ACP 状态和网络连通性。")
        }
    }

    /** Sync with all known online peers. */
    suspend fun syncWithAllPeers(): List<TwinSyncResult> {
        if (qosLevel == QosLevel.METERED) {
            return listOf(TwinSyncResult(0, "按流量计费模式下已暂停自动同步", "使用 twin.sync 手动触发"))
        }
        val online = peers.values.filter { it.online }
        _syncState.value = _syncState.value.copy(
            phase = SyncPhase.SYNCING, totalPeers = online.size, completedPeers = 0
        )
        val results = mutableListOf<TwinSyncResult>()
        online.forEach { peer ->
            val result = syncWithPeer(peer.peerId)
            results.add(result)
            if (result.entriesReceived > 0) {
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

    /** Called when new entries are received and merged into the local ledger. */
    fun onEntriesReceived(fromPeerId: String, entries: List<LedgerEntry>) {
        _syncState.value = _syncState.value.copy(
            lastEntriesReceived = entries.size,
            lastSyncAt = System.currentTimeMillis()
        )
        // Resolve pending sync deferred (CAS-safe: no-op if already completed by timeout)
        pendingSyncs[fromPeerId]?.complete(
            TwinSyncResult(entries.size, null, null)
        )
        // Trigger memory.md rebuild from merged ledger
        scope.launch { rebuildMemoryDoc() }
        // P1.3: Propagate dream entries & update cluster dream coordinator
        entries.filter { it.type == EntryType.DREAM }.forEach { entry ->
            lastClusterDreamTimestamp = maxOf(lastClusterDreamTimestamp, entry.timestamp)
            lastClusterDreamDevice = entry.deviceName
            scope.launch { applyDreamEntry(entry) }
        }
        // Apply identity updates
        entries.filter { it.type == EntryType.SOUL_UPDATE || it.type == EntryType.PROFILE_UPDATE }
            .forEach { entry ->
                scope.launch { applyIdentityUpdate(entry) }
            }
    }

    /** Called when a peer acknowledges our entries. */
    fun onAckReceived(peerId: String, hash: String) {
        peers[peerId]?.let {
            it.lastAckedHash = hash
            it.lastSyncAt = System.currentTimeMillis()
            it.online = true
        }
    }

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
        // 3. Mark this peer's ledger entries as compromised
        val entries = TwinLedgerStore.loadAll()
        val compromisedIds = entries.filter { it.deviceId == peerId }.map { it.hash }.toSet()
        android.util.Log.w("MengPawTwin",
            "孪生解绑: peer=$peerId, 共 ${compromisedIds.size} 条记忆标记为 compromised")
        // 4. Write audit log
        try {
            val auditFile = java.io.File(com.mengpaw.kernel.DataPaths.TWIN_AUDIT)
            auditFile.parentFile?.mkdirs()
            auditFile.appendText(
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date()) +
                " | REVOKE | peer=$peerId | entries=${compromisedIds.size}\n"
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
                address = "", port = 9876,
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
        persistPeerInfo()
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
                appendLine("- `twin.ledger.stats` — 查看账本统计和来源分布")
                appendLine("- `twin.ledger.verify` — 验证记忆链完整性")
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

    /** Rebuild the agent's memory.md from the merged ledger. */
    private suspend fun rebuildMemoryDoc() {
        try {
            val entries = TwinLedgerStore.loadAll().filter { it.type == EntryType.MEMORY }
            val doc = buildString {
                appendLine("---")
                appendLine("# 记忆索引 (孪生同步)")
                appendLine()
                appendLine("> 索引更新: ${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date())}")
                appendLine("> 总条目: ${entries.size} | 来源设备: ${entries.map { it.deviceName }.distinct().joinToString()}")
                appendLine()
                appendLine("| ID | 日期 | 设备 | 标题 | 关键词 |")
                appendLine("|----|------|------|------|--------|")
                entries.forEach { entry ->
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(entry.timestamp))
                    val title = entry.content.take(60).replace("\n", " ").replace("|", "\\|").replace("\r", "")
                    val tags = entry.tags.joinToString(", ") { it.replace("|", "\\|") }
                    val safeDeviceName = entry.deviceName.replace("|", "\\|")
                    appendLine("| ${entry.id} | $date | $safeDeviceName | $title | $tags |")
                }
                appendLine()
                appendLine("---")
                appendLine()
                entries.forEach { entry ->
                    appendLine("## ${entry.id}: ${entry.content.take(60).replace("\n", " ")}")
                    appendLine("- **日期**: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(entry.timestamp))}")
                    appendLine("- **设备**: ${entry.deviceName}")
                    appendLine("- **关键词**: ${entry.tags.joinToString(", ")}")
                    appendLine("- **内容**: ${entry.content}")
                    appendLine()
                    appendLine("---")
                    appendLine()
                }
            }
            // Write to long-term memory (injected into system prompt)
            val memFile = File(DataPaths.longTermMemoryFile(agentName))
            memFile.parentFile?.mkdirs()
            val tmp = File(memFile.parentFile, "memory.tmp")
            tmp.writeText(doc)
            if (memFile.exists()) memFile.delete()
            tmp.renameTo(memFile)
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinSyncEngine.rebuildMemoryDoc")
        }
    }

    /** Apply a dream entry to DREAM.md — atomic write to prevent corruption. */
    private fun applyDreamEntry(entry: LedgerEntry) {
        try {
            val dreamDir = File(DataPaths.TWIN_DREAMS)
            if (!dreamDir.exists()) dreamDir.mkdirs()
            val dreamFile = File(dreamDir, "DREAM.md")
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(entry.timestamp))
            val dreamContent = buildString {
                if (!dreamFile.exists()) {
                    appendLine("# $agentName · 孪生梦境记录 (同步)")
                }
                appendLine()
                appendLine("---")
                appendLine("## $timestamp · 来自 ${entry.deviceName}")
                appendLine()
                appendLine(entry.content)
                appendLine()
            }
            val existing = if (dreamFile.exists()) try { dreamFile.readText() } catch (_: Exception) { "" } else ""
            // Atomic write
            val tmp = File(dreamDir, "DREAM.tmp")
            tmp.writeText(dreamContent + existing)
            if (dreamFile.exists()) dreamFile.delete()
            tmp.renameTo(dreamFile)
            if (tmp.exists()) try { tmp.delete() } catch (_: Exception) {}
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinSyncEngine.applyDreamEntry")
        }
    }

    /** Apply identity document update from a twin peer. */
    private fun applyIdentityUpdate(entry: LedgerEntry) {
        try {
            val docType = when (entry.type) {
                EntryType.SOUL_UPDATE -> "soul.md"
                EntryType.PROFILE_UPDATE -> "profile.md"
                else -> return
            }
            val docFile = File(DataPaths.AGENTS, "$agentName/$docType")

            // P2.1: Conflict detection — if local is newer AND content differs, save .conflict backup
            if (docFile.exists() && docFile.lastModified() > entry.timestamp) {
                val localContent = try { docFile.readText() } catch (_: Exception) { "" }
                if (localContent != entry.content) {
                    // Content diverged — save peer version as .conflict instead of overwriting
                    val dateStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                        .format(java.util.Date())
                    val conflictFile = File(docFile.parent, "$docType.conflict.$dateStamp.from_${entry.deviceName}")
                    conflictFile.writeText(entry.content)
                    android.util.Log.w("MengPawTwin",
                        "身份文档冲突: $docType — 本地更新晚于 ${entry.deviceName} 的同步,已保存 .conflict 备份")
                    // Write audit
                    val auditFile = File(DataPaths.TWIN_AUDIT)
                    auditFile.parentFile?.mkdirs()
                    auditFile.appendText(
                        "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} | " +
                        "IDENTITY_CONFLICT | from=${entry.deviceId}(${entry.deviceName}) | type=$docType | saved=${conflictFile.name}\n"
                    )
                    // Inject notification to agent inbox
                    try {
                        val inboxNote = File(DataPaths.AGENTS, "$agentName/inbox/identity_conflict_${System.currentTimeMillis()}.md")
                        inboxNote.parentFile?.mkdirs()
                        inboxNote.writeText(
                            "⚠️ 身份文档冲突 — $docType\n\n" +
                            "本地版本和来自 ${entry.deviceName} 的同步版本存在差异。\n" +
                            "已保存对端版本到: ${conflictFile.name}\n\n" +
                            "手动解决后可用 twin.identity.push 推送最终版本到所有节点。\n"
                        )
                    } catch (_: Exception) {}
                    return // Keep local version, don't overwrite
                }
            }

            // Normal case: overwrite with received content
            val tmp = File(docFile.parent, "$docType.tmp")
            tmp.writeText(entry.content)
            if (docFile.exists()) docFile.delete()
            tmp.renameTo(docFile)

            val auditFile = File(DataPaths.TWIN_AUDIT)
            auditFile.parentFile?.mkdirs()
            auditFile.appendText(
                "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} | " +
                "IDENTITY_SYNC | from=${entry.deviceId}(${entry.deviceName}) | type=$docType\n"
            )
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinSyncEngine.applyIdentityUpdate")
        }
    }

    /** Persist peer info to disk using proper JSON serialization. */
    private fun persistPeerInfo() {
        try {
            val dir = File(DataPaths.TWIN_PEERS)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "peers.json")
            val jsonArray = org.json.JSONArray()
            peers.values.forEach { peer ->
                val obj = org.json.JSONObject().apply {
                    put("peerId", peer.peerId)
                    put("agentName", peer.agentName)
                    put("address", peer.address)
                    put("port", peer.port)
                    put("lastAckedHash", peer.lastAckedHash ?: "")
                    put("lastSeen", peer.lastSeen)
                    put("lastSyncAt", peer.lastSyncAt)
                    put("online", peer.online)
                }
                jsonArray.put(obj)
            }
            val tmp = File(dir, "peers.tmp")
            tmp.writeText(jsonArray.toString(2))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinSyncEngine.persistPeerInfo")
        }
    }
}

// ── Supporting types ──────────────────────────────────────────────────

/** Information about a twin peer known on the LAN. */
data class TwinPeerInfo(
    val peerId: String,
    var agentName: String,
    var address: String,
    var port: Int = 9876,
    var lastSeen: Long = System.currentTimeMillis(),
    var lastSyncAt: Long = 0L,
    var lastAckedHash: String? = null,
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
    val lastEntriesReceived: Int = 0,
    val lastSyncAt: Long = 0L
)

/** Concrete sync result — no more silent "return 0". */
data class TwinSyncResult(
    val entriesReceived: Int,
    val error: String?,
    val suggestion: String?
)
