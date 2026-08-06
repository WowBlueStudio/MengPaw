// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.acp.*
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.serialization.json.*
import java.io.File

/**
 * ACP message handler for Memory Twin protocol messages.
 *
 * This is the first concrete implementation of the [AcpHandler] interface
 * defined in the kernel. It handles Memory Twin message types and
 * delegates business logic to [TwinSyncEngine].
 */
class TwinAcpHandler(
    private val syncEngine: TwinSyncEngine
) : AcpHandler {

    override val supportedTypes: List<AcpMessageType> = listOf(
        AcpMessageType.WS_MANIFEST,
        AcpMessageType.WS_PULL,
        AcpMessageType.CAPABILITY_ANNOUNCE,
        AcpMessageType.TWIN_DELEGATE,
        AcpMessageType.PAIR_CHALLENGE,
        AcpMessageType.PAIR_CONFIRM,
        AcpMessageType.REVOKE,
        AcpMessageType.HEARTBEAT
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun handle(message: AcpMessage, server: AcpServer): AcpResult? {
        val type = try {
            AcpMessageType.valueOf(message.type)
        } catch (_: IllegalArgumentException) {
            return null // Not a twin message
        }

        if (type !in supportedTypes) return null

        return try {
            when (type) {
                AcpMessageType.WS_MANIFEST -> handleWsManifest(message, server)
                AcpMessageType.WS_PULL -> handleWsPull(message, server)
                AcpMessageType.CAPABILITY_ANNOUNCE -> handleCapabilityAnnounce(message)
                AcpMessageType.TWIN_DELEGATE -> handleTwinDelegate(message)
                AcpMessageType.PAIR_CHALLENGE -> handlePairChallenge(message)
                AcpMessageType.PAIR_CONFIRM -> handlePairConfirm(message)
                AcpMessageType.REVOKE -> handleRevoke(message)
                AcpMessageType.HEARTBEAT -> handleHeartbeat(message)
                else -> null
            }
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinAcpHandler.handle(${message.type})")
            AcpResult(false, "Handler error: ${e.message}")
        }
    }

    // ── Message handlers ───────────────────────────────────────────

    /**
     * WS_MANIFEST: 收到对端工作区清单 → 与本机比对:
     * - send: 本机有、对端没有或哈希不同的文件内容 (对端需要)
     * - request: 本机没有、对端有的文件路径 (对端返回给本机)
     * 响应 data: {"send": {relPath: content}, "request": [relPath, ...]}
     */
    private suspend fun handleWsManifest(msg: AcpMessage, server: AcpServer): AcpResult {
        val payload = try { json.parseToJsonElement(msg.payload).jsonObject } catch (_: Exception) { null }
            ?: return AcpResult(false, "invalid_manifest")
        val peerFiles = payload["files"]?.jsonObject ?: return AcpResult(false, "no_files")

        val agentName = syncEngine.agentName
        val localManifest = TwinWorkspace.buildManifest(agentName)

        // 解析对端清单 (哈希截断为 16 字符防超长 payload)
        // P0 fix: relPath 必须消毒 — 对端可控, "../" 穿越可让本机读任意文件并回传
        val peerHashes = mutableMapOf<String, String>()
        for ((relPath, meta) in peerFiles.entries) {
            if (sanitizeRelPath(relPath) == null) continue
            val hash = meta.jsonObject["hash"]?.jsonPrimitive?.content ?: ""
            if (hash.isNotBlank()) peerHashes[relPath] = hash
        }

        // send: 本机有、对端缺失或不同的文件内容
        val send = buildJsonObject {
            localManifest.forEach { (relPath, entry) ->
                val peerHash = peerHashes[relPath]
                if (peerHash == null || peerHash != entry.hash) {
                    val content = try {
                        File(DataPaths.AGENTS, "$agentName/$relPath").readText()
                    } catch (_: Exception) { return@forEach }
                    put(relPath, JsonPrimitive(content))
                }
            }
        }
        // request: 本机没有但对端有的文件 (已消毒)
        val request = buildJsonArray {
            peerHashes.keys.forEach { relPath ->
                if (relPath !in localManifest) add(JsonPrimitive(relPath))
            }
        }

        return AcpResult(true, "ws_manifest_${peerHashes.size}", buildJsonObject {
            put("send", send)
            put("request", request)
        }.toString())
    }

    /** WS_PULL: 对端请求指定文件 → 响应 data: {"files": {relPath: content}} */
    private suspend fun handleWsPull(msg: AcpMessage, server: AcpServer): AcpResult {
        val payload = try { json.parseToJsonElement(msg.payload).jsonObject } catch (_: Exception) { null }
            ?: return AcpResult(false, "invalid_pull")
        val paths = payload["paths"]?.jsonArray ?: return AcpResult(false, "no_paths")
        val agentName = syncEngine.agentName

        val files = buildJsonObject {
            paths.forEach { path ->
                val relPath = path.jsonPrimitive?.content ?: return@forEach
                // P0 fix: 同 WS_MANIFEST — 穿越路径拒绝
                val safe = sanitizeRelPath(relPath) ?: return@forEach
                val f = File(DataPaths.AGENTS, "$agentName/$safe")
                if (f.exists() && f.isFile) {
                    try { put(safe, JsonPrimitive(f.readText())) } catch (_: Exception) {}
                }
            }
        }
        return AcpResult(true, "ws_pull_${paths.size}", buildJsonObject {
            put("files", files)
        }.toString())
    }

    private suspend fun handleCapabilityAnnounce(msg: AcpMessage): AcpResult {
        // Parse payload to extract nonce and capability card
        val payload = try { json.parseToJsonElement(msg.payload).jsonObject } catch (_: Exception) { null }
        val capabilityCard = payload?.get("capabilityCard")?.jsonPrimitive?.content ?: msg.payload
        val nonce = payload?.get("nonce")?.jsonPrimitive?.content ?: ""

        // If this is a pairing request (has nonce), use pairing engine
        if (nonce.isNotBlank()) {
            val transport = syncEngine.getTransport()
            val deviceId = MemoryTwinPlugin.appContext?.let {
                try { AcpCrypto.myFingerprint() } catch (_: Exception) { "device-${System.currentTimeMillis()}" }
            } ?: "device-unknown"
            val myFingerprint = try { AcpCrypto.myFingerprint() } catch (_: Exception) { deviceId }

            if (transport != null) {
                TwinPairingEngine.handleAnnounce(msg.from, nonce, deviceId, myFingerprint, transport)
                return AcpResult(true, "pairing_challenge_sent")
            }
        }

        // Legacy: still write to inbox for backward compatibility
        syncEngine.onCapabilityReceived(msg.from, capabilityCard)
        return AcpResult(true, "capability_stored")
    }

    private suspend fun handleTwinDelegate(msg: AcpMessage): AcpResult {
        val payload = try { json.parseToJsonElement(msg.payload).jsonObject } catch (_: Exception) { null }
        val task = payload?.get("task")?.jsonPrimitive?.content ?: ""
        // 修复: twinDelegate 工厂默认把 requirements 作为 JsonArray 写入 (parseToJsonElement("[]")),
        // 旧实现 .jsonPrimitive 直接抛异常 — 信任门永远不可达 (任何标准委派都返回 Handler error)。
        // JsonPrimitive → 原串; 其它 JsonElement (JsonArray) → toString 还原数组文本。
        val requirementsStr = payload?.get("requirements")?.let {
            if (it is JsonPrimitive) it.content else it.toString()
        } ?: "[]"
        // SECURITY: Only accept delegate tasks from trusted peers
        if (!com.mengpaw.kernel.security.PromptFirewall.isTrusted(msg.from)) {
            return AcpResult(false, "untrusted_delegate",
                "Task delegation requires paired trust. Complete twin pairing first.")
        }
        syncEngine.onTwinDelegateReceived(msg.from, task, requirementsStr)
        return AcpResult(true, "delegate_queued")
    }

    // ── Pairing protocol handlers ───────────────────────────────────

    private suspend fun handlePairChallenge(msg: AcpMessage): AcpResult {
        val payload = try { json.parseToJsonElement(msg.payload).jsonObject } catch (_: Exception) { null }
        val deviceId = payload?.get("deviceId")?.jsonPrimitive?.content ?: return AcpResult(false, "no_device_id")
        val nonceB = payload?.get("nonceB")?.jsonPrimitive?.content ?: return AcpResult(false, "no_nonce")
        val peerFingerprint = payload?.get("fingerprint")?.jsonPrimitive?.content ?: ""

        // Forward to pairing engine (initiator side)
        val result = TwinPairingEngine.handleChallenge(deviceId, nonceB, peerFingerprint)
        if (result.error.isNotBlank()) {
            return AcpResult(false, "pair_challenge_failed", result.error)
        }
        return AcpResult(true, "challenge_received", result.verificationCode)
    }

    private suspend fun handleHeartbeat(msg: AcpMessage): AcpResult {
        syncEngine.onHeartbeatReceived(msg.from)
        return AcpResult(true, "alive")
    }

    private suspend fun handleRevoke(msg: AcpMessage): AcpResult {
        val payload = try { json.parseToJsonElement(msg.payload).jsonObject } catch (_: Exception) { null }
        val requested = payload?.get("revokedPeerId")?.jsonPrimitive?.content ?: ""
        // P0 fix: REVOKE 只能撤销自己 — 此前任意可信 peer 可携带任意 revokedPeerId
        // 解除/破坏其它 peer 的信任 (横向破坏)。解绑自身由 AcpServer 层 isTrusted +
        // transport 层 IP 绑定双重保证发送者身份。
        if (requested.isNotBlank() && requested != msg.from) {
            return AcpResult(false, "revoke_denied", "REVOKE must target the sender peer")
        }
        val revokedPeerId = msg.from
        android.util.Log.w("MengPawTwin", "收到孪生解绑: from=${revokedPeerId}")
        syncEngine.onRevokeReceived(revokedPeerId)
        return AcpResult(true, "revoke_processed", "已处理 $revokedPeerId 的解绑请求")
    }

    /**
     * P0 fix: 消毒对端提供的相对路径。
     * 拒绝: 空白 / 绝对路径 / 含 `..` 段的路径 (反斜杠也防 — 双平台保险)。
     * 未通过 → null, 调用方跳过该条目 (拒绝服务单文件, 不整包拒绝)。
     */
    // internal 为测试可见性 — 纯函数, 无副作用
    internal fun sanitizeRelPath(relPath: String): String? {
        if (relPath.isBlank()) return null
        if (relPath.startsWith("/") || relPath.startsWith("\\")) return null
        if (relPath.contains(":")) return null  // Windows 盘符/URL scheme 保险
        if (relPath.split('/', '\\').any { it == ".." }) return null
        return relPath
    }

    private suspend fun handlePairConfirm(msg: AcpMessage): AcpResult {
        val payload = try { json.parseToJsonElement(msg.payload).jsonObject } catch (_: Exception) { null }
        val deviceId = payload?.get("deviceId")?.jsonPrimitive?.content ?: return AcpResult(false, "no_device_id")
        val verificationCode = payload?.get("verificationCode")?.jsonPrimitive?.content ?: ""
        val signature = payload?.get("signature")?.jsonPrimitive?.content ?: ""

        // Forward to pairing engine (responder side)
        val result = TwinPairingEngine.handleConfirm(deviceId, verificationCode, signature)
        if (result.error.isNotBlank()) {
            return AcpResult(false, "pair_confirm_failed", result.error)
        }
        // After successful pairing, update sync engine with new peer
        syncEngine.onPairingEstablished(deviceId)
        return AcpResult(true, "pairing_established")
    }
}
