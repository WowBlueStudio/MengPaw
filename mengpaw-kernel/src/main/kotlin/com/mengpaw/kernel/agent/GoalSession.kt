// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

/**
 * Runtime state for an active Goal-mode execution session.
 * Ported & adapted from QwenPaw GoalSession architecture.
 */
data class GoalSession(
    val goal: String,
    var active: Boolean = true,
    var iteration: Int = 0,
    val maxIterations: Int = 20,
    val maxTokens: Int = 300_000,
    var tokensUsed: Int = 0,
    var lastVerdict: String = "",
    var lastFeedback: String = ""
)

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
