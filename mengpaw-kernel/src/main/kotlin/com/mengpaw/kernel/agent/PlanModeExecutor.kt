// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.error.ErrorType
import com.mengpaw.kernel.security.Sanitizer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Plan-mode executor: structured plan generation and step-by-step execution.
 *
 * Decomposes a task into a step-by-step plan, then executes each step
 * sequentially using a dedicated session per step.
 */
class PlanModeExecutor(
    private val agentEngine: AgentEngine,
    private val pipelineManager: PipelineManager,
    private val sessionManager: com.mengpaw.kernel.session.SessionManager,
    private val promptEngine: com.mengpaw.kernel.llm.PromptEngine
) {
    /**
     * Run a task using structured plan decomposition and execution.
     *
     * @param task the task description
     * @param maxStepsPerPlanStep maximum ReAct steps per plan step
     * @param onStep optional step callback for progress tracking
     * @return the execution summary
     */
    suspend fun runWithPlan(
        task: String, maxStepsPerPlanStep: Int = 5,
        onStep: ((AgentEngine.TraceStep) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null
    ): String {
        val llmProvider = agentEngine.getLlmProvider()

        agentEngine.updateAgentState(AgentState.Running(task, 0, 0))
        agentEngine.updateAgentOutput("")

        val plan = generatePlan(task, llmProvider)
        if (plan.steps.isEmpty()) {
            val msg = localizedError("no_plan", task, agentEngine.agentLanguage)
            agentEngine.updateAgentState(AgentState.Error(msg))
            return msg
        }

        agentEngine.updateAgentOutput(formatPlanSummary(plan))

        val results = mutableListOf<String>()
        for (step in plan.steps) {
            currentCoroutineContext().ensureActive()  // 取消契约: stop() 后立即中断剩余步骤
            step.status = PlanStepStatus.RUNNING
            agentEngine.updateAgentState(AgentState.Running("[Step ${step.index + 1}/${plan.totalSteps}] ${step.description}", step.index + 1, plan.totalSteps))
            try {
                val stepResult = executePlanStep(step, maxStepsPerPlanStep, llmProvider, onDelta)
                results.add("[OK] Step ${step.index + 1}: ${stepResult}")
                step.status = PlanStepStatus.COMPLETED
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // 取消契约: 用户 stop() 不吞成步骤失败 (同 MissionModeExecutor 先例)
            } catch (e: Exception) {
                ErrorCollector.report(ErrorType.AGENT_CRASH, "PlanModeExecutor",
                    "Step ${step.index + 1}: ${step.description}", throwable = e, agentName = agentEngine.agentName)
                results.add("[FAIL] Step ${step.index + 1}: ${e.message}")
                step.status = PlanStepStatus.FAILED
            }
            agentEngine.updateAgentOutput("${results.joinToString("\n")}\nProgress: ${plan.completedSteps}/${plan.totalSteps} steps done")
        }

        val summary = buildString {
            appendLine("=== Task Plan Execution Complete ===")
            appendLine("Task: ${plan.task}")
            appendLine("Steps: ${plan.completedSteps}/${plan.totalSteps} completed")
            appendLine()
            results.forEach { appendLine(it) }
            val failed = plan.steps.filter { it.status == PlanStepStatus.FAILED }
            if (failed.isNotEmpty()) {
                appendLine()
                appendLine("WARNING: ${failed.size} step(s) failed:")
                failed.forEach { appendLine("  - ${it.description}") }
            }
        }

        agentEngine.updateAgentState(AgentState.Finished(summary))
        return summary
    }

    /** Generate a plan by asking the LLM to decompose the task. */
    suspend fun generatePlan(task: String, llmProvider: com.mengpaw.kernel.llm.LlmProvider): TaskPlan {
        val planPrompt = listOf(mapOf("role" to "user", "content" to """
                Decompose the following task into a step-by-step execution plan.
                Your response must use ONLY the following format, one step per line:

                STEP <N>: <description> | ACTION: <cli-command> | EXPECT: <expected outcome>

                Rules:
                - Number steps starting from 1
                - Each ACTION must be a single CLI command (e.g. fs.cat /path)
                - Keep the total to 3-7 steps
                - Do NOT include any other text before or after the plan

                Task: $task
            """.trimIndent()))
        val response = llmProvider.completeWithMessages(planPrompt)
        return parsePlan(task, response)
    }

    /** Parse an LLM plan response into a [TaskPlan]. */
    private fun parsePlan(task: String, text: String): TaskPlan {
        val stepRegex = Regex("""STEP\s*(\d+)\s*:\s*(.+?)\s*\|\s*ACTION\s*:\s*(.+?)\s*\|\s*EXPECT\s*:\s*(.+)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        val steps = stepRegex.findAll(text).map { match ->
            val (num, desc, action, expected) = match.destructured
            PlanStep(index = num.toIntOrNull() ?: 0, description = desc.trim(), action = action.trim(), expectedOutcome = expected.trim())
        }.toList().sortedBy { it.index }
        return TaskPlan(task = task, steps = steps.mapIndexed { i, s -> s.copy(index = i) })
    }

    /** Execute a single plan step in a dedicated session. */
    private suspend fun executePlanStep(
        step: PlanStep, maxSteps: Int,
        llmProvider: com.mengpaw.kernel.llm.LlmProvider,
        onDelta: ((String) -> Unit)? = null
    ): String {
        val stepSession = sessionManager.createSession("PlanStep: ${step.description}")
        val context = ExecutionContext(sessionId = stepSession.id)
        sessionManager.addMessage(stepSession.id, com.mengpaw.kernel.session.Message("system",
            "Execute this single step: ${step.description}\nPlanned action: ${step.action}\nExpected outcome: ${step.expectedOutcome}"))
        for (iteration in 0 until maxSteps) {
            val conversation = agentEngine.buildConversation(stepSession.id)
            // v0.28.4: 步骤执行 LLM 调用流式化 (onDelta 透传)
            val llmResponse = if (onDelta != null) {
                llmProvider.completeStreamingWithMessages(conversation, onDelta)
            } else {
                llmProvider.completeWithMessages(conversation)
            }
            val sanitized = Sanitizer.sanitize(llmResponse)
            sessionManager.addMessage(stepSession.id, com.mengpaw.kernel.session.Message("assistant", sanitized))
            val parsed = promptEngine.parse(sanitized)
            if (parsed.isFinal) return parsed.thought
            if (parsed.action != null) {
                val cmd = "${parsed.action.name} ${parsed.action.parameters.values.joinToString(" ")}"
                val result = parsed.action.paramFormatError()?.let {
                    ExecutionResult.fail(it, errorCode = ErrorCodes.PARAM_FORMAT_ERROR)
                } ?: pipelineManager.buildPipeline().execute(cmd, context)
                val observation = if (result.success) result.output
                    else (result.errorCode?.let { "Error [$it]: ${result.error}" } ?: "Error: ${result.error}")
                sessionManager.addMessage(stepSession.id, com.mengpaw.kernel.session.Message("assistant", "Command: $cmd\nResult: $observation"))
            }
        }
        return "Step completed (max iterations reached): ${step.description}"
    }

    /** Format a plan summary for display. */
    fun formatPlanSummary(plan: TaskPlan): String = buildString {
        appendLine("=== Task Plan ===")
        appendLine("Task: ${plan.task}")
        appendLine("Steps: ${plan.totalSteps}")
        plan.steps.forEach { step ->
            appendLine("  ${step.index + 1}. ${step.description}")
            appendLine("     Action: ${step.action}")
            appendLine("     Expect: ${step.expectedOutcome}")
        }
    }
}
