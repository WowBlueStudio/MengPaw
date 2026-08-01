// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.agent.MissionSubtask
import com.mengpaw.kernel.agent.SubtaskStatus
import com.mengpaw.kernel.security.PromptFirewall

/**
 * Mission-mode executor: decompose -> worker execution -> verification.
 *
 * Uses the LLM to decompose the task, then runs each subtask sequentially
 * with retry+verify (Worker-Verifier pattern) and LLM synthesis.
 *
 * Ported from QwenPaw MissionMode architecture.
 */
class MissionModeExecutor(
    private val agentEngine: AgentEngine
) {
    /**
     * Run a complex task as a mission of decomposed, verified subtasks.
     *
     * @param task the complex task description
     * @param maxSubtasks maximum number of subtasks to decompose into
     * @param maxStepsPerSubtask maximum ReAct steps per subtask
     * @param maxRetriesPerSubtask maximum retries on verification failure
     * @param onStep optional step callback for progress tracking
     * @return the synthesized mission report
     */
    suspend fun runWithMission(
        task: String, maxSubtasks: Int = 5, maxStepsPerSubtask: Int = 12,
        maxRetriesPerSubtask: Int = 2, onStep: ((AgentEngine.TraceStep) -> Unit)? = null
    ): String {
        val llmProvider = agentEngine.getLlmProvider()
        val guardedTask = if (PromptFirewall.checkUserPrompt(task) != null)
            PromptFirewall.wrapWithDefense(task) else task

        // Step 1: Structured decomposition — LLM produces JSON subtask list
        val decomposePrompt = """
You are decomposing a complex task into independent subtasks for parallel execution.

Task: $guardedTask

Output a JSON array of subtasks. Each subtask has:
- "id": short kebab-case id
- "desc": what to do (one sentence, actionable)
- "criteria": how to verify success (one sentence, concrete)

Rules:
- Maximum $maxSubtasks subtasks
- Each subtask must be independently executable (no cross-dependencies)
- Order from most critical to least

Output ONLY the JSON array, no other text:
[{"id":"...","desc":"...","criteria":"..."}]
""".trimIndent()

        val decomposeResult = try {
            llmProvider.complete(decomposePrompt)
        } catch (e: Exception) {
            return agentEngine.run(task, maxStepsPerSubtask * maxSubtasks, onStep)
        }

        // Parse JSON subtasks
        val subtasks = try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val jsonStr = decomposeResult.trim().substringAfter("[").substringBeforeLast("]").let { "[$it]" }
            val array = json.parseToJsonElement(jsonStr)
            (array as? kotlinx.serialization.json.JsonArray)?.map { el ->
                val obj = el as? kotlinx.serialization.json.JsonObject ?: return@map null
                MissionSubtask(
                    id = (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "task-?",
                    description = (obj["desc"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "?",
                    expectedOutcome = (obj["criteria"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                )
            }?.filterNotNull()?.take(maxSubtasks) ?: emptyList()
        } catch (e: Exception) {
            // Fallback: simple line parsing
            decomposeResult.lines()
                .filter { it.trimStart().startsWith("-") || it.trimStart().startsWith("*") }
                .take(maxSubtasks)
                .mapIndexed { i, line ->
                    val parts = line.removePrefix("-").removePrefix("*").trim().split("|", limit = 2)
                    MissionSubtask(
                        id = "task-${i + 1}",
                        description = parts.getOrElse(0) { "Subtask ${i + 1}" }.trim(),
                        expectedOutcome = parts.getOrElse(1) { "" }.trim()
                    )
                }
        }

        if (subtasks.isEmpty()) {
            return agentEngine.run(task, maxStepsPerSubtask * maxSubtasks, onStep)
        }

        // Update state to show mission progress
        agentEngine.updateAgentState(AgentState.Running("Mission: ${subtasks.size} subtasks", 0, subtasks.size))

        // Step 2: Sequential execution with retry+verify per subtask
        val results = mutableListOf<String>()
        for ((i, subtask) in subtasks.withIndex()) {
            agentEngine.updateAgentState(AgentState.Running("Mission: ${i + 1}/${subtasks.size}", i + 1, subtasks.size))
            val result = executeSubtask(subtask, maxStepsPerSubtask, maxRetriesPerSubtask, llmProvider, onStep)
            results.add(result)
        }

        // Step 3: LLM synthesis of all results
        val verified = subtasks.count { it.status == SubtaskStatus.VERIFIED }
        val failed = subtasks.count { it.status == SubtaskStatus.FAILED }
        val parts = subtasks.joinToString("\n") { st ->
            val icon = when (st.status) {
                SubtaskStatus.VERIFIED -> "✅"
                SubtaskStatus.DONE -> "👍"
                SubtaskStatus.FAILED -> "❌"
                else -> "⬜"
            }
            "$icon ${st.description}: ${st.output.take(300)}"
        }
        val synthesisPrompt = """
Synthesize the following Mission results into a clear, structured final report.

Original task: $guardedTask
Subtask results ($verified verified, $failed failed of ${subtasks.size}):

$parts

Provide a concise summary with:
1. What was accomplished
2. Key findings or outputs
3. Any remaining issues (if $failed > 0)
""".trimIndent()

        val synthesis = try {
            llmProvider.complete(synthesisPrompt)
        } catch (_: Exception) {
            parts
        }

        return buildString {
            appendLine("## Mission: $task")
            appendLine("子任务: ${subtasks.size} | ✅ $verified | 👍 ${subtasks.filter { it.status == SubtaskStatus.DONE }.size} | ❌ $failed")
            appendLine()
            appendLine(synthesis)
        }
    }

    /** Execute a single subtask with verification and retry. */
    private suspend fun executeSubtask(
        subtask: MissionSubtask,
        maxSteps: Int, maxRetries: Int,
        llmProvider: com.mengpaw.kernel.llm.LlmProvider,
        onStep: ((AgentEngine.TraceStep) -> Unit)? = null
    ): String {
        var retries = 0
        var lastVerifierFeedback = ""

        while (retries <= maxRetries) {
            subtask.status = SubtaskStatus.RUNNING

            // Build task prompt — include verifier feedback on retry
            val taskPrompt = if (retries > 0 && lastVerifierFeedback.isNotBlank()) {
                buildString {
                    append(subtask.description)
                    append("\n\n## 质量审查反馈（第 $retries 次）\n")
                    append("上一轮未通过验证，请根据以下审查意见改进：\n\n")
                    append(lastVerifierFeedback)
                    append("\n\n请修正上述问题后重新执行。原任务：${subtask.description}")
                }
            } else {
                subtask.description
            }

            val workerResult = try {
                agentEngine.run(taskPrompt, maxSteps = maxSteps, onStep = onStep)
            } catch (e: Exception) {
                "Error: ${e.message}"
            }

            subtask.output = workerResult
            val isHardError = workerResult.startsWith("Error:") ||
                workerResult.startsWith("已达到最大步数") ||
                workerResult.startsWith("Max steps")

            if (isHardError) {
                lastVerifierFeedback = "Worker execution error: ${workerResult.take(300)}"
                retries++
                continue
            }

            // Strict Verifier (Worker-Verifier pattern)
            val verifierPrompt = """
You are a strict quality verifier. Review the worker agent's output against the success criteria.

**Success criteria**: ${subtask.expectedOutcome.ifBlank { "Complete the task: ${subtask.description}" }}

**Worker output**:
${workerResult.take(2000)}

**Analysis rules**:
- Check if the output actually fulfills the criteria (not just mentions it)
- Check for factual errors, incomplete data, or vague hand-waving
- A "Final Answer" that says "I cannot do this" without trying alternatives = FAIL
- Partial completion with clear next steps = FAIL (must retry to complete)

Respond in this exact format:

VERDICT: <PASS or FAIL>
ANALYSIS: <1-3 sentences on what was checked and whether it meets criteria>
FIX: <if FAIL, give the worker concrete, actionable instructions for the retry. Be specific — name which tool to use, what data to look for, what approach to try differently>
""".trimIndent()

            try {
                val verifyResult = llmProvider.complete(verifierPrompt)
                val verdict = verifyResult.lines()
                    .find { it.trimStart().startsWith("VERDICT:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()?.uppercase() ?: "PASS"

                if (verdict == "PASS") {
                    subtask.status = SubtaskStatus.VERIFIED
                    subtask.verifierNote = verifyResult.lines()
                        .find { it.trimStart().startsWith("ANALYSIS:", ignoreCase = true) }
                        ?.substringAfter(":")?.trim() ?: "PASS"
                    return workerResult
                } else {
                    // FAIL — extract analysis and fix instructions for the worker
                    lastVerifierFeedback = buildString {
                        val analysis = verifyResult.lines()
                            .find { it.trimStart().startsWith("ANALYSIS:", ignoreCase = true) }
                            ?.substringAfter(":")?.trim()
                        val fix = verifyResult.lines()
                            .find { it.trimStart().startsWith("FIX:", ignoreCase = true) }
                            ?.substringAfter(":")?.trim()
                        if (analysis != null) { append("问题: $analysis\n") }
                        if (fix != null) { append("修复建议: $fix") }
                        if (isBlank()) { append(verifyResult.take(300)) }
                    }
                    subtask.verifierNote = "FAIL: ${lastVerifierFeedback.take(150)}"
                    retries++
                }
            } catch (_: Exception) {
                // Verification unavailable — accept result without verification
                subtask.status = SubtaskStatus.DONE
                return workerResult
            }
        }

        subtask.status = SubtaskStatus.FAILED
        return lastVerifierFeedback.ifBlank { subtask.output }
    }
}
