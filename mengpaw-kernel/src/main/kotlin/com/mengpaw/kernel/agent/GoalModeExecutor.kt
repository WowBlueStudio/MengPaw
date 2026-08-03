// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.agent.GoalSession
import com.mengpaw.kernel.agent.RubricEvaluator
import com.mengpaw.kernel.security.PromptFirewall

/**
 * Goal-mode execution with RubricGate auto-completion detection.
 *
 * Each turn: inject goal prompt -> run ReAct loop -> evaluate completion via LLM.
 * Stops when RubricGate returns SATISFIED or max iterations exhausted.
 *
 * Ported from QwenPaw GoalMode architecture.
 */
class GoalModeExecutor(
    private val agentEngine: AgentEngine
) {
    /**
     * Run a goal-oriented task with automatic completion detection (RubricGate).
     *
     * @param task the goal description
     * @param maxTurns maximum goal iterations
     * @param maxTokensBudget maximum tokens to spend across all turns
     * @param onStep optional step callback for progress tracking
     * @return the final result string
     */
    suspend fun runWithGoal(
        task: String, maxTurns: Int = 20, maxTokensBudget: Int = 300_000,
        onStep: ((AgentEngine.TraceStep) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null
    ): String {
        val llmProvider = agentEngine.getLlmProvider()
        val guardedTask = if (PromptFirewall.checkUserPrompt(task) != null)
            PromptFirewall.wrapWithDefense(task) else task
        val session = GoalSession(
            goal = guardedTask, maxIterations = maxTurns, maxTokens = maxTokensBudget
        )
        val evaluator = RubricEvaluator()
        val turnResults = mutableListOf<String>()

        for (turn in 0 until maxTurns) {
            if (!session.active) break
            session.iteration = turn + 1

            // Build goal-aware prompt — RubricGate feedback is the only signal needed between turns.
            // Prior turn results are NOT replayed; replaying biases the agent toward repeating old work.
            val goalPrompt = if (turn == 0) {
                "## 目标\n${session.goal}\n\n使用 Thought → Action → Final Answer 格式。自然对话，不要主动汇报进度或回溯历史——除非用户询问。"
            } else {
                "## 目标 (第 ${turn + 1}/$maxTurns 轮)\n${session.goal}\n\n反馈: ${session.lastFeedback.ifEmpty { "无" }}"
            }

            // Run ReAct loop — no prior context injection; RubricGate feedback is sufficient
            val result = agentEngine.runReActLoop(
                task = "$goalPrompt\n\n$guardedTask",
                maxSteps = 50,
                onStep = onStep,
                onDelta = onDelta
            )
            turnResults.add(result)

            // Budget gate: estimate tokens from result length
            session.tokensUsed += result.length / 4  // rough char->token estimate
            if (session.tokensUsed >= maxTokensBudget) {
                session.active = false
                session.lastVerdict = "Token budget exceeded"
                break
            }

            // RubricGate: LLM-based completion evaluation on every turn
            val evalPrompt = evaluator.buildPrompt(session.goal, result)
            try {
                val evalResult = llmProvider.complete(evalPrompt)
                val satisfied = evalResult.trim().uppercase().startsWith("YES")
                if (satisfied) {
                    session.lastVerdict = "SATISFIED"
                    session.active = false
                } else {
                    session.lastVerdict = "NEEDS_REVISION"
                    session.lastFeedback = evalResult.take(200)
                }
            } catch (_: Exception) {
                // LLM eval failed — fall back to heuristic
                if (result.contains("Final Answer:", ignoreCase = true)) {
                    session.lastVerdict = "SATISFIED (heuristic)"
                    session.active = false
                }
            }
        }

        return if (!session.active && session.lastVerdict.startsWith("SATISFIED")) {
            "目标已完成: ${session.goal}\n\n" + turnResults.lastOrNull().orEmpty()
        } else {
            "目标未完成 (${session.iteration}/${maxTurns} 轮): ${session.goal}\n\n最后结果:\n" +
                turnResults.lastOrNull().orEmpty()
        }
    }
}
