// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.agent.SwarmBudget
import com.mengpaw.kernel.agent.SwarmResultCard
import com.mengpaw.kernel.agent.SwarmSubtask
import com.mengpaw.kernel.agent.SwarmSubtaskStatus
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.security.PromptFirewall
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
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

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

    private val json = Json { ignoreUnknownKeys = true }

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
        onStep: ((AgentEngine.TraceStep) -> Unit)? = null
    ): String {
        val guardedTask = if (PromptFirewall.checkUserPrompt(task) != null)
            PromptFirewall.wrapWithDefense(task) else task

        // stop() 可达: 挂载当前协程 Job (worker 不经 runReActLoop, runningJob 不会自动挂上)
        agentEngine.attachRunningJob(currentCoroutineContext()[Job])
        agentEngine.updateAgentState(AgentState.Running("火种: 规划中", 0, 0))

        // ── Phase 0: 规划器拆解 ──
        val planner = providerFor("planner", roles)
        val subtasks = decompose(guardedTask, planner, maxSubtasks)
        if (subtasks.isEmpty()) {
            // 拆解失败兜底: 退化为单 Agent 执行 (同 Mission 策略)
            return agentEngine.run(guardedTask, maxStepsPerSubtask * maxSubtasks, onStep)
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

        // ── Phase 3: 合成器汇总 ──
        val synthesis = synthesize(guardedTask, cards, providerFor("synthesizer", roles))
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
            val outcome = runWorker(subtask, provider, maxSteps, budget, feedback, onStep)

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
            val (passed, note) = verify(subtask, outcome, providerFor("verifier", roles))
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

    // ── 轻量 Worker ReAct 循环 (零待命) ────────────────────────────

    /**
     * Worker 执行: 独立 Session (scope="swarm") + 轻量 ReAct 循环。
     *
     * 刻意精简的并发安全清单 (对照主 runReActLoop):
     * - 不写 _state/_output/conversationSessionId — 状态由协调器主协程独占更新
     * - 不调 promptEngine.detectLoop/trackResult (共享可变状态, 并行竞争)
     * - 不调 EvolutionHook/checkpoint/上下文折叠 (短生命周期不需要)
     */
    private suspend fun runWorker(
        subtask: SwarmSubtask,
        provider: LlmProvider,
        maxSteps: Int,
        budget: SwarmBudget,
        feedback: String,
        onStep: ((AgentEngine.TraceStep) -> Unit)? = null
    ): WorkerOutcome {
        val swarmId = "swarm-" + subtask.id
        // JIT/SMED: 独立会话, 不入 conversationSessionId, 用完即销毁
        val session = sessionManager.createSession(
            task = subtask.description,
            metadata = mapOf("swarmId" to swarmId, "role" to subtask.role),
            scope = "swarm",
            agentId = agentEngine.agentName
        )
        try {
            val context = ExecutionContext(
                sessionId = session.id,
                agentName = agentEngine.agentName,
                scope = "swarm"
            )
            val taskPrompt = if (feedback.isNotBlank()) {
                buildString {
                    append(subtask.description)
                    append("\n\n## 质量审查反馈（第 ${subtask.retryCount} 次）\n")
                    append(feedback)
                    append("\n\n请修正上述问题后重新执行。")
                }
            } else {
                subtask.description
            }
            sessionManager.addMessage(session.id, Message("user", taskPrompt))
            sessionManager.addMessage(session.id, Message("system",
                "你是火种模式的并行 worker，只完成本子任务，不依赖其他 worker 的结果，也不写记忆。"))

            var step = 0
            var tokens = 0L
            while (step < maxSteps) {
                // stop() 可达检查 (与主循环同款: Job.isActive 成员属性)
                val job = currentCoroutineContext()[Job]
                if (job != null && !job.isActive) throw CancellationException("Swarm stopped")
                // 闸1: 总预算 (实际步数, AtomicInteger CAS 无锁安全)
                if (!budget.tryConsume()) {
                    return WorkerOutcome("预算耗尽，停止执行", step, tokens, budgetExhausted = true)
                }

                val conversation = agentEngine.buildConversation(session.id)
                val response = try {
                    provider.completeWithMessages(conversation)
                } catch (e: Exception) {
                    return WorkerOutcome("", step, tokens, "LLM 错误: ${e.message}")
                }
                tokens += provider.lastUsage?.totalTokens?.toLong() ?: 0L
                val sanitized = Sanitizer.sanitize(response)
                sessionManager.addMessage(session.id, Message("assistant", sanitized))

                val parsed = promptEngine.parse(sanitized)
                if (parsed.isFinal) {
                    return WorkerOutcome(parsed.thought, step + 1, tokens)
                }
                if (parsed.needsContinue) {
                    sessionManager.addMessage(session.id, Message("user", "继续。输出 Action: <命令> 和 Action Input: <参数>。"))
                    continue
                }
                if (parsed.action != null) {
                    val commandLine = "${parsed.action.name} ${parsed.action.parameters.values.joinToString(" ")}"
                    val result = try {
                        withTimeout(60_000L) { pipelineManager.buildPipeline().execute(commandLine, context) }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        ExecutionResult.fail("命令超时 (60s): $commandLine", errorCode = ErrorCodes.ERR_INTERNAL)
                    }
                    // 防单条结果撑爆 worker 上下文
                    val observation = (if (result.success) result.output else "Error: ${result.error}").take(4000)
                    sessionManager.addMessage(session.id, Message("assistant", "Command: $commandLine\nResult: $observation"))
                    onStep?.invoke(AgentEngine.TraceStep(step + 1, parsed.thought, commandLine, observation))
                }
                step++
            }
            return WorkerOutcome("达到最大步数 ($maxSteps) 未完成", step, tokens, "max_steps")
        } finally {
            // 零待命: 销毁会话, 无跨任务记忆
            sessionManager.deleteSession(session.id)
        }
    }

    // ── Verifier (Worker-Verifier 模式) ────────────────────────────

    /** 严格质量审查: VERDICT/ANALYSIS/FIX 格式 (与 Mission 一致的验证协议)。 */
    private suspend fun verify(
        subtask: SwarmSubtask,
        outcome: WorkerOutcome,
        verifierProvider: LlmProvider
    ): Pair<Boolean, String> {
        val verifierPrompt = """
You are a strict quality verifier. Review the worker agent's output against the success criteria.

**Success criteria**: ${subtask.expectedOutcome.ifBlank { "Complete the task: ${subtask.description}" }}

**Worker output**:
${outcome.answer.take(2000)}

**Analysis rules**:
- Check if the output actually fulfills the criteria (not just mentions it)
- Check for factual errors, incomplete data, or vague hand-waving
- A "Final Answer" that says "I cannot do this" without trying alternatives = FAIL
- Partial completion with clear next steps = FAIL (must retry to complete)

Respond in this exact format:

VERDICT: <PASS or FAIL>
ANALYSIS: <1-3 sentences on what was checked and whether it meets criteria>
FIX: <if FAIL, give the worker concrete, actionable instructions for the retry>
""".trimIndent()

        return try {
            val verifyResult = verifierProvider.complete(verifierPrompt)
            val verdict = verifyResult.lines()
                .find { it.trimStart().startsWith("VERDICT:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()?.uppercase() ?: "PASS"
            val note = buildString {
                val analysis = verifyResult.lines()
                    .find { it.trimStart().startsWith("ANALYSIS:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()
                val fix = verifyResult.lines()
                    .find { it.trimStart().startsWith("FIX:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()
                if (analysis != null) append("问题: $analysis\n")
                if (fix != null) append("修复建议: $fix")
                if (isBlank()) append(verifyResult.take(200))
            }
            (verdict == "PASS") to note
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
        val decomposePrompt = """
You are decomposing a complex task into independent subtasks for parallel execution.

Task: $task

Output a JSON array of subtasks. Each subtask has:
- "id": short kebab-case id
- "desc": what to do (one sentence, actionable)
- "criteria": how to verify success (one sentence, concrete)
- "role": optional worker role (omit for default worker)

Rules:
- Maximum $maxSubtasks subtasks
- Each subtask must be independently executable (no cross-dependencies)
- Order from most critical to least

Output ONLY the JSON array, no other text:
[{"id":"...","desc":"...","criteria":"...","role":"worker"}]
""".trimIndent()

        val decomposeResult = try {
            plannerProvider.complete(decomposePrompt)
        } catch (e: Exception) {
            return emptyList()
        }

        return try {
            val jsonStr = decomposeResult.trim().substringAfter("[").substringBeforeLast("]").let { "[$it]" }
            val array = json.parseToJsonElement(jsonStr)
            (array as? kotlinx.serialization.json.JsonArray)?.mapNotNull { el ->
                val obj = el.jsonObject
                SwarmSubtask(
                    id = (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "task-?",
                    description = (obj["desc"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "?",
                    expectedOutcome = (obj["criteria"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "",
                    role = (obj["role"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "worker"
                )
            }?.take(maxSubtasks) ?: emptyList()
        } catch (e: Exception) {
            // Fallback: 简单行解析
            decomposeResult.lines()
                .filter { it.trimStart().startsWith("-") || it.trimStart().startsWith("*") }
                .take(maxSubtasks)
                .mapIndexed { i, line ->
                    val parts = line.removePrefix("-").removePrefix("*").trim().split("|", limit = 2)
                    SwarmSubtask(
                        id = "task-${i + 1}",
                        description = parts.getOrElse(0) { "Subtask ${i + 1}" }.trim(),
                        expectedOutcome = parts.getOrElse(1) { "" }.trim()
                    )
                }
        }
    }

    // ── 合成器 ─────────────────────────────────────────────────────

    private suspend fun synthesize(
        task: String,
        cards: List<SwarmResultCard>,
        synthesizerProvider: LlmProvider
    ): String {
        val verified = cards.count { it.status == SwarmSubtaskStatus.VERIFIED }
        val failed = cards.count { it.status == SwarmSubtaskStatus.FAILED }
        val parts = cards.joinToString("\n") { card ->
            "${card.icon} ${card.subtaskId}: ${card.summary.take(300)}"
        }
        val synthesisPrompt = """
Synthesize the following parallel-worker results into a clear, structured final report.

Original task: $task
Subtask results ($verified verified, $failed failed of ${cards.size}):

$parts

Provide a concise summary with:
1. What was accomplished
2. Key findings or outputs
3. Any remaining issues (if $failed > 0)
""".trimIndent()

        return try {
            synthesizerProvider.complete(synthesisPrompt)
        } catch (e: Exception) {
            // 合成不可用 — 拼接卡片兜底
            parts
        }
    }

    // ── 角色解析 ───────────────────────────────────────────────────

    /** 角色 → provider: 指定角色 → 缺省 "worker" → 引擎主 provider。 */
    private fun providerFor(role: String, roles: Map<String, LlmProvider>): LlmProvider =
        roles[role] ?: roles["worker"] ?: agentEngine.getLlmProvider()

    /** Andon 重派角色: 优先 roles["worker.alt"] (可不同模型), 无则原角色。 */
    private fun retryRoleFor(role: String, roles: Map<String, LlmProvider>): String =
        if ("worker.alt" in roles) "worker.alt" else role
}

/** Worker 循环返回值 — 协调器只消费此结构, 不看 Worker 内部日志。 */
private data class WorkerOutcome(
    val answer: String,
    val stepsUsed: Int,
    val tokensUsed: Long,
    val error: String? = null,
    val budgetExhausted: Boolean = false
)
