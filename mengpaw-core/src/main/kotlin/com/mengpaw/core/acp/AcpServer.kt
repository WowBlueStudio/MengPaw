// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.acp

import com.mengpaw.core.agent.AgentProfile
import com.mengpaw.core.security.PromptFirewall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ACP (Agent Communication Protocol) Server for MengPaw.
 *
 * Manages peer discovery, task delegation, and memory/skill sharing between Agents.
 *
 * Transport:
 * - Primary: HTTP REST (discovery + message relay)
 * - Extended: WebSocket (real-time), BLE (proximity) — via AcpTransport interface
 *
 * Discovery:
 * - LAN: mDNS broadcast on port 9876
 * - WAN: Central registry (future)
 */
class AcpServer(
    private val profile: AgentProfile,
    private val port: Int = 9876,
    private val sharedSecret: String = ""  // SECURITY: Must be set! Empty = auth disabled (warned below)
) {
    init {
        if (sharedSecret.isEmpty()) {
            android.util.Log.w("AcpServer",
                "WARNING: ACP sharedSecret is empty — device-to-device authentication is DISABLED. " +
                "Set a shared secret via AcpServer(profile, secret=\"your-secret\") to enable peer auth.")
        }
    }
    private val peers = mutableMapOf<String, PeerAgent>()
    private val handlers = mutableListOf<AcpHandler>()
    private val transports = mutableListOf<AcpTransport>()
    private val json = Json { ignoreUnknownKeys = true }

    // ── Handler registration ──────────────────────────────────────────

    /** Register a custom ACP handler (called by plugins). */
    fun registerHandler(handler: AcpHandler) {
        handlers.add(handler)
    }

    /** Register a transport channel. */
    fun registerTransport(transport: AcpTransport) {
        transports.add(transport)
    }

    // ── Discovery ─────────────────────────────────────────────────────

    /**
     * Discover peer Agents on the local network.
     * Returns list of currently known peers.
     */
    suspend fun discover(): List<PeerAgent> {
        // Broadcast DISCOVER message
        val msg = AcpMessage.discover(profile.agentId)
        transports.forEach { it.send(msg) }
        return peers.values.toList()
    }

    /** Handle an incoming DISCOVER response and register the peer (with auth check). */
    fun onDiscoverResponse(from: PeerAgent, authToken: String = ""): Boolean {
        // VULN-FIX: Require shared secret for peer registration
        if (sharedSecret.isNotEmpty() && authToken != sharedSecret) {
            return false
        }
        peers[from.agentId] = from
        return true
    }

    // ── Task delegation ───────────────────────────────────────────────

    /**
     * Delegate a task to a peer Agent.
     */
    suspend fun delegate(peerId: String, task: String): AcpResult {
        val peer = peers[peerId]
            ?: return AcpResult(false, "Peer not found: $peerId")

        val msg = AcpMessage.delegate(profile.agentId, peerId, task)

        // Try custom handlers first
        handlers.forEach { handler ->
            if (AcpMessageType.DELEGATE in handler.supportedTypes) {
                val result = handler.handle(msg, this)
                if (result != null) return result
            }
        }

        // Default: send via transport
        val sent = transports.any { it.send(msg) }
        return if (sent) AcpResult(true, "Task delegated to $peerId")
        else AcpResult(false, "No transport available to reach $peerId")
    }

    // ── Memory/Skill sharing ──────────────────────────────────────────

    /** Share a memory entry with a peer Agent. */
    suspend fun shareMemory(peerId: String, memoryId: String): AcpResult {
        val peer = peers[peerId] ?: return AcpResult(false, "Peer not found: $peerId")
        val msg = AcpMessage.shareMemory(profile.agentId, peerId, memoryId)
        transports.forEach { it.send(msg) }
        return AcpResult(true, "Memory $memoryId shared with $peerId")
    }

    /** Share a skill definition with a peer Agent. */
    suspend fun shareSkill(peerId: String, skillName: String): AcpResult {
        val peer = peers[peerId] ?: return AcpResult(false, "Peer not found: $peerId")
        val msg = AcpMessage.shareSkill(profile.agentId, peerId, skillName)
        transports.forEach { it.send(msg) }
        return AcpResult(true, "Skill $skillName shared with $peerId")
    }

    // ── Message dispatch ──────────────────────────────────────────────

    /**
     * Handle an incoming raw ACP message string (JSON).
     * Route to appropriate handler or process internally.
     */
    suspend fun handleMessage(raw: String): AcpResult {
        return try {
            val msg = json.decodeFromString<AcpMessage>(raw)
            processMessage(msg)
        } catch (e: Exception) {
            AcpResult(false, "Invalid ACP message: ${e.message}")
        }
    }

    private suspend fun processMessage(msg: AcpMessage): AcpResult {
        val type = try { AcpMessageType.valueOf(msg.type) } catch (e: Exception) { null }
            ?: return AcpResult(false, "Unknown message type: ${msg.type}")

        return when (type) {
            AcpMessageType.HEARTBEAT -> {
                peers[msg.from]?.let {
                    peers[msg.from] = it.copy(lastSeen = System.currentTimeMillis())
                }
                AcpResult(true, "alive")
            }
            AcpMessageType.BROWSER_PUSH_RESPONSE -> AcpResult(true, "ack", msg.type)
            AcpMessageType.DISCOVER -> {
                val peer = PeerAgent(profile.agentId, profile.agentName, "local", port,
                    listOf("acp/1.0", "mcp/1.0"))
                peers[msg.from] = peer
                AcpResult(true, profile.agentName, profile.agentId)
            }
            AcpMessageType.RESULT -> {
                AcpResult(true, msg.payload, msg.from)
            }
            AcpMessageType.DELEGATE, AcpMessageType.SHARE_MEMORY, AcpMessageType.SHARE_SKILL,
            AcpMessageType.BROWSER_PUSH -> {
                // Firewall check for untrusted peers
                // For BROWSER_PUSH: trusted peers auto-accept, guest peers go to inbox
                if (type == AcpMessageType.BROWSER_PUSH) {
                    if (!PromptFirewall.isTrusted(msg.from)) {
                        // Write to Agent inbox for manual authorization
                        writePushToInbox(msg.from, msg.payload)
                        return AcpResult(true, "push_queued", "Browser push queued for agent approval")
                    }
                }
                val fwCheck = PromptFirewall.check(msg.from, msg.payload)
                if (fwCheck != null && !PromptFirewall.isTrusted(msg.from)) {
                    return AcpResult(false, "Firewall blocked: $fwCheck")
                }
                // Continue to custom handlers
                var customResult: AcpResult? = null
                for (handler in handlers) {
                    if (type in handler.supportedTypes) {
                        val result = handler.handle(msg, this)
                        if (result != null) { customResult = result; break }
                    }
                }
                customResult ?: AcpResult(true, "ack", msg.type)
            }
        }
    }

    // ── Status ────────────────────────────────────────────────────────

    fun getPeers(): List<PeerAgent> = peers.values.toList()
        .filter { System.currentTimeMillis() - it.lastSeen < 60_000 } // 1 min timeout

    fun peerCount(): Int = getPeers().size

    /** Write a browser push request to the Agent inbox for manual authorization. */
    private fun writePushToInbox(from: String, payload: String) {
        try {
            val inbox = java.io.File(com.mengpaw.core.DataPaths.AGENT_INBOX).also { it.mkdirs() }
            val taskFile = java.io.File(inbox, "push_${System.currentTimeMillis()}.md")
            val data = try { kotlinx.serialization.json.Json.parseToJsonElement(payload).jsonObject } catch (_: Exception) { null }
            val url = data?.get("url")?.jsonPrimitive?.content ?: payload
            val title = data?.get("title")?.jsonPrimitive?.content ?: ""
            taskFile.writeText("""# 浏览器推送请求
- 来自: $from
- 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}
- URL: $url
- 标题: $title

## 操作
- 接受: browser.push.accept push_${taskFile.nameWithoutExtension}
- 拒绝: browser.push.reject push_${taskFile.nameWithoutExtension}
""".trimIndent())
        } catch (_: Exception) { }
    }

    /** Clean up stale peers. */
    fun cleanup() {
        peers.entries.removeAll { (_, peer) ->
            System.currentTimeMillis() - peer.lastSeen > 120_000 // 2 min
        }
    }
}
