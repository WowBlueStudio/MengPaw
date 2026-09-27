// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import kotlinx.serialization.Serializable

/**
 * Runtime state for an active Goal-mode execution session.
 * Ported & adapted from QwenPaw GoalSession architecture.
 *
 * @Serializable: 支持 [GoalSessionStore] 持久化到磁盘, 实现跨会话续跑 (P2-4)。
 */
@Serializable
data class GoalSession(
    val goal: String,
    var active: Boolean = true,
    var iteration: Int = 0,
    var maxIterations: Int = 20,
    var maxTokens: Int = 300_000,
    var tokensUsed: Int = 0,
    var lastVerdict: String = "",
    var lastFeedback: String = "",
    // ── 断点续跑扩展字段 (全部带默认值, 旧档缺字段照常解码) ──────────────
    /** 所属模式: [MODE_GOAL] / [MODE_RALPH] / [MODE_FLEET] (SWARM 看板走 [SwarmProgress])。 */
    val mode: String = MODE_GOAL,
    /** 所属 Agent 名 — 决定恢复入口的落盘文件 (`{CONFIG}/模式进度/{agent}/...`)。 */
    val agentName: String = "",
    /** Ralph 模式已完成的轮次序号 (0 = 未开始); GOAL 模式恒为 0 (用 [iteration])。 */
    var round: Int = 0,
    /** Ralph/GOAL 共用的已消耗预算 (Ralph 记累计 tokens, GOAL 与 [tokensUsed] 同值)。 */
    var tokensConsumed: Long = 0L,
    /** 最近一次更新时间 (epoch millis) — 供看板与僵尸判定。 */
    var updatedAt: Long = 0L
) {
    /**
     * 剩余 token 预算 = 总额 − 已消耗。恢复后必须连续 (不是总额),
     * 故一律由本属性派生, 不额外落盘"剩余"字段 (避免两个真相)。
     */
    val tokenBudgetRemaining: Int get() = (maxTokens - tokensUsed).coerceAtLeast(0)

    /** 剩余轮次 = 上限 − 已完成轮次 (GOAL 模式)。 */
    val iterationsRemaining: Int get() = (maxIterations - iteration).coerceAtLeast(0)

    companion object {
        const val MODE_GOAL = "goal"
        const val MODE_RALPH = "ralph"
        const val MODE_FLEET = "fleet"

        /**
         * 构造 GOAL 进度快照 (工作流 C) — 供执行器按 Agent 归位进度档
         * (`{CONFIG}/模式进度/{agent}/goal.json`)。属性名与 [GoalSession] 构造参数一致,
         * 便于调用方用 `copy` 载入存档续跑。
         */
        fun goalProgress(
            goal: String,
            agentName: String,
            iteration: Int,
            maxIterations: Int,
            tokensUsed: Int,
            maxTokens: Int,
            lastVerdict: String,
            lastFeedback: String
        ): GoalSession = GoalSession(
            goal = goal, active = true, iteration = iteration,
            maxIterations = maxIterations, maxTokens = maxTokens,
            tokensUsed = tokensUsed, lastVerdict = lastVerdict, lastFeedback = lastFeedback,
            mode = MODE_GOAL, agentName = agentName,
            tokensConsumed = tokensUsed.toLong(), updatedAt = 0L
        )
    }
}

/**
 * LLM-based goal completion evaluator — the core RubricGate innovation.
 *
 * After each goal turn, calls the LLM to evaluate whether the goal is complete.
 * This replaces simple step-count limits with intelligent completion detection.
 */
class RubricEvaluator(private val evaluatorPrompt: String = DEFAULT_RUBRIC_PROMPT) {

    /** Build the evaluation prompt to send to the LLM. Used by AgentEngine.runWithGoal(). */
    fun buildPrompt(goal: String, output: String): String =
        evaluatorPrompt.replace("{goal}", goal).replace("{output}", output.take(3000))

    companion object {
        val DEFAULT_RUBRIC_PROMPT = """
判断以下目标的完成状态, 并检查是否偏离目标。

目标: {goal}

Agent 执行结果:
{output}

回答 (三选一, 可加一句简短说明):
- YES — 目标已完成
- NO — 未完成或部分完成, 但仍在目标范围内, 继续执行
- OFFTRACK — 已偏离原目标 (执行了与目标无关的操作, 或擅自改变/扩展任务范围)

只回答 YES / NO / OFFTRACK。
""".trimIndent()
    }
}

enum class RubricVerdict {
    SATISFIED,
    NEEDS_REVISION,
    FAILED
}
