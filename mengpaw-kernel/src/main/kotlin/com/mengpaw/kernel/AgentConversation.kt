// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.session.*

/**
 * Agent 对话构建 + 完整性门闩 — 拆自 AgentRuntime (400 行文件拆分)。
 * buildConversation (LLM 调用消息列表: 完整性门禁/恢复块注入/省察引导/缓存注解),
 * checkIntegrity/repairIntegrity (terminal latch), 以及循环共享的进化引导状态。
 */
internal class AgentConversation(private val engine: AgentEngine) {

    // ── Evolution (Agent 进化系统) ─────────────────────────────────
    /** 待注入的金字塔省察引导片段 (失败后生成, 下次 LLM 调用消费). */
    @Volatile internal var pendingGuideFragment: String? = null
    /** 本会话已注入引导次数 (限流, 防刷屏). */
    internal var guideInjections = 0

    // ── Integrity terminal latch (matching OpenClaw terminal latch pattern) ──
    // Once tripped, blocks further LLM calls until the session is repaired.
    @Volatile internal var integrityFailed: Boolean = false

    /**
     * 后台预压缩作用域 (v0.28.6) — 独立于 runningJob, 随引擎生灭。
     * 刻意不在 stop() 取消: submitTask 每轮先 stop, 取消会杀死在途压缩
     * (浪费一次 LLM 调用 + 历史永远压不下去)。
     */
    private val compressionScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    /** Check integrity of the current session. Returns false if terminal latch is active. */
    internal fun checkIntegrity(sessionId: String? = null): Boolean {
        if (integrityFailed) return false
        val sid = sessionId ?: engine.conversationSessionId ?: return true
        if (!engine.getSessionManager().checkSessionIntegrity(sid)) {
            // v0.28.7 自动修复(幂等): 清理空白 assistant 消息等可修项 → 重查。
            // 修好则放行不锁死 — 空响应触发的 latch 不应阻塞后续轮次。
            if (engine.getSessionManager().repairSessionIntegrity(sid) && engine.getSessionManager().checkSessionIntegrity(sid)) {
                KernelLog.w("AgentEngine", "Integrity auto-repaired for session $sid — latch not engaged")
                return true
            }
            integrityFailed = true
            KernelLog.w("AgentEngine", "Integrity check failed for session $sid — terminal latch engaged")
            return false
        }
        return true
    }

    /** Attempt to repair integrity. Returns true if repair succeeded and latch is released. */
    internal fun repairIntegrity(sessionId: String? = null): Boolean {
        val sid = sessionId ?: engine.conversationSessionId ?: return false
        if (engine.getSessionManager().repairSessionIntegrity(sid)) {
            // Re-check after repair
            if (engine.getSessionManager().checkSessionIntegrity(sid)) {
                integrityFailed = false
                KernelLog.i("AgentEngine", "Integrity repaired for session $sid — latch released")
                return true
            }
        }
        return false
    }

    /**
     * Extract completed tool summaries from a session's history.
     * Scans the most recent assistant messages for Command: patterns.
     */
    internal fun extractCompletedToolSummaries(sessionId: String): List<InterruptedToolSummary> {
        val msgs = engine.getSessionManager().getHistory(sessionId)
        val summaries = mutableListOf<InterruptedToolSummary>()
        for (msg in msgs.reversed()) {
            if (msg.localOnly) continue  // skip recovery metadata
            if (msg.role == "user") break // stop at user boundary
            if (msg.content.startsWith("Command:")) {
                val summary = extractToolSummary(msg.content)
                if (summary != null) summaries.add(summary)
            }
        }
        return summaries.takeLast(10) // keep only recent tools
    }

    /**
     * Build the conversation message list for an LLM call.
     * Includes integrity gate, recovery block injection, and cache annotations.
     */
    internal suspend fun buildConversation(sessionId: String): List<Map<String, String>> {
        KernelLog.d("MengPawLatency", "BC-ENTER $sessionId msgs=${engine.getSessionManager().getSession(sessionId)?.messages?.size}")
        // ★ Integrity gate: terminal latch (matching OpenClaw assertSqliteIntegrity)
        // If session data is corrupted, block LLM calls with a warning instead of
        // letting the model act on potentially garbage history.
        if (integrityFailed) {
            KernelLog.w("AgentEngine", "Integrity latch active — blocking LLM call")
            return listOf(mapOf("role" to "system", "content" to "Session data integrity issue detected. " +
                "Please use agent.repair or start a new conversation to continue."))
        }
        // v0.28.6: 后台预压缩 (≥42 提前压, 不在请求前同步插 LLM 调用) + 同步兜底
        engine.getSessionManager().scheduleCompressionIfNeeded(sessionId, compressionScope, engine.getLlmProvider())
        engine.getSessionManager().awaitCompressionIfNeeded(engine.getLlmProvider(), sessionId = sessionId)
        val history = engine.getSessionManager().getStructuredHistory(sessionId)
        val nonSystemHistory = if (history.isNotEmpty() && history[0]["role"] == "system") history.drop(1) else history

        // ★ Recovery block injection: if there's a pending interrupted turn from a prior
        // failed LLM call, inject the structured recovery block before the last user message.
        // Matching Reasonix [withInterruptedRecovery] in interrupted_recovery.go.
        val rawMessages = engine.getSessionManager().getSession(sessionId)?.messages ?: emptyList()
        val pendingRecovery = com.mengpaw.kernel.session.findPendingRecovery(rawMessages)
        if (pendingRecovery != null) {
            val block = com.mengpaw.kernel.session.buildInterruptedRecoveryBlock(pendingRecovery)
            // Prepend recovery block to the last user message
            val mutableMessages = nonSystemHistory.toMutableList()
            val lastUserIdx = mutableMessages.indexOfLast { it["role"] == "user" }
            if (lastUserIdx >= 0) {
                val lastUser = mutableMessages[lastUserIdx]
                // v0.32.1+: lastUser + 覆盖 content — 重建 map 会丢 _image/_audio_data 键,
                // 最后一条附件的多模态通道在恢复轮将整体丢失
                mutableMessages[lastUserIdx] = lastUser + ("content" to "$block\n\n${lastUser["content"]}")
            }
            // Consume the recovery after injection so it doesn't fire again
            engine.getSessionManager().consumePendingRecovery(sessionId)
            // Emit recovery event (matching OpenClaw session_state_notices pattern)
            engine.getSessionManager().recordSessionEvent(sessionId, SessionEventBus.SessionEvent(
                kind = SessionEventBus.EventKind.SESSION_RECOVERED,
                sessionId = sessionId,
                agentName = engine.agentName,
                summary = "Recovery block injected: ${pendingRecovery.completedTools.size} tools completed"
            ))
            KernelLog.d("MengPawLatency", "BC-EXIT $sessionId recovery")
            return engine.llmRequestBuilder.buildMessages(
                listOf(mapOf("role" to "system", "content" to engine.llmRequestBuilder.currentSystemPrompt)) +
                    mutableMessages,
                injectCacheAnnotations = true
            )
        }

        // ── Evolution 省察引导注入: 金字塔提问片段 (限流 MAX_INJECTIONS/会话) ──
        // 追加到对话末尾而非 add(0) 前插 — 前插会使后续所有消息位移, 击穿整个
        // 前缀缓存 (prompt caching 按字节前缀命中); 末尾追加只增不改, 缓存前缀不受扰动,
        // 且"最新指令"语义更强（紧贴当前轮次）。
        // 只对主会话注入 — 并行 worker（mission/swarm 零待命会话）不消费主循环遗留的
        // 省察引导（防注入错目标会话）。
        val guide = if (engine.getSessionManager().getSession(sessionId)?.scope in AgentEngine.WORKER_SCOPES) null
        else pendingGuideFragment
        if (guide != null && guideInjections < com.mengpaw.kernel.evolution.EvolutionGuide.MAX_INJECTIONS) {
            guideInjections++
            pendingGuideFragment = null
            val mutable = nonSystemHistory.toMutableList()
            mutable.add(mapOf("role" to "system", "content" to guide))
            KernelLog.d("MengPawLatency", "BC-EXIT $sessionId guide")
            return engine.llmRequestBuilder.buildMessages(mutable, injectCacheAnnotations = true)
        }

        KernelLog.d("MengPawLatency", "BC-EXIT $sessionId normal")
        return engine.llmRequestBuilder.buildMessages(nonSystemHistory, injectCacheAnnotations = true)
    }
}
