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

    // ── v0.44 静默分支进化 ─────────────────────────────────────────
    // 原 pendingGuideFragment/guideInjections/pendingVeracityFeedback 字段已随
    // "进化省察引导 + 幻觉门禁移出主会话"一并移除 (由分支会话沉淀)。

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

        // ── 注入片段 (只进当轮请求, 不落会话历史) ──
        // v0.44 (静默分支进化): 进化省察引导 / 幻觉静默门禁已移出主会话, 由分支会话沉淀;
        // 此处仅保留对话需求跟踪块注入。
        // 追加到对话末尾而非 add(0) 前插 — 前插会使后续所有消息位移, 击穿整个
        // 前缀缓存 (prompt caching 按字节前缀命中); 末尾追加只增不改, 缓存前缀不受扰动,
        // 且"最新指令"语义更强（紧贴当前轮次）。
        val isWorkerScope = engine.getSessionManager().getSession(sessionId)?.scope in AgentEngine.WORKER_SCOPES
        val injectables = mutableListOf<String>()
        if (!isWorkerScope) {
            // 对话需求跟踪块 (v0.41.1 未发布): 规则式目标清单 — 从会话 user 消息抽取
            // 最近需求, 最新为当前重点、旧需求为待办。只进当轮请求, 不落历史;
            // 追加到末尾保护前缀缓存。
            buildGoalTrackingBlock(
                rawMessages.filter { it.role == "user" && !it.localOnly }.map { it.content }
            )?.let { injectables.add(it) }
        }
        if (injectables.isNotEmpty()) {
            val mutable = nonSystemHistory.toMutableList()
            injectables.forEach { mutable.add(mapOf("role" to "system", "content" to it)) }
            KernelLog.d("MengPawLatency", "BC-EXIT $sessionId inject")
            return engine.llmRequestBuilder.buildMessages(mutable, injectCacheAnnotations = true)
        }

        KernelLog.d("MengPawLatency", "BC-EXIT $sessionId normal")
        return engine.llmRequestBuilder.buildMessages(nonSystemHistory, injectCacheAnnotations = true)
    }
}

/**
 * 对话需求跟踪块构造 (v0.41.1 未发布, 规则式) — 解决"新话题覆盖旧目标 / 旧目标淹没新重点"
 * 的两难: 从会话 user 消息自动抽取最近需求, 最新一条 = 当前重点 (置顶), 之前的 = 待办/背景。
 * 仅提示, 不落会话历史; 由 buildConversation 追加到请求末尾 (保护前缀缓存, 与进化引导同机制)。
 *
 * 过滤: 空消息 / needsContinue 注入的 "继续。输出 Action..." 系统引导 (非用户需求)。
 * 限长: 最多保留最近 [MAX_GOALS] 条, 每条截断, 控制注入 token 成本。
 */
internal fun buildGoalTrackingBlock(userRequests: List<String>, maxGoals: Int = 5): String? {
    val reqs = userRequests
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("继续。输出 Action") }
        .takeLast(maxGoals)
    if (reqs.isEmpty()) return null
    val sb = StringBuilder("## 对话需求跟踪（自动维护，仅参考）\n")
    sb.appendLine("- 当前重点: ${reqs.last().take(80)}")
    reqs.dropLast(1).reversed().forEach { req ->
        sb.appendLine("- 待办/背景: ${req.take(60)}")
    }
    sb.appendLine(
        "规则: 当前重点优先。若当前重点是旧需求的补充/延续, 合并进原目标推进, 不要另起炉灶;" +
            "若用户明确转向新话题, 新话题成为当前重点, 未完成的旧需求保留为待办;" +
            "完成当前重点后用户未转向时, 可主动询问是否继续待办; 已放弃/完成的忽略。"
    )
    return sb.toString()
}
