// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.agent.GoalSession
import com.mengpaw.kernel.agent.GoalSessionStore
import com.mengpaw.kernel.agent.SwarmBudget
import com.mengpaw.kernel.agent.SwarmSubtask
import com.mengpaw.kernel.llm.LlmProvider
import java.io.File

/**
 * Ralph 风格串行 fresh-agent 迭代 (P2-5) — 参照 DSH 的 `dsh-tool-ralph`:
 *
 * 同一个不可变目标依次交给多个"全新子 agent" (每轮新建独立会话, 无父对话/先前上下文累积),
 * 每轮只注入「目标 + 上一轮结构化交接」, 用共享工作区作为长期记忆。相比 Goal-mode
 * (持久会话累积上下文), 本模式每次以干净视角重试, 对抗早期错误固化与上下文污染;
 * 相比 Swarm (并行 fan-out), 本模式是串行且带交接的迭代。
 *
 * 完成判定由 LLM 评估 (worker 报告式, RubricGate 二态)。某轮硬错误不静默重试,
 * 而是把错误作为交接反馈让下一轮换新视角重试; 到 maxRounds 仍无完成 → INCOMPLETE。
 * 复用 [SwarmWorkerRunner] 的零待命独立会话循环 (不写 _state/conversationSessionId),
 * 不会扰动主对话。
 *
 * ── 断点续跑 (工作流 C) ────────────────────────────────────────────
 * 落盘复用 [GoalSessionStore] (与 GOAL/FLEET 同一套进度库, 不做第二状态源),
 * 文件: `{CONFIG}/模式进度/{agent}/ralph.json`。
 *
 * - [GoalSession.round] = 已完成的轮次序号 (看板进度);
 * - [GoalSession.tokensConsumed] = 已完成轮次的累计 tokens;
 * - [GoalSession.iteration] = 看板预算闸已消耗步数 (`SwarmBudget`) — Ralph 不按轮次迭代,
 *   复用该字段承载步数预算, 保证重启后剩余步数 = 总额 − 已消耗;
 * - [GoalSession.lastFeedback] = 上一轮的交接文本 (续跑时注入下一轮)。
 *
 * 续跑语义: 同一个 `objective` 再次调用且 [sessionFile] 指向同一档 → 从
 * `round + 1` 轮继续 (已完成轮次不重做, worker 工具副作用不可回滚), 且预算连续。
 * 若中途被取消 (CancellationException), 存档保留; 走到终态 (COMPLETE/INCOMPLETE/BLOCKED)
 * 则清档, 防误续跑已终结的迭代。
 */
class RalphRunner(private val engine: AgentEngine) {

    private val workerRunner = SwarmWorkerRunner(engine)

    /** Ralph 运行终态。 */
    enum class RalphStatus { COMPLETE, INCOMPLETE, BLOCKED }

    /** Ralph 运行结果 — 协调方只消费此结构。 */
    data class RalphOutcome(
        val status: RalphStatus,
        val finalAnswer: String,
        val roundsUsed: Int,
        val tokensUsed: Long
    )

    /**
     * 运行一轮 Ralph 迭代。
     * @param objective 不可变目标 (每轮原样交给全新 agent)
     * @param provider 使用的 LLM provider
     * @param maxRounds 最大轮数 (上限)
     * @param maxStepsPerRound 每轮 worker 的最大 ReAct 步数
     * @param onStep 进度回调 (透传给 worker)
     * @param sessionFile 进度档文件 (工作流 C): 非 null 时每轮结束原子落盘, 且若已存在
     *   同一 objective 的存档则从下一轮续跑 (预算连续)。缺省时不落盘 (行为同改造前)。
     */
    suspend fun run(
        objective: String,
        provider: LlmProvider,
        maxRounds: Int = 3,
        maxStepsPerRound: Int = 20,
        onStep: ((AgentEngine.TraceStep) -> Unit)? = null,
        sessionFile: File? = null
    ): RalphOutcome {
        val rounds = maxRounds.coerceAtLeast(1)
        val steps = maxStepsPerRound.coerceAtLeast(1)
        val session = loadSession(sessionFile, objective)
        val firstRound = session?.round?.plus(1)?.coerceAtLeast(1) ?: 1
        val totalSteps = session?.maxIterations?.takeIf { it > 0 } ?: (rounds * steps)
        val budget = SwarmBudget.restore(totalSteps, session?.iteration ?: 0)
        var handoff = session?.lastFeedback.orEmpty()
        var tokens = session?.tokensConsumed ?: 0L
        var round = firstRound

        // 存档轮次已达/超过本次上限 → 无剩余轮次可跑, 清档并按 INCOMPLETE 归还 (不空转)
        if (round > rounds) {
            clearSession(sessionFile)
            return RalphOutcome(RalphStatus.INCOMPLETE, handoff, 0, tokens)
        }

        // 预算恢复闸 (工作流 C): 存档已把步数预算吃满 → 不得再新开轮次。
        // 缺这道闸时每次重启都会白烧一轮 (GoalResume/RalphResume 实测暴露)。
        if (budget.remaining <= 0) {
            clearSession(sessionFile)
            return RalphOutcome(RalphStatus.INCOMPLETE, handoff, 0, tokens)
        }

        while (round <= rounds) {
            val subtask = SwarmSubtask(id = "ralph-$round", description = objective, role = "ralph")
            val outcome = workerRunner.runWorker(subtask, provider, steps, budget, handoff, onStep)
            tokens += outcome.tokensUsed
            handoff = outcome.error ?: outcome.answer.take(MAX_HANDOFF)
            persist(sessionFile, objective, round, tokens, budget, handoff)

            if (outcome.error != null) {
                // 本轮受阻: 把错误作为交接让下一轮换视角重试; 无更多轮次 → BLOCKED
                if (round == rounds) {
                    clearSession(sessionFile)
                    return RalphOutcome(RalphStatus.BLOCKED, outcome.answer, round - firstRound + 1, tokens)
                }
                round++
                continue
            }
            // 完成判定: LLM 评估 (worker 报告式)。保守失败返回 false → 继续下一轮。
            if (evaluateComplete(objective, outcome.answer, provider)) {
                clearSession(sessionFile)
                return RalphOutcome(RalphStatus.COMPLETE, outcome.answer, round - firstRound + 1, tokens)
            }
            round++
        }
        clearSession(sessionFile)
        return RalphOutcome(RalphStatus.INCOMPLETE, handoff, rounds - firstRound + 1, tokens)
    }

    // ── 进度落盘 (工作流 C) ────────────────────────────────────────

    /** 读取可续跑的存档: 文件不存在/损坏/objective 不符/已跑满轮次 → null (全新迭代)。 */
    private fun loadSession(sessionFile: File?, objective: String): GoalSession? {
        if (sessionFile == null) return null
        val loaded = GoalSessionStore.load(sessionFile) ?: return null
        return loaded.takeIf { it.goal == objective && it.mode == GoalSession.MODE_RALPH && it.active }
    }

    /** 每轮结束落盘: 已完成轮次 (round)、累计 tokens、看板预算已消耗步数 (iteration)。 */
    private fun persist(
        sessionFile: File?,
        objective: String,
        round: Int,
        tokens: Long,
        budget: SwarmBudget,
        handoff: String
    ) {
        if (sessionFile == null) return
        GoalSessionStore.save(
            GoalSession(
                goal = objective,
                active = true,
                iteration = budget.consumedSteps,
                maxIterations = budget.maxSteps,
                maxTokens = 0,
                tokensUsed = tokens.toInt(),
                lastVerdict = "",
                lastFeedback = handoff,
                mode = GoalSession.MODE_RALPH,
                agentName = engine.agentName,
                round = round,
                tokensConsumed = tokens,
                updatedAt = System.currentTimeMillis()
            ),
            sessionFile
        )
    }

    /** 终态清档 (完成/受阻/轮次耗尽后不应再被续跑)。 */
    private fun clearSession(sessionFile: File?) {
        sessionFile?.let { GoalSessionStore.clear(it) }
    }

    /**
     * LLM 判定目标是否已达成。只接受以 YES 开头的答复; 调用失败/非 YES 保守返回 false。
     */
    private suspend fun evaluateComplete(objective: String, answer: String, provider: LlmProvider): Boolean {
        return try {
            val prompt = "目标: $objective\n\nAgent 本轮执行结果:\n${answer.take(2000)}\n\n" +
                "该目标是否已达成? 只回答 YES 或 NO。"
            provider.complete(prompt).trim().uppercase().startsWith("YES")
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        /** 交接文本上限 — 防止上轮结果撑爆下一轮上下文。 */
        const val MAX_HANDOFF = 800
    }
}
