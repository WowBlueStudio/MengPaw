// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mengpaw.core.AgentEngine
import com.mengpaw.core.AgentState
import com.mengpaw.core.agent.AgentMiddleware
import com.mengpaw.core.agent.PostCallMiddleware
import com.mengpaw.core.agent.PostCallResult
import com.mengpaw.core.agent.ScrollContextManager
import com.mengpaw.core.llm.LlmProvider
import com.mengpaw.core.llm.PromptEngine
import com.mengpaw.core.llm.ProviderInfo
import com.mengpaw.core.llm.ProviderType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Per-agent session: independent engine, provider, and message history.
 */
class AgentSession(
    val name: String,
    val framework: String?,       // null = local device, non-null = remote framework name
    var modelName: String,
    var provider: LlmProvider,
    val engine: AgentEngine,
    val messages: MutableStateFlow<List<ChatMessageUi>>,
    val scrollContext: ScrollContextManager
)

/**
 * ViewModel for the main agent chat screen.
 * Manages multiple agent sessions — each agent has its own AgentEngine and message history.
 */
class AgentViewModel : ViewModel() {

    // ── Global LLM config (shared across new agents as default) ──
    private var globalEndpoint: String = ""
    private var globalApiKey: String = ""
    private var globalModel: String = "unknown"
    private var globalUseSimulated: Boolean = true
    private var globalAgentLang: PromptEngine.AgentLanguage = PromptEngine.AgentLanguage.CHINESE

    // ── Multi-session store ──
    private val sessions = mutableMapOf<String, AgentSession>()

    private fun defaultProvider(): LlmProvider =
        if (globalUseSimulated || globalApiKey.isBlank()) {
            SimulatedLlmProvider()
        } else {
            try {
                com.mengpaw.core.llm.AdaptiveLlmProvider(
                    apiEndpoint = globalEndpoint,
                    apiKey = globalApiKey,
                    model = globalModel
                )
            } catch (_: Exception) {
                SimulatedLlmProvider()
            }
        }

    private fun createSession(name: String, framework: String?): AgentSession {
        val model = globalModel.ifBlank { "unknown" }
        val provider = defaultProvider()

        // Scroll context manager — eviction index + recall per agent
        val scroll = ScrollContextManager(name)

        // Memory middleware: inject agent docs into system prompt
        val memoryMw = AgentMiddleware { prompt, agentName ->
            val memoryDoc = com.mengpaw.core.agent.AgentDocs.readMemoryDoc(agentName)
            if (memoryDoc.isNotBlank() && memoryDoc !in prompt) {
                "$prompt\n\n## 长期记忆\n\n$memoryDoc"
            } else prompt
        }

        // Post-call middleware: context folding + scroll eviction
        val postMw = PostCallMiddleware { response, step, totalChars, estimatedTokens ->
            // Trigger folding when above 80% threshold (delegates to engine's maybeFoldContext)
            val ratio = estimatedTokens.toDouble() / 131_072.0
            if (ratio > 0.80) {
                PostCallResult(response, shouldFold = true,
                    foldReason = "Step $step: context at ${(ratio * 100).toInt()}%")
            } else {
                PostCallResult(response)
            }
        }

        val engine = AgentEngine(
            llmProvider = provider,
            middleware = memoryMw,
            postCallMiddleware = postMw,
            scrollContext = scroll
        ).also {
            it.setAgentIdentity(name, framework, model)
            it.setAgentLanguage(globalAgentLang)
            it.configureCacheStrategy(globalEndpoint)
        }

        val msgs = MutableStateFlow<List<ChatMessageUi>>(
            listOf(ChatMessageUi.System("$name 就绪。请描述你想完成的任务。"))
        )
        return AgentSession(name, framework, model, provider, engine, msgs, scroll)
    }

    /** Ensure the default "MengPaw" agent session always exists. */
    private fun ensureDefaultSession() {
        if (!sessions.containsKey("MengPaw")) {
            sessions["MengPaw"] = createSession("MengPaw", null)
        }
    }

    // ── Active agent state ──
    private var _activeAgentName = "MengPaw"

    private fun activeSession(): AgentSession {
        ensureDefaultSession()
        return sessions[_activeAgentName] ?: sessions["MengPaw"]!!
    }

    // ── Observable state (backed by active session) ──
    private val _messages = MutableStateFlow<List<ChatMessageUi>>(emptyList())
    val messages: StateFlow<List<ChatMessageUi>> = _messages.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _inputEnabled = MutableStateFlow(true)
    val inputEnabled: StateFlow<Boolean> = _inputEnabled.asStateFlow()

    private val _activeAgent = MutableStateFlow("MengPaw")
    val activeAgent: StateFlow<String> = _activeAgent.asStateFlow()

    /** All agent names currently in the session map. */
    val agentNames: Set<String> get() = sessions.keys

    private var stateObserverJob: Job? = null

    /**
     * Reconfigure the LLM provider with real API settings.
     * Applies to all existing sessions and will be used as default for new ones.
     */
    fun configureLlm(
        endpoint: String,
        apiKey: String,
        model: String,
        useSimulated: Boolean,
        agentLang: PromptEngine.AgentLanguage = PromptEngine.AgentLanguage.CHINESE
    ) {
        globalEndpoint = endpoint
        globalApiKey = apiKey
        globalModel = model
        globalUseSimulated = useSimulated
        globalAgentLang = agentLang

        // Update all existing sessions' providers
        val newProvider = defaultProvider()
        sessions.values.forEach { session ->
            session.provider = newProvider
            session.modelName = model
            session.engine.setAgentIdentity(session.name, session.framework, model)
            session.engine.setAgentLanguage(agentLang)
        }

        // Re-bind to active session
        bindActiveSession()
    }

    /** Switch to a different agent. */
    fun switchAgent(name: String) {
        if (name == _activeAgentName) return
        _activeAgentName = name
        bindActiveSession()
    }

    /** Create a new agent with the given name and optional framework. */
    fun createAgent(name: String, framework: String? = null) {
        if (sessions.containsKey(name)) return
        // Bootstrap agent documentation files
        com.mengpaw.core.agent.AgentDocs.bootstrap(name)
        sessions[name] = createSession(name, framework)
        switchAgent(name)
    }

    /** Update Agent language without re-creating the engine. */
    fun setAgentLanguage(lang: PromptEngine.AgentLanguage) {
        globalAgentLang = lang
        sessions.values.forEach { it.engine.setAgentLanguage(lang) }
    }

    /** Update the system banner text (for localization). */
    fun setBanner(text: String) {
        val current = activeSession().messages.value
        if (current.isNotEmpty() && current.first() is ChatMessageUi.System) {
            activeSession().messages.value = listOf(ChatMessageUi.System(text)) + current.drop(1)
        }
    }

    /**
     * Submit a task to the currently active agent.
     */
    fun submitTask(task: String, pluginViewModel: PluginViewModel? = null, maxSteps: Int = 50) {
        if (task.isBlank() || _isRunning.value) return

        val session = activeSession()
        session.messages.value = session.messages.value + ChatMessageUi.User(task)

        viewModelScope.launch {
            val traces = mutableListOf<AgentTrace>()

            session.messages.value = session.messages.value + ChatMessageUi.AgentWithTrace(
                finalContent = "思考中...",
                traces = emptyList(),
                isRunning = true
            )

            val result = session.engine.run(
                task = task,
                maxSteps = maxSteps,
                onStep = { trace ->
                    traces.add(AgentTrace(trace.step, trace.thought, trace.action, trace.observation))
                    val current = session.messages.value.toMutableList()
                    val runningIndex = current.indexOfLast {
                        it is ChatMessageUi.AgentWithTrace && it.isRunning
                    }
                    if (runningIndex >= 0) {
                        current[runningIndex] = ChatMessageUi.AgentWithTrace(
                            finalContent = "思考中...",
                            traces = traces.toList(),
                            isRunning = true
                        )
                        session.messages.value = current
                    }
                }
            )

            val current = session.messages.value.toMutableList()
            val runningIndex = current.indexOfLast {
                it is ChatMessageUi.AgentWithTrace && it.isRunning
            }
            if (runningIndex >= 0) {
                current[runningIndex] = ChatMessageUi.AgentWithTrace(
                    finalContent = result,
                    traces = traces.toList(),
                    isRunning = false
                )
            } else {
                current.add(ChatMessageUi.Agent(result))
            }

            val suggestion = checkMissingPlugin(result)
            if (suggestion != null && pluginViewModel != null) {
                current.add(ChatMessageUi.Suggestion(suggestion))
                pluginViewModel.suggestPluginForCommand(result)
            }

            session.messages.value = current
        }
    }

    fun stopAgent() { activeSession().engine.stop() }

    /** Get all sessions for the current agent. */
    fun getSessions(): List<String> = listOf(_activeAgentName)

    /** Clear the active agent's messages. */
    fun newSession() {
        activeSession().messages.value = listOf(ChatMessageUi.Agent("新会话已创建。"))
    }

    // ── Internals ──

    private fun bindActiveSession() {
        ensureDefaultSession()
        val session = sessions[_activeAgentName] ?: return
        _activeAgent.value = _activeAgentName
        _messages.value = session.messages.value

        // Re-bind state observer to the new engine
        stateObserverJob?.cancel()
        stateObserverJob = viewModelScope.launch {
            session.engine.state.collect { state ->
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
                        session.messages.value = session.messages.value + ChatMessageUi.Agent("⚠️ ${state.message}")
                        com.mengpaw.plugin.pad.PadPlugin.updateState(com.mengpaw.plugin.pad.PadPlugin.DotState.ERROR)
                    }
                }
            }
        }
    }

    init {
        ensureDefaultSession()
        bindActiveSession()
    }

    // ── Plugin suggestion logic (unchanged) ──

    private fun checkMissingPlugin(output: String): PluginSuggestion? {
        val unknownRegex = Regex("Unknown command: (\\w+)\\.")
        val match = unknownRegex.find(output) ?: return null
        val namespace = match.groupValues[1]
        val pluginId = "$namespace-plugin"

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
}

// ── Data types (unchanged) ──

/**
 * Simulated LLM provider for development/testing.
 */
class SimulatedLlmProvider : LlmProvider {
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

data class AgentTrace(
    val step: Int,
    val thought: String,
    val action: String?,
    val observation: String?
)

sealed class ChatMessageUi {
    data class User(val content: String) : ChatMessageUi()
    data class Agent(val content: String) : ChatMessageUi()
    data class AgentWithTrace(
        val finalContent: String,
        val traces: List<AgentTrace>,
        val isRunning: Boolean = false
    ) : ChatMessageUi()
    data class System(val content: String) : ChatMessageUi()
    data class Suggestion(val suggestion: PluginSuggestion) : ChatMessageUi()
}
