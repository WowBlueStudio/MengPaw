// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.session.AttachmentData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ── JSON serialization helpers for persistence — 拆自 SessionPersistenceService.kt (批次4) ──

internal val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

@Serializable
data class SessionPersistenceData(
    val sessionId: String = "",
    val engineSessionId: String = "",
    val messages: List<MessageData> = emptyList()
)

@Serializable
data class MessageData(
    val type: String,
    val text: String,
    val executionMode: String? = null,
    val agentRef: String? = null,
    val traces: List<TraceData> = emptyList(),
    /** True for failed "!command" results (type="command"). Default false keeps old files compatible. */
    val isError: Boolean = false,
    /** 结构化附件 (v0.33.0+) — 旧会话 JSON 无此键 → 默认空, 零迁移。 */
    val attachments: List<AttachmentData> = emptyList(),
    /** AgentStep 步骤气泡 (v0.3x, type="agent_step") — 默认值兼容旧文件。 */
    val step: Int = 0,
    val thought: String = "",
    val action: String = "",
    val isFinal: Boolean = false,
    /** v0.34.3 思考过程容器 (type="thinking_process") / 最终答案 (type="final_answer")。 */
    val isRunning: Boolean = false,
    val collapsed: Boolean = false,
    val steps: List<ProcessStepData> = emptyList()
)

/** 一轮 ReAct 交互持久化 (v0.34.3 气泡 UI 重构)。 */
@Serializable
data class ProcessStepData(
    val thought: String = "",
    val tools: List<ProcessToolData> = emptyList()
)

/** 一次工具调用持久化 — 折叠行只显命令名, 展开看参数与观察全文。 */
@Serializable
data class ProcessToolData(
    val command: String = "",
    val actionInput: String = "",
    val observation: String = "",
    val isError: Boolean = false
)

@Serializable
data class TraceData(
    val step: Int = 0,
    val thought: String = "",
    val action: String = "",
    val observation: String = ""
)
