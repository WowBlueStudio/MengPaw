package com.mengpaw.core.llm

import kotlinx.serialization.json.*

/**
 * Parsed ReAct response from LLM output.
 */
data class ReActResponse(
    val thought: String,
    val action: ToolCall?,
    val isFinal: Boolean
)

data class ToolCall(
    val name: String,
    val parameters: Map<String, String>
)

/**
 * ReAct prompt templates and parsing engine.
 */
class PromptEngine {

    private val recentCommands = java.util.LinkedList<String>()

    /**
     * Build the system prompt that instructs the LLM on how to use CLI commands.
     */
    fun buildSystemPrompt(): String = """
        你是 檬爪，运行在 Android 上的自主 Agent。
        你通过 CLI 命令操控设备。

        ## 快速索引（被动加载）
        - Tool 索引: memory.read tool-index      # 被动加载工具列表
        - Skill 索引: memory.read skill-index     # 被动加载技能列表
        - CLI 完整参考: memory.read cli-reference  # 被动加载详细文档

        ## 常用命令（无需加载索引）
        - memory ls         # 列出所有记忆
        - memory read <id>  # 读取记忆/索引
        - skill.ls          # 列出所有 Skill
        - skill.run <name>  # 运行指定 Skill
        - fs.cat <path>     # 读取文件

        ## 响应格式（必须遵守）
        Thought: （你的推理）
        Action: （命令名称）
        Action Input: （参数）
        ...或...
        Final Answer: （你的结论）

        注意：仅在需要时加载索引文档，不要每次对话都加载全部内容。
    """.trimIndent()

    /**
     * Parse LLM output into a structured ReAct response.
     */
    fun parse(text: String): ReActResponse {
        val normalized = text.trim()

        // Match Thought (case-insensitive, Chinese/English colon)
        val thoughtRegex = Regex(
            "(?i)thought[:：]\\s*(.+?)(?=Action[:：]|Final Answer[:：]|$)",
            RegexOption.DOT_MATCHES_ALL
        )
        val thought = thoughtRegex.find(normalized)?.groupValues?.get(1)?.trim()
            ?: normalized.take(200)

        // Check for Final Answer
        val finalRegex = Regex("(?i)final answer[:：]\\s*(.+)", RegexOption.DOT_MATCHES_ALL)
        finalRegex.find(normalized)?.let {
            return ReActResponse(it.groupValues[1].trim(), null, isFinal = true)
        }

        // Parse Action
        val actionRegex = Regex("(?i)action[:：]\\s*(\\S+)")
        val actionName = actionRegex.find(normalized)?.groupValues?.get(1)?.trim()
            ?: return ReActResponse(thought, null, isFinal = false)

        // Parse Action Input (tolerant JSON parsing)
        val inputRegex = Regex(
            "(?i)action input[:：]\\s*(.+?)(?=Thought[:：]|Action[:：]|$)",
            RegexOption.DOT_MATCHES_ALL
        )
        val inputText = inputRegex.find(normalized)?.groupValues?.get(1)?.trim() ?: "{}"

        val params = try {
            val obj = Json.parseToJsonElement(inputText) as JsonObject
            obj.mapValues { (it.value as? JsonPrimitive)?.content ?: it.value.toString() }
        } catch (e: Exception) {
            mapOf("raw" to inputText)
        }

        return ReActResponse(thought, ToolCall(actionName, params), isFinal = false)
    }

    /**
     * Detect command loops (same command repeated 3+ times).
     */
    fun detectLoop(command: String): Boolean {
        recentCommands.add(command)
        if (recentCommands.size > 5) recentCommands.removeFirst()
        return recentCommands.count { it == command } >= 3
    }
}
