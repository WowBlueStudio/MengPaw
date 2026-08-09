// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.shell.ui.screens.model.AgentSession
import com.mengpaw.shell.ui.screens.model.ChatMessageUi

/**
 * 会话消息助手 (自 AgentViewModel 拆出 — delegate-object 模式):
 * 系统消息注入 / 横幅 / 撤回 / 引用格式化 / 停止执行。
 * 依赖经构造器注入: 会话表、活动会话桥接、活动 Agent 名桥接。
 */
internal class SessionMessageCenter(
    private val sessions: MutableMap<String, AgentSession>,
    private val getActiveSession: () -> AgentSession,
    private val getActiveAgentName: () -> String,
) {

    /** Stop the active agent's engine. */
    fun stopAgent() { getActiveSession().engine.stop() }

    /** Set a system message for the loading state. Called from AgentRuntime. */
    fun setInitializingMessage(text: String) {
        val session = sessions[getActiveAgentName()] ?: return
        session.messages.value = listOf(ChatMessageUi.System(text))
    }

    /** 当前活动 Agent 的模型名 (v0.33.0+: 语音按钮能力判定用). */
    fun activeModelName(): String = getActiveSession().modelName

    /** Inject an Agent-pushed notification into the chat message list. */
    fun notifyAgentMessage(text: String) {
        val session = getActiveSession()
        session.messages.value = session.messages.value + ChatMessageUi.System(text)
    }

    /** Update the system banner text (for localization). */
    fun setBanner(text: String) {
        val current = getActiveSession().messages.value
        if (current.isNotEmpty() && current.first() is ChatMessageUi.System) {
            getActiveSession().messages.value = listOf(ChatMessageUi.System(text)) + current.drop(1)
        }
    }

    /** Retract the last user message: stop agent, remove user+agent msgs, return text to input. */
    fun retractLastUserMessage(): String? {
        stopAgent()
        // ── Evolution: 撤回 = 用户否定上次回答, 记入用户反应档案 ──
        com.mengpaw.kernel.evolution.EvolutionHook.recordCorrection(
            agentName = getActiveAgentName(),
            correction = "(用户撤回上一条消息)",
            contextSnippet = "",
            task = ""
        )
        val msgs = getActiveSession().messages.value.toMutableList()
        // Find last user message
        val lastUserIdx = msgs.indexOfLast { it is ChatMessageUi.User }
        if (lastUserIdx < 0) return null
        val userMsg = msgs[lastUserIdx] as ChatMessageUi.User
        // Remove user message and everything after it (agent responses)
        val keep = msgs.take(lastUserIdx)
        getActiveSession().messages.value = keep
        return userMsg.content
    }

    /** Build a quoted reference string for Agent context. */
    fun formatQuote(msg: ChatMessageUi): String {
        return when (msg) {
            is ChatMessageUi.User -> "> 用户说: ${msg.content.take(200)}"
            is ChatMessageUi.Agent -> "> Agent 回复: ${msg.content.take(200)}"
            is ChatMessageUi.AgentWithTrace -> "> Agent 回复: ${msg.finalContent.take(200)}"
            is ChatMessageUi.AgentStep -> "> Agent 步骤: ${msg.content.take(200)}"
            is ChatMessageUi.FinalAnswer -> "> Agent 回复: ${msg.content.take(200)}"
            is ChatMessageUi.CommandResult -> "> 命令输出: ${msg.content.take(200)}"
            else -> ""
        }
    }

    /** Whether the given message is the last user message (retractable). */
    fun isLastUserMessage(msg: ChatMessageUi): Boolean {
        val msgs = getActiveSession().messages.value
        val lastUser = msgs.lastOrNull { it is ChatMessageUi.User }
        return msg == lastUser
    }
}
