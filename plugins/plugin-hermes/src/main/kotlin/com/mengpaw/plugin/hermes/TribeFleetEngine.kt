// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.hermes

import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.llm.LlmProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 部落 Fleet 引擎 — LLM 分解 → 并行委派 → LLM 合成。
 *
 * 与 TribePlugin 通过 [delegateFn] 解耦，可独立测试。
 * 并行上限 [MAX_PARALLEL]=4，防止过度并发。
 */
class TribeFleetEngine(
    private val delegateFn: suspend (TribeTask, String, String) -> ExecutionResult
) {
    companion object {
        private const val MAX_PARALLEL = 4
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** LLM 分解出的子任务。 */
    data class FleetSubtask(val id: String, val desc: String, val agent: String = "")

    /**
     * 执行 Fleet 任务。
     * @param task 总任务描述
     * @param members 团队成员
     * @param llm LLM 提供者
     * @param parentTaskId 父任务 ID（嵌套预留）
     * @return Fleet 执行报告（Markdown）
     */
    suspend fun run(
        task: String,
        members: List<TeamMember>,
        llm: LlmProvider?,
        parentTaskId: String? = null
    ): String {
        // ── 1. LLM 分解 ───────────────────────────────────────────
        val subtasks = if (llm != null) decompose(task, members, llm) else emptyList()
        val plan = if (subtasks.isNotEmpty()) subtasks
        else listOf(FleetSubtask("1", task, members.firstOrNull()?.id ?: ""))

        // ── 2-4. 并行委派 + 收集 ──────────────────────────────────
        val semaphore = Semaphore(MAX_PARALLEL)
        val results = coroutineScope {
            plan.map { sub ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val target = members.find { it.id == sub.agent || it.name == sub.agent } ?: members.first()
                        val tribeTask = TribeTask(
                            title = sub.desc.take(200),
                            description = sub.desc,
                            priority = TaskPriority.P1,
                            fromAgent = "fleet",
                            toAgent = target.id,
                            timeoutMs = 90_000L,
                            parentTaskId = parentTaskId,
                            depth = if (parentTaskId != null) 1 else 0
                        )
                        val result = delegateFn(tribeTask, target.id, target.name)
                        Triple(sub, target.name, result)
                    }
                }
            }.awaitAll()
        }

        val succeeded = results.count { it.third.success }
        val failed = results.count { !it.third.success }
        val parts = results.joinToString("\n\n") { (sub, targetName, r) ->
            val icon = if (r.success) "✅" else "❌"
            val body = if (r.success) r.output.take(300) else r.error?.take(200) ?: "执行失败"
            "### $icon 子任务 ${sub.id}（$targetName）\n$body"
        }

        // ── 5. LLM 合成 ───────────────────────────────────────────
        val synthesis = if (llm != null && results.any { it.third.success }) {
            synthesize(task, succeeded, failed, parts, llm)
        } else {
            "共 ${plan.size} 个子任务：$succeeded 成功 / $failed 失败。"
        }

        return """
## Fleet: $task

**统计**: ${plan.size} 个子任务 | ✅ $succeeded 成功 | ❌ $failed 失败

$parts

## 合成报告

$synthesis
        """.trimIndent()
    }

    // ── 分解 ────────────────────────────────────────────────────

    private suspend fun decompose(task: String, members: List<TeamMember>, llm: LlmProvider): List<FleetSubtask> {
        val memberNames = members.joinToString(" ") { it.name }
        val prompt = """
            你是任务分解专家。将以下任务分解为 2-4 个可并行执行的子任务。

            任务: $task
            可用 Agent: $memberNames

            输出 JSON 数组（只输出 JSON，不要其他文字）:
            [{"id":"1","desc":"<子任务描述>","agent":"<最合适的成员名>"}]

            每个子任务的 agent 必须是可用 Agent 中的一员。
        """.trimIndent()

        return try {
            val text = llm.complete(prompt)
            val start = text.indexOf('[')
            val end = text.lastIndexOf(']')
            if (start < 0 || end <= start) return emptyList()
            val arr = json.parseToJsonElement(text.substring(start, end + 1)).jsonArray
            arr.mapNotNull { el ->
                val obj = el.jsonObject
                val desc = obj["desc"]?.jsonPrimitive?.content ?: return@mapNotNull null
                FleetSubtask(
                    id = obj["id"]?.jsonPrimitive?.intOrNull?.toString() ?: "1",
                    desc = desc,
                    agent = obj["agent"]?.jsonPrimitive?.content ?: ""
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    // ── 合成 ────────────────────────────────────────────────────

    private suspend fun synthesize(task: String, succeeded: Int, failed: Int, parts: String, llm: LlmProvider): String {
        val prompt = """
            你是舰队指挥官。汇总以下 Fleet 并行任务的子任务结果（$succeeded 成功 / $failed 失败），
            输出一份面向用户的最终报告，涵盖：总体结论、关键发现、未完成事项。

            原始任务: $task

            子任务结果:
            $parts
        """.trimIndent()
        return runCatching { llm.complete(prompt) }.getOrNull() ?: "合成失败，请查看子任务结果。"
    }
}
