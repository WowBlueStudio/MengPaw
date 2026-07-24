// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.acp

import com.mengpaw.kernel.agent.AgentProfile
import com.mengpaw.kernel.security.PromptFirewall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AcpServer(
    private val profile: AgentProfile,
    private val port: Int = 9876,
    /** Shared secret for peer authentication. Must be set to enable secure pairing.
     *  Use AcpCrypto.deriveKey() to generate this from paired device fingerprints. */
    private val sharedSecret: String
) {
    init {
        if (sharedSecret.isEmpty()) {
            com.mengpaw.kernel.KernelLog.w("AcpServer",
                "WARNING: ACP sharedSecret is empty — device-to-device authentication is DISABLED. " +
                "Set a shared secret via AcpServer(profile, secret=\"your-secret\") to enable peer auth.")
        }
    }
    private val peers = mutableMapOf<String, PeerAgent>()
    private val handlers = mutableListOf<AcpHandler>()
    private val transports = mutableListOf<AcpTransport>()
    private val json = Json { ignoreUnknownKeys = true }

    /** Kernel-level handlers registered automatically — no plugin dependency needed. */
    val delegateHandler = DelegateHandler()
    val shareHandler = ShareMemoryHandler()
    private var mcpBridge: McpOverAcpBridge? = null

    /** Enable MCP-over-ACP bridge. Must be called after McpServer is initialized. */
    fun enableMcpBridge(mcpServer: com.mengpaw.kernel.mcp.McpServer) {
        mcpBridge = McpOverAcpBridge(mcpServer)
        handlers.add(mcpBridge!!)
    }

    init {
        // Auto-register kernel handlers so core ACP messages are always processed
        handlers.add(delegateHandler)
        handlers.add(shareHandler)
    }

    fun registerHandler(handler: AcpHandler) {
        handlers.add(handler)
    }

    fun registerTransport(transport: AcpTransport) {
        transports.add(transport)
    }

    suspend fun discover(): List<PeerAgent> {
        val msg = AcpMessage.discover(profile.agentId)
        transports.forEach { it.send(msg) }
        return peers.values.toList()
    }

    fun onDiscoverResponse(from: PeerAgent, authToken: String = ""): Boolean {
        if (sharedSecret.isNotEmpty() && authToken != sharedSecret) {
            return false
        }
        peers[from.agentId] = from
        return true
    }

    /** Directly register a peer without auth (for internal use by sync engines). */
    fun registerPeer(peer: PeerAgent) {
        peers[peer.agentId] = peer
    }

    suspend fun delegate(peerId: String, task: String): AcpResult {
        val peer = peers[peerId]
            ?: return AcpResult(false, "Peer not found: $peerId")
        val msg = AcpMessage.delegate(profile.agentId, peerId, task)
        handlers.forEach { handler ->
            if (AcpMessageType.DELEGATE in handler.supportedTypes) {
                val result = handler.handle(msg, this)
                if (result != null) return result
            }
        }
        val sent = transports.any { it.send(msg) }
        return if (sent) AcpResult(true, "Task delegated to $peerId")
        else AcpResult(false, "No transport available to reach $peerId")
    }

    suspend fun shareMemory(peerId: String, memoryId: String): AcpResult {
        val peer = peers[peerId] ?: return AcpResult(false, "Peer not found: $peerId")
        val msg = AcpMessage.shareMemory(profile.agentId, peerId, memoryId)
        transports.forEach { it.send(msg) }
        return AcpResult(true, "Memory $memoryId shared with $peerId")
    }

    suspend fun shareSkill(peerId: String, skillName: String): AcpResult {
        val peer = peers[peerId] ?: return AcpResult(false, "Peer not found: $peerId")
        val msg = AcpMessage.shareSkill(profile.agentId, peerId, skillName)
        transports.forEach { it.send(msg) }
        return AcpResult(true, "Skill $skillName shared with $peerId")
    }

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
                if (type == AcpMessageType.BROWSER_PUSH) {
                    if (!PromptFirewall.isTrusted(msg.from)) {
                        writePushToInbox(msg.from, msg.payload)
                        return AcpResult(true, "push_queued", "Browser push queued for agent approval")
                    }
                }
                val fwCheck = PromptFirewall.check(msg.from, msg.payload)
                if (fwCheck != null && !PromptFirewall.isTrusted(msg.from)) {
                    return AcpResult(false, "Firewall blocked: $fwCheck")
                }
                var customResult: AcpResult? = null
                for (handler in handlers) {
                    if (type in handler.supportedTypes) {
                        val result = handler.handle(msg, this)
                        if (result != null) { customResult = result; break }
                    }
                }
                customResult ?: AcpResult(true, "ack", msg.type)
            }
            // Memory Twin ledger sync — requires trusted peer (P0 fix: auth check)
            AcpMessageType.LEDGER_HEAD, AcpMessageType.LEDGER_PULL,
            AcpMessageType.LEDGER_BATCH, AcpMessageType.LEDGER_ACK -> {
                // SECURITY: Only trusted (paired) devices can access ledger data
                if (!PromptFirewall.isTrusted(msg.from)) {
                    return AcpResult(false, "auth_required",
                        "Ledger sync requires paired trust. Complete twin pairing first.")
                }
                var customResult: AcpResult? = null
                for (handler in handlers) {
                    if (type in handler.supportedTypes) {
                        val result = handler.handle(msg, this)
                        if (result != null) { customResult = result; break }
                    }
                }
                customResult ?: AcpResult(true, "ack", msg.type)
            }
            // MCP-over-ACP bridge — route to MCP handler
            AcpMessageType.MCP_REQUEST -> {
                var mcpResult: AcpResult? = null
                for (handler in handlers) {
                    if (AcpMessageType.MCP_REQUEST in handler.supportedTypes) {
                        val r = handler.handle(msg, this)
                        if (r != null) { mcpResult = r; break }
                    }
                }
                mcpResult ?: AcpResult(false, "no_mcp_handler", "MCP bridge not enabled. Call server.enableMcpBridge(mcpServer).")
            }
            AcpMessageType.MCP_RESPONSE -> AcpResult(true, "mcp_response", msg.payload)
            // Memory Twin pairing types — NO firewall (their purpose IS establishing trust)
            AcpMessageType.CAPABILITY_ANNOUNCE, AcpMessageType.TWIN_DELEGATE,
            AcpMessageType.PAIR_CHALLENGE, AcpMessageType.PAIR_CONFIRM -> {
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

    fun getPeers(): List<PeerAgent> = peers.values.toList()
        .filter { System.currentTimeMillis() - it.lastSeen < 60_000 }

    fun peerCount(): Int = getPeers().size

    /** Write a Claude Code bridge task to the Agent's inbox. */
    private fun writeBridgeTaskToInbox(from: String, payload: String) {
        try {
            val data = try { json.parseToJsonElement(payload).jsonObject } catch (_: Exception) { null }
            val task = data?.get("task")?.jsonPrimitive?.content ?: payload
            val replyTo = data?.get("replyTo")?.jsonPrimitive?.content ?: ""
            val inbox = java.io.File(com.mengpaw.kernel.DataPaths.AGENT_INBOX).also { it.mkdirs() }
            val taskFile = java.io.File(inbox, "claude_task_${System.currentTimeMillis()}.md")
            taskFile.writeText("""# Claude Code 任务
> 来自: $from
> 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}
> 回复: $replyTo

$task

---
完成后将结果写入工作区，并用 `agent.write` 回复。
""".trimIndent())
        } catch (_: Exception) { }
    }

    private fun writePushToInbox(from: String, payload: String) {
        try {
            val inbox = java.io.File(com.mengpaw.kernel.DataPaths.AGENT_INBOX).also { it.mkdirs() }
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

    fun cleanup() {
        peers.entries.removeAll { (_, peer) ->
            System.currentTimeMillis() - peer.lastSeen > 120_000
        }
    }
}
