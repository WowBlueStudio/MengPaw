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
     *
     * @param resume 断点续跑 (P0-1/P0-2): true 时不再新建会话, 改为按检查点重建
     *   ([CheckpointManager.loadLatest] → 仅 RUNNING 可续), 用 `checkpoint.messages`
     *   复原引擎历史 (工具 Observation 不再丢失), 步号从 `checkpoint.step` 接续,
     *   且**不重复追加用户任务** (messages 已含)。非 resume 路径不读旧检查点、步号从 0
     *   开始 —— 但两条路径都**每步**写 RUNNING 检查点 (见 [AgentCheckpointWriter])。
     */
    internal suspend fun runReActLoop(
        task: String,
        maxSteps: Int,
        contextPrefix: String = "",
        onStep: ((AgentEngine.TraceStep) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null,
        attachments: List<AttachmentData> = emptyList(),
        onReasoning: ((String) -> Unit)? = null,
        resume: Boolean = false
    ): String {
        ErrorCollector.init()

        // ── Evolution: 钩子归系统 (失败捕获入队) ──
        // v0.44 (静默分支进化): 会话开局绩效提醒 buildSessionBrief 已移出主会话,
        // 不再注入主对话; 进化由失败/纠正入队后分支会话静默沉淀。
        com.mengpaw.kernel.evolution.EvolutionHook.install()

        // 检查点写入器 (每步 RUNNING + 终态 COMPLETED/FAILED) — 落盘失败不影响主任务
        val checkpointWriter = AgentCheckpointWriter(engine.checkpointManager)
        checkpointWriter.bindContext(engine.agentName, engine.modelName)

        // ── 续跑前置检查 (P0-3): 查检查点 + 取单会话锁 ──
        // 计划为空 = 不满足自动续跑条件 (锁被占/无 RUNNING 检查点/用户关掉开关), 静默回落新任务路径
        val resumeLock = ResumeLock()
        val resumePlan: ResumePlan.AutoResume? =
            if (resume) runtime.planResume(resumeLock, RESUME_EVENTS_LIMIT) else null

        // ── Persistent conversation (Claude Code pattern / 续跑按检查点重建) ──
        // 续跑: 按检查点原 sessionId 重建会话并复原 messages (键一致才能让后续 loadLatest 命中同一份进度);
        // 新任务: 复用既有会话, 不存在才新建 — 语义与改造前逐字一致。
        val (session, restoredStep) = runtime.resolveConversationSession(resumePlan, task, checkpointWriter)
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

        engine._state.value = AgentState.Running(task, restoredStep, maxSteps)
        engine._output.value = ""

        // 续跑不重复追加用户任务 (恢复的 messages 末条已是本任务); 非续跑路径保持原语义
        if (resumePlan == null) {
            engine.getSessionManager().addMessage(session.id, Message("user", task, attachments = attachments))
            if (contextPrefix.isNotBlank()) {
                engine.getSessionManager().addMessage(session.id, Message("system", contextPrefix))
            }
        } else if (contextPrefix.isNotBlank()) {
            engine.getSessionManager().addMessage(session.id, Message("system", contextPrefix))
        }

        // 终态收口容器 — 正常路径在 try 内赋值, finally 统一落终态检查点
        var terminalOutcome: Pair<Boolean, String>? = null
        // 单轮共享可变状态 (v0.40.4 P2 拆分): 处理器与主循环共同读写, 同协程串行无竞争。
        // 声明在 try 之外, 使 finally 的终态检查点能拿到真实步号 (state.step)。
        val state = AgentReActStepProcessor.ReActStepState(
            session = session,
            task = task,
            step = restoredStep,
            consecutiveFailures = 0,
            probeMisses = 0,
            hallucinationRejections = 0,
            sessionFailures = mutableListOf(),
            retryCounts = mutableMapOf(),
            retryNotified = mutableSetOf()
        )
        // 自适应步数上限 (原循环内局部量) — 提到 try 外仅为让 finally 可见步号, 语义不变
        val originalMaxSteps = maxSteps
        var effectiveMax = maxSteps

        try {
            val job = kotlinx.coroutines.currentCoroutineContext()[Job]
            engine.runningJob = job
            var consecutiveContinueCount = 0 // Tracks needsContinue without action
            var emptyResponseCount = 0        // Tracks empty LLM responses (retry once, then error)
            var extended = false
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
                    // v0.46.3 诊断: 区分「仅思维链(正文空)」与「完全空流」— 前者是模型/思考模式行为,
                    // 后者是链路问题, 排查方向完全不同。只记长度计数, 不记内容。
                    val reasoningChars = engine.getLlmProvider().lastReasoning?.length ?: 0
                    val mode = if (reasoningChars > 0) "reasoning_only" else "empty_stream"
                    if (emptyResponseCount >= 2) {
                        val errorMsg = localizedError("empty_response", "", engine.agentLanguage)
                        engine.getSessionManager().addMessage(session.id, Message("assistant", errorMsg))
                        engine.getSessionManager().recordSessionEvent(session.id, SessionEventBus.SessionEvent(
                            kind = SessionEventBus.EventKind.LLM_CALL_ERROR,
                            sessionId = session.id,
                            agentName = engine.agentName,
                            summary = "Empty LLM response after retry ($mode, reasoning=${reasoningChars} chars)",
                            payload = mapOf(
                                "error" to "empty_response",
                                "consecutive" to "true",
                                "mode" to mode,
                                "reasoning_chars" to reasoningChars.toString(),
                                "model" to (engine.getLlmProvider().info().model)
                            )
                        ))
                        KernelLog.w("AgentEngine", "连续空响应终止: mode=$mode reasoningChars=$reasoningChars model=${engine.getLlmProvider().info().model}")
                        engine._state.value = AgentState.Error(errorMsg)
                        // 进化介入 (2026-08-08): 模型层失败 (连续空响应) — 记录上下文
                        termination.record(session.id, "empty_response", "", "LLM_EMPTY_RESPONSE", task)
                        terminalOutcome = false to errorMsg
                        break
                    }
                    KernelLog.w("AgentEngine", "Empty LLM response at step ${state.step} — mode=$mode reasoningChars=$reasoningChars — retrying once")
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
                        is AgentReActStepProcessor.ReActTurnResult.Finish -> {
                            // 终态检查点 (COMPLETED) 由 finally 统一落盘 — 此处只记结论
                            terminalOutcome = true to outcome.text
                            break
                        }
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
                        terminalOutcome = false to msg
                        break
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
                if (terminateMsg != null) {
                    // 循环检测 / 连续失败 / 会话完整性 — 均为失败终态
                    terminalOutcome = false to terminateMsg
                    break
                }

                state.step++
                // ── Checkpoint (P0-1): 每步落 RUNNING ──
                // 时机: 工具批次执行完毕 + Observation 已入库 (executeActions 写 assistant 观察消息)
                // + state.step++ 之后。旧实现"每 5 步写一次"已删除 — 5 步窗口内的崩溃会丢 4 步进度,
                // 且终态不写盘使恢复方无从判断"这轮是断了还是跑完了"。
                checkpointWriter.writeRunning(
                    sessionId = session.id,
                    step = state.step,
                    task = task,
                    messages = engine.getSessionManager().getHistory(session.id)
                )
            }

            // ── 步数耗尽收口 ──
            // 只有"循环自然跑满步数"(terminalOutcome 仍为空)才走这里。
            // 终态分支 (最终答案 / 循环检测 / 只思考不行动 / 空响应) 改用 break 跳出循环,
            // 而 break 会继续执行循环之后的代码 —— 不加这道守卫就会把它们已经拿到的
            // 结论覆盖成 max_steps (断点续跑改造引入的回归, 由既有 19 个用例暴露)。
            if (terminalOutcome == null) {
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
                // 步数耗尽 = 未完成 (即便原因含失败) — 续跑方需要看到"还能接着跑", 故记 FAILED
                terminalOutcome = false to msg
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消传播契约: 必须先 rethrow (P1 已修, 禁止吞掉 CancellationException)。
            // P2: 外部作用域取消(非 stop())时 _state 残留 Running — 若本 job 仍是
            // 当前 runningJob 则复位 Idle; stop() 已 release 或新 run 已挂新 job 时不覆盖。
            val thisJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            if (engine.runningJob === thisJob) {
                engine._state.value = AgentState.Idle
                engine.runningJob = null
            }
            // 用户停止/作用域取消也是"未完成"的一种 — 落 FAILED(cancelled), 否则重启后会误判为已完成
            terminalOutcome = false to CANCELLED_MARK
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
            terminalOutcome = false to errorMsg
        } finally {
            // ── 终态检查点 (P0-1 收口) ──
            // 覆盖全部终止路径: 正常答案 / 退化输出 / 空响应 / 步数耗尽 / 只思考不行动 /
            // 循环检测 / 连续失败 / 用户取消 / 任意异常。cancelledMark 用于把"用户停止"这一
            // 特殊失败 (走 CancellationException) 区别于崩溃/模型错误。
            // 检查点写失败已在 writer 内部 try/catch, 绝不影响返回与取消传播。
            checkpointWriter.finalize(
                sessionId = session.id,
                task = task,
                outcome = terminalOutcome,
                step = state.step,
                messages = engine.getSessionManager().getHistory(session.id),
                cancelledMark = CANCELLED_MARK
            )
            // 续跑结束释放单会话锁 (仅当本次确实持有)
            try {
                resumeLock.release()
            } catch (_: Exception) {
                // 释放失败不影响返回; 锁会在 TTL 过期后自动可抢
            }
        }
        return terminalOutcome?.second ?: ""
    }

    private companion object {
        /** 取消路径的终止原因标记 (与普通错误区分开)。 */
        const val CANCELLED_MARK = "cancelled"

        /** 恢复判定读取的会话事件条数 — 足够覆盖"连续错误 ≥5"判据且不拖慢启动。 */
        const val RESUME_EVENTS_LIMIT = 50
    }
}
