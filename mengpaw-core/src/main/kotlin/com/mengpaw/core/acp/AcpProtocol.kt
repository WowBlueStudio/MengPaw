// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.acp

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
    HEARTBEAT       // 存活检测
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
