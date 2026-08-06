// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens.model

import com.mengpaw.kernel.session.AttachmentData
import com.mengpaw.shell.ui.screens.PluginSuggestion

data class AgentTrace(
    val step: Int,
    val thought: String,
    val action: String?,
    val observation: String?
)

sealed class ChatMessageUi {
    abstract val stableId: String
    internal val createdAt: Long = java.lang.System.nanoTime()

    data class User(
        val content: String,
        val attachments: List<AttachmentData> = emptyList()
    ) : ChatMessageUi() {
        override val stableId get() = "u_$createdAt"
    }

    data class Agent(
        val content: String,
        val executionMode: String? = null,
        val agentRef: String? = null
    ) : ChatMessageUi() {
        override val stableId get() = "a_$createdAt"
    }

    data class AgentWithTrace(
        val finalContent: String,
        val traces: List<AgentTrace>,
        val isRunning: Boolean = false,
        val executionMode: String? = null,
        val agentRef: String? = null
    ) : ChatMessageUi() {
        override val stableId get() = "t_$createdAt"
    }

    /**
     * 单步执行气泡 (v0.3x) — 每个 ReAct 步骤一个独立气泡:
     * 思考折叠头 (Step N + 完整 thought, 展开全程可见) + 正文
     * (运行中 = 流式文本, 完成后 = 工具结果 / 最终答案)。
     * 最终答案 = 最后一步 (isFinal=true)。
     */
    data class AgentStep(
        val step: Int,
        val thought: String,
        val action: String?,
        val content: String,
        val isRunning: Boolean = false,
        val isFinal: Boolean = false,
        val executionMode: String? = null,
        val agentRef: String? = null
    ) : ChatMessageUi() {
        override val stableId get() = "st_${step}_$createdAt"
    }

    data class System(val content: String) : ChatMessageUi() {
        override val stableId get() = "s_$createdAt"
    }

    /** Result of a "!command" — executed locally, bypassing the agent. */
    data class CommandResult(val content: String, val isError: Boolean = false) : ChatMessageUi() {
        override val stableId get() = "c_$createdAt"
    }

    data class Suggestion(val suggestion: PluginSuggestion) : ChatMessageUi() {
        override val stableId get() = "sg_$createdAt"
    }
}
