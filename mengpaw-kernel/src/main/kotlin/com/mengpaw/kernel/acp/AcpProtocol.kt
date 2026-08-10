// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.acp

import com.mengpaw.kernel.ports.Ports
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ACP (Agent Communication Protocol) — MengPaw Agent 间通信协议。
 *
 * 允许:
 * - Agent 发现其他 Agent（局域网 mDNS / 互联网 registry）
 * - 委派任务（delegate）
 * - 共享记忆/技能
 * - 心跳存活检测
 */

/** ACP 消息类型。 */
enum class AcpMessageType {
    DISCOVER,       // 发现请求/响应
    DELEGATE,       // 委派任务
    RESULT,         // 任务结果
    SHARE_MEMORY,   // 共享记忆条目
    SHARE_SKILL,    // 共享技能定义
    HEARTBEAT,      // 存活检测
    TRIBE_CHAT,     // 部落广播消息（tribe.chat 群聊）
    BROWSER_PUSH,           // 推送网页到对端
    BROWSER_PUSH_RESPONSE,  // 推送响应（接受/拒绝）
    // ── Memory Twin (记忆孪生, 工作区文件同步 v0.22.0) ──
    WS_MANIFEST,            // 交换工作区文件清单 (manifest 比对 → 收敛差异)
    WS_PULL,                // 请求缺失/变更的文件内容
    CAPABILITY_ANNOUNCE,    // 宣告设备能力卡
    TWIN_DELEGATE,          // 孪生任务委派（带能力需求）
    PAIR_CHALLENGE,         // 配对挑战（接收方响应, 携带 nonce+指纹）
    PAIR_CONFIRM,           // 配对确认（发起方验证短码后, 携带签名）
    FRAMEWORK_PAIR_REQUEST, // 框架通讯录配对请求（发现节点 → 请求入册, 对方确认后双向入册）
    FRAMEWORK_PAIR_ACCEPT,  // 框架配对请求同意（双方入册）
    FRAMEWORK_PAIR_DECLINE, // 框架配对请求拒绝
    FLEET_RESULT,           // 舰队委派结果回传 (v0.36: 对端执行完 → 指挥舰状态回收)
    MCP_REQUEST,            // MCP JSON-RPC 请求 (tools/list, tools/call, etc.)
    MCP_RESPONSE,           // MCP JSON-RPC 响应 (通过 ACP 返回)
    // ── Session Sync (会话同步 / Upstream Links) ──
    SESSION_HEAD,           // 交换会话最新事件序列号
    SESSION_PULL,           // 请求 N 个最新的会话事件
    SESSION_DELTA,          // 传输会话事件增量
    SESSION_ACK,            // 确认接收会话事件
    // ── Memory Twin lifecycle ──
    REVOKE                  // 孪生撤销/解绑（设备丢失/手动解绑）
}

/** ACP 消息。 */
@Serializable
data class AcpMessage(
    val from: String,
    val to: String,
    val type: String,
    val payload: String = "",
    val ttl: Int = 10,
    /** 请求-响应关联 ID (MCP_REQUEST/MCP_RESPONSE 往返用; 旧消息默认空, 向后兼容). */
    val requestId: String = ""
) {
    companion object {
        /** DISCOVER 带协议能力协商: payload = {"protocols":[...]} — 空参保持旧格式兼容. */
        fun discover(from: String, protocols: List<String> = emptyList()) = AcpMessage(
            from, "*", AcpMessageType.DISCOVER.name,
            if (protocols.isEmpty()) "" else kotlinx.serialization.json.buildJsonObject {
                put("protocols", kotlinx.serialization.json.buildJsonArray { protocols.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            }.toString()
        )
        fun delegate(from: String, to: String, task: String) = AcpMessage(from, to, AcpMessageType.DELEGATE.name, task)
        fun result(from: String, to: String, text: String) = AcpMessage(from, to, AcpMessageType.RESULT.name, text)
        fun shareMemory(from: String, to: String, memoryId: String) = AcpMessage(from, to, AcpMessageType.SHARE_MEMORY.name, memoryId)
        fun shareSkill(from: String, to: String, skillName: String) = AcpMessage(from, to, AcpMessageType.SHARE_SKILL.name, skillName)
        fun heartbeat(from: String) = AcpMessage(from, "*", AcpMessageType.HEARTBEAT.name, ttl = 1)
        fun tribeChat(from: String, to: String, message: String) =
            AcpMessage(from, to, AcpMessageType.TRIBE_CHAT.name,
                kotlinx.serialization.json.buildJsonObject {
                    put("message", JsonPrimitive(message))
                }.toString())
        fun browserPush(from: String, to: String, url: String, title: String = "") =
            // SECURITY: Use kotlinx.serialization to prevent JSON injection via URL/title
            AcpMessage(from, to, AcpMessageType.BROWSER_PUSH.name,
                kotlinx.serialization.json.buildJsonObject {
                    put("url", JsonPrimitive(url))
                    put("title", JsonPrimitive(title))
                }.toString())
        fun browserPushResponse(from: String, to: String, accepted: Boolean, reason: String = "") =
            AcpMessage(from, to, AcpMessageType.BROWSER_PUSH_RESPONSE.name,
                kotlinx.serialization.json.buildJsonObject {
                    put("accepted", JsonPrimitive(accepted))
                    put("reason", JsonPrimitive(reason))
                }.toString())

        // ── Memory Twin factory methods (工作区文件同步) ──────────────

        /** WS_MANIFEST: 本机工作区文件清单 {relPath: {hash, mtime}} — 对端比对后经响应返回差异。 */
        fun wsManifest(from: String, to: String, manifest: String) =
            AcpMessage(from, to, AcpMessageType.WS_MANIFEST.name, manifest)

        /** WS_PULL: 请求指定路径的文件内容。 */
        fun wsPull(from: String, to: String, paths: String) =
            AcpMessage(from, to, AcpMessageType.WS_PULL.name, paths)

        fun capabilityAnnounce(from: String, to: String, capabilityCard: String, nonce: String = "") =
            AcpMessage(from, to, AcpMessageType.CAPABILITY_ANNOUNCE.name,
                kotlinx.serialization.json.buildJsonObject {
                    put("capabilityCard", JsonPrimitive(capabilityCard))
                    put("nonce", JsonPrimitive(nonce))
                }.toString())

        fun twinDelegate(
            from: String, to: String, task: String, requirements: String = "[]",
            delegateId: String = "", callbackAddress: String = "", callbackPort: Int = 0
        ) = AcpMessage(from, to, AcpMessageType.TWIN_DELEGATE.name,
            kotlinx.serialization.json.buildJsonObject {
                put("task", JsonPrimitive(task))
                put("sessionId", JsonPrimitive(from))
                put("requirements", kotlinx.serialization.json.Json.parseToJsonElement(requirements))
                // v0.36 舰队闭环: 委派 ID + 回传地址 — 对端执行完后 fleet.reply 回传结果
                if (delegateId.isNotBlank()) put("delegateId", JsonPrimitive(delegateId))
                if (callbackAddress.isNotBlank()) put("callbackAddress", JsonPrimitive(callbackAddress))
                if (callbackPort > 0) put("callbackPort", JsonPrimitive(callbackPort))
            }.toString())

        /** FLEET_RESULT: 对端执行完舰队委派, 结果回传指挥舰 (delegateId 关联)。 */
        fun fleetResult(
            from: String, to: String, delegateId: String, result: String, success: Boolean
        ) = AcpMessage(from, to, AcpMessageType.FLEET_RESULT.name,
            kotlinx.serialization.json.buildJsonObject {
                put("delegateId", JsonPrimitive(delegateId))
                put("result", JsonPrimitive(result))
                put("success", JsonPrimitive(success))
            }.toString(), requestId = delegateId)

        // ── Session Sync factory methods ────────────────────────────

        fun sessionHead(from: String, to: String, sessionKey: String, lastSequence: Int, entryCount: Int) =
            AcpMessage(from, to, AcpMessageType.SESSION_HEAD.name,
                buildJsonObject {
                    put("sessionKey", JsonPrimitive(sessionKey))
                    put("lastSequence", JsonPrimitive(lastSequence))
                    put("entryCount", JsonPrimitive(entryCount))
                }.toString())

        fun sessionPull(from: String, to: String, sessionKey: String, afterSequence: Int, limit: Int = 50) =
            AcpMessage(from, to, AcpMessageType.SESSION_PULL.name,
                buildJsonObject {
                    put("sessionKey", JsonPrimitive(sessionKey))
                    put("afterSequence", JsonPrimitive(afterSequence))
                    put("limit", JsonPrimitive(limit))
                }.toString())

        fun sessionDelta(from: String, to: String, sessionKey: String, events: String,
                         rangeStart: Int, rangeEnd: Int) =
            AcpMessage(from, to, AcpMessageType.SESSION_DELTA.name,
                buildJsonObject {
                    put("sessionKey", JsonPrimitive(sessionKey))
                    put("events", Json.parseToJsonElement(events))
                    put("rangeStart", JsonPrimitive(rangeStart))
                    put("rangeEnd", JsonPrimitive(rangeEnd))
                }.toString())

        fun sessionAck(from: String, to: String, sessionKey: String, receivedSequence: Int) =
            AcpMessage(from, to, AcpMessageType.SESSION_ACK.name,
                buildJsonObject {
                    put("sessionKey", JsonPrimitive(sessionKey))
                    put("receivedSequence", JsonPrimitive(receivedSequence))
                }.toString())

        // ── MCP over ACP (协议升级: 请求-响应一轮完成, requestId 关联) ──

        /** MCP_REQUEST: 把 JSON-RPC 请求封装进 ACP (requestId 关联往返)。 */
        fun mcpRequest(from: String, jsonRpc: String, requestId: String) =
            AcpMessage(from, "*", AcpMessageType.MCP_REQUEST.name, jsonRpc, ttl = 1, requestId = requestId)

        /** MCP_RESPONSE: MCP 调用结果回发 (to = 请求方, requestId 回显)。 */
        fun mcpResponse(from: String, to: String, jsonRpc: String, requestId: String) =
            AcpMessage(from, to, AcpMessageType.MCP_RESPONSE.name, jsonRpc, ttl = 1, requestId = requestId)

        // ── Memory Twin pairing protocol ─────────────────────────────

        fun pairChallenge(from: String, to: String, deviceId: String, nonceB: String, fingerprint: String) =
            AcpMessage(from, to, AcpMessageType.PAIR_CHALLENGE.name,
                kotlinx.serialization.json.buildJsonObject {
                    put("deviceId", JsonPrimitive(deviceId))
                    put("nonceB", JsonPrimitive(nonceB))
                    put("fingerprint", JsonPrimitive(fingerprint))
                }.toString())

        fun pairConfirm(from: String, to: String, deviceId: String, verificationCode: String, signature: String) =
            AcpMessage(from, to, AcpMessageType.PAIR_CONFIRM.name,
                kotlinx.serialization.json.buildJsonObject {
                    put("deviceId", JsonPrimitive(deviceId))
                    put("verificationCode", JsonPrimitive(verificationCode))
                    put("signature", JsonPrimitive(signature))
                }.toString())

        // ── Framework directory pairing (框架通讯录配对请求) ──────────

        /** FRAMEWORK_PAIR_REQUEST: 请求对方将本机加入其框架通讯录 (UI 添加按钮 → 请求-同意流程)。 */
        fun frameworkPairRequest(
            from: String, to: String, requestId: String,
            fingerprint: String, displayName: String, address: String, port: Int
        ) = AcpMessage(from, to, AcpMessageType.FRAMEWORK_PAIR_REQUEST.name,
            kotlinx.serialization.json.buildJsonObject {
                put("requestId", JsonPrimitive(requestId))
                put("fingerprint", JsonPrimitive(fingerprint))
                put("displayName", JsonPrimitive(displayName))
                put("address", JsonPrimitive(address))
                put("port", JsonPrimitive(port))
            }.toString(), requestId = requestId)

        /** FRAMEWORK_PAIR_ACCEPT/DECLINE: 回应配对请求 — accepted=true 携带接受方名片 (发起方据此入册)。 */
        fun frameworkPairResponse(
            from: String, to: String, requestId: String, accepted: Boolean,
            fingerprint: String = "", displayName: String = "",
            address: String = "", port: Int = com.mengpaw.kernel.ports.Ports.ACP,
            reason: String = ""
        ) =
            AcpMessage(from, to,
                if (accepted) AcpMessageType.FRAMEWORK_PAIR_ACCEPT.name else AcpMessageType.FRAMEWORK_PAIR_DECLINE.name,
                kotlinx.serialization.json.buildJsonObject {
                    put("requestId", JsonPrimitive(requestId))
                    put("accepted", JsonPrimitive(accepted))
                    put("fingerprint", JsonPrimitive(fingerprint))
                    put("displayName", JsonPrimitive(displayName))
                    put("address", JsonPrimitive(address))
                    put("port", JsonPrimitive(port))
                    put("reason", JsonPrimitive(reason))
                }.toString(), requestId = requestId)

        /** REVOKE: broadcast twin unpair / device loss. */
        fun revoke(from: String, to: String, revokedPeerId: String) =
            AcpMessage(from, to, AcpMessageType.REVOKE.name,
                kotlinx.serialization.json.buildJsonObject {
                    put("revokedPeerId", JsonPrimitive(revokedPeerId))
                    put("timestamp", JsonPrimitive(System.currentTimeMillis()))
                }.toString())
    }
}

/** A discovered peer Agent. */
data class PeerAgent(
    val agentId: String,
    val agentName: String,
    val address: String,
    val port: Int = Ports.ACP,
    val capabilities: List<String> = emptyList(),
    val lastSeen: Long = System.currentTimeMillis()
)

/** Result of an ACP operation. */
data class AcpResult(
    val success: Boolean,
    val message: String,
    val data: String = ""
)

/**
 * ACP transport abstraction — supports multiple transport layers.
 * Plugins can implement ACPTransport for custom channels (WebSocket, BLE, etc.).
 */
interface AcpTransport {
    suspend fun send(message: AcpMessage): Boolean

    /**
     * 请求-响应发送: 发给指定 peer 并等待 HTTP 响应体 (含 data)。
     * 默认实现无响应返回 null — 支持请求-响应的传输层覆写。
     */
    suspend fun sendForResult(message: AcpMessage, toPeerId: String, timeoutMs: Long = 15_000L): AcpResult? = null

    suspend fun receive(): AcpMessage?
    fun isConnected(): Boolean
    fun close()
}

/**
 * ACP message handler — plugins implement this to handle specific message types.
 */
interface AcpHandler {
    /** Message types this handler can process. */
    val supportedTypes: List<AcpMessageType>

    /** Handle an incoming ACP message. Return null if not handled. */
    suspend fun handle(message: AcpMessage, server: AcpServer): AcpResult?
}
