// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import kotlinx.serialization.Serializable

/**
 * A single message in the conversation history.
 *
 * @property localOnly if true, this message is metadata only — never sent to the LLM in getStructuredHistory.
 * @property interruptedTurn recovery metadata for an interrupted assistant turn (localOnly implied).
 */
@Serializable
data class Message(
    val role: String,        // "user", "assistant", "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val localOnly: Boolean = false,
    val interruptedTurn: InterruptedTurnRecovery? = null,
    // 结构化附件 (v0.33.0+): 旧会话 JSON 无此键 → 默认空列表, 零迁移
    val attachments: List<AttachmentData> = emptyList()
)

/**
 * Recovery metadata for an interrupted assistant turn.
 * When [pending] is true, the engine will inject a structured recovery block
 * before the next user message. Once injected, [pending] is set to false.
 *
 * Only structured facts (tool names, file paths, diff stats) are stored —
 * never raw assistant text or reasoning content. See interrupted_recovery.kt.
 */
@Serializable
data class InterruptedTurnRecovery(
    val pending: Boolean = true,
    val completedTools: List<InterruptedToolSummary> = emptyList(),
    val interruptedTools: List<String> = emptyList(),
    val droppedPartialText: Boolean = false,
    val droppedPartialReasoning: Boolean = false
)

/**
 * Summary of a successfully completed tool call during an interrupted turn.
 * Only structured facts: tool name, involved files, line diff stats.
 */
@Serializable
data class InterruptedToolSummary(
    val name: String,
    val files: List<String> = emptyList(),
    val added: Int = 0,
    val removed: Int = 0
)

/**
 * Represents a session - a single Agent conversation.
 *
 * @property scope the lifecycle scope: "agent" (default), "framework", "system"
 * @property agentId the agent handling this session
 * @property schemaVersion incremented when the persisted schema changes; see migrateSession()
 */
@Serializable
data class Session(
    val id: String,
    val task: String,
    val scope: String = "agent",
    val agentId: String = "agent",
    val schemaVersion: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val messages: MutableList<Message> = mutableListOf(),
    val metadata: Map<String, String> = emptyMap()
)

/**
 * A checkpoint for saving and restoring Agent progress.
 */
@Serializable
data class Checkpoint(
    val sessionId: String,
    val step: Int,
    val remainingTask: String,
    val context: Map<String, String>,
    val createdAt: Long = System.currentTimeMillis()
)
