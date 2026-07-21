// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.llm

import com.mengpaw.kernel.agent.AgentDocs
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
     * Build the system prompt with agent identity, framework context, and model info.
     * @param lang Output language
     * @param agentName The name of this agent (e.g. "MengPaw", "平板-Agent")
     * @param framework The framework this agent belongs to (null = local)
     * @param modelName The LLM model powering this agent (for self-awareness)
     */
    fun buildSystemPrompt(
        lang: AgentLanguage = AgentLanguage.CHINESE,
        agentName: String = "MengPaw",
        framework: String? = null,
        modelName: String = "unknown"
    ): String {
        val identity = if (lang == AgentLanguage.CHINESE) {
            buildString {
                append("你是 **$agentName**，MengPaw 智能体系统中的一员。\n")
                if (framework != null) {
                    append("你运行在远程框架「**$framework**」上，通过网络与该框架协作。你的操作会传递给该框架执行。\n")
                } else {
                    append("你运行在**本地设备**上，可以直接操控本设备。\n")
                }
                append("你当前由 **$modelName** 模型驱动。\n")
                append("\n")
            }
        } else {
            buildString {
                append("You are **$agentName**, a member of the MengPaw agent system.\n")
                if (framework != null) {
                    append("You run on the remote framework \"**$framework**\" and collaborate over the network. Your actions are forwarded to that framework for execution.\n")
                } else {
                    append("You run on the **local device** and can control it directly.\n")
                }
                append("You are currently powered by the **$modelName** model.\n")
                append("\n")
            }
        }

        val basePrompt = when (lang) {
            AgentLanguage.CHINESE -> CHINESE_PROMPT
            AgentLanguage.ENGLISH -> ENGLISH_PROMPT
        }

        val fewShot = when (lang) {
            AgentLanguage.CHINESE -> CHINESE_FEWSHOT
            AgentLanguage.ENGLISH -> ENGLISH_FEWSHOT
        }

        // Inject agent's own documentation (AGENTS.md + SOUL.md)
        val agentsDoc = AgentDocs.readAgentsDoc(agentName)
        val soulDoc = AgentDocs.readSoulDoc(agentName)
        val memoryDoc = AgentDocs.readMemoryDoc(agentName)

        val docsBlock = buildString {
            append("\n## 你的操作手册（AGENTS.md）\n\n")
            append(agentsDoc)
            if (soulDoc.isNotBlank()) {
                append("\n\n## 你的灵魂准则（SOUL.md）\n\n")
                append(soulDoc)
            }
            if (memoryDoc.isNotBlank()) {
                append("\n\n## 你的长期记忆（MEMORY.md）\n\n")
                append(memoryDoc)
            }
        }

        return identity + basePrompt + "\n" + fewShot + "\n" + docsBlock
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
        // ── Few-shot examples: demonstrate correct ReAct usage with real MengPaw commands ──
        val CHINESE_FEWSHOT = """
            ## 示例对话（请严格模仿格式）

            **示例 1 — 简单查询**

            用户：检查一下设备状态

            Thought: 用户想了解设备当前状态。我先用 self.status 获取基本信息。
            Action: self.status
            Action Input: {}

            Observation: {"status":"online","version":"0.2.1","uptime":"2h 15m"}

            Thought: 设备在线，运行正常。可以用 sys.battery 和 sys.storage 获取更多信息。
            Action: sys.battery
            Action Input: {}

            Observation: Battery: 85%, Charging: false

            Final Answer: 设备状态正常。\n- MengPaw 版本：0.2.1\n- 运行时间：2小时15分钟\n- 电量：85%（未充电）

            ---

            **示例 2 — 文件操作**

            用户：看看 /sdcard/Download 下有什么文件

            Thought: 用户想查看下载目录。文件系统操作需要用 fs 插件。先确认 fs 插件是否已安装。
            Action: plugin.list
            Action Input: {}

            Observation: 已安装插件：fs-plugin, net-plugin, memory-plugin

            Thought: fs-plugin 已安装。使用 fs.ls 列出目录内容。
            Action: fs.ls
            Action Input: {"path":"/sdcard/Download"}

            Observation: [文件] report.pdf (2.3MB)\n[文件] photo.jpg (1.1MB)\n[目录] temp/

            Final Answer: /sdcard/Download 目录下有：\n- report.pdf（2.3MB）\n- photo.jpg（1.1MB）\n- temp/（子目录）

            ---

            **示例 3 — 插件发现**

            用户：我需要搜索网页

            Thought: 用户需要网页搜索能力。我先查 CLI 参考看看有没有相关命令，然后搜索可用插件。
            Action: agent.cli
            Action Input: {}

            Observation: [CLI.md 内容——列出所有 namespace 和命令]

            Thought: CLI.md 没有直接列出网页搜索命令。让我搜索插件市场。
            Action: plugin.search
            Action Input: {"keyword":"web search"}

            Observation: 找到插件：tavily-plugin（网页搜索），hermes-plugin（多引擎搜索）

            Thought: 找到了两个搜索插件。向用户推荐并安装 tavily-plugin。
            Action: plugin.install
            Action Input: {"id":"tavily-plugin"}

            Observation: tavily-plugin 安装成功。

            Final Answer: 已为你安装网页搜索插件。现在可以用 tavily.search 搜索了。需要我帮你搜什么？
        """.trimIndent()

        val ENGLISH_FEWSHOT = """
            ## Examples (follow this format exactly)

            **Example 1 — System Check**

            User: Check the device status

            Thought: The user wants to know the device state. Let me start with self.status.
            Action: self.status
            Action Input: {}

            Observation: {"status":"online","version":"0.2.1","uptime":"2h 15m"}

            Thought: Device is online and healthy. Let me also check battery.
            Action: sys.battery
            Action Input: {}

            Observation: Battery: 85%, Charging: false

            Final Answer: Device status is normal.\n- MengPaw version: 0.2.1\n- Uptime: 2h 15m\n- Battery: 85% (not charging)

            ---

            **Example 2 — File Operations**

            User: What's in /sdcard/Download?

            Thought: User wants to list files. File operations use the fs plugin. Let me verify it's installed.
            Action: plugin.list
            Action Input: {}

            Observation: Installed plugins: fs-plugin, net-plugin, memory-plugin

            Thought: fs-plugin is available. Using fs.ls to list the directory.
            Action: fs.ls
            Action Input: {"path":"/sdcard/Download"}

            Observation: [file] report.pdf (2.3MB)\n[file] photo.jpg (1.1MB)\n[dir] temp/

            Final Answer: Contents of /sdcard/Download:\n- report.pdf (2.3MB)\n- photo.jpg (1.1MB)\n- temp/ (subdirectory)

            ---

            **Example 3 — Plugin Discovery**

            User: I need web search capability

            Thought: User needs web search. Let me check CLI reference first, then search plugins.
            Action: agent.cli
            Action Input: {}

            Observation: [CLI.md content — lists all namespaces and commands]

            Thought: CLI.md doesn't have web search built-in. Let me search the plugin marketplace.
            Action: plugin.search
            Action Input: {"keyword":"web search"}

            Observation: Found plugins: tavily-plugin (web search), hermes-plugin (multi-engine search)

            Thought: Found search plugins. Let me install tavily-plugin.
            Action: plugin.install
            Action Input: {"id":"tavily-plugin"}

            Observation: tavily-plugin installed successfully.

            Final Answer: Web search plugin installed. You can now use tavily.search. What should I search for?
        """.trimIndent()

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

            **注意**：每次任务有步数限制，请在接近限制时主动给出当前最佳答案，避免因步数耗尽而中断。
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

            **Note**: You have a limited number of steps per task. Provide your best answer proactively when approaching the limit to avoid interruption.
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

        // FIX A5: Only treat as Final Answer if it's the LAST section (not before an Action)
        // Find all occurrences of "Final Answer:" and "Action:"
        val finalLocs = Regex("(?i)final answer[:：]", RegexOption.MULTILINE).findAll(normalized).map { it.range.first }.toList()
        val actionLocs = Regex("(?i)action[:：]", RegexOption.MULTILINE).findAll(normalized).map { it.range.first }.toList()

        // Final Answer is valid only if it appears AFTER the last Action (or there are no Actions)
        if (finalLocs.isNotEmpty()) {
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

    /** Reset loop detection state (call on session/model switch). */
    fun resetLoopDetection() { recentCommands.clear() }
}
