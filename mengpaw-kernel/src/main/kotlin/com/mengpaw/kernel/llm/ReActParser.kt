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

        // ── Rule 2b: XML 工具调用转译 (Claude/GPT 原生 <invoke>/<antml:invoke>) ──
        // 模型可能用原生 XML 信封 (Claude Code <tool_calls> / AntML <antml:invoke>) 而非
        // ReAct Action: 语法 — 此前被 Rule 3 当最终答案吞掉: 工具从不执行, 用户只见原始 XML。
        // 转译为 ToolCall 走并行执行链路 — 去重/循环检测/paramFormatError 门卫/超时全部复用。
        val xmlCalls = parseXmlToolCalls(normalized)
        if (xmlCalls.isNotEmpty()) {
            val xmlStart = normalized.indexOf("<tool_calls").let { if (it >= 0) it else normalized.indexOf("<invoke") }
            val thought = if (xmlStart >= 0) normalized.substring(0, xmlStart).trim() else extractThought(normalized)
            return ReActResponse(thought, xmlCalls.first(), isFinal = false, actions = xmlCalls)
        }

        // ── Rule 2c: JSON 数组工具调用 (P1-1 补强) ──
        // 模型可能直接输出 JSON 数组 (或 ```json 代码块) 形式的工具调用, 而非 ReAct 文本。
        // 本规则把这类结构化形态转译为 ToolCall, 复用去重/循环检测/门卫/超时整条链路,
        // 降低"依赖复读文本标记"的脆弱性。保守门禁: 对象须带 command/name 键 (name 需再带
        // input/parameters 等输入键) 才认定是工具调用, 避免把"文件清单 [{name:...}]"这类
        // 合法 JSON 答案误判为工具调用。
        val jsonCalls = parseJsonToolCalls(normalized)
        if (jsonCalls.isNotEmpty()) {
            return ReActResponse(extractThought(normalized), jsonCalls.first(), isFinal = false, actions = jsonCalls)
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

    /**
     * 解析 Claude/GPT 原生 XML 工具调用信封:
     * `<tool_calls><invoke name="X"><parameter name="k">v</parameter></invoke></tool_calls>`
     * 兼容 `antml:invoke` / `antml:parameter` 前缀变体, 单双引号属性。
     * 无参数 invoke → 空参数 (对齐省略 Action Input 语义)。
     */
    private fun parseXmlToolCalls(text: String): List<ToolCall> {
        // v0.37.3: 兼容 `<action name="X">` — 模型可能把 ReAct 提示词误解为 XML 标签
        val invokeRe = Regex("(?is)<(?:\\w+:)?(?:invoke|action)\\s+name\\s*=\\s*([\"'])(.*?)\\1\\s*>(.*?)</(?:\\w+:)?(?:invoke|action)>")
        val paramRe = Regex("(?is)<(?:\\w+:)?parameter\\s+name\\s*=\\s*([\"'])(.*?)\\1\\s*>(.*?)</(?:\\w+:)?parameter>")
        return invokeRe.findAll(text).mapNotNull { m ->
            val name = m.groupValues[2].trim()
            if (name.isEmpty()) return@mapNotNull null
            val params = paramRe.findAll(m.groupValues[3]).associate { p ->
                p.groupValues[2].trim() to p.groupValues[3].trim()
            }
            ToolCall(name, params)
        }.toList()
    }

    /**
     * 退化输出检测 (v0.37.3) — 模型卡在重复生成同一标记/标签 (如 `<Action><Action>…`)
     * 或极低多样性 token 流时返回 true, 上层不应把这类垃圾当最终答案。
     * internal 为测试可见性。
     */
    internal fun isDegenerateOutput(text: String): Boolean {
        val t = text.trim()
        if (t.length < 40) return false
        // 连续重复同一 XML 标签 ≥ 3 次
        if (Regex("""(?is)(<[a-zA-Z_][a-zA-Z0-9_]*>){3,}""").containsMatchIn(t)) return true
        // 整段几乎只有一种/少数几种字符 (重复同一 token 流)
        if (t.toSet().size <= 4) return true
        // 同一行刷屏重复 ≥ 3 行
        val lines = t.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size >= 3 && lines.distinct().size == 1) return true
        return false
    }

    /**
     * 解析 JSON 数组形态的工具调用 (P1-1):
     * ```
     * [{"command":"fs.cat","input":{"path":"/a"}}, {"name":"agent.ls","parameters":{"path":"."}}]
     * ```
     * 兼容 ```json 代码块包裹。对象须带 "command"/"name" 键 (仅 name 时需再带
     * input/parameters/params/arguments 输入键) 才认定为工具调用。无匹配返回空列表,
     * 交回既有文本规则处理。永不抛异常。
     */
    private fun parseJsonToolCalls(text: String): List<ToolCall> {
        try {
            val candidate = extractJsonArray(text) ?: return emptyList()
            val array = try {
                Json.parseToJsonElement(candidate) as? JsonArray ?: return emptyList()
            } catch (_: Exception) {
                return emptyList()
            }
            val calls = array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                // 分开取值, 避免 `as?` 与 `?.takeIf` 优先级歧义
                val rawName = obj["command"] ?: obj["name"]
                val namePrim = rawName as? JsonPrimitive
                val name = namePrim?.takeIf { it.isString }?.content?.trim()
                    ?: return@mapNotNull null
                if (name.isEmpty()) return@mapNotNull null
                // 仅 name 无输入键 → 疑似数据答案 (如文件清单), 不当工具调用
                val hasInputKey = INPUT_KEYS.any { obj[it] != null }
                if (obj["command"] == null && !hasInputKey) return@mapNotNull null
                val inputValue = INPUT_KEYS.firstNotNullOfOrNull { obj[it] }
                val params = when (inputValue) {
                    is JsonObject -> inputValue.mapValues { (_, v) ->
                        (v as? JsonPrimitive)?.content ?: v.toString()
                    }
                    is JsonPrimitive -> mapOf("raw" to inputValue.content)
                    null -> emptyMap()
                    else -> emptyMap()
                }
                ToolCall(name, params)
            }
            return calls
        } catch (_: Exception) {
            return emptyList()
        }
    }

    /** 提取 JSON 数组候选: 优先 ```json 代码块内容, 其次整串开头即 '['。 */
    private fun extractJsonArray(text: String): String? {
        val fenceRe = Regex("(?is)```(?:json)?\\s*([\\s\\S]*?)\\s*```")
        val fenced = fenceRe.find(text)?.groupValues?.get(1)?.trim()
        if (fenced != null && fenced.startsWith("[") && fenced.endsWith("]")) return fenced
        val trimmed = text.trimStart()
        if (trimmed.startsWith("[")) {
            val end = trimmed.indexOfLast { it == ']' }
            if (end > 0) return trimmed.substring(0, end + 1)
        }
        return null
    }

    /** 工具调用的输入键候选 (按优先级取第一个存在者)。 */
    private val INPUT_KEYS = listOf("input", "parameters", "params", "arguments")

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
