// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.error.ErrorType
import com.mengpaw.kernel.security.Sanitizer
import com.mengpaw.kernel.session.*
import kotlinx.coroutines.Job

/**
 * ReAct 主循环骨架 — 拆自 AgentRuntime (400 行文件拆分)。
 * v0.40.4 P2 再拆 (400 行红线): 单步处理 (最终答案门禁/动作批执行) 移至
 * AgentReActStepProcessor, 工具执行移至 AgentToolRunner, 终止进化记录移至
 * AgentTerminationRecorder — 本文件只保留会话装配 + 循环骨架 + 终止路径。
 * 全部可变状态仍由 AgentEngine/AgentRuntime/AgentConversation 持有。
 */
internal class AgentReActLoop(
    private val engine: AgentEngine,
    private val runtime: AgentRuntime,
    private val conversation: AgentConversation
) {

    private val termination = AgentTerminationRecorder(engine)

    /**
     * Internal ReAct loop with optional context prefix.
     * Shared by run() and runWithGoal() to avoid session-creation overhead.
     */
    internal suspend fun runReActLoop(
        task: String,
        maxSteps: Int,
        contextPrefix: String = "",
        onStep: ((AgentEngine.TraceStep) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null,
        attachments: List<AttachmentData> = emptyList(),
        onReasoning: ((String) -> Unit)? = null
    ): String {
        ErrorCollector.init()

        // ── Evolution: 钩子归系统 + 绩效反馈注入 ──
        com.mengpaw.kernel.evolution.EvolutionHook.install()
        conversation.guideInjections = 0
        conversation.pendingGuideFragment = com.mengpaw.kernel.evolution.EvolutionGuide.buildSessionBrief(engine.agentName)

        // ── Persistent conversation (Claude Code pattern) ──
        // Reuse existing session across multiple user messages so the
        // LLM sees full conversation history, not just the current message.
        val session: Session
        // Snapshot volatile field to avoid TOCTOU race with newConversation()
        val currentSessionId = engine.conversationSessionId
        if (currentSessionId != null) {
            val existing = engine.getSessionManager().getSession(currentSessionId)
            if (existing != null) {
                session = existing
            } else {
                // Session lost (e.g., process restart) — create new
                session = engine.getSessionManager().createSession(task)
                engine.conversationSessionId = session.id
            }
        } else {
            session = engine.getSessionManager().createSession(task)
            engine.conversationSessionId = session.id
        }
        engine.getSessionManager().agentName = engine.agentName
        // FIX(自检报告 P0-2): workDir 指向 Agent 工作区而非 BASE — 此前 self.status 显示
        // /data/user/0/.../files (BASE), 与 Linux 通道 cwd 的工作区基准是两套路径体系。
        val context = ExecutionContext(
            sessionId = session.id, agentName = engine.agentName,
            workDir = "${com.mengpaw.kernel.DataPaths.AGENTS}/${engine.agentName}"
        )

        // ★ Integrity check after session creation — terminal latch blocks corrupt sessions
        if (!conversation.checkIntegrity(session.id)) {
            val errorMsg = localizedError("session_corrupted", session.id, engine.agentLanguage)
            engine.getSessionManager().addMessage(session.id, Message("system", errorMsg))
            engine._state.value = AgentState.Error(errorMsg)
            // 进化介入 (2026-08-08): 完整性失败也是负面事件 — 记录截断上下文
            termination.record(session.id, "session_corrupted", "", "SESSION_INTEGRITY", task)
            return errorMsg
        }

        engine._state.value = AgentState.Running(task, 0, maxSteps)
        engine._output.value = ""

        // Append user message to existing conversation history (Claude Code pattern)
        // 结构化附件 (v0.33.0+): 由 getStructuredHistory 挂 _image/_audio_data 二进制键
        engine.getSessionManager().addMessage(session.id, Message("user", task, attachments = attachments))
        if (contextPrefix.isNotBlank()) {
            engine.getSessionManager().addMessage(session.id, Message("system", contextPrefix))
        }

        try {
            val job = kotlinx.coroutines.currentCoroutineContext()[Job]
            engine.runningJob = job
            var consecutiveContinueCount = 0 // Tracks needsContinue without action
            var emptyResponseCount = 0        // Tracks empty LLM responses (retry once, then error)
            val originalMaxSteps = maxSteps
            var effectiveMax = maxSteps
            var extended = false
            // 单轮共享可变状态 (v0.40.4 P2 拆分): 处理器与主循环共同读写, 同协程串行无竞争
            val state = AgentReActStepProcessor.ReActStepState(
                session = session,
                task = task,
                step = 0,
                consecutiveFailures = 0,
                probeMisses = 0,
                hallucinationRejections = 0,
                sessionFailures = mutableListOf(),
                retryCounts = mutableMapOf(),
                retryNotified = mutableSetOf()
            )
            val processor = AgentReActStepProcessor(engine, runtime, conversation, termination)

            while (state.step < effectiveMax) {
                engine.runningJob?.let { if (!it.isActive) throw kotlinx.coroutines.CancellationException("Agent stopped") }
                engine._state.value = AgentState.Running(task, state.step + 1, effectiveMax)

                // ── Adaptive step extension ──
                // If agent is still making productive progress near the limit, auto-extend.
                // P1-4: 幻觉门禁拒绝 (hallucinationRejections > 0) 时禁止扩展 — 模型在顽固
                // 输出含幻觉 Final Answer, 拒绝只消耗步数且不记失败, 原条件 (仅看
                // consecutiveFailures) 会被扩展放大成本/时长; 加入该门后最多烧到
                // effectiveMax (=originalMaxSteps), 不再 1.5× 放大。
                if (!extended && state.step >= effectiveMax * 0.75 &&
                    state.consecutiveFailures == 0 && state.hallucinationRejections == 0
                ) {
                    val extendTo = minOf((effectiveMax * 1.5).toInt(), originalMaxSteps * 2)
                    if (extendTo > effectiveMax) {
                        effectiveMax = extendTo
                        extended = true
                    }
                }

                val conversationMsgs = conversation.buildConversation(session.id)
                // 流式调用: 增量 token 经 onDelta 实时透传 UI(打字机效果); 完整文本仍用于解析
                val llmResponse = if (onDelta != null)
                    engine.getLlmProvider().completeStreamingWithMessages(conversationMsgs, onDelta, onReasoning)
                else engine.getLlmProvider().completeWithMessages(conversationMsgs)
                // 利用 LLM 等待窗口刚刚结束的间隙刷盘中期记忆 (I/O 成本隐藏)
                com.mengpaw.kernel.agent.AgentDocs.flushMidTermMemoryQueue()
                val sanitized = Sanitizer.sanitize(llmResponse)

                // ── 空响应防御 (v0.28.7): DeepSeek 偶发空流 (SSE 零增量, S-DONE len=0) ──
                // 根因链: 空响应 → 空白 assistant 消息入库 → checkSessionIntegrity 失败 →
                // 完整性 terminal latch 锁死该会话后续所有轮次 ("会话数据完整性检查失败")。
                // 修复: 空响应不入库空白消息, 重试一次 (step 不递增); 仍空则写明确错误并终止。
                if (sanitized.isBlank()) {
                    emptyResponseCount++
                    if (emptyResponseCount >= 2) {
                        val errorMsg = localizedError("empty_response", "", engine.agentLanguage)
                        engine.getSessionManager().addMessage(session.id, Message("assistant", errorMsg))
                        engine.getSessionManager().recordSessionEvent(session.id, SessionEventBus.SessionEvent(
                            kind = SessionEventBus.EventKind.LLM_CALL_ERROR,
                            sessionId = session.id,
                            agentName = engine.agentName,
                            summary = "Empty LLM response after retry",
                            payload = mapOf("error" to "empty_response", "consecutive" to "true")
                        ))
                        engine._state.value = AgentState.Error(errorMsg)
                        // 进化介入 (2026-08-08): 模型层失败 (连续空响应) — 记录上下文
                        termination.record(session.id, "empty_response", "", "LLM_EMPTY_RESPONSE", task)
                        return errorMsg
                    }
                    KernelLog.w("AgentEngine", "Empty LLM response at step ${state.step} — retrying once")
                    continue
                }
                emptyResponseCount = 0

                // v0.32.1+: 轻量字符统计 — 不再调 getStructuredHistory (该函数会对最近附件
                // 做 base64, 此处仅需 content 长度校准 tok/char, 白做编码纯浪费)
                val historyChars = engine.getSessionManager().getSession(session.id)?.messages
                    ?.filter { !it.localOnly }?.sumOf { it.content.length } ?: 0
                val totalChars = engine.llmRequestBuilder.currentSystemPrompt.length + historyChars
                val estimatedTokens = (totalChars * engine.llmRequestBuilder.calibratedTokPerChar).toInt()
                engine.llmRequestBuilder.calibrateFromUsage(estimatedTokens, totalChars)

                val postResult = engine.postCallMiddleware.onPostCall(sanitized, state.step + 1, totalChars, estimatedTokens)
                // DeepSeek 思考模式回传 (v0.41.1 未发布): 本轮思维链随 assistant 消息落历史,
                // 下一轮 buildConversation 原样回传 — 官方要求工具调用轮次必须回传,
                // 否则 API 400 ("The reasoning_content in the thinking mode must be
                // passed back to the API"), 导致多轮任务后段中断/混乱。
                engine.getSessionManager().addMessage(
                    session.id,
                    Message("assistant", postResult.text, reasoning = engine.getLlmProvider().lastReasoning)
                )
                engine._output.value = postResult.text

                if (postResult.shouldFold) {
                    engine.scrollContext?.evictSpan(
                        seqLo = maxOf(0, state.step - 10), seqHi = state.step,
                        text = postResult.text.take(6000),
                        headline = postResult.foldReason ?: "Step ${state.step + 1} context eviction")
                    runtime.maybeFoldContext(session.id, estimatedTokens, state.step + 1)
                }

                val parsed = engine.getPromptEngine().parse(sanitized)

                // 最终答案轮: 门禁 + 收尾交给处理器 (v0.40.4 P2 拆分)
                if (parsed.isFinal) {
                    when (val outcome = processor.processFinalAnswer(state, parsed.thought)) {
                        is AgentReActStepProcessor.ReActTurnResult.Finish -> return outcome.text
                        AgentReActStepProcessor.ReActTurnResult.Continue -> continue
                    }
                }

                // Handle needsContinue: model output Thought but no Action
                // Inject a continue prompt instead of stopping
                if (parsed.needsContinue) {
                    consecutiveContinueCount++
                    if (consecutiveContinueCount >= 2) {
                        // Model keeps thinking without acting — force finalize
                        val msg = localizedError("max_steps", maxSteps.toString(), engine.agentLanguage)
                        engine.getSessionManager().addMessage(session.id, Message("assistant", msg))
                        engine._state.value = AgentState.Finished(msg)
                        // 进化介入 (2026-08-08): 只思考不行动 = 完成度低 — 记录截断上下文
                        termination.record(session.id, "incomplete_action", "", "NO_ACTION", task)
                        return msg
                    }
                    val continuePrompt = "继续。输出 Action: <命令> 和 Action Input: <参数>。"
                    engine.getSessionManager().addMessage(session.id, Message("user", continuePrompt))
                    continue
                }
                consecutiveContinueCount = 0 // Reset on successful action

                // ── 单次 LLM 输出可含多个 Action — 并行执行后合并 Observation ──
                // 同批去重: 相同命令(名称+参数)只执行一次 — 模型偶发重复输出同一 Action
                val actionList = parsed.actions.ifEmpty { listOfNotNull(parsed.action) }
                val terminateMsg = processor.executeActions(state, parsed, actionList, context, onStep)
                if (terminateMsg != null) return terminateMsg

                state.step++
                // ── Checkpoint: persist progress every 5 steps ──
                if (state.step > 0 && state.step % 5 == 0) {
                    engine.checkpointManager.save(Checkpoint(
                        sessionId = session.id,
                        step = state.step,
                        remainingTask = task,
                        context = mapOf("agentName" to engine.agentName, "modelName" to engine.modelName)
                    ))
                    engine.checkpointManager.cleanup(session.id, keep = 3)
                }
            }

            val msg = localizedError("max_steps", maxSteps.toString(), engine.agentLanguage)
            engine.getSessionManager().addMessage(session.id, Message("assistant", msg))
            engine.getSessionManager().recordSessionEvent(session.id, SessionEventBus.SessionEvent(
                kind = SessionEventBus.EventKind.RUN_COMPLETED,
                sessionId = session.id,
                agentName = engine.agentName,
                summary = "Max steps ($effectiveMax) reached",
                payload = mapOf("steps" to state.step.toString(), "max" to effectiveMax.toString())
            ))
            engine._state.value = AgentState.Finished(msg)
            // 失败截断进化介入: 步数上限终止 — 若本轮有失败, 关联最近失败; 否则记纯 max_steps 模式
            val lastFailure = state.sessionFailures.lastOrNull()
            termination.record(
                session.id, "max_steps",
                lastFailure?.first ?: "",
                lastFailure?.second ?: "MAX_STEPS",
                task)
            return msg
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消传播契约: 必须先 rethrow (P1 已修, 禁止吞掉 CancellationException)。
            // P2: 外部作用域取消(非 stop())时 _state 残留 Running — 若本 job 仍是
            // 当前 runningJob 则复位 Idle; stop() 已复位或新 run 已挂新 job 时不覆盖。
            val thisJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            if (engine.runningJob === thisJob) {
                engine._state.value = AgentState.Idle
                engine.runningJob = null
            }
            throw e
        } catch (e: Exception) {
            // ★ Record completed tools as interrupted turn recovery (Reasonix Level 2)
            val completedTools = conversation.extractCompletedToolSummaries(session.id)
            engine.getSessionManager().recordInterruptedTurn(
                sessionId = session.id,
                completedTools = completedTools,
                interruptedTools = emptyList(),
                hasPartialText = false,
                hasPartialReasoning = false
            )

            // ★ Emit lifecycle events (matching OpenClaw session-state-events.ts)
            engine.getSessionManager().recordSessionEvent(session.id, SessionEventBus.SessionEvent(
                kind = SessionEventBus.EventKind.LLM_CALL_ERROR,
                sessionId = session.id,
                agentName = engine.agentName,
                summary = e.message?.take(120) ?: "Unknown error",
                payload = mapOf("error" to (e.message?.take(200) ?: ""), "consecutive" to "true")
            ))
            engine.getSessionManager().recordSessionEvent(session.id, SessionEventBus.SessionEvent(
                kind = SessionEventBus.EventKind.RUN_INTERRUPTED,
                sessionId = session.id,
                agentName = engine.agentName,
                summary = "Run interrupted after error: ${e.message?.take(80) ?: "unknown"}"
            ))

            ErrorCollector.report(ErrorType.AGENT_CRASH, "AgentEngine.runReActLoop", e.message ?: "(no message)",
                throwable = e, sessionId = session.id, agentName = engine.agentName)
            val errorMsg = localizedError("agent_error", e.message ?: e::class.simpleName ?: "unknown", engine.agentLanguage)
            engine.getSessionManager().addMessage(session.id, Message("assistant", errorMsg))
            engine._state.value = AgentState.Error(errorMsg)
            // 失败截断进化介入: 异常中断 — 剪取崩溃前上下文片段
            termination.record(session.id, "interrupted", "", "AGENT_CRASH", task)
            return errorMsg
        }
    }
}
