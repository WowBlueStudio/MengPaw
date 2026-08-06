// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import kotlinx.serialization.json.*

/**
 * ReAct 响应解析器 — 拆自 PromptEngine (400 行文件拆分)。
 * 纯函数 (无状态), 经 [PromptEngine.parse] 委托。
 */
internal class ReActParser {

    /**
     * Parse LLM output into a structured ReAct response.
     *
     * Tolerant parsing strategy:
     * 1. If "Final Answer:" present (after last Action) → final answer
     * 2. If "Action:" present with valid command → execute action
     * 3. If NEITHER marker present (non-ReAct model / natural response) → treat as final answer
     * 4. If "Thought:" only (no action, no final) → also treat as final answer
     */
    internal fun parse(text: String): ReActResponse {
        val normalized = text.trim()

        // Find all marker positions (case-insensitive, Chinese/English colon)
        val finalLocs = Regex("(?i)final answer[:：]", RegexOption.MULTILINE).findAll(normalized).map { it.range.first }.toList()
        // Action 只认行首（P2 修复: 全文匹配会误切 Action Input JSON 值内的 "action:" 字样）
        val actionLocs = Regex("(?i)(?m)^\\s*action[:：]").findAll(normalized).map { it.range.first }.toList()

        // ── Rule 1: Final Answer (must appear after last Action, or with no Action at all) ──
        // 注: 多个 Action + Final Answer 属于"模型要并行执行"形态 — 让位给 Rule 2 执行，
        //     Final Answer 内容留待模型下轮（看到 Observation 后）重新总结
        if (finalLocs.isNotEmpty() && actionLocs.size < 2) {
            val lastFinalPos = finalLocs.last()
            val lastActionPos = actionLocs.lastOrNull() ?: -1
            if (lastFinalPos > lastActionPos) {
                val finalRegex = Regex("(?i)final answer[:：]\\s*(.+)", RegexOption.DOT_MATCHES_ALL)
                val finalMatch = finalRegex.find(normalized.substring(lastFinalPos))
                if (finalMatch != null) {
                    return ReActResponse(finalMatch.groupValues[1].trim(), null, isFinal = true)
                }
            }
        }

        // ── Rule 2: Parse Action(s) — 一次输出可含多个 Action（并行执行）──
        val actionRegex = Regex("(?i)(?m)^\\s*action[:：]\\s*(\\S+)")
        val inputRegex = Regex(
            "(?i)action input[:：]\\s*(.+?)(?=Thought[:：]|Action[:：]|Final Answer[:：]|$)",
            RegexOption.DOT_MATCHES_ALL
        )

        // 用全部 Action 位置切段：每段起点=Action 位置，终点=下一个 Action 位置或文本尾
        // 段内 Final Answer 内容由 inputRegex 的 lookahead 排除（Action 段永远以 Action 开头）
        val actions = actionLocs.mapIndexedNotNull { i, pos ->
            val segmentStart = pos
            val segmentEnd = actionLocs.getOrNull(i + 1) ?: normalized.length
            val segment = normalized.substring(segmentStart, segmentEnd)
            val name = actionRegex.find(segment)?.groupValues?.get(1)?.trim() ?: return@mapIndexedNotNull null
            // Parse Action Input (tolerant JSON parsing)
            val inputText = inputRegex.find(segment)?.groupValues?.get(1)?.trim().orEmpty()
            // FIX(自检报告 P1-3): 无参命令两形态（省略 Action Input 行 / 显式 `Action Input: {}`）
            // 统一映射为空参数 — 此前默认 "{}" 经 raw 兜底被 paramFormatError 的 looksLikeJson 误拦,
            // 且字面 "{}" 会作为真实参数传入命令 (如 agent.memory {} 搜关键词 "{}")。
            val params = when {
                inputText.isBlank() || inputText == "{}" -> emptyMap()
                inputText.startsWith("{") && ':' in inputText -> {
                    try {
                        val obj = Json.parseToJsonElement(inputText) as JsonObject
                        obj.mapValues { (it.value as? JsonPrimitive)?.content ?: it.value.toString() }
                    } catch (e: Exception) {
                        mapOf("raw" to inputText)
                    }
                }
                else -> mapOf("raw" to inputText)
            }
            ToolCall(name, params)
        }

        if (actions.isNotEmpty()) {
            val thought = extractThought(normalized)
            return ReActResponse(thought, actions.first(), isFinal = false, actions = actions)
        }

        // ── Rule 3: No "Action:" and no "Final Answer:" → natural language response ──
        // Key distinction:
        //   Explicit "Thought:" without "Action:" → model mid-reasoning → needsContinue
        //   Pure natural language (no markers at all) → model giving answer → isFinal
        if (finalLocs.isEmpty()) {
            val thought = extractThought(normalized)
            val hasExplicitThought = Regex("(?i)thought[:：]").containsMatchIn(normalized)
            if (hasExplicitThought && thought.length > normalized.length / 2) {
                // Model output explicit Thought but no Action — ask it to continue
                return ReActResponse(thought, null, isFinal = false, needsContinue = true)
            }
            // Natural language without any ReAct markers — this IS the final answer
            // (Regardless of length. Models often give detailed answers without Final Answer: prefix)
            return ReActResponse(normalized, null, isFinal = true)
        }

        // Fallback (should not reach here with current rules)
        return ReActResponse(normalized.take(200), null, isFinal = true)
    }

    /** Extract Thought content from ReAct-format text, or return truncated beginning. */
    private fun extractThought(normalized: String): String {
        val thoughtRegex = Regex(
            "(?i)thought[:：]\\s*(.+?)(?=Action[:：]|Final Answer[:：]|$)",
            RegexOption.DOT_MATCHES_ALL
        )
        return thoughtRegex.find(normalized)?.groupValues?.get(1)?.trim()
            ?: normalized.take(200)
    }
}
