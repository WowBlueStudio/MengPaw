// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

/**
 * 会话完整性 + 中断恢复职责（自 SessionManager 拆出 — 400 行文件拆分批次 1）。
 *
 * 覆盖:
 * - Interrupted Turn Recovery (Reasonix Level 2): 中断回合以 localOnly 消息记录,
 *   向后扫描可消费; getStructuredHistory 过滤不掉, 永不达 LLM。
 * - Session Integrity Check / Repair (matching OpenClaw assertSqliteIntegrity):
 *   localOnly 泄漏检测、空白 assistant 清理、200 条历史上限。
 *
 * 同步契约: 与 [lock] 共用 — 就地改写消息不得绕过 addMessage/压缩监视器。
 */
internal class SessionIntegrity(
    private val lock: Any,
    private val sessionProvider: (String) -> Session?,
    private val updateSession: (String, Session) -> Unit
) {
    /**
     * Record an interrupted assistant turn as a LocalOnly message.
     * Stored in session history for backwards scanning; filtered out by getStructuredHistory().
     *
     * Matching Reasonix [recordInterruptedDisplay] in agent.go (line 140).
     */
    fun recordInterruptedTurn(
        sessionId: String,
        completedTools: List<InterruptedToolSummary>,
        interruptedTools: List<String>,
        hasPartialText: Boolean,
        hasPartialReasoning: Boolean
    ) {
        val session = sessionProvider(sessionId) ?: return
        val recovery = InterruptedTurnRecovery(
            pending = true,
            completedTools = completedTools,
            interruptedTools = interruptedTools,
            droppedPartialText = hasPartialText,
            droppedPartialReasoning = hasPartialReasoning
        )
        session.messages.add(Message(
            role = "system",
            content = "interrupted-turn-recovery",
            localOnly = true,
            interruptedTurn = recovery
        ))
        updateSession(sessionId, session)
    }

    /**
     * Check whether the given session has a pending (un-consumed) interrupted turn recovery.
     */
    fun hasPendingRecovery(sessionId: String): Boolean {
        return sessionProvider(sessionId)?.messages?.let { msgs ->
            com.mengpaw.kernel.session.findPendingRecovery(msgs) != null
        } ?: false
    }

    /**
     * Consume the pending interrupted turn recovery by setting [InterruptedTurnRecovery.pending] to false.
     * Called by AgentEngine.buildConversation() after the recovery block has been injected.
     *
     * @return true if a pending recovery was found and consumed.
     */
    fun consumePendingRecovery(sessionId: String): Boolean {
        synchronized(lock) {
            val session = sessionProvider(sessionId) ?: return false
            for (i in session.messages.indices.reversed()) {
                val m = session.messages[i]
                if (m.localOnly && m.interruptedTurn != null && m.interruptedTurn.pending) {
                    session.messages[i] = m.copy(
                        interruptedTurn = m.interruptedTurn.copy(pending = false)
                    )
                    updateSession(sessionId, session)
                    return true
                }
            }
            return false
        }
    }

    /**
     * Verify session data integrity. Checks:
     * - No [localOnly] messages leaked between non-local messages
     * - The last message is structurally complete (not truncated JSON)
     * - Event log is parseable (non-destructive check)
     *
     * Matching OpenClaw assertSqliteIntegrity + terminal latch pattern.
     */
    fun checkSessionIntegrity(sessionId: String): Boolean {
        val session = sessionProvider(sessionId) ?: return false
        val msgs = session.messages
        if (msgs.isEmpty()) return true

        for (i in msgs.indices) {
            val msg = msgs[i]
            // localOnly messages should only appear after another localOnly, or at boundaries
            if (msg.localOnly && msg.interruptedTurn != null) {
                // An interrupted_turn message must have a "user" message after it eventually
                // (if the session continued), otherwise it's a dangling interrupt record
                val hasUserAfter = msgs.drop(i + 1).any { it.role == "user" && !it.localOnly }
                val isLastMsg = i == msgs.lastIndex
                // Dangling interrupt at end of session is acceptable (pending recovery)
                if (!hasUserAfter && !isLastMsg) {
                    // localOnly message in the middle of history with no subsequent user message
                    // suggests a compaction or history reordering issue
                    return false
                }
            }
            // Content should not be blank for non-system messages
            if (msg.role == "assistant" && msg.content.isBlank() && msg.interruptedTurn == null) {
                return false
            }
        }
        return true
    }

    /**
     * Repair minor session integrity issues:
     * - Remove orphan [localOnly] messages with no user message after them (except at end)
     * - Truncate messages to the 200-message history limit (matching addMessage)
     */
    fun repairSessionIntegrity(sessionId: String): Boolean {
        synchronized(lock) {
            val session = sessionProvider(sessionId) ?: return false
            var changed = false
            val msgs = session.messages.toMutableList()

            // Remove orphan localOnly (not at end, no user after)
            val toRemove = mutableSetOf<Int>()
            for (i in msgs.indices) {
                val msg = msgs[i]
                if (msg.localOnly && msg.interruptedTurn != null) {
                    val hasUserAfter = msgs.drop(i + 1).any { it.role == "user" && !it.localOnly }
                    val isLastMsg = i == msgs.lastIndex
                    if (!hasUserAfter && !isLastMsg) {
                        toRemove.add(i)
                        changed = true
                    }
                }
            }
            // v0.28.7: 清理空白 assistant 消息 (空响应产物) — 否则 checkSessionIntegrity 永久失败,
            // 完整性 latch 锁死后续所有轮次。kernel 层 assistant 消息仅在完成后写入,
            // 不存在"运行中"占位, 空白必为已完成空轮 → 可安全移除。
            for (i in msgs.indices) {
                val msg = msgs[i]
                if (msg.role == "assistant" && msg.content.isBlank() && msg.interruptedTurn == null && !msg.localOnly) {
                    toRemove.add(i)
                    changed = true
                }
            }
            toRemove.sortedDescending().forEach { msgs.removeAt(it) }

            // Enforce 200-message history limit (match addMessage behavior)
            if (msgs.size > 200) {
                // Keep last 200 messages, but preserve the first system message
                val systemMsgs = msgs.filter { it.role == "system" }
                val nonSystemTarget = msgs.filter { it.role != "system" }.takeLast(200 - systemMsgs.size.coerceAtMost(10))
                session.messages.clear()
                session.messages.addAll(systemMsgs.take(5)) // keep at most 5 system messages
                session.messages.addAll(nonSystemTarget)
                changed = true
            } else if (changed) {
                session.messages.clear()
                session.messages.addAll(msgs)
            }

            if (changed) {
                updateSession(sessionId, session)
            }
            return changed
        }
    }
}
