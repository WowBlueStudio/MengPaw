// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.hermes

import com.mengpaw.kernel.llm.LlmProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 路由推荐结果。 */
data class RouteResult(
    val agent: String,
    val reason: String,
    val confidence: Double
)

/**
 * 部落 LLM 能力路由 — 基于成员角色/技能 + 历史成功率，用 LLM 推荐最佳 Agent。
 */
object TribeRouter {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 为任务推荐最佳 Agent。
     * @param taskDesc 任务描述
     * @param members 团队成员
     * @param history Kanban 历史（成功率统计）
     * @param llm LLM 提供者
     * @return 推荐结果；失败时兜底为成功率最高的成员
     */
    suspend fun route(
        taskDesc: String,
        members: List<TeamMember>,
        history: List<TribeKanbanBoard.KanbanTaskLite>,
        llm: LlmProvider
    ): RouteResult {
        if (members.isEmpty()) return RouteResult("", "团队为空", 0.0)

        // 1. 历史成功率: COMPLETED / (COMPLETED+FAILED+TIMED_OUT)
        val stats = members.associate { m ->
            val tasks = history.filter { it.toAgent == m.id || it.toAgent == m.name }
            val completed = tasks.count { it.status == TaskStatus.COMPLETED }
            val attempts = tasks.count { it.status in setOf(TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.TIMED_OUT) }
            m.id to (if (attempts > 0) completed.toDouble() / attempts else 0.0)
        }

        // 2. 构造 LLM prompt
        val memberLines = members.joinToString("\n") { m ->
            val rate = stats[m.id] ?: 0.0
            "- ${m.name}（角色: ${m.role}，擅长: ${m.skills}，历史成功率: ${(rate * 100).toInt()}%）"
        }
        val prompt = """
            你是任务调度专家。请为以下任务推荐最合适的 Agent。

            任务: $taskDesc

            可用 Agent:
            $memberLines

            输出 JSON（只输出 JSON，不要其他文字）:
            {"agent":"<成员名>","reason":"<一句话理由>","confidence":0.0-1.0}
        """.trimIndent()

        // 3. 调用 LLM 并解析
        val llmResult = runCatching { llm.complete(prompt) }.getOrNull()
        val parsed = llmResult?.let { parseJson(it) }
        if (parsed != null) {
            val member = members.find { it.name == parsed.agent || it.id == parsed.agent }
            if (member != null) return parsed.copy(agent = member.id)
        }

        // 4. 兜底: 成功率最高且非零的成员；再兜底: 第一个成员
        val best = members.maxByOrNull { stats[it.id] ?: 0.0 }
        return RouteResult(best?.id ?: "", "历史成功率最高（LLM 解析失败兜底）", stats[best?.id] ?: 0.0)
    }

    private fun parseJson(text: String): RouteResult? {
        return try {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val obj = json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
            RouteResult(
                agent = obj["agent"]?.jsonPrimitive?.content ?: return null,
                reason = obj["reason"]?.jsonPrimitive?.content ?: "",
                confidence = obj["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            )
        } catch (_: Exception) { null }
    }
}
