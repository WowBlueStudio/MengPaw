// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

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
 * Decomposed subtask for Mission mode.
 */
data class MissionSubtask(
    val id: String,
    val description: String,
    val expectedOutcome: String = "",
    var status: SubtaskStatus = SubtaskStatus.PENDING,
    var output: String = "",
    var verifierNote: String = ""
)

enum class SubtaskStatus { PENDING, RUNNING, DONE, FAILED, VERIFIED }

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
你是目标完成度评估器。阅读 Agent 的最终输出，判断目标任务是否已完成。

目标: {goal}

Agent 输出:
{output}

请判断：目标是否已完成？
- 如果 Agent 已成功完成目标，回答 YES
- 如果目标尚未完成或只完成了部分，回答 NO

只回答 YES 或 NO，然后简要说明原因。
""".trimIndent()
    }
}

enum class RubricVerdict {
    SATISFIED,
    NEEDS_REVISION,
    FAILED
}
