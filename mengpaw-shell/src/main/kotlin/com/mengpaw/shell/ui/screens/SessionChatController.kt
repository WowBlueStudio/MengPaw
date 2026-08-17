// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.AgentState
import com.mengpaw.shell.ui.screens.model.AgentSession
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import com.mengpaw.shell.ui.screens.model.PendingTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 会话聊天状态控制器 (自 AgentViewModel 拆出 — delegate-object 模式):
 * 持有可观察 UI 状态 (messages/isRunning/inputEnabled/pendingTasks/activeAgent),
 * 负责把活动会话的消息流与引擎状态流绑定到这些 StateFlow, 并解析活动会话。
 */
internal class SessionChatController(
    private val sessions: MutableMap<String, AgentSession>,
    private val sessionFactory: AgentSessionFactory,
    private val scope: CoroutineScope,
    private val getActiveAgentName: () -> String,
) {

    // ── Observable state (backed by active session) ──
    val messagesFlow = MutableStateFlow<List<ChatMessageUi>>(emptyList())
    val isRunningFlow = MutableStateFlow(false)
    val inputEnabledFlow = MutableStateFlow(true)
    val pendingTasksFlow = MutableStateFlow<List<PendingTask>>(emptyList())
    val activeAgentFlow = MutableStateFlow(DEFAULT_AGENT_NAME)

    val messages: StateFlow<List<ChatMessageUi>> = messagesFlow.asStateFlow()
    val isRunning: StateFlow<Boolean> = isRunningFlow.asStateFlow()
    val inputEnabled: StateFlow<Boolean> = inputEnabledFlow.asStateFlow()
    val pendingTasks: StateFlow<List<PendingTask>> = pendingTasksFlow.asStateFlow()
    val activeAgent: StateFlow<String> = activeAgentFlow.asStateFlow()

    private var stateObserverJob: Job? = null
    private var messageBindingJob: Job? = null

    /** 活动会话 — 不存在则创建 (原 AgentViewModel.activeSession, 逻辑不变). */
    fun activeSession(): AgentSession {
        sessionFactory.ensureDefaultSession()
        return sessions.getOrPut(getActiveAgentName()) { sessionFactory.createSession(getActiveAgentName(), null) }
    }

    /** 绑定活动会话的消息流 + 引擎状态流到公开 StateFlow (原 bindActiveSession, 逻辑不变). */
    fun bind() {
        sessionFactory.ensureDefaultSession()
        val session = sessions[getActiveAgentName()] ?: return
        activeAgentFlow.value = getActiveAgentName()

        // FIX U1: Reactively bind session.messages → _messages so UI updates on every message change
        messageBindingJob?.cancel()
        messageBindingJob = scope.launch {
            session.messages.collect { msgs -> messagesFlow.value = msgs }
        }

        // Re-bind state observer to the new engine
        stateObserverJob?.cancel()
        stateObserverJob = scope.launch {
            session.engine.state.collect { state ->
                when (state) {
                    is AgentState.Idle -> {
                        session.isRunning.value = false; isRunningFlow.value = false
                        session.inputEnabled.value = true; inputEnabledFlow.value = true
                    }
                    is AgentState.Running -> {
                        session.isRunning.value = true; isRunningFlow.value = true
                    }
                    is AgentState.Finished -> {
                        session.isRunning.value = false; isRunningFlow.value = false
                        session.inputEnabled.value = true; inputEnabledFlow.value = true
                    }
                    is AgentState.Error -> {
                        session.isRunning.value = false; isRunningFlow.value = false
                        session.inputEnabled.value = true; inputEnabledFlow.value = true
                        // FIX(双弹): 引擎错误路径返回 errorMsg 经 run() 尾段写入 running 消息,
                        // 此处 Error 监听再次追加 → 同源错误消息连弹两条。幂等检查防重。
                        val last = session.messages.value.lastOrNull()
                        val alreadyShown = (last is ChatMessageUi.Agent && last.content == state.message) ||
                            (last is ChatMessageUi.AgentWithTrace && last.finalContent == state.message) ||
                            (last is ChatMessageUi.AgentStep && last.content == state.message) ||
                            (last is ChatMessageUi.FinalAnswer && last.content == state.message)
                        if (!alreadyShown) {
                            session.messages.value = session.messages.value + ChatMessageUi.Agent(state.message)
                        }
                    }
                }
            }
        }
    }
}
