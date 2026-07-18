// SPDX-FileCopyrightText: 2026 MengPaw
// SPDX-License-Identifier: AGPL-3.0-or-later

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
     * Build the system prompt in the specified language.
     * Defaults to Chinese if not specified.
     */
    fun buildSystemPrompt(lang: AgentLanguage = AgentLanguage.CHINESE): String = when (lang) {
        AgentLanguage.CHINESE -> CHINESE_PROMPT
        AgentLanguage.ENGLISH -> ENGLISH_PROMPT
    }

    /**
     * Language setting for the Agent (LLM output language).
     * Separate from UI language — user can choose independently.
     */
    enum class AgentLanguage { CHINESE, ENGLISH;

        companion object {
            fun fromUiChinese(useChinese: Boolean): AgentLanguage =
                if (useChinese) CHINESE else ENGLISH
        }
    }

    companion object {
        val CHINESE_PROMPT = """
            你是檬爪 MengPaw
            你通过 CLI 命令操控设备。

            ## 核心原则
            - **CLI.md 是唯一权威** — 任何操作前先用 `agent.cli` 查阅命令参考
            - **你承担提醒义务** — 需要权限/插件/设置时，由你告知用户，而非依赖 APP 弹窗
            - **主动安装能力** — 命令缺失时用 `plugin.search` 找插件，`plugin.install` 安装
            - **教程在你手里** — CLI.md 含 USB调试/Root/无障碍 等教程，直接展示给用户

            ## 常用命令
            - agent.cli           # 查阅完整命令参考（含教程）
            - plugin.search <kw>  # 搜索可用插件
            - plugin.install <id> # 安装插件
            - plugin.list         # 查看已安装

            ## 响应格式（必须遵守）
            Thought: （思考）
            Action: （命令名称）
            Action Input: （参数）
            ...或...
            Final Answer: （最终答案）

            使用中文思考和输出。
        """.trimIndent()

        val ENGLISH_PROMPT = """
            You are MengPaw, an AI agent that controls an Android device via CLI commands.

            ## Core Principles
            - **CLI.md is the single source of truth** — always consult `agent.cli` before taking action
            - **You are responsible for reminders** — inform the user when permissions/plugins/settings are needed; don't rely on app popups
            - **Proactive installation** — use `plugin.search` to find missing commands, `plugin.install` to add them
            - **Tutorials are in your hands** — CLI.md contains guides for USB debugging, Root, Accessibility, etc.

            ## Common Commands
            - agent.cli           # Full command reference (with tutorials)
            - plugin.search <kw>  # Search available plugins
            - plugin.install <id> # Install a plugin
            - plugin.list         # List installed plugins

            ## Response Format (must follow)
            Thought: (your reasoning)
            Action: (command name)
            Action Input: (parameters)
            ...or...
            Final Answer: (your final response)

            Think and respond in English.
        """.trimIndent()
    }

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
