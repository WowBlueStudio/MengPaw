// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mengpaw.core.AgentEngine
import com.mengpaw.core.AgentState
import com.mengpaw.core.llm.ProviderInfo
import com.mengpaw.core.llm.ProviderType
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for the main agent chat screen.
 * Manages the connection between UI and the AgentEngine.
 */
class AgentViewModel : ViewModel() {

    private var llmProvider: com.mengpaw.core.llm.LlmProvider = SimulatedLlmProvider()
    private var agentEngine = AgentEngine(llmProvider)

    /**
     * Reconfigure the LLM provider with real API settings.
     * Call this from MainScreen when SettingsViewModel state changes.
     */
    fun configureLlm(
        endpoint: String,
        apiKey: String,
        model: String,
        useSimulated: Boolean,
        agentLang: com.mengpaw.core.llm.PromptEngine.AgentLanguage = com.mengpaw.core.llm.PromptEngine.AgentLanguage.CHINESE
    ) {
        llmProvider = if (useSimulated || apiKey.isBlank()) {
            SimulatedLlmProvider()
        } else {
            try {
                com.mengpaw.core.llm.AdaptiveLlmProvider(
                    apiEndpoint = endpoint,
                    apiKey = apiKey,
                    model = model
                )
            } catch (e: Exception) {
                SimulatedLlmProvider()
            }
        }
        agentEngine = AgentEngine(llmProvider).also { it.setAgentLanguage(agentLang) }
    }

    /** Update Agent language without re-creating the engine. */
    fun setAgentLanguage(lang: com.mengpaw.core.llm.PromptEngine.AgentLanguage) {
        agentEngine.setAgentLanguage(lang)
    }

    private val _messages = MutableStateFlow<List<ChatMessageUi>>(
        listOf(ChatMessageUi.System("Agent is ready. Describe the task you want to accomplish."))
    )
    val messages: StateFlow<List<ChatMessageUi>> = _messages.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _inputEnabled = MutableStateFlow(true)
    val inputEnabled: StateFlow<Boolean> = _inputEnabled.asStateFlow()

    init {
        // Observe agent state changes — update UI + floating dot
        viewModelScope.launch {
            agentEngine.state.collect { state ->
                when (state) {
                    is AgentState.Idle -> {
                        _isRunning.value = false
                        _inputEnabled.value = true
                        com.mengpaw.plugin.pad.PadPlugin.updateState(com.mengpaw.plugin.pad.PadPlugin.DotState.IDLE)
                    }
                    is AgentState.Running -> {
                        _isRunning.value = true
                        _inputEnabled.value = false
                        com.mengpaw.plugin.pad.PadPlugin.updateState(com.mengpaw.plugin.pad.PadPlugin.DotState.WORKING)
                    }
                    is AgentState.Finished -> {
                        _isRunning.value = false
                        _inputEnabled.value = true
                        com.mengpaw.plugin.pad.PadPlugin.updateState(com.mengpaw.plugin.pad.PadPlugin.DotState.IDLE)
                    }
                    is AgentState.Error -> {
                        _isRunning.value = false
                        _inputEnabled.value = true
                        _messages.value = _messages.value + ChatMessageUi.Agent("⚠️ ${state.message}")
                        com.mengpaw.plugin.pad.PadPlugin.updateState(
                            com.mengpaw.plugin.pad.PadPlugin.DotState.ERROR)
                    }
                }
            }
        }
    }

    /**
     * Update the system banner text (for localization).
     */
    fun setBanner(text: String) {
        val current = _messages.value
        if (current.isNotEmpty() && current.first() is ChatMessageUi.System) {
            _messages.value = listOf(ChatMessageUi.System(text)) + current.drop(1)
        }
    }

    /**
     * Submit a task for the agent to execute.
     * Checks for missing plugin commands and emits adaptive suggestions.
     */
    fun submitTask(task: String, pluginViewModel: PluginViewModel? = null) {
        if (task.isBlank() || _isRunning.value) return

        _messages.value = _messages.value + ChatMessageUi.User(task)
        _messages.value = _messages.value + ChatMessageUi.Agent("🤔 思考中...")

        viewModelScope.launch {
            val result = agentEngine.run(task)
            val current = _messages.value.toMutableList()
            val thinkingIndex = current.indexOfLast { it is ChatMessageUi.Agent && it.content == "🤔 思考中..." }
            if (thinkingIndex >= 0) {
                current[thinkingIndex] = ChatMessageUi.Agent(result)
            } else {
                current.add(ChatMessageUi.Agent(result))
            }

            // Adaptive suggestion: check if result mentions unknown commands
            val suggestion = checkMissingPlugin(result)
            if (suggestion != null && pluginViewModel != null) {
                current.add(ChatMessageUi.Suggestion(suggestion))
                pluginViewModel.suggestPluginForCommand(result)
            }

            _messages.value = current
        }
    }

    /**
     * Check if the agent output contains an "Unknown command" error,
     * and generate a plugin suggestion if the relevant plugin exists.
     */
    private fun checkMissingPlugin(output: String): PluginSuggestion? {
        val unknownRegex = Regex("Unknown command: (\\w+)\\.")
        val match = unknownRegex.find(output) ?: return null
        val namespace = match.groupValues[1]
        val pluginId = "$namespace-plugin"

        // Map known namespaces to plugin info for offline suggestions
        val knownPlugins = mapOf(
            "fs" to PluginSuggestion("fs", "fs-plugin", "File System", "文件系统操作：cat, ls, write, rm 等", "fs.${match.value.substringAfter("$namespace.").take(20)}"),
            "net" to PluginSuggestion("net", "net-plugin", "Network", "HTTP 网络请求：curl, get, post", "net.*"),
            "memory" to PluginSuggestion("memory", "memory-plugin", "Memory System", "Markdown 持久化记忆系统", "memory.*"),
            "skill" to PluginSuggestion("skill", "skill-plugin", "Skill System", "可复用的 Agent 剧本系统", "skill.*"),
            "ui" to PluginSuggestion("ui", "ui-plugin", "UI Automation", "界面操控：click, swipe, input 等", "ui.*"),
            "proc" to PluginSuggestion("proc", "proc-plugin", "Process Management", "进程管理：ps, kill, exec", "proc.*"),
            "clipboard" to PluginSuggestion("clipboard", "clipboard-plugin", "Clipboard", "剪贴板操作", "clipboard.*"),
            "notification" to PluginSuggestion("notification", "notification-plugin", "Notification", "通知管理", "notification.*"),
        )

        return knownPlugins[namespace]
    }

    /**
     * Stop the current agent execution.
     */
    fun stopAgent() { agentEngine.stop() }

    fun getSessions(): List<String> = listOf("当前会话")

    fun newSession() {
        _messages.value = listOf(ChatMessageUi.Agent("新会话已创建。"))
    }
}

/**
 * Simulated LLM provider for development/testing.
 */
class SimulatedLlmProvider : com.mengpaw.core.llm.LlmProvider {
    private var callCount = 0

    override suspend fun complete(prompt: String): String {
        callCount++
        return when (callCount) {
            1 -> """
                Thought: I need to check the system status first.
                Action: self.status
                Action Input: {}
            """.trimIndent()
            2 -> """
                Thought: Let me check the available storage.
                Action: self.stats
                Action Input: {}
            """.trimIndent()
            3 -> """
                Thought: The system looks healthy. Let me provide a summary.
                Final Answer: System check complete. All systems operational.
            """.trimIndent()
            else -> """
                Final Answer: Task completed in $callCount steps.
            """.trimIndent()
        }
    }

    override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String {
        val result = complete(prompt)
        result.forEach { onToken(it.toString()) }
        return result
    }

    override fun info(): ProviderInfo = ProviderInfo(
        "Simulated", "simulation-v1", ProviderType.LOCAL
    )
}

/**
 * UI representation of a chat message.
 */
sealed class ChatMessageUi {
    data class User(val content: String) : ChatMessageUi()
    data class Agent(val content: String) : ChatMessageUi()
    data class System(val content: String) : ChatMessageUi()
    data class Suggestion(val suggestion: PluginSuggestion) : ChatMessageUi()
}
