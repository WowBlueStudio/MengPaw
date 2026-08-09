// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.agent.MissionSubtask
import com.mengpaw.kernel.agent.MissionSwarmPrompts
import com.mengpaw.kernel.agent.SubtaskStatus
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.security.Sanitizer
import com.mengpaw.kernel.session.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Mission-mode executor: decompose -> parallel worker execution -> verification -> synthesis.
 *
 * 子任务并行执行（WIP 闸限流），worker 零待命（独立 session，不入主会话、
 * 不写记忆，用完即销毁）— 修复原串行版子任务污染主会话历史的缺陷。
 * 保留 Mission 特性: 👍 DONE 降级 / ## Mission: 报告头 / hard-error 走 retry。
 */
class MissionModeExecutor(
    private val agentEngine: AgentEngine
) {
    /**
     * Run a complex task as a mission of decomposed, verified subtasks.
     *
     * @param task the complex task description
     * @param maxSubtasks maximum number of subtasks to decompose into
     * @param maxStepsPerSubtask maximum ReAct steps per subtask
     * @param maxRetriesPerSubtask maximum retries on verification failure
     * @param maxParallel maximum concurrent subtask pipelines (WIP gate)
     * @param onStep optional step callback for progress tracking
     * @return the synthesized mission report
     */
    suspend fun runWithMission(
        task: String, maxSubtasks: Int = 5, maxStepsPerSubtask: Int = 12,
        maxRetriesPerSubtask: Int = 2, maxParallel: Int = 4,
        onStep: ((AgentEngine.TraceStep) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null
    ): String {
        val llmProvider = agentEngine.getLlmProvider()
        // P0 注入防护: 任务入口静默剥离精确注入模式
        val guardedTask = com.mengpaw.kernel.security.UntrustedContent.sanitizeForAgent(task)

        // Step 1: Structured decomposition — LLM produces JSON subtask list
        val decomposeResult = try {
            llmProvider.complete(MissionSwarmPrompts.buildDecomposePrompt(guardedTask, maxSubtasks))
        } catch (e: Exception) {
            return agentEngine.run(task, maxStepsPerSubtask * maxSubtasks, onStep, onDelta)
        }

        // Parse subtasks (JSON first, line-parse fallback)
        val specs = MissionSwarmPrompts.parseSubtasks(decomposeResult, maxSubtasks)
        if (specs.isEmpty()) {
            return agentEngine.run(task, maxStepsPerSubtask * maxSubtasks, onStep, onDelta)
        }
        val subtasks = specs.map {
            MissionSubtask(id = it.id, description = it.desc, expectedOutcome = it.criteria)
        }

        // Update state to show mission progress
        agentEngine.updateAgentState(AgentState.Running("Mission: ${subtasks.size} subtasks", 0, subtasks.size))
        // stop() 可达 — 并行 worker 依赖父 Job 取消传播
        agentEngine.attachRunningJob(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])

        try {
            // Step 2: Parallel execution with retry+verify per subtask (WIP gate)
            val semaphore = Semaphore(maxParallel)
            coroutineScope {
                subtasks.map { sub ->
                    async(KernelDispatchers.BACKGROUND) {
                        semaphore.withPermit {
                            executeSubtask(sub, maxStepsPerSubtask, maxRetriesPerSubtask, llmProvider, onStep)
                        }
                    }
                }.awaitAll()
            }

            // Step 3: LLM synthesis of all results
            val verified = subtasks.count { it.status == SubtaskStatus.VERIFIED }
            val failed = subtasks.count { it.status == SubtaskStatus.FAILED }
            val parts = subtasks.joinToString("\n") { st ->
                val icon = when (st.status) {
                    SubtaskStatus.VERIFIED -> "✅"
                    SubtaskStatus.DONE -> "👍"
                    SubtaskStatus.FAILED -> "❌"
                    else -> "⬜"
                }
                "$icon ${st.description}: ${st.output.take(300)}"
            }
            val synthesisPrompt = MissionSwarmPrompts.buildSynthesisPrompt(
                task = guardedTask, mode = "Mission", verified = verified, failed = failed,
                total = subtasks.size, parts = parts
            )

            val synthesis = try {
                // v0.28.4: 合成阶段流式化 — 最终报告逐字输出
                if (onDelta != null) {
                    llmProvider.completeStreaming(synthesisPrompt, onDelta)
                } else {
                    llmProvider.complete(synthesisPrompt)
                }
            } catch (_: Exception) {
                parts
            }

            val report = buildString {
                appendLine("## Mission: $task")
                appendLine("子任务: ${subtasks.size} | ✅ $verified | 👍 ${subtasks.filter { it.status == SubtaskStatus.DONE }.size} | ❌ $failed")
                appendLine()
                appendLine(synthesis)
            }
            // 状态机复位: 任务结束必须 Finished（此前停留 Running, 引擎状态失真 — 同 Swarm 对齐）
            agentEngine.updateAgentState(AgentState.Finished(report))
            return report
        } catch (e: CancellationException) {
            // 取消传播契约: 必须先 rethrow; P2 — 状态机复位, 否则 _state 残留 Running
            agentEngine.updateAgentState(AgentState.Idle)
            throw e
        }
    }

    /** Execute a single subtask with verification and retry. */
    private suspend fun executeSubtask(
        subtask: MissionSubtask,
        maxSteps: Int, maxRetries: Int,
        llmProvider: com.mengpaw.kernel.llm.LlmProvider,
        onStep: ((AgentEngine.TraceStep) -> Unit)? = null
    ): String {
        var retries = 0
        var lastVerifierFeedback = ""

        while (retries <= maxRetries) {
            subtask.status = SubtaskStatus.RUNNING

            // Build task prompt — include verifier feedback on retry
            val taskPrompt = if (retries > 0 && lastVerifierFeedback.isNotBlank()) {
                MissionSwarmPrompts.buildRetryPrompt(subtask.description, retries, lastVerifierFeedback)
            } else {
                subtask.description
            }

            val workerResult = try {
                runMissionWorker(subtask, taskPrompt, maxSteps, onStep)
            } catch (e: CancellationException) {
                throw e  // 保持取消契约 — 用户 stop() 不被吞成重试
            } catch (e: Exception) {
                "Error: ${e.message}"
            }

            subtask.output = workerResult
            val isHardError = workerResult.startsWith("Error:") ||
                workerResult.startsWith("已达到最大步数") ||
                workerResult.startsWith("Max steps")

            if (isHardError) {
                lastVerifierFeedback = "Worker execution error: ${workerResult.take(300)}"
                retries++
                continue
            }

            // Strict Verifier (Worker-Verifier pattern)
            val (passed, note, fix) = try {
                val verifyResult = llmProvider.complete(MissionSwarmPrompts.buildVerifierPrompt(
                    criteria = subtask.expectedOutcome.ifBlank { "Complete the task: ${subtask.description}" },
                    output = workerResult
                ))
                MissionSwarmPrompts.parseVerifierVerdict(verifyResult)
            } catch (_: Exception) {
                // Verification unavailable — accept result without verification
                subtask.status = SubtaskStatus.DONE
                return workerResult
            }

            if (passed) {
                subtask.status = SubtaskStatus.VERIFIED
                subtask.verifierNote = note
                return workerResult
            } else {
                // FAIL — build feedback from analysis + fix instructions
                // 兜底用 worker 输出前段（原实现为验证器原文 take(300)，语义近似；保留 worker 输出更贴合反馈）
                lastVerifierFeedback = buildString {
                    if (note.isNotBlank() && note != "PASS") { append("问题: $note\n") }
                    if (fix.isNotBlank()) { append("修复建议: $fix") }
                    if (isBlank()) { append(workerResult.take(300)) }
                }
                subtask.verifierNote = "FAIL: ${lastVerifierFeedback.take(150)}"
                retries++
            }
        }

        subtask.status = SubtaskStatus.FAILED
        return lastVerifierFeedback.ifBlank { subtask.output }
    }

    /**
     * 零待命 worker — 独立 session (scope="mission")，不入主会话、不写记忆
     * （AgentMemoryExecutor 对 mission scope 屏蔽写命令），用完即销毁。
     * ReAct 循环同主引擎（含多 Action 并行执行），但共享可变状态
     * （detectLoop/trackResult 等）一律不碰 — 并行安全纪律同 Swarm runWorker。
     */
    private suspend fun runMissionWorker(
        subtask: MissionSubtask,
        taskPrompt: String,
        maxSteps: Int,
        onStep: ((AgentEngine.TraceStep) -> Unit)?
    ): String {
        val sessionManager = agentEngine.getSessionManager()
        val session = sessionManager.createSession(
            task = subtask.description,
            metadata = mapOf("missionId" to "mission-" + subtask.id),
            scope = "mission",
            agentId = agentEngine.agentName,
            activate = false  // 零待命 worker 不抢占 activeSessionId（防折叠压缩错会话）
        )
        try {
            val context = ExecutionContext(
                sessionId = session.id,
                agentName = agentEngine.agentName,
                scope = "mission"
            )
            sessionManager.addMessage(session.id, Message("user", taskPrompt))
            sessionManager.addMessage(session.id, Message("system",
                "你是 Mission 模式的并行 worker，只完成本子任务，不依赖其他 worker 的结果，也不写记忆。"))

            var step = 0
            while (step < maxSteps) {
                val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                if (job != null && !job.isActive) throw kotlinx.coroutines.CancellationException("Mission stopped")

                val conversation = agentEngine.buildConversation(session.id)
                val response = try {
                    agentEngine.getLlmProvider().completeWithMessages(conversation)
                } catch (e: Exception) {
                    recordMissionWorkerTermination(session.id, "worker_llm_error", subtask.description)
                    return "Error: ${e.message}"
                }
                val sanitized = com.mengpaw.kernel.security.Sanitizer.sanitize(response)
                sessionManager.addMessage(session.id, Message("assistant", sanitized))

                val parsed = agentEngine.getPromptEngine().parse(sanitized)
                if (parsed.isFinal) {
                    return parsed.thought
                }
                if (parsed.needsContinue) {
                    sessionManager.addMessage(session.id, Message("user",
                        "继续。输出 Action: <命令> 和 Action Input: <参数>。"))
                    continue
                }
                val actionList = parsed.actions.ifEmpty { listOfNotNull(parsed.action) }
                if (actionList.isNotEmpty()) {
                    val entries = coroutineScope {
                        actionList.map { call ->
                            async(KernelDispatchers.BACKGROUND) {
                                // P0 对齐 (v0.34.1): 高危门禁 + 来源黑名单硬闸 (与主循环同一纯函数)
                                val gate = com.mengpaw.kernel.security.HighRiskCommandGate.evaluate(call)
                                val commandLine = gate.commandLine
                                val result = try {
                                    when {
                                        gate.error != null ->
                                            ExecutionResult.fail(gate.error, errorCode = gate.errorCode ?: ErrorCodes.PARAM_FORMAT_ERROR)
                                        else -> {
                                            // ── 分级拦截 (v0.34.3): worker 无用户交互, 高危直接拒绝 ──
                                            val riskError = com.mengpaw.kernel.security.RiskGate
                                                .evaluate(gate, context.agentName ?: agentEngine.agentName, allowUserConfirm = false)
                                            if (riskError != null) {
                                                ExecutionResult.fail(riskError, errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
                                            } else {
                                                val source = com.mengpaw.kernel.security.SourceBlocklist.extractSource(commandLine)
                                                if (source != null && com.mengpaw.kernel.security.SourceBlocklist.isBlocked(source)) {
                                                    ExecutionResult.fail("来源已在黑名单，工具结果已阻止。", errorCode = ErrorCodes.ERR_SOURCE_BLOCKED)
                                                } else {
                                                    kotlinx.coroutines.withTimeout(60_000L) {
                                                        agentEngine.getPipelineManager().buildPipeline().execute(commandLine, context)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                                    ExecutionResult.fail("命令超时 (60s): $commandLine", errorCode = ErrorCodes.ERR_INTERNAL)
                                }
                                val obs = (if (result.success) result.output
                                    else (result.errorCode?.let { "Error [$it]: ${result.error}" } ?: "Error: ${result.error}"))
                                    .take(com.mengpaw.kernel.agent.MissionSwarmPrompts.WORKER_OBSERVATION_MAX)
                                // P0 一致性 (v0.34.1): 与主循环对齐 — 剥离指令形态片段 + <untrusted_data> 包裹;
                                // worker 无用户交互: 命中仅日志, 不 banner 不提醒 (协调器主循环兜底)
                                val cleaned = com.mengpaw.kernel.security.UntrustedContent.stripInjection(obs)
                                com.mengpaw.kernel.security.InjectionPatterns.findMatch(obs)?.let {
                                    KernelLog.w("MissionWorker", "检测到疑似$it, 内容已净化 (worker 零用户交互)")
                                }
                                onStep?.invoke(AgentEngine.TraceStep(step + 1, parsed.thought, commandLine, cleaned))
                                "Command: $commandLine\nResult: ${com.mengpaw.kernel.security.UntrustedContent.wrap(cleaned)}"
                            }
                        }.awaitAll()
                    }
                    sessionManager.addMessage(session.id, Message("assistant", entries.joinToString("\n\n")))
                }
                step++
            }
            recordMissionWorkerTermination(session.id, "worker_max_steps", subtask.description)
            return "已达到最大步数 ($maxSteps) 未完成"
        } finally {
            // 零待命: 销毁会话, 无跨任务记忆
            sessionManager.deleteSession(session.id)
        }
    }

    /**
     * Mission worker 终止进化介入 (2026-08-08): LLM 错误 / 步数上限 = 完成度低信号,
     * 记录到主 agent 失败模式库 (reason 带 worker_ 前缀), 剪取子任务上下文片段。
     * worker 零待命不写记忆, 但失败模式必须沉淀供进化学习。永不抛异常。
     */
    private fun recordMissionWorkerTermination(sessionId: String, reason: String, task: String) {
        try {
            com.mengpaw.kernel.evolution.EvolutionStore.recordTermination(
                agentName = agentEngine.agentName,
                reason = reason,
                command = "",
                errorCode = reason.uppercase(),
                contextSnippet = clipMissionContext(sessionId),
                task = task
            )
        } catch (_: Exception) { /* 进化记录永不阻塞 worker */ }
    }

    /** 剪取 mission worker 会话尾部最近上下文片段 (非 localOnly, 限长限条数)。 */
    private fun clipMissionContext(sessionId: String, maxEntries: Int = 6, maxChars: Int = 500): String {
        return try {
            val msgs = agentEngine.getSessionManager().getSession(sessionId)?.messages ?: return ""
            msgs.filter { !it.localOnly && it.content.isNotBlank() }
                .takeLast(maxEntries)
                .joinToString("\n") { "[${it.role}] ${it.content.take(160)}" }
                .take(maxChars)
        } catch (_: Exception) { "" }
    }
}
