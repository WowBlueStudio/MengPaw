// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.acp.*
import com.mengpaw.kernel.agent.AgentDocManager
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
 * Also handles post-sync hooks: dream event propagation, identity doc sync,
 * capability card updates, and task delegation intake.
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

    /** Known peers with their latest ledger hash. */
    private val peers = mutableMapOf<String, TwinPeerInfo>()

    // ── Public API ──────────────────────────────────────────────────

    /** Start auto-sync in background. */
    fun startAutoSync(intervalMs: Long = 60_000L) {
        if (autoSyncJob?.isActive == true) return
        autoSyncJob = scope.launch {
            while (isActive) {
                try {
                    syncWithAllPeers()
                } catch (e: Exception) {
                    ErrorCollector.report(e, "TwinSyncEngine.autoSync")
                }
                delay(intervalMs)
            }
        }
    }

    /** Stop auto-sync. */
    fun stopAutoSync() {
        autoSyncJob?.cancel()
        autoSyncJob = null
    }

    /** Update the peer list from discovery. */
    fun updatePeers(discovered: List<TwinPeerInfo>) {
        discovered.forEach { peer ->
            val existing = peers[peer.peerId]
            if (existing == null || existing.address != peer.address) {
                peers[peer.peerId] = peer.copy(lastSeen = System.currentTimeMillis())
            } else {
                existing.lastSeen = System.currentTimeMillis()
            }
        }
    }

    /** Get known peers. */
    fun getPeers(): List<TwinPeerInfo> = peers.values.toList()

    /**
     * Execute a full sync cycle with a specific peer.
     * Returns the number of new entries received.
     */
    suspend fun syncWithPeer(peerId: String): Int {
        val server = serverSupplier() ?: return 0
        val transport = transportSupplier() ?: return 0

        return try {
            // Ensure peer is registered in the server's peer list
            val peer = peers[peerId] ?: return 0
            val peerAgent = PeerAgent(
                agentId = peerId,
                agentName = peer.agentName,
                address = peer.address,
                port = peer.port,
                capabilities = listOf("memory-twin/0.1")
            )
            // Register via discovery response mechanism
            server.onDiscoverResponse(peerAgent)

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
            // Response handled asynchronously by TwinAcpHandler → onEntriesReceived

            0 // Actual count updated asynchronously via AcpHandler
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinSyncEngine.syncWithPeer($peerId)")
            0
        }
    }

    /** Sync with all known peers. */
    suspend fun syncWithAllPeers() {
        val online = peers.values.filter {
            System.currentTimeMillis() - it.lastSeen < 300_000 // seen in last 5 min
        }
        _syncState.value = _syncState.value.copy(
            phase = SyncPhase.SYNCING,
            totalPeers = online.size,
            completedPeers = 0
        )

        var completed = 0
        online.forEach { peer ->
            val received = syncWithPeer(peer.peerId)
            if (received > 0) {
                completed++
            }
        }

        _syncState.value = _syncState.value.copy(
            phase = SyncPhase.IDLE,
            completedPeers = completed,
            lastSyncAt = System.currentTimeMillis()
        )
    }

    /** Announce our capability card to a peer. */
    suspend fun announceCapability(peerId: String, card: String) {
        val transport = transportSupplier() ?: return
        val msg = AcpMessage.capabilityAnnounce(deviceId, peerId, card)
        transport.send(msg)
    }

    /** Announce capability to all known peers. */
    suspend fun broadcastCapability(card: String) {
        val transport = transportSupplier() ?: return
        peers.keys.forEach { peerId ->
            val msg = AcpMessage.capabilityAnnounce(deviceId, peerId, card)
            transport.send(msg)
        }
    }

    // ── Event hooks (called by TwinAcpHandler) ─────────────────────

    /** Called when new entries are received and merged into the local ledger. */
    fun onEntriesReceived(entries: List<LedgerEntry>) {
        _syncState.value = _syncState.value.copy(
            lastEntriesReceived = entries.size,
            lastSyncAt = System.currentTimeMillis()
        )
        // Trigger memory.md rebuild from merged ledger
        scope.launch {
            rebuildMemoryDoc()
        }
        // Propagate dream entries to DREAM.md
        entries.filter { it.type == EntryType.DREAM }.forEach { entry ->
            scope.launch {
                applyDreamEntry(entry)
            }
        }
        // Apply identity updates
        entries.filter { it.type == EntryType.SOUL_UPDATE || it.type == EntryType.PROFILE_UPDATE }
            .forEach { entry ->
                scope.launch {
                    applyIdentityUpdate(entry)
                }
            }
    }

    /** Called when a peer acknowledges our entries. */
    fun onAckReceived(peerId: String, hash: String) {
        peers[peerId]?.let {
            it.lastAckedHash = hash
            it.lastSyncAt = System.currentTimeMillis()
        }
    }

    /** Called when a peer announces its capabilities. */
    fun onCapabilityReceived(peerId: String, cardJson: String) {
        peers[peerId]?.let {
            it.capabilityCard = cardJson
            it.lastSeen = System.currentTimeMillis()
        }
        // Write pairing request to inbox file — UI polls this
        val card = try { CapabilityCard.fromJson(cardJson) } catch (_: Exception) { null }
        try {
            val inbox = java.io.File(com.mengpaw.kernel.DataPaths.AGENT_INBOX)
            inbox.mkdirs()
            val file = java.io.File(inbox, "twin_pair_${peerId.take(16)}.json")
            val json = org.json.JSONObject().apply {
                put("peerId", peerId)
                put("deviceName", card?.deviceName ?: peerId.take(12))
                put("deviceModel", card?.deviceModel ?: "")
                put("agentName", card?.deviceName ?: "")
                put("receivedAt", System.currentTimeMillis())
                put("capabilityCard", cardJson)
            }
            val tmp = java.io.File(inbox, "twin_pair_${peerId.take(16)}.tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(file)
        } catch (e: Exception) {
            com.mengpaw.kernel.error.ErrorCollector.report(e, "TwinSyncEngine.onCapability")
        }
        persistPeerInfo()
    }

    /** Check if a peer is already trusted (has a .trusted file). */
    private fun isPeerTrusted(peerId: String): Boolean {
        return java.io.File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED, "$peerId.trusted").exists()
    }

    /** Called when a peer delegates a task. */
    fun onTwinDelegateReceived(fromPeerId: String, task: String, requirements: String) {
        // Write to inbox for the agent to pick up
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

    /**
     * Rebuild the agent's memory.md from the merged ledger.
     * This ensures the system prompt always includes the latest synchronized memories.
     */
    private suspend fun rebuildMemoryDoc() {
        try {
            val entries = TwinLedgerStore.loadAll()
                .filter { it.type == EntryType.MEMORY }

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
                    val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(entry.timestamp))
                    val title = entry.content.take(60).replace("\n", " ")
                    val tags = entry.tags.joinToString(", ")
                    appendLine("| ${entry.id} | $date | ${entry.deviceName} | $title | $tags |")
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

            // Atomic write
            val memFile = File(DataPaths.AGENTS, "$agentName/memory.md")
            val tmp = File(memFile.parent, "memory.tmp")
            tmp.writeText(doc)
            if (memFile.exists()) memFile.delete()
            tmp.renameTo(memFile)
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinSyncEngine.rebuildMemoryDoc")
        }
    }

    /** Apply a dream entry to DREAM.md. */
    private fun applyDreamEntry(entry: LedgerEntry) {
        try {
            val dreamDir = File(DataPaths.TWIN_DREAMS)
            if (!dreamDir.exists()) dreamDir.mkdirs()
            val dreamFile = File(dreamDir, "DREAM.md")
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(entry.timestamp))
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
            val existing = if (dreamFile.exists()) dreamFile.readText() else ""
            dreamFile.writeText(dreamContent + existing)
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
            // LWW: only apply if our local version is older
            if (docFile.exists() && docFile.lastModified() > entry.timestamp) {
                return // Local is newer, keep it
            }
            // Atomic write
            val tmp = File(docFile.parent, "$docType.tmp")
            tmp.writeText(entry.content)
            if (docFile.exists()) docFile.delete()
            tmp.renameTo(docFile)

            // Log the identity change
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

    /** Persist peer info to disk. */
    private fun persistPeerInfo() {
        try {
            val dir = File(DataPaths.TWIN_PEERS)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "peers.json")
            val json = buildString {
                appendLine("[")
                peers.values.forEachIndexed { i, peer ->
                    appendLine("  {")
                    appendLine("    \"peerId\": \"${peer.peerId}\",")
                    appendLine("    \"agentName\": \"${peer.agentName}\",")
                    appendLine("    \"address\": \"${peer.address}\",")
                    appendLine("    \"port\": ${peer.port},")
                    appendLine("    \"lastAckedHash\": \"${peer.lastAckedHash ?: ""}\",")
                    appendLine("    \"lastSeen\": ${peer.lastSeen},")
                    appendLine("    \"lastSyncAt\": ${peer.lastSyncAt}")
                    appendLine("  }${if (i < peers.size - 1) "," else ""}")
                }
                appendLine("]")
            }
            val tmp = File(dir, "peers.tmp")
            tmp.writeText(json)
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
    var capabilityCard: String? = null
)

/** Sync phase for UI status display. */
enum class SyncPhase { IDLE, DISCOVERING, SYNCING, MERGING, ERROR }

/** Observable sync state. */
data class TwinSyncState(
    val phase: SyncPhase = SyncPhase.IDLE,
    val totalPeers: Int = 0,
    val completedPeers: Int = 0,
    val lastEntriesReceived: Int = 0,
    val lastSyncAt: Long = 0L
)
