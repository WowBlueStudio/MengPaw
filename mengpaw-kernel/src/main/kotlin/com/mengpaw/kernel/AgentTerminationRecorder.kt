// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

/**
 * 失败截断进化记录器 (2026-08-08 引入, v0.40.4 P2 拆自 AgentReActLoop 400 行红线)。
 * 职责: 终止/中断路径统一记录进化上下文 — 剪取会话尾部最近消息片段后写入 EvolutionStore。
 * 永不抛异常, 不影响主链路返回。
 */
internal class AgentTerminationRecorder(private val engine: AgentEngine) {

    /**
     * 失败截断进化介入: 剪取上下文片段并记录。所有终止路径 (会话损坏/空响应/循环/连续失败/
     * 步数上限/异常中断) 共用此入口。
     */
    fun record(sessionId: String, reason: String, command: String, errorCode: String, task: String = "") {
        try {
            com.mengpaw.kernel.evolution.EvolutionStore.recordTermination(
                agentName = engine.agentName,
                reason = reason,
                command = command,
                errorCode = errorCode,
                contextSnippet = clipSessionContext(sessionId),
                task = task
            )
        } catch (_: Exception) { /* 进化记录永不阻塞主链路 */ }
    }

    /**
     * 上下文片段剪取: 取会话尾部最近 N 条非 localOnly 消息
     * (Thought/Action/Observation 序列), 截断到 maxChars。剪取失败返回空串。
     */
    private fun clipSessionContext(sessionId: String, maxEntries: Int = 6, maxChars: Int = 500): String {
        return try {
            val msgs = engine.getSessionManager().getSession(sessionId)?.messages ?: return ""
            msgs.filter { !it.localOnly && it.content.isNotBlank() }
                .takeLast(maxEntries)
                .joinToString("\n") { "[${it.role}] ${it.content.take(160)}" }
                .take(maxChars)
        } catch (_: Exception) { "" }
    }
}
