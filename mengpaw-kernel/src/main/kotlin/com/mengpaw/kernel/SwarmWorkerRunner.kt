// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.agent.SwarmBudget
import com.mengpaw.kernel.agent.SwarmSubtask
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.security.Sanitizer
import com.mengpaw.kernel.session.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout

/**
 * 火种模式零待命 Worker 执行器 — 拆自 SwarmModeExecutor.runWorker
 * (400 行文件拆分)。独立 Session (scope="swarm") + 轻量 ReAct 循环,
 * 用完即销毁, 只回报 [WorkerOutcome] 结果卡片。
 */
internal class SwarmWorkerRunner(private val engine: AgentEngine) {

    /**
     * Worker 执行: 独立 Session (scope="swarm") + 轻量 ReAct 循环。
     *
     * 刻意精简的并发安全清单 (对照主 runReActLoop):
     * - 不写 _state/_output/conversationSessionId — 状态由协调器主协程独占更新
     * - 不调 promptEngine.detectLoop/trackResult (共享可变状态, 并行竞争)
     * - 不调 EvolutionHook/checkpoint/上下文折叠 (短生命周期不需要)
     */
    internal suspend fun runWorker(
        subtask: SwarmSubtask,
        provider: LlmProvider,
        maxSteps: Int,
        budget: SwarmBudget,
        feedback: String,
        onStep: ((AgentEngine.TraceStep) -> Unit)? = null
    ): WorkerOutcome {
        val swarmId = "swarm-" + subtask.id
        // JIT/SMED: 独立会话, 不入 conversationSessionId, 用完即销毁
        val session = engine.getSessionManager().createSession(
            task = subtask.description,
            metadata = mapOf("swarmId" to swarmId, "role" to subtask.role),
            scope = "swarm",
            agentId = engine.agentName,
            activate = false  // 零待命 worker 不抢占 activeSessionId（防折叠压缩错会话）
        )
        try {
            val context = ExecutionContext(
                sessionId = session.id,
                agentName = engine.agentName,
                scope = "swarm"
            )
            val taskPrompt = if (feedback.isNotBlank()) {
                com.mengpaw.kernel.agent.MissionSwarmPrompts.buildRetryPrompt(
                    subtask.description, subtask.retryCount, feedback)
            } else {
                subtask.description
            }
            engine.getSessionManager().addMessage(session.id, Message("user", taskPrompt))
            engine.getSessionManager().addMessage(session.id, Message("system",
                "你是火种模式的并行 worker，只完成本子任务，不依赖其他 worker 的结果，也不写记忆。"))

            var step = 0
            var tokens = 0L
            while (step < maxSteps) {
                // stop() 可达检查 (与主循环同款: Job.isActive 成员属性)
                val job = currentCoroutineContext()[Job]
                if (job != null && !job.isActive) throw CancellationException("Swarm stopped")
                // 闸1: 总预算 (实际步数, AtomicInteger CAS 无锁安全)
                if (!budget.tryConsume()) {
                    recordWorkerTermination(session.id, "worker_budget_exhausted", "", "SWARM_BUDGET_EXHAUSTED", subtask.description)
                    return WorkerOutcome("预算耗尽，停止执行", step, tokens, budgetExhausted = true)
                }

                val conversation = engine.buildConversation(session.id)
                val response = try {
                    provider.completeWithMessages(conversation)
                } catch (e: Exception) {
                    recordWorkerTermination(session.id, "worker_llm_error", "", "WORKER_LLM_ERROR", subtask.description)
                    return WorkerOutcome("", step, tokens, "LLM 错误: ${e.message}")
                }
                tokens += provider.lastUsage?.totalTokens?.toLong() ?: 0L
                val sanitized = Sanitizer.sanitize(response)
                engine.getSessionManager().addMessage(session.id, Message("assistant", sanitized))

                val parsed = engine.getPromptEngine().parse(sanitized)
                if (parsed.isFinal) {
                    return WorkerOutcome(parsed.thought, step + 1, tokens)
                }
                if (parsed.needsContinue) {
                    engine.getSessionManager().addMessage(session.id, Message("user", "继续。输出 Action: <命令> 和 Action Input: <参数>。"))
                    continue
                }
                // P2 修复: 多 Action 并行（与主循环对齐 — 一次 LLM 输出多工具）
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
                                                .evaluate(gate, context.agentName ?: engine.agentName, allowUserConfirm = false)
                                            if (riskError != null) {
                                                ExecutionResult.fail(riskError, errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
                                            } else {
                                                val source = com.mengpaw.kernel.security.SourceBlocklist.extractSource(commandLine)
                                                if (source != null && com.mengpaw.kernel.security.SourceBlocklist.isBlocked(source)) {
                                                    ExecutionResult.fail("来源已在黑名单，工具结果已阻止。", errorCode = ErrorCodes.ERR_SOURCE_BLOCKED)
                                                } else {
                                                    withTimeout(60_000L) { engine.getPipelineManager().buildPipeline().execute(commandLine, context) }
                                                }
                                            }
                                        }
                                    }
                                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                                    ExecutionResult.fail("命令超时 (60s): $commandLine", errorCode = ErrorCodes.ERR_INTERNAL)
                                }
                                // 防单条结果撑爆 worker 上下文
                                val observation = (if (result.success) result.output
                                    else (result.errorCode?.let { "Error [$it]: ${result.error}" } ?: "Error: ${result.error}"))
                                    .take(com.mengpaw.kernel.agent.MissionSwarmPrompts.WORKER_OBSERVATION_MAX)
                                // P0 一致性 (v0.34.1): 与主循环对齐 — 剥离指令形态片段 + <untrusted_data> 包裹;
                                // worker 无用户交互: 命中仅日志, 不 banner 不提醒 (协调器主循环兜底)
                                val cleaned = com.mengpaw.kernel.security.UntrustedContent.stripInjection(observation)
                                com.mengpaw.kernel.security.InjectionPatterns.findMatch(observation)?.let {
                                    KernelLog.w("SwarmWorker", "检测到疑似$it, 内容已净化 (worker 零用户交互)")
                                }
                                onStep?.invoke(AgentEngine.TraceStep(step + 1, parsed.thought, commandLine, cleaned))
                                "Command: $commandLine\nResult: ${com.mengpaw.kernel.security.UntrustedContent.wrap(cleaned)}"
                            }
                        }.awaitAll()
                    }
                    engine.getSessionManager().addMessage(session.id, Message("assistant", entries.joinToString("\n\n")))
                }
                step++
            }
            recordWorkerTermination(session.id, "worker_max_steps", "", "WORKER_MAX_STEPS", subtask.description)
            return WorkerOutcome("达到最大步数 ($maxSteps) 未完成", step, tokens, "max_steps")
        } finally {
            // 零待命: 销毁会话, 无跨任务记忆
            engine.getSessionManager().deleteSession(session.id)
        }
    }

    /**
     * Worker 终止进化介入 (2026-08-08): 预算耗尽 / LLM 错误 / 步数上限都是"完成度低"信号,
     * 记录到主 agent 的失败模式库 (reason 带 worker_ 前缀区分来源), 剪取子任务上下文片段。
     * worker 零待命不写记忆, 但失败模式必须沉淀供进化学习。永不抛异常。
     */
    private fun recordWorkerTermination(
        sessionId: String,
        reason: String,
        command: String,
        errorCode: String,
        task: String
    ) {
        try {
            com.mengpaw.kernel.evolution.EvolutionStore.recordTermination(
                agentName = engine.agentName,
                reason = reason,
                command = command,
                errorCode = errorCode,
                contextSnippet = clipContext(sessionId),
                task = task
            )
        } catch (_: Exception) { /* 进化记录永不阻塞 worker */ }
    }

    /** 剪取 worker 会话尾部最近上下文片段 (非 localOnly, 限长限条数)。 */
    private fun clipContext(sessionId: String, maxEntries: Int = 6, maxChars: Int = 500): String {
        return try {
            val msgs = engine.getSessionManager().getSession(sessionId)?.messages ?: return ""
            msgs.filter { !it.localOnly && it.content.isNotBlank() }
                .takeLast(maxEntries)
                .joinToString("\n") { "[${it.role}] ${it.content.take(160)}" }
                .take(maxChars)
        } catch (_: Exception) { "" }
    }
}

/** Worker 循环返回值 — 协调器只消费此结构, 不看 Worker 内部日志。 */
internal data class WorkerOutcome(
    val answer: String,
    val stepsUsed: Int,
    val tokensUsed: Long,
    val error: String? = null,
    val budgetExhausted: Boolean = false
)
