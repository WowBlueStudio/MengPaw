// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.acp

import kotlinx.serialization.Serializable

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
    BROWSER_PUSH,           // 推送网页到对端
    BROWSER_PUSH_RESPONSE,  // 推送响应（接受/拒绝）
    // ── Memory Twin (记忆孪生) ──
    LEDGER_HEAD,            // 交换账本头部（最新哈希 + 条目数）
    LEDGER_PULL,            // 请求 since-hash 之后的条目
    LEDGER_BATCH,           // 批量传输账本条目
    LEDGER_ACK,             // 确认接收并验证通过
    CAPABILITY_ANNOUNCE,    // 宣告设备能力卡
    TWIN_DELEGATE           // 孪生任务委派（带能力需求）
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
        fun browserPush(from: String, to: String, url: String, title: String = "") =
            AcpMessage(from, to, AcpMessageType.BROWSER_PUSH.name,
                """{"url":"$url","title":"$title"}""")
        fun browserPushResponse(from: String, to: String, accepted: Boolean, reason: String = "") =
            AcpMessage(from, to, AcpMessageType.BROWSER_PUSH_RESPONSE.name,
                """{"accepted":$accepted,"reason":"$reason"}""")

        // ── Memory Twin factory methods ──────────────────────────────

        fun ledgerHead(from: String, to: String, latestHash: String, entryCount: Int) =
            AcpMessage(from, to, AcpMessageType.LEDGER_HEAD.name,
                """{"latestHash":"$latestHash","entryCount":$entryCount}""")

        fun ledgerPull(from: String, to: String, sinceHash: String, maxCount: Int = 100) =
            AcpMessage(from, to, AcpMessageType.LEDGER_PULL.name,
                """{"sinceHash":"$sinceHash","maxCount":$maxCount}""")

        fun ledgerBatch(from: String, to: String, entries: String, rangeStart: String, rangeEnd: String) =
            AcpMessage(from, to, AcpMessageType.LEDGER_BATCH.name,
                """{"entries":$entries,"rangeStart":"$rangeStart","rangeEnd":"$rangeEnd"}""")

        fun ledgerAck(from: String, to: String, receivedHash: String, verified: Boolean = true) =
            AcpMessage(from, to, AcpMessageType.LEDGER_ACK.name,
                """{"receivedHash":"$receivedHash","verified":$verified}""")

        fun capabilityAnnounce(from: String, to: String, capabilityCard: String) =
            AcpMessage(from, to, AcpMessageType.CAPABILITY_ANNOUNCE.name, capabilityCard)

        fun twinDelegate(from: String, to: String, task: String, requirements: String = "[]") =
            AcpMessage(from, to, AcpMessageType.TWIN_DELEGATE.name,
                """{"task":"${task.replace("\"", "\\\"")}","requirements":$requirements,"sessionId":"$from"}""")
    }
}

/** A discovered peer Agent. */
data class PeerAgent(
    val agentId: String,
    val agentName: String,
    val address: String,
    val port: Int = 9876,
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
