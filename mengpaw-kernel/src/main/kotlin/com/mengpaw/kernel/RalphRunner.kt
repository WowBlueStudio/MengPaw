// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.agent.SwarmBudget
import com.mengpaw.kernel.agent.SwarmSubtask
import com.mengpaw.kernel.llm.LlmProvider

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
     */
    suspend fun run(
        objective: String,
        provider: LlmProvider,
        maxRounds: Int = 3,
        maxStepsPerRound: Int = 20,
        onStep: ((AgentEngine.TraceStep) -> Unit)? = null
    ): RalphOutcome {
        val rounds = maxRounds.coerceAtLeast(1)
        val budget = SwarmBudget(rounds * maxStepsPerRound)
        var handoff = ""
        var tokens = 0L
        for (round in 1..rounds) {
            val subtask = SwarmSubtask(id = "ralph-$round", description = objective, role = "ralph")
            val outcome = workerRunner.runWorker(subtask, provider, maxStepsPerRound, budget, handoff, onStep)
            tokens += outcome.tokensUsed
            if (outcome.error != null) {
                // 本轮受阻: 把错误作为交接让下一轮换视角重试; 无更多轮次 → BLOCKED
                handoff = outcome.error
                if (round == rounds) return RalphOutcome(RalphStatus.BLOCKED, outcome.answer, round, tokens)
                continue
            }
            // 完成判定: LLM 评估 (worker 报告式)。保守失败返回 false → 继续下一轮。
            if (evaluateComplete(objective, outcome.answer, provider)) {
                return RalphOutcome(RalphStatus.COMPLETE, outcome.answer, round, tokens)
            }
            handoff = outcome.answer.take(MAX_HANDOFF)
        }
        return RalphOutcome(RalphStatus.INCOMPLETE, handoff, rounds, tokens)
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
