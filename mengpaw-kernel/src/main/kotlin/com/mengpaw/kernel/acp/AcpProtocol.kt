// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

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
    // ── Memory Twin (记忆孪生) ──
    LEDGER_HEAD,            // 交换账本头部（最新哈希 + 条目数）
    LEDGER_PULL,            // 请求 since-hash 之后的条目
    LEDGER_BATCH,           // 批量传输账本条目
    LEDGER_ACK,             // 确认接收并验证通过
    CAPABILITY_ANNOUNCE,    // 宣告设备能力卡
    TWIN_DELEGATE,          // 孪生任务委派（带能力需求）
    PAIR_CHALLENGE,         // 配对挑战（接收方响应, 携带 nonce+指纹）
    PAIR_CONFIRM,           // 配对确认（发起方验证短码后, 携带签名）
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
    val ttl: Int = 10
) {
    companion object {
        fun discover(from: String) = AcpMessage(from, "*", AcpMessageType.DISCOVER.name)
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

        // ── Memory Twin factory methods ──────────────────────────────

        fun ledgerHead(from: String, to: String, latestHash: String, entryCount: Int) =
            AcpMessage(from, to, AcpMessageType.LEDGER_HEAD.name,
                kotlinx.serialization.json.buildJsonObject {
                    put("latestHash", JsonPrimitive(latestHash))
                    put("entryCount", JsonPrimitive(entryCount))
                }.toString())

        fun ledgerPull(from: String, to: String, sinceHash: String, maxCount: Int = 100) =
            AcpMessage(from, to, AcpMessageType.LEDGER_PULL.name,
                kotlinx.serialization.json.buildJsonObject {
                    put("sinceHash", JsonPrimitive(sinceHash))
                    put("maxCount", JsonPrimitive(maxCount))
                }.toString())

        fun ledgerBatch(from: String, to: String, entries: String, rangeStart: String, rangeEnd: String) =
            AcpMessage(from, to, AcpMessageType.LEDGER_BATCH.name,
                // entries is already a serialized JSON array — embed directly
                buildJsonObject {
                    put("entries", Json.parseToJsonElement(entries))
                    put("rangeStart", JsonPrimitive(rangeStart))
                    put("rangeEnd", JsonPrimitive(rangeEnd))
                }.toString())

        fun ledgerAck(from: String, to: String, receivedHash: String, verified: Boolean = true) =
            AcpMessage(from, to, AcpMessageType.LEDGER_ACK.name,
                kotlinx.serialization.json.buildJsonObject {
                    put("receivedHash", JsonPrimitive(receivedHash))
                    put("verified", JsonPrimitive(verified))
                }.toString())

        fun capabilityAnnounce(from: String, to: String, capabilityCard: String, nonce: String = "") =
            AcpMessage(from, to, AcpMessageType.CAPABILITY_ANNOUNCE.name,
                kotlinx.serialization.json.buildJsonObject {
                    put("capabilityCard", JsonPrimitive(capabilityCard))
                    put("nonce", JsonPrimitive(nonce))
                }.toString())

        fun twinDelegate(from: String, to: String, task: String, requirements: String = "[]") =
            AcpMessage(from, to, AcpMessageType.TWIN_DELEGATE.name,
                kotlinx.serialization.json.buildJsonObject {
                    put("task", JsonPrimitive(task))
                    put("sessionId", JsonPrimitive(from))
                    put("requirements", kotlinx.serialization.json.Json.parseToJsonElement(requirements))
                }.toString())

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
