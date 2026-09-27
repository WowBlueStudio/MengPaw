// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import kotlinx.serialization.Serializable
import com.mengpaw.harness.CheckpointStatus

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
    val attachments: List<AttachmentData> = emptyList(),
    // DeepSeek 思考模式 (v0.41.1 未发布): 思维链 reasoning_content — 官方要求
    // 多轮工具调用时 assistant 的 reasoning_content 必须原样回传, 否则 API 400
    // ("The reasoning_content in the thinking mode must be passed back to the API")。
    // 旧会话 JSON 无此键 → 默认 null, 零迁移; 仅 assistant 消息持有, 仅请求侧
    // 按供应商 (deepseek) 透传, 其它 OpenAI 兼容端点忽略该键。
    val reasoning: String? = null
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
 *
 * 状态与消息序列 (工作流 D 升级): 旧实现只有进度摘要, 无法"接着跑" —
 * 恢复后既不知道上次是正常结束还是崩在半路 ([status]), 也没有可回灌的对话上下文
 * ([messages])。三个新字段全部带默认值, 旧档 (无这些键) 零迁移可读, 源码级兼容。
 *
 * @property status 运行态; 缺失时按 [CheckpointStatus.RUNNING] 处理 (旧档语义: 未标记完成即视为进行中)
 * @property messages 恢复所需的消息序列 (只存 role/content/reasoning; 落盘前由 CheckpointManager 截断)
 * @property updatedAt 最后写入时间 — 保留策略的排序主判据 (默认回落 createdAt, 保持旧档语义)
 * @property terminationReason 终止原因 (与 AgentResult.terminationReason 同源; 清除时写 "cleared")
 * @property answer 最终答复 (正常完成时有值)
 */
@Serializable
data class Checkpoint(
    val sessionId: String,
    val step: Int,
    val remainingTask: String,
    val context: Map<String, String>,
    val createdAt: Long = System.currentTimeMillis(),
    val status: CheckpointStatus = CheckpointStatus.RUNNING,
    val messages: List<Message> = emptyList(),
    val updatedAt: Long = createdAt,
    val terminationReason: String? = null,
    val answer: String? = null
)
