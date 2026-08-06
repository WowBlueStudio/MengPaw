// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.error.ErrorType
import com.mengpaw.kernel.llm.*
import com.mengpaw.kernel.security.Sanitizer
import com.mengpaw.kernel.session.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

/**
 * ReAct 主循环 — 拆自 AgentRuntime (400 行文件拆分)。
 * 全部可变状态仍由 AgentEngine/AgentRuntime/AgentConversation 持有,
 * 本类只搬移循环方法体, 逻辑零改动。
 */
internal class AgentReActLoop(
    private val engine: AgentEngine,
    private val runtime: AgentRuntime,
    private val conversation: AgentConversation
) {

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
        attachments: List<AttachmentData> = emptyList()
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
        // /data/user/0/.../files (BASE), 与 agent.read/ls 的工作区基准是两套路径体系。
        val context = ExecutionContext(
            sessionId = session.id, agentName = engine.agentName,
            workDir = "${com.mengpaw.kernel.DataPaths.AGENTS}/${engine.agentName}"
        )

        // ★ Integrity check after session creation — terminal latch blocks corrupt sessions
        if (!conversation.checkIntegrity(session.id)) {
            val errorMsg = localizedError("session_corrupted", session.id, engine.agentLanguage)
            engine.getSessionManager().addMessage(session.id, Message("system", errorMsg))
            engine._state.value = AgentState.Error(errorMsg)
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
            var consecutiveFailures = 0       // Tracks consecutive tool failures
            var emptyResponseCount = 0        // Tracks empty LLM responses (retry once, then error)
            val originalMaxSteps = maxSteps
            var effectiveMax = maxSteps
            var step = 0
            var extended = false

            while (step < effectiveMax) {
                engine.runningJob?.let { if (!it.isActive) throw kotlinx.coroutines.CancellationException("Agent stopped") }
                engine._state.value = AgentState.Running(task, step + 1, effectiveMax)

                // ── Adaptive step extension ──
                // If agent is still making productive progress near the limit, auto-extend
                if (!extended && step >= effectiveMax * 0.75 && consecutiveFailures == 0) {
                    val extendTo = minOf((effectiveMax * 1.5).toInt(), originalMaxSteps * 2)
                    if (extendTo > effectiveMax) {
                        effectiveMax = extendTo
                        extended = true
                    }
                }

                val conversationMsgs = conversation.buildConversation(session.id)
                // 流式调用: 增量 token 经 onDelta 实时透传 UI(打字机效果); 完整文本仍用于解析
                val llmResponse = if (onDelta != null)
                    engine.getLlmProvider().completeStreamingWithMessages(conversationMsgs, onDelta)
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
                        return errorMsg
                    }
                    KernelLog.w("AgentEngine", "Empty LLM response at step $step — retrying once")
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

                val postResult = engine.postCallMiddleware.onPostCall(sanitized, step + 1, totalChars, estimatedTokens)
                engine.getSessionManager().addMessage(session.id, Message("assistant", postResult.text))
                engine._output.value = postResult.text

                if (postResult.shouldFold) {
                    engine.scrollContext?.evictSpan(
                        seqLo = maxOf(0, step - 10), seqHi = step,
                        text = postResult.text.take(6000),
                        headline = postResult.foldReason ?: "Step ${step + 1} context eviction")
                    runtime.maybeFoldContext(session.id, estimatedTokens, step + 1)
                }

                val parsed = engine.getPromptEngine().parse(sanitized)

                if (parsed.isFinal) {
                    val answer = parsed.thought
                    engine.getSessionManager().addMessage(session.id, Message("assistant", answer))
                    // No boundary message — the conversation continues naturally.
                    // The LLM sees full history: previous FinalAnswer + new user message = context.
                    engine.getSessionManager().recordSessionEvent(session.id, SessionEventBus.SessionEvent(
                        kind = SessionEventBus.EventKind.RUN_COMPLETED,
                        sessionId = session.id,
                        agentName = engine.agentName,
                        summary = "Run completed at step ${step + 1}",
                        payload = mapOf("steps" to (step + 1).toString())
                    ))
                    engine._state.value = AgentState.Finished(answer)
                    com.mengpaw.kernel.agent.AgentDocs.flushMidTermMemoryQueue()
                    runtime.recordTaskMemory(task, answer)
                    // Periodic cleanup of old tool result cache files
                    if (java.lang.Math.random() < 0.1) engine.toolResultManager.cleanupOldToolResults()
                    return answer
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
                        return msg
                    }
                    val continuePrompt = "继续。输出 Action: <命令> 和 Action Input: <参数>。"
                    engine.getSessionManager().addMessage(session.id, Message("user", continuePrompt))
                    continue
                }
                consecutiveContinueCount = 0 // Reset on successful action

                // ── 单次 LLM 输出可含多个 Action — 并行执行后合并 Observation ──
                // 并发纪律照搬 SwarmModeExecutor.runWorker: 共享可变状态
                // (detectLoop/trackResult/consecutiveFailures/ErrorCollector) 只在主协程串行更新
                // 同批去重: 相同命令(名称+参数)只执行一次 — 模型偶发重复输出同一 Action
                val actionList = (parsed.actions.ifEmpty { listOfNotNull(parsed.action) })
                    .distinctBy { "${it.name} ${it.parameters.values.joinToString(" ")}" }

                if (actionList.isNotEmpty()) {
                    // ── 组装命令行 + 参数格式门卫 (PARAM_FORMAT_ERROR) ──
                    // JSON 双轨制防护见 ToolCall.paramFormatError() — 命中即不执行, 直接返回格式错误。
                    val formattedCalls = actionList.map { call ->
                        Triple("${call.name} ${call.parameters.values.joinToString(" ")}", call.paramFormatError(), call)
                    }
                    val commandLines = formattedCalls.map { it.first }

                    // Loop detection on the first command (kept serial — shared mutable state)
                    if (engine.getPromptEngine().detectLoop(commandLines.first())) {
                        val cmd = commandLines.first()
                        ErrorCollector.report(ErrorType.LOOP_DETECTED, "AgentEngine", cmd,
                            sessionId = session.id, agentName = engine.agentName)
                        val errorMsg = localizedError("loop_detected", cmd, engine.agentLanguage)
                        engine.getSessionManager().addMessage(session.id, Message("assistant", errorMsg))
                        engine._state.value = AgentState.Error(errorMsg)
                        onStep?.invoke(AgentEngine.TraceStep(step + 1, parsed.thought, cmd, errorMsg))
                        return errorMsg
                    }

                    // ── 并行执行（结构化并发: async 内 withTimeout + pipeline.execute）──
                    val results = coroutineScope {
                        formattedCalls.map { (cmd, formatError, _) ->
                            async(KernelDispatchers.BACKGROUND) {
                                try {
                                    if (formatError != null) {
                                        ExecutionResult.fail(formatError, errorCode = ErrorCodes.PARAM_FORMAT_ERROR)
                                    } else {
                                        withTimeout(60_000L) { engine.getPipelineManager().buildPipeline().execute(cmd, context) }
                                    }
                                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                                    ExecutionResult.fail("命令超时 (60s): $cmd。请检查网络连接或尝试其他方式。", errorCode = ErrorCodes.ERR_INTERNAL)
                                }
                            }
                        }.awaitAll()
                    }
                    // 命令批次执行完毕 → 通知监听器 (UI 实时刷新 Tools/Skills/Plugins 列表)
                    runtime.notifyCommandExecuted()

                    // ── 合并后串行更新共享可变状态 + 组装 Observation ──
                    val observationEntries = mutableListOf<String>()
                    var anyFailure = false
                    results.forEachIndexed { i, result ->
                        val commandLine = commandLines[i]
                        if (!result.success) {
                            anyFailure = true
                            ErrorCollector.report(ErrorType.TOOL_CALL_FAILED, "AgentEngine",
                                "$commandLine → ${result.error}", sessionId = session.id, agentName = engine.agentName,
                                metadata = mapOf("errorCode" to (result.errorCode ?: ""), "command" to commandLine))
                            // 进化省察: 生成金字塔引导片段, 下次 LLM 调用注入 (轻/深分级)
                            conversation.pendingGuideFragment = com.mengpaw.kernel.evolution.EvolutionGuide.buildFragment(
                                agentName = engine.agentName, command = commandLine, message = result.error ?: "")
                        }
                        // errorCode 注入 Observation — 模型可见错误类型 (PARAM_FORMAT_ERROR/NETWORK_OFFLINE/...)
                        var rawObservation = if (result.success) {
                            result.output
                        } else {
                            result.errorCode?.let { "Error [$it]: ${result.error}" } ?: "Error: ${result.error}"
                        }
                        // ── QwenPaw-style tool result pruning ──
                        rawObservation = engine.toolResultManager.pruneToolResult(commandLine, rawObservation, step + 1)
                        // 多 Action 并行: 思考只在第一个 Action 上呈现, 后续 Action 复用同一步序号
                        // (UI 对空 thought 渲染成纯工具行, 避免 N 条相同思考重复)
                        onStep?.invoke(AgentEngine.TraceStep(step + 1, if (i == 0) parsed.thought else "", commandLine, rawObservation))
                        observationEntries.add("Command: $commandLine\nResult: $rawObservation")
                    }
                    // 连续失败统计与失败循环检测（串行，无竞争）
                    if (anyFailure) consecutiveFailures++ else consecutiveFailures = 0
                    if (engine.getPromptEngine().trackResult(!anyFailure)) {
                        val errorMsg = localizedError("consecutive_failures", "5", engine.agentLanguage)
                        engine.getSessionManager().addMessage(session.id, Message("assistant", errorMsg))
                        engine._state.value = AgentState.Error(errorMsg)
                        onStep?.invoke(AgentEngine.TraceStep(step + 1, parsed.thought, commandLines.first(), errorMsg))
                        return errorMsg
                    }
                    // 合并为一条 assistant 消息（多 Action 的多个 Observation）
                    engine.getSessionManager().addMessage(session.id, Message("assistant", observationEntries.joinToString("\n\n")))
                } else {
                    onStep?.invoke(AgentEngine.TraceStep(step + 1, parsed.thought, null, null))
                }
                step++
                // ── Checkpoint: persist progress every 5 steps ──
                if (step > 0 && step % 5 == 0) {
                    engine.checkpointManager.save(Checkpoint(
                        sessionId = session.id,
                        step = step,
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
                payload = mapOf("steps" to step.toString(), "max" to effectiveMax.toString())
            ))
            engine._state.value = AgentState.Finished(msg)
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
            return errorMsg
        }
    }
}
