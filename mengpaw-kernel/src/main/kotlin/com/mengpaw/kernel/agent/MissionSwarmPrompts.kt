// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Mission / Swarm 共用的 LLM 提示词与解析逻辑（两执行器的拆解/验证/合成/重试
 * 提示词原为逐字复制的双份，提取后单一事实源）。
 */

/** 拆解后的子任务规格（JSON 或行解析两种来源的公共中间结构）。 */
data class SubtaskSpec(
    val id: String,
    val desc: String,
    val criteria: String,
    /** Swarm 专属: worker 角色 (混合模型用); Mission 恒为 null。 */
    val role: String? = null
)

object MissionSwarmPrompts {

    /** 拆解提示词 — withRole=true 供 Swarm（角色混合模型），Mission 用 false。 */
    fun buildDecomposePrompt(task: String, maxSubtasks: Int, withRole: Boolean = false): String = buildString {
        append("You are decomposing a complex task into independent subtasks for parallel execution.\n\n")
        append("Task: $task\n\n")
        append("Output a JSON array of subtasks. Each subtask has:\n")
        append("- \"id\": short kebab-case id\n")
        append("- \"desc\": what to do (one sentence, actionable)\n")
        append("- \"criteria\": how to verify success (one sentence, concrete)\n")
        if (withRole) {
            append("- \"role\": optional worker role (omit for default worker)\n")
        }
        append("\nRules:\n")
        append("- Maximum $maxSubtasks subtasks\n")
        append("- Each subtask must be independently executable (no cross-dependencies)\n")
        append("- Order from most critical to least\n\n")
        append("Output ONLY the JSON array, no other text:\n")
        if (withRole) {
            append("[{\"id\":\"...\",\"desc\":\"...\",\"criteria\":\"...\",\"role\":\"worker\"}]")
        } else {
            append("[{\"id\":\"...\",\"desc\":\"...\",\"criteria\":\"...\"}]")
        }
    }

    /**
     * 解析拆解结果 — JSON 优先（substringAfter("[") 粗切容错），失败回退行解析
     * （"- " / "* " 开头，描述 | 验收标准）。
     */
    fun parseSubtasks(raw: String, maxSubtasks: Int): List<SubtaskSpec> = try {
        val json = Json { ignoreUnknownKeys = true }
        val jsonStr = raw.trim().substringAfter("[").substringBeforeLast("]").let { "[$it]" }
        val array = json.parseToJsonElement(jsonStr)
        (array as? JsonArray)?.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            SubtaskSpec(
                id = (obj["id"] as? JsonPrimitive)?.content ?: "task-?",
                desc = (obj["desc"] as? JsonPrimitive)?.content ?: "?",
                criteria = (obj["criteria"] as? JsonPrimitive)?.content ?: "",
                role = (obj["role"] as? JsonPrimitive)?.content
            )
        }?.take(maxSubtasks) ?: emptyList()
    } catch (e: Exception) {
        raw.lines()
            .filter { it.trimStart().startsWith("-") || it.trimStart().startsWith("*") }
            .take(maxSubtasks)
            .mapIndexed { i, line ->
                val parts = line.removePrefix("-").removePrefix("*").trim().split("|", limit = 2)
                SubtaskSpec(
                    id = "task-${i + 1}",
                    desc = parts.getOrElse(0) { "Subtask ${i + 1}" }.trim(),
                    criteria = parts.getOrElse(1) { "" }.trim()
                )
            }
    }

    /** Verifier 提示词 — FIX 行取详细版（Mission 原文）。 */
    fun buildVerifierPrompt(criteria: String, output: String): String = """
You are a strict quality verifier. Review the worker agent's output against the success criteria.

**Success criteria**: ${criteria.ifBlank { "Complete the task" }}

**Worker output**:
${output.take(2000)}

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

    /**
     * 解析 Verifier 输出。
     * @return Triple(passed, note, fix) — 找不到 VERDICT 默认 PASS；fix 为原始 FIX 行。
     */
    fun parseVerifierVerdict(raw: String): Triple<Boolean, String, String> {
        val lines = raw.lines()
        val verdict = lines.find { it.trimStart().startsWith("VERDICT:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.uppercase() ?: "PASS"
        val note = lines.find { it.trimStart().startsWith("ANALYSIS:", ignoreCase = true) }
            ?.substringAfter(":")?.trim() ?: "PASS"
        val fix = lines.find { it.trimStart().startsWith("FIX:", ignoreCase = true) }
            ?.substringAfter(":")?.trim() ?: ""
        return Triple(verdict == "PASS", note, fix)
    }

    /** 合成提示词 — mode 为 "Mission" / "火种模式" 等显示名。 */
    fun buildSynthesisPrompt(task: String, mode: String, verified: Int, failed: Int, total: Int, parts: String): String = """
Synthesize the following $mode results into a clear, structured final report.

Original task: $task
Subtask results ($verified verified, $failed failed of $total):

$parts

Provide a concise summary with:
1. What was accomplished
2. Key findings or outputs
3. Any remaining issues (if $failed > 0)
""".trimIndent()

    /** 重试反馈注入 — 质量审查反馈（第 N 次）。 */
    fun buildRetryPrompt(desc: String, retryCount: Int, feedback: String): String = buildString {
        append(desc)
        append("\n\n## 质量审查反馈（第 $retryCount 次）\n")
        append("上一轮未通过验证，请根据以下审查意见改进：\n\n")
        append(feedback)
        append("\n\n请修正上述问题后重新执行。原任务：$desc")
    }
}
