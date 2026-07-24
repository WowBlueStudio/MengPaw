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

            Thought: 使用 agent.read 读取文件。
            Action: agent.read
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

            Thought: Using agent.read to read the file.
            Action: agent.read
            Action Input: /sdcard/Download/task.md

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
            你通过 CLI 命令操控 Android 设备。

            ## 核心原则
            - **self.tools 是命令入口** — 先用 `self.tools [namespace]` 查看可用命令，不靠记忆
            - **agent.docs 查阅工作区** — 读取 Soul/Agents/Memory/Boost 了解自己的设定和任务
            - **你承担提醒义务** — 需要权限/插件/设置时，由你告知用户
            - **主动安装能力** — 命令缺失时用 `plugin.search` 找插件，`plugin.install` 安装
            - **教程在框架设置中** — USB调试/Root/无障碍等操作指南在 设置→框架设置→Tools 中

            ## 自身能力（全部内建，无需安装）

            ### 斜杠命令（用户点输入框 + → 执行模式区选择。MengPaw 特有功能，没有 Normal/Deep/Dream 模式）
            消息带标签时你自动切换执行策略：
            - **/Mission** — 复杂任务拆解为子任务，Worker 并行执行，Verifier 验证
            - **/Research** — 多轮搜索+交叉验证+来源标注，输出结构化报告
            - **/Translate** — 翻译输入内容为目标语言
            - **/Silent** — 后台静默执行，不阻塞对话，完成后以系统消息推送结果
            用户问「有什么模式」时：列出这四种，说明怎么在输入框+号里选。

            ### 记忆梦境 DreamEngine（不是执行模式！是记忆整理功能）
            - **agent.dream** — 整理记忆：自动标签、交叉链接、归档30天前旧记录。对话长了或用户说「整理」「归档」时主动用
            - **agent.memory.record <内容>** — 记录用户偏好/重要决策
            - **agent.cleanup** — 清理截图/临时文件
            - **agent.storage** — 查看存储使用量

            ### 发现更多
            - **skill.ls** — 列出内置说明书
            - **skill.run dream-engine** — 读梦境功能的完整说明
            - **skill.run execution-modes** — 读斜杠命令的完整说明

            ## 常用命令
            - self.tools [ns]     # 列出可用命令（按命名空间: self/agent/plugin/sys/fs/net...）
            - agent.docs          # 列出工作区文档 (Soul/Agents/Memory/Boost/Profile)
            - agent.memory <kw>   # 搜索长期记忆
            - agent.read <path>   # 读取文件（工作区 + /sdcard/Download/）
            - agent.write <path> <内容>  # 写入文件
            - agent.sessions <kw> # 跨会话搜索历史
            - plugin.marketplace  # 浏览插件市场
            - plugin.search <kw>  # 搜索可用插件
            - plugin.install <id> # 安装插件
            - plugin.list         # 查看已安装
            - sys.permission.list              # 查看权限状态
            - sys.permission.request <name>    # 申请权限（弹出系统对话框）

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
            - **self.tools is the command entry point** — always check `self.tools [namespace]` for available commands
            - **agent.docs for workspace** — read Soul/Agents/Memory/Boost to understand your settings and tasks
            - **You are responsible for reminders** — inform the user when permissions/plugins/settings are needed
            - **Proactive installation** — use `plugin.search` to find missing commands, `plugin.install` to add them
            - **Tutorials in Settings** — guides for USB debugging, Root, Accessibility etc. are in Settings→Framework→Tools

            ## Built-in Capabilities (no plugins needed)

            ### Slash Commands (user taps + → Execution Mode. MengPaw-specific, NOT Normal/Deep/Dream)
            Tagged messages auto-switch your execution strategy:
            - **/Mission** — Decompose complex tasks→subtasks→parallel Workers→Verifier
            - **/Research** — Multi-round search+cross-validation+structured report
            - **/Translate** — Translate input to target language
            - **/Silent** — Background silent execution, push result when done
            When asked "what modes": list these four, explain + button.

            ### Memory Dream (DreamEngine — NOT an execution mode! memory maintenance)
            - **agent.dream** — Organize: auto-tag, cross-link, archive 30d+ records
            - **agent.memory.record <content>** — Save user preference/decision
            - **agent.cleanup** — Clean screenshots/temp files
            - **agent.storage** — Check storage usage

            ### Discover More
            - **skill.ls** — List built-in skill manuals
            - **skill.run dream-engine** — Full DreamEngine guide
            - **skill.run execution-modes** — Full slash command guide

            ## Common Commands
            - self.tools [ns]     # List available commands (by namespace)
            - agent.docs          # List workspace documents
            - agent.memory <kw>   # Search long-term memory
            - agent.read <path>          # Read file (workspace + /sdcard/Download/)
            - agent.write <path> <content> # Write file
            - agent.sessions <kw> # Cross-session search
            - plugin.marketplace  # Browse plugin market
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
     *
     * Tolerant parsing strategy:
     * 1. If "Final Answer:" present (after last Action) → final answer
     * 2. If "Action:" present with valid command → execute action
     * 3. If NEITHER marker present (non-ReAct model / natural response) → treat as final answer
     * 4. If "Thought:" only (no action, no final) → also treat as final answer
     */
    fun parse(text: String): ReActResponse {
        val normalized = text.trim()

        // Find all marker positions (case-insensitive, Chinese/English colon)
        val finalLocs = Regex("(?i)final answer[:：]", RegexOption.MULTILINE).findAll(normalized).map { it.range.first }.toList()
        val actionLocs = Regex("(?i)action[:：]", RegexOption.MULTILINE).findAll(normalized).map { it.range.first }.toList()

        // ── Rule 1: Final Answer (must appear after last Action, or with no Action at all) ──
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

        // ── Rule 2: Parse Action ──
        val actionRegex = Regex("(?i)action[:：]\\s*(\\S+)")
        val actionName = actionRegex.find(normalized)?.groupValues?.get(1)?.trim()

        if (actionName != null) {
            // Parse Action Input (tolerant JSON parsing)
            val inputRegex = Regex(
                "(?i)action input[:：]\\s*(.+?)(?=Thought[:：]|Action[:：]|Final Answer[:：]|$)",
                RegexOption.DOT_MATCHES_ALL
            )
            val inputText = inputRegex.find(normalized)?.groupValues?.get(1)?.trim() ?: "{}"

            val params = try {
                val obj = Json.parseToJsonElement(inputText) as JsonObject
                obj.mapValues { (it.value as? JsonPrimitive)?.content ?: it.value.toString() }
            } catch (e: Exception) {
                mapOf("raw" to inputText)
            }

            val thought = extractThought(normalized)
            return ReActResponse(thought, ToolCall(actionName, params), isFinal = false)
        }

        // ── Rule 3: No "Action:" and no "Final Answer:" → natural language response ──
        // This happens with non-reasoning models (e.g. DeepSeek-Chat) that don't follow
        // the ReAct format strictly. Treat the entire response as a final answer.
        // Only extract thought if explicitly marked with "Thought:".
        if (finalLocs.isEmpty()) {
            val thought = extractThought(normalized)
            // If there's an explicit Thought but no Action/Final, the model might be mid-reasoning.
            // Check if the thought marker was explicitly provided.
            val hasExplicitThought = Regex("(?i)thought[:：]").containsMatchIn(normalized)
            if (hasExplicitThought && thought.length > normalized.length / 2) {
                // Model is thinking but didn't produce an action — treat thought as partial answer
                return ReActResponse(thought, null, isFinal = true)
            }
            // Natural language response without ReAct markers → final answer
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

    /** Safe-to-repeat commands — never trigger loop detection. */
    private val safeCommands = setOf(
        "agent.docs", "agent.cli", "agent.memory", "agent.profile",
        "agent.soul", "agent.audit", "agent.storage", "agent.sessions",
        "agent.read", // read-only, safe to repeat
        "self.stats", "self.version", "self.time", "self.tools", "self.status",
        "plugin.list", "plugin.info", "plugin.marketplace",
        "sys.battery", "sys.network", "sys.cpu", "sys.memory", "sys.storage",
    )

    /**
     * Detect command loops (same command repeated 5+ times in recent window).
     * Safe info/list commands are exempt.
     */
    fun detectLoop(command: String): Boolean {
        if (safeCommands.any { command.startsWith(it) }) return false
        recentCommands.add(command)
        if (recentCommands.size > 8) recentCommands.removeFirst()
        return recentCommands.count { it == command } >= 5
    }

    private var consecutiveFailures = 0

    /**
     * Track command result for failure-loop detection.
     * Call after each command execution. If 5+ consecutive commands fail,
     * the agent is likely stuck in a failure loop and should stop.
     */
    fun trackResult(success: Boolean): Boolean {
        if (success) { consecutiveFailures = 0; return false }
        consecutiveFailures++
        return consecutiveFailures >= 5
    }

    /** Reset loop detection state (call on session/model switch). */
    fun resetLoopDetection() { recentCommands.clear(); consecutiveFailures = 0 }
}
