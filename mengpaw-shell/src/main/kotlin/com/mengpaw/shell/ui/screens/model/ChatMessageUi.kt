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

    // ── 思考过程容器 + 最终答案 (v0.34.3 气泡 UI 重构) ──
    // 一次任务 = 单一过程容器 (思考/调用/观察循环, 折叠) + 最终答案气泡。
    // 工具行只显示命令名, 失败红字; 观察全文点击展开; 思考全文保留可回看。

    /** 一次工具调用 — 折叠行显示命令名, 展开显示完整参数与观察全文。 */
    data class ProcessTool(
        val command: String,      // 命令名 (如 agent.write)
        val actionInput: String,  // 完整 Action Input (折叠, 展开显示)
        val observation: String,  // 工具结果全文 (折叠, 点击展开)
        val isError: Boolean = false
    )

    /** 一轮 ReAct 交互 — 思考 + 该轮工具调用列表 (多 Action 并行)。 */
    data class ProcessStep(
        // v0.36.3: roundId 是流式播放缓冲的轮次 id — 运行中把同一轮思考/工具
        // 增量路由到同一步 (播放协程与 addTool 并发, 不能再靠 tools 空否判断轮界);
        // 仅运行期瞬态, 持久化不落盘 (历史恢复默认 0, 静态渲染不依赖)。
        val roundId: Long = 0,
        val thought: String = "",
        val tools: List<ProcessTool> = emptyList()
    )

    /** 思考过程容器 — 跨所有轮次, 最终答案开始时自动折叠 (collapsed=true)。 */
    data class ThinkingProcess(
        val steps: List<ProcessStep>,
        val isRunning: Boolean = false,
        val collapsed: Boolean = false,
        val executionMode: String? = null,
        val agentRef: String? = null
    ) : ChatMessageUi() {
        override val stableId get() = "tp_$createdAt"
        val toolCount: Int get() = steps.sumOf { it.tools.size }
    }

    /** 最终答案气泡 — 与过程容器分离, 流式输出。 */
    data class FinalAnswer(
        val content: String,
        val isRunning: Boolean = false,
        val executionMode: String? = null,
        val agentRef: String? = null
    ) : ChatMessageUi() {
        override val stableId get() = "fa_$createdAt"
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
