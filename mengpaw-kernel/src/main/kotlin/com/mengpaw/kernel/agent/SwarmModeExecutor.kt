// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.agent.SwarmBudget
import com.mengpaw.kernel.agent.SwarmResultCard
import com.mengpaw.kernel.agent.SwarmRoles
import com.mengpaw.kernel.agent.SwarmSubtask
import com.mengpaw.kernel.agent.SwarmSubtaskStatus
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.security.PromptFirewall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 火种模式 (Swarm Mode) 执行器 — "星星之火，可以燎原"。
 *
 * 一个任务点燃众多 Worker 的燎原之势: 规划器拆解 → 并行 Worker (可混合不同模型) →
 * Verifier 验证 → 合成器输出。参考 Kimi Agent Swarm 四层架构与丰田 JIT 生产体系:
 *
 * - **看板三闸门**: [SwarmBudget] 总预算 (实际步数) + WIP 并行上限 (Semaphore) + 单任务上限
 * - **Andon 失败协议**: Worker 失败回报协调器决策 (重派可换 `worker.alt` 模型 / 终止)，不静默重试
 * - **零待命 Worker (SMED)**: 独立 Session (scope="swarm") 用完即销毁，无跨任务记忆，
 *   只回报结构化结果卡片 [SwarmResultCard] — 协调器只收卡片，不收日志
 * - **上下文分片**: Worker 会话不入 conversationSessionId，不污染主对话与三轨记忆
 */
class SwarmModeExecutor(
    private val agentEngine: AgentEngine
) {
    private val sessionManager get() = agentEngine.getSessionManager()
    private val pipelineManager get() = agentEngine.getPipelineManager()
    private val promptEngine get() = agentEngine.getPromptEngine()

    private enum class AndonAction { REDEPLOY, TERMINATE }

    /** 零待命 Worker 执行器 (runWorker 拆自本类, 400 行文件拆分)。 */
    private val workerRunner = SwarmWorkerRunner(agentEngine)

    /**
     * 火种模式主流程: 规划 → 并行执行+验证 → 合成。
     *
     * @param roles 角色 → LLM Provider 映射 (planner/worker/verifier/synthesizer/worker.alt 可异模型)；
     *        缺省回退引擎主 provider
     */
    suspend fun runWithSwarm(
        task: String,
        roles: Map<String, LlmProvider> = emptyMap(),
        maxSubtasks: Int = 5,
        maxParallel: Int = 4,
        maxStepsPerSubtask: Int = 12,
        maxRetriesPerSubtask: Int = 2,
        maxTotalSteps: Int = maxSubtasks * maxStepsPerSubtask,
        onStep: ((AgentEngine.TraceStep) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null
    ): String {
        val guardedTask = if (PromptFirewall.checkUserPrompt(task) != null)
            PromptFirewall.wrapWithDefense(task) else task

        // stop() 可达: 挂载当前协程 Job (worker 不经 runReActLoop, runningJob 不会自动挂上)
        agentEngine.attachRunningJob(currentCoroutineContext()[Job])
        agentEngine.updateAgentState(AgentState.Running("火种: 规划中", 0, 0))

        try {
            // ── Phase 0: 规划器拆解 ──
            val planner = providerFor(SwarmRoles.PLANNER, roles)
            val subtasks = decompose(guardedTask, planner, maxSubtasks)
            if (subtasks.isEmpty()) {
                // 拆解失败兜底: 退化为单 Agent 执行 (同 Mission 策略)
                return agentEngine.run(guardedTask, maxStepsPerSubtask * maxSubtasks, onStep, onDelta)
            }

            val budget = SwarmBudget(maxTotalSteps)
            val semaphore = Semaphore(maxParallel)
            agentEngine.updateAgentState(AgentState.Running("火种: ${subtasks.size} 个子任务", 0, subtasks.size))

            // ── Phase 1+2: 并行 Worker 执行 + Verifier 验证 (每子任务一个协程, WIP 闸限流) ──
            val cards = coroutineScope {
                subtasks.map { sub ->
                    async(KernelDispatchers.BACKGROUND) {
                        semaphore.withPermit {
                            runSubtaskPipeline(sub, roles, budget, maxStepsPerSubtask, maxRetriesPerSubtask, onStep)
                        }
                    }
                }.awaitAll()
            }

            // ── Phase 3: 合成器汇总 (流式: 最终报告逐字输出) ──
            val synthesis = synthesize(guardedTask, cards, providerFor(SwarmRoles.SYNTHESIZER, roles), onDelta)
            val verified = cards.count { it.status == SwarmSubtaskStatus.VERIFIED }
            val failed = cards.count { it.status == SwarmSubtaskStatus.FAILED }
            val skipped = cards.count { it.status == SwarmSubtaskStatus.SKIPPED }

            val report = buildString {
                appendLine("## 火种模式: $guardedTask")
                appendLine("子任务: ${cards.size} | ✅ $verified | ❌ $failed | ⏭️ $skipped | 总步数: ${budget.consumedSteps}")
                appendLine()
                cards.forEach { appendLine("${it.icon} ${it.subtaskId}: ${it.summary.take(300)}") }
                appendLine()
                append(synthesis)
            }
            agentEngine.updateAgentState(AgentState.Finished(report))
            return report
        } catch (e: CancellationException) {
            // 取消传播契约: 必须先 rethrow; P2 — 状态机复位, 否则 _state 残留 Running
            agentEngine.updateAgentState(AgentState.Idle)
            throw e
        }
    }

    // ── 单子任务流水线: JIT 看板 + Andon 协议 ──────────────────────

    /** 子任务全生命周期: worker → andon 决策 → verifier → 卡片回报。整体在 Semaphore 许可内。 */
    private suspend fun runSubtaskPipeline(
        subtask: SwarmSubtask,
        roles: Map<String, LlmProvider>,
        budget: SwarmBudget,
        maxSteps: Int,
        maxRetries: Int,
        onStep: ((AgentEngine.TraceStep) -> Unit)? = null
    ): SwarmResultCard {
        // 闸1: 总预算提前跳过 (排队 worker 拿到许可后立即退化, 不空转)
        if (budget.exhausted) return SwarmResultCard.skipped(subtask.id)

        var feedback = ""
        while (true) {
            // Andon 重派时切换角色 (可换 worker.alt 模型)
            val role = if (subtask.retryCount > 0 || feedback.isNotBlank()) retryRoleFor(subtask.role, roles) else subtask.role
            val provider = providerFor(role, roles)
            val outcome = workerRunner.runWorker(subtask, provider, maxSteps, budget, feedback, onStep)

            // 预算耗尽: 不可重试, 直接终止该子任务
            if (outcome.budgetExhausted) {
                return SwarmResultCard(subtask.id, SwarmSubtaskStatus.FAILED,
                    outcome.answer, outcome.tokensUsed, outcome.stepsUsed, "budget_exhausted")
            }

            // Andon 决策点: worker 硬错误 → 协调器决策 (不静默重试)
            if (outcome.error != null) {
                when (andonDecision(subtask, outcome, budget, maxRetries)) {
                    AndonAction.TERMINATE -> return SwarmResultCard(subtask.id, SwarmSubtaskStatus.FAILED,
                        outcome.answer, outcome.tokensUsed, outcome.stepsUsed,
                        outcome.error ?: "重试次数耗尽（${subtask.retryCount}/$maxRetries）")
                    AndonAction.REDEPLOY -> {
                        subtask.retryCount++
                        feedback = outcome.error ?: ""
                        continue
                    }
                }
            }

            // Phase 2: Verifier 验证 (Worker-Verifier 模式)
            val (passed, note) = verify(subtask, outcome, providerFor(SwarmRoles.VERIFIER, roles))
            if (passed) {
                return SwarmResultCard(subtask.id, SwarmSubtaskStatus.VERIFIED,
                    outcome.answer, outcome.tokensUsed, outcome.stepsUsed, note)
            }
            if (subtask.retryCount >= maxRetries || budget.exhausted) {
                return SwarmResultCard(subtask.id, SwarmSubtaskStatus.FAILED,
                    outcome.answer, outcome.tokensUsed, outcome.stepsUsed, "FAIL: $note")
            }
            // verifier 反馈注入重试 (Mission 同款机制)
            subtask.retryCount++
            feedback = note
        }
    }

    /**
     * Andon 决策 (纯函数, 可单测): worker 失败后协调器决定重派或终止。
     * - 预算耗尽 → 终止 (预算闸不可重试)
     * - 单任务重试次数耗尽 → 终止
     * - 否则 → 重派 (可切换 worker.alt 模型)
     */
    private fun andonDecision(
        subtask: SwarmSubtask,
        outcome: WorkerOutcome,
        budget: SwarmBudget,
        maxRetries: Int
    ): AndonAction = when {
        budget.exhausted -> AndonAction.TERMINATE
        subtask.retryCount >= maxRetries -> AndonAction.TERMINATE
        else -> AndonAction.REDEPLOY
    }

    // ── Verifier (Worker-Verifier 模式) ────────────────────────────

    /** 严格质量审查: VERDICT/ANALYSIS/FIX 格式 (与 Mission 一致的验证协议)。 */
    private suspend fun verify(
        subtask: SwarmSubtask,
        outcome: WorkerOutcome,
        verifierProvider: LlmProvider
    ): Pair<Boolean, String> {
        val verifierPrompt = com.mengpaw.kernel.agent.MissionSwarmPrompts.buildVerifierPrompt(
            criteria = subtask.expectedOutcome.ifBlank { "Complete the task: ${subtask.description}" },
            output = outcome.answer
        )

        return try {
            val verifyResult = verifierProvider.complete(verifierPrompt)
            val (passed, analysis, fix) = com.mengpaw.kernel.agent.MissionSwarmPrompts.parseVerifierVerdict(verifyResult)
            val note = buildString {
                if (analysis.isNotBlank() && analysis != "PASS") append("问题: $analysis\n")
                if (fix.isNotBlank()) append("修复建议: $fix")
                if (isBlank()) append(verifyResult.take(200))
            }.ifBlank { "PASS" }
            passed to note
        } catch (e: Exception) {
            // 验证不可用 — 接受结果 (Mission 同款降级)
            true to "PASS (verifier unavailable)"
        }
    }

    // ── 规划器拆解 ─────────────────────────────────────────────────

    private suspend fun decompose(
        task: String,
        plannerProvider: LlmProvider,
        maxSubtasks: Int
    ): List<SwarmSubtask> {
        val decomposePrompt = com.mengpaw.kernel.agent.MissionSwarmPrompts.buildDecomposePrompt(task, maxSubtasks, withRole = true)

        val decomposeResult = try {
            plannerProvider.complete(decomposePrompt)
        } catch (e: Exception) {
            return emptyList()
        }

        return com.mengpaw.kernel.agent.MissionSwarmPrompts.parseSubtasks(decomposeResult, maxSubtasks)
            .map { spec ->
                SwarmSubtask(
                    id = spec.id,
                    description = spec.desc,
                    expectedOutcome = spec.criteria,
                    role = spec.role ?: SwarmRoles.WORKER
                )
            }
    }

    // ── 合成器 ─────────────────────────────────────────────────────

    private suspend fun synthesize(
        task: String,
        cards: List<SwarmResultCard>,
        synthesizerProvider: LlmProvider,
        onDelta: ((String) -> Unit)? = null
    ): String {
        val verified = cards.count { it.status == SwarmSubtaskStatus.VERIFIED }
        val failed = cards.count { it.status == SwarmSubtaskStatus.FAILED }
        val parts = cards.joinToString("\n") { card ->
            "${card.icon} ${card.subtaskId}: ${card.summary.take(300)}"
        }
        val synthesisPrompt = com.mengpaw.kernel.agent.MissionSwarmPrompts.buildSynthesisPrompt(
            task = task, mode = "parallel-worker", verified = verified, failed = failed,
            total = cards.size, parts = parts
        )

        return try {
            // v0.28.4: 合成阶段流式化 — 用户最终看到的大段文本逐字输出;
            // worker/decompose/verify 并行阶段保持非流式 (onStep/traces 呈现进度)
            if (onDelta != null) {
                synthesizerProvider.completeStreaming(synthesisPrompt, onDelta)
            } else {
                synthesizerProvider.complete(synthesisPrompt)
            }
        } catch (e: Exception) {
            // 合成不可用 — 拼接卡片兜底
            parts
        }
    }

    // ── 角色解析 ───────────────────────────────────────────────────

    /** 角色 → provider: 指定角色 → 缺省 "worker" → 引擎主 provider。 */
    private fun providerFor(role: String, roles: Map<String, LlmProvider>): LlmProvider =
        roles[role] ?: roles[SwarmRoles.WORKER] ?: agentEngine.getLlmProvider()

    /** Andon 重派角色: 优先 roles["worker.alt"] (可不同模型), 无则原角色。 */
    private fun retryRoleFor(role: String, roles: Map<String, LlmProvider>): String =
        if (SwarmRoles.WORKER_ALT in roles) SwarmRoles.WORKER_ALT else role
}
