// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.acp

import com.mengpaw.kernel.agent.AgentProfile
import com.mengpaw.kernel.ports.Ports
import com.mengpaw.kernel.security.PromptFirewall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Atomic file write: write to tmp, then rename (crash-safe, prevents partial writes). */
private fun java.io.File.atomicWriteText(text: String) {
    val tmp = java.io.File(this.parentFile, "${this.name}.tmp")
    try {
        tmp.writeText(text)
        if (this.exists()) this.delete()
        tmp.renameTo(this)
    } catch (e: Exception) {
        try { tmp.delete() } catch (_: Exception) {}
        throw e
    }
}

class AcpServer(
    private val profile: AgentProfile,
    private val port: Int = Ports.ACP,
    /** Shared secret for peer authentication. Must be set to enable secure pairing.
     *  Use AcpCrypto.deriveKey() to generate this from paired device fingerprints. */
    private val sharedSecret: String,
    /** SessionManager for session sync handler. If null, session sync is disabled. */
    private val sessionManager: com.mengpaw.kernel.session.SessionManager? = null
) {
    init {
        if (sharedSecret.isEmpty()) {
            com.mengpaw.kernel.KernelLog.w("AcpServer",
                "WARNING: ACP sharedSecret is empty — device-to-device authentication is DISABLED. " +
                "Set a shared secret via AcpServer(profile, secret=\"your-secret\") to enable peer auth.")
        }
    }
    // 并发安全: handleMessage 可被多协程/多 transport 并发调用 —
    // peers 用 ConcurrentHashMap ([] 操作符/values 快照均可用), handlers 用 CopyOnWriteArrayList 快照遍历
    private val peers = java.util.concurrent.ConcurrentHashMap<String, PeerAgent>()
    private val handlers = java.util.concurrent.CopyOnWriteArrayList<AcpHandler>()
    private val transports = mutableListOf<AcpTransport>()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * SECURITY (P0 fix): peerId → 已见来源 IP 集合。
     * ACP 明文 HTTP 上 msg.from 完全可伪造 — 攻击者可冒充任意已配对 peer 的 agentId
     * 通过 isTrusted 检查。因此敏感消息类型 (工作区同步/会话/REVOKE/MCP) 额外要求
     * 消息来源 socket IP 与该 peerId 历史上通信过的 IP 匹配。
     * 每 peer 保留最近 4 个 IP (DHCP 换地址容错)。
     */
    private val peerIpBindings = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    /** 记录 peerId 的来源 IP (所有类型消息首次到达时调用, 配对流程即建立绑定)。 */
    fun bindPeerIp(peerId: String, ip: String) {
        if (peerId.isBlank() || ip.isBlank()) return
        val set = peerIpBindings.getOrPut(peerId) { mutableSetOf() }
        synchronized(set) {
            if (set.add(ip)) while (set.size > 4) set.remove(set.first())
        }
    }

    /** 敏感消息校验: peerId 必须曾从此 IP 通信过 (冒充尝试返回 false)。 */
    fun isPeerFromBoundIp(peerId: String, ip: String): Boolean {
        if (peerId.isBlank() || ip.isBlank()) return false
        val set = peerIpBindings[peerId] ?: return false
        synchronized(set) { return ip in set }
    }

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
        // Register session sync handler if SessionManager is available
        if (sessionManager != null) {
            handlers.add(SessionSyncHandler(sessionManager, profile.agentName))
        }
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

    /**
     * Send an ACP message directly via transport, bypassing local handler dispatch.
     * Used by plugins (e.g. Tribe) that manage their own message lifecycle
     * and don't want local handlers to intercept outgoing messages.
     */
    suspend fun sendViaTransport(msg: AcpMessage): Boolean =
        transports.any { it.send(msg) }

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
                // Also notify handlers (e.g. TwinAcpHandler for peer liveness tracking)
                for (handler in handlers) {
                    if (AcpMessageType.HEARTBEAT in handler.supportedTypes) {
                        handler.handle(msg, this)
                    }
                }
                AcpResult(true, "alive")
            }
            AcpMessageType.BROWSER_PUSH_RESPONSE -> AcpResult(true, "ack", msg.type)
            AcpMessageType.DISCOVER -> {
                // 版本协商 (v0.22.1): payload 可为 {"protocols":["acp/1.0","acp/1.1","mcp/1.0"]};
                // 旧格式 (空 payload) 按 acp/1.0 降级, 向后兼容。
                val protocols = try {
                    val obj = kotlinx.serialization.json.Json.parseToJsonElement(msg.payload).jsonObject
                    obj["protocols"]?.jsonArray?.map { it.jsonPrimitive.content }
                        ?: listOf("acp/1.0")
                } catch (_: Exception) { listOf("acp/1.0") }
                val peer = PeerAgent(profile.agentId, profile.agentName, "local", port, protocols)
                peers[msg.from] = peer
                AcpResult(true, profile.agentName, profile.agentId)
            }
            AcpMessageType.RESULT -> {
                AcpResult(true, msg.payload, msg.from)
            }
            AcpMessageType.DELEGATE, AcpMessageType.SHARE_MEMORY, AcpMessageType.SHARE_SKILL,
            AcpMessageType.TRIBE_CHAT,
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
                        if (result != null) { customResult = result } // 不 break — 允许所有 handler 依次处理
                    }
                }
                customResult ?: AcpResult(true, "ack", msg.type)
            }
            // Memory Twin workspace sync — requires trusted peer (P0 fix: auth check)
            AcpMessageType.WS_MANIFEST, AcpMessageType.WS_PULL,
            AcpMessageType.REVOKE -> {
                // SECURITY: Only trusted (paired) devices can access workspace data
                if (!PromptFirewall.isTrusted(msg.from)) {
                    return AcpResult(false, "auth_required",
                        "Workspace sync requires paired trust. Complete twin pairing first.")
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
                // SECURITY (P0 fix): MCP tools/call 直接执行插件命令 (绕过 Pipeline 命令过滤),
                // 与工作区同步同级 — 必须已配对受信。IP 绑定校验由 transport 层完成。
                if (!PromptFirewall.isTrusted(msg.from)) {
                    return AcpResult(false, "auth_required",
                        "MCP tools require paired trust. Complete twin pairing first.")
                }
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
            // Session Sync — requires trusted peer (session data is sensitive)
            AcpMessageType.SESSION_HEAD, AcpMessageType.SESSION_PULL,
            AcpMessageType.SESSION_DELTA, AcpMessageType.SESSION_ACK -> {
                if (!PromptFirewall.isTrusted(msg.from)) {
                    return AcpResult(false, "auth_required",
                        "Session sync requires paired trust. Complete twin pairing first.")
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
            val content = """# Claude Code 任务
> 来自: $from
> 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}
> 回复: $replyTo

$task

---
完成后将结果写入工作区，并用 `agent.write` 回复。
""".trimIndent()
            taskFile.atomicWriteText(content)
        } catch (e: Exception) {
            com.mengpaw.kernel.KernelLog.w("AcpServer", "writeBridgeTaskToInbox: ${e.message}")
        }
    }

    private fun writePushToInbox(from: String, payload: String) {
        try {
            val inbox = java.io.File(com.mengpaw.kernel.DataPaths.AGENT_INBOX).also { it.mkdirs() }
            val taskFile = java.io.File(inbox, "push_${System.currentTimeMillis()}.md")
            val data = try { kotlinx.serialization.json.Json.parseToJsonElement(payload).jsonObject } catch (_: Exception) { null }
            val url = data?.get("url")?.jsonPrimitive?.content ?: payload
            val title = data?.get("title")?.jsonPrimitive?.content ?: ""
            val content = """# 浏览器推送请求
- 来自: $from
- 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}
- URL: $url
- 标题: $title

## 操作
- 接受: browser.push.accept push_${taskFile.nameWithoutExtension}
- 拒绝: browser.push.reject push_${taskFile.nameWithoutExtension}
""".trimIndent()
            taskFile.atomicWriteText(content)
        } catch (e: Exception) {
            com.mengpaw.kernel.KernelLog.w("AcpServer", "writePushToInbox: ${e.message}")
        }
    }

    fun cleanup() {
        peers.entries.removeAll { (_, peer) ->
            System.currentTimeMillis() - peer.lastSeen > 120_000
        }
    }
}
