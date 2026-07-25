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

    /** Get known peers. */
    fun getPeers(): List<TwinPeerInfo> = peers.values.toList()

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
            val result = withTimeoutOrNull(15_000L) { deferred.await() }
                ?: TwinSyncResult(0, "同步超时 (15s)", "对端未在规定时间内响应。检查: 1) 对端是否在线 2) ACP 端口 9876 是否互通 3) 防火墙是否拦截")
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
        // Resolve pending sync deferred
        pendingSyncs[fromPeerId]?.complete(
            TwinSyncResult(entries.size, null, null)
        )
        // Trigger memory.md rebuild from merged ledger
        scope.launch { rebuildMemoryDoc() }
        // Propagate dream entries to DREAM.md
        entries.filter { it.type == EntryType.DREAM }.forEach { entry ->
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

        // Write pairing request to inbox file — UI polls this
        val card = try { CapabilityCard.fromJson(cardJson) } catch (_: Exception) { null }
        try {
            val inbox = File(DataPaths.AGENT_INBOX)
            inbox.mkdirs()
            val file = File(inbox, "twin_pair_${peerId.take(16)}.json")
            val json = org.json.JSONObject().apply {
                put("peerId", peerId)
                put("deviceName", card?.deviceName ?: peerId.take(12))
                put("deviceModel", card?.deviceModel ?: "")
                put("agentName", card?.deviceName ?: "")
                put("receivedAt", System.currentTimeMillis())
                put("capabilityCard", cardJson)
                put("protocolVersion", card?.protocolVersion ?: "0.1")
            }
            val tmp = File(inbox, "twin_pair_${peerId.take(16)}.tmp")
            tmp.writeText(json.toString())
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
            if (docFile.exists() && docFile.lastModified() > entry.timestamp) return // Local is newer
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
