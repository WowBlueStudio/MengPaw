// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mengpaw.kernel.AgentEngine
import com.mengpaw.kernel.AgentState
import com.mengpaw.kernel.agent.AgentMiddleware
import com.mengpaw.kernel.agent.PostCallMiddleware
import com.mengpaw.kernel.agent.PostCallResult
import com.mengpaw.kernel.agent.ScrollContextManager
import com.mengpaw.kernel.llm.AdaptiveLlmProvider
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.PromptEngine
import com.mengpaw.kernel.llm.ProviderInfo
import com.mengpaw.kernel.llm.ProviderType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Per-agent session: independent engine, provider, and message history.
 */
class AgentSession(
    val name: String,
    val framework: String?,       // null = local device, non-null = remote framework name
    var modelName: String,
    var endpoint: String = "",
    var apiKey: String = "",
    var provider: LlmProvider,
    val engine: AgentEngine,
    val messages: MutableStateFlow<List<ChatMessageUi>>,
    val scrollContext: ScrollContextManager,
    val isRunning: MutableStateFlow<Boolean> = MutableStateFlow(false),
    val inputEnabled: MutableStateFlow<Boolean> = MutableStateFlow(true)
) {
    /** Human-readable provider + model label for UI display. */
    val providerLabel: String get() {
        if (endpoint.isBlank() || apiKey.isBlank()) return "智能体还未配置模型"
        val p = when {
            endpoint.contains("openai.com") -> "OpenAI"
            endpoint.contains("deepseek.com") -> "DeepSeek"
            endpoint.contains("x.ai") -> "Grok"
            endpoint.contains("moonshot.cn") -> "Kimi"
            endpoint.contains("bigmodel.cn") -> "GLM"
            endpoint.contains("dashscope") -> "Qwen"
            endpoint.contains("volces.com") -> "火山引擎"
            endpoint.contains("openmodel.ai") -> "OpenModel"
            else -> "Custom"
        }
        val modelLabel = modelName.take(24).ifBlank { "auto" }
        return "$p / $modelLabel"
    }
}

/**
 * ViewModel for the main agent chat screen.
 * Manages multiple agent sessions — each agent has its own AgentEngine and message history.
 */
class AgentViewModel : ViewModel() {

    override fun onCleared() {
        super.onCleared()
        // Close all HTTP clients to prevent resource leaks
        sessions.values.forEach { session ->
            try { (session.provider as? java.io.Closeable)?.close() } catch (_: Exception) {}
        }
    }

    // ── Global LLM config (shared across new agents as default) ──
    private var globalEndpoint: String = ""
    private var globalApiKey: String = ""
    private var globalModel: String = "unknown"
    private var globalAgentLang: PromptEngine.AgentLanguage = PromptEngine.AgentLanguage.CHINESE

    // ── Multi-session store ──
    private val sessions = mutableMapOf<String, AgentSession>()

    // Track which agents have completed the bootstrap startup flow.
    // Prevents re-triggering on every config change.
    private val bootstrappedAgents = mutableSetOf<String>()

    private fun defaultProvider(): LlmProvider =
        if (globalApiKey.isBlank()) SimulatedLlmProvider()
        else try { AdaptiveLlmProvider(globalEndpoint, globalApiKey, globalModel) } catch (_: Exception) { SimulatedLlmProvider() }

    private fun createSession(name: String, framework: String?): AgentSession {
        val model = globalModel.ifBlank { "unknown" }
        val provider = defaultProvider()

        // Scroll context manager — eviction index + recall per agent
        val scroll = ScrollContextManager(name)

        // Memory middleware: inject agent docs into system prompt
        val memoryMw = AgentMiddleware { prompt, agentName ->
            val memoryDoc = com.mengpaw.kernel.agent.AgentDocs.readMemoryDoc(agentName)
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
            scrollContext = scroll,
            additionalNamespaces = mapOf("sys" to com.mengpaw.core.namespace.SysExecutor.commands)
        ).also {
            it.setAgentIdentity(name, framework, model)
            it.setAgentLanguage(globalAgentLang)
            it.configureCacheStrategy(globalEndpoint)
        }

        val msgs = MutableStateFlow<List<ChatMessageUi>>(
            listOf(ChatMessageUi.System("$name 就绪。请描述你想完成的任务。"))
        )
        return AgentSession(name, framework, model, globalEndpoint, globalApiKey, provider, engine, msgs, scroll)
    }

    /** Ensure the default "MengPaw" agent session always exists, with workspace files. */
    private fun ensureDefaultSession() {
        if (!sessions.containsKey("MengPaw")) {
            sessions["MengPaw"] = createSession("MengPaw", null)
        }
        // Bootstrap workspace files if missing (safe: writeIfMissing won't overwrite existing).
        // This ensures the default agent has all preset .md files (agents, soul, boost, trigger, etc.)
        com.mengpaw.kernel.agent.AgentDocs.bootstrap("MengPaw")
    }

    // ── Active agent state ──
    private var _activeAgentName = "MengPaw"

    private fun activeSession(): AgentSession {
        ensureDefaultSession()
        return sessions.getOrPut(_activeAgentName) { createSession(_activeAgentName, null) }
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

    /** Provider/model label for the active agent (shown under agent name). */
    val activeSessionLabel: String get() = activeSession().providerLabel

    /** All agent names currently in the session map. */
    val agentNames: Set<String> get() = sessions.keys

    private var stateObserverJob: Job? = null
    private var messageBindingJob: Job? = null

    /**
     * Reconfigure the LLM provider with real API settings.
     * Applies to all existing sessions and will be used as default for new ones.
     */
    /**
     * Configure LLM for the active agent (or all agents if agentName is null).
     * Each agent can have a different provider/model.
     */
    fun configureLlm(
        endpoint: String,
        apiKey: String,
        model: String,
        agentLang: PromptEngine.AgentLanguage = PromptEngine.AgentLanguage.CHINESE,
        agentName: String? = null  // null = all agents, non-null = specific agent
    ) {
        // Always update global defaults for new agents
        globalEndpoint = endpoint
        globalApiKey = apiKey
        globalModel = model
        globalAgentLang = agentLang

        val targetName = agentName ?: _activeAgentName

        if (agentName == null) {
            // Apply to ALL existing sessions
            val newProvider = defaultProvider()
            sessions.values.forEach { session ->
                try { (session.provider as? java.io.Closeable)?.close() } catch (_: Exception) { }
                session.provider = newProvider
                session.engine.updateLlmProvider(newProvider)
                session.modelName = model
                session.endpoint = endpoint
                session.apiKey = apiKey
                session.engine.setAgentIdentity(session.name, session.framework, model)
                session.engine.setAgentLanguage(agentLang)
                session.engine.configureCacheStrategy(endpoint)
            }
        } else {
            // Apply to a specific agent only
            val session = sessions[targetName] ?: return
            try { (session.provider as? java.io.Closeable)?.close() } catch (_: Exception) { }
            val newProvider = createProviderForSession(endpoint, apiKey, model)
            session.provider = newProvider
            session.engine.updateLlmProvider(newProvider)
            session.modelName = model
            session.endpoint = endpoint
            session.apiKey = apiKey
            session.engine.setAgentIdentity(session.name, session.framework, model)
            session.engine.setAgentLanguage(agentLang)
            session.engine.configureCacheStrategy(endpoint)
        }

        bindActiveSession()

        // ── Auto-start: first time a real API key is configured ──
        // When switching from simulated → real provider, trigger the agent's
        // bootstrap flow: read Boost.md, introduce itself, and engage the user.
        if (apiKey.isNotBlank()) {
            val agentsToCheck = if (agentName == null) sessions.keys.toList()
                else listOf(targetName)
            agentsToCheck.forEach { name ->
                if (name !in bootstrappedAgents) {
                    bootstrappedAgents.add(name)
                    autoStartAgent(name, name) // workspace folder = agent name for default
                }
            }
        }
    }

    private fun createProviderForSession(endpoint: String, apiKey: String, model: String): LlmProvider =
        if (apiKey.isBlank()) SimulatedLlmProvider()
        else try { AdaptiveLlmProvider(endpoint, apiKey, model) }
        catch (e: Exception) {
            com.mengpaw.kernel.KernelLog.w("AgentViewModel", "Cannot create real provider, using simulated: ${e.message}")
            SimulatedLlmProvider()
        }

    /** Get the framework name for an agent, or null if local. */
    fun frameworkFor(name: String): String? = sessions[name]?.framework

    /** Get active CLI namespaces from the current agent's engine. */
    fun activeNamespaces(): List<String> = sessions[_activeAgentName]?.engine?.getActiveNamespaces() ?: listOf("self", "agent", "plugin", "sys")

    /** Get the active agent's engine (for plugin/tool access). */
    fun activeEngine(): AgentEngine? = sessions[_activeAgentName]?.engine

    /** Get (endpoint, model) for an agent. */
    fun agentConfig(name: String): Pair<String, String> {
        val s = sessions[name]
        return (s?.endpoint ?: "") to (s?.modelName ?: "")
    }

    /** Switch to a different agent. Stops old agent engine to prevent orphaned execution. */
    fun switchAgent(name: String) {
        if (name == _activeAgentName) return
        stopAgent() // Stop old agent engine before switching
        // Reset old session state
        val old = sessions[_activeAgentName]
        old?.isRunning?.value = false
        _activeAgentName = name
        bindActiveSession()
    }

    /** Create a new agent with the given name and optional framework. */
    fun createAgent(name: String, framework: String? = null) {
        createAgentWithDetails(name, name, "", framework)
    }

    /**
     * Create a new agent with full details.
     * @param name Agent display name
     * @param workspaceFolder Folder name for workspace (under AGENTS/)
     * @param intro Agent introduction/bio
     * @param framework Optional remote framework
     */
    fun createAgentWithDetails(
        name: String,
        workspaceFolder: String,
        intro: String,
        framework: String? = null
    ) {
        if (sessions.containsKey(name)) return

        // Bootstrap agent documentation files into the workspace folder
        com.mengpaw.kernel.agent.AgentDocs.bootstrap(workspaceFolder)

        // Save profile with intro
        if (intro.isNotBlank()) {
            val profile = com.mengpaw.kernel.agent.AgentProfile(
                agentName = name,
                name = name,
                bio = intro
            )
            com.mengpaw.kernel.agent.AgentProfile.save(workspaceFolder, profile)
        }

        // Create session and switch to new agent
        sessions[name] = createSession(name, framework)
        switchAgent(name)

        // Auto-start: send "启动" — agent reads Boost.md and begins onboarding
        autoStartAgent(name, workspaceFolder)
    }

    /**
     * Auto-start a newly created agent: sends "启动" message so the agent reads
     * Boost.md from its workspace and proactively engages with the user.
     */
    private fun autoStartAgent(agentName: String, workspaceFolder: String) {
        val session = sessions[agentName] ?: return
        bootstrappedAgents.add(agentName)
        // Read Boost.md content for the agent to process on startup
        val boostFile = java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, "$workspaceFolder/boost.md")
        val boostContent = if (boostFile.exists()) {
            try { boostFile.readText() } catch (_: Exception) { "" }
        } else ""

        // Set initial system message, then trigger agent startup
        session.messages.value = listOf(
            ChatMessageUi.System("$agentName 已创建。正在读取工作区引导文件...")
        )

        // Submit startup task — agent reads Boost.md and proactively engages
        viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            val prompt = if (boostContent.isNotBlank()) {
                "启动。请读取并执行你的工作区引导文件 Boost.md，内容如下：\n\n$boostContent"
            } else {
                "启动。请介绍你自己并询问用户如何配置你的身份。"
            }
            submitTask(prompt, maxSteps = 30)
        }
    }

    /** Update Agent language without re-creating the engine. */
    fun setAgentLanguage(lang: PromptEngine.AgentLanguage) {
        globalAgentLang = lang
        sessions.values.forEach { it.engine.setAgentLanguage(lang) }
    }

    /** Inject an Agent-pushed notification into the chat message list. */
    fun notifyAgentMessage(text: String) {
        val session = activeSession()
        session.messages.value = session.messages.value + ChatMessageUi.System("📢 $text")
    }

    /** Update the system banner text (for localization). */
    fun setBanner(text: String) {
        val current = activeSession().messages.value
        if (current.isNotEmpty() && current.first() is ChatMessageUi.System) {
            activeSession().messages.value = listOf(ChatMessageUi.System(text)) + current.drop(1)
        }
    }

    /** Current loop mode — read by submitTask() to choose engine method. */
    var loopMode: LoopMode = LoopMode.GOAL

    /**
     * Submit a task to the currently active agent.
     * Uses the active [loopMode] to select engine execution strategy.
     */
    fun submitTask(task: String, pluginViewModel: PluginViewModel? = null, maxSteps: Int = 50) {
        if (task.isBlank() || _isRunning.value) return
        val session = activeSession()
        if (session.isRunning.value) return

        session.messages.value = session.messages.value + ChatMessageUi.User(task)

        viewModelScope.launch {
            try {
                // Auto-translate for English-optimized models (saves ~40% tokens)
                val doTranslate = translator.shouldTranslate(session.modelName)
                val translatedTask = if (doTranslate) translator.toEnglish(task) else task
                val actualTask = if (doTranslate && translatedTask != task) translatedTask else task

                val traces = mutableListOf<AgentTrace>()

                session.messages.value = session.messages.value + ChatMessageUi.AgentWithTrace(
                    finalContent = "思考中...",
                    traces = emptyList(),
                    isRunning = true
                )

                // Shared step callback for trace collection + token stats + UI update
                val onStep: (com.mengpaw.kernel.AgentEngine.TraceStep) -> Unit = { trace ->
                    traces.add(AgentTrace(trace.step, trace.thought, trace.action, trace.observation))
                    session.provider.lastUsage?.let { usage ->
                        com.mengpaw.shell.ui.components.TokenStatsCollector.record(
                            model = session.modelName,
                            tokens = usage.totalTokens,
                            cacheHit = usage.cacheHitTokens > 0,
                            cacheHitTokens = usage.cacheHitTokens
                        )
                    }
                    val cur = session.messages.value.toMutableList()
                    val ri = cur.indexOfLast { it is ChatMessageUi.AgentWithTrace && it.isRunning }
                    if (ri >= 0) {
                        cur[ri] = ChatMessageUi.AgentWithTrace("思考中...", traces.toList(), isRunning = true)
                        session.messages.value = cur
                    }
                }

                // Execute via the appropriate engine mode
                val result = when (loopMode) {
                    LoopMode.GOAL -> session.engine.run(task = actualTask, maxSteps = maxSteps, onStep = onStep)
                    LoopMode.MISSION -> session.engine.runWithMission(task = actualTask, onStep = onStep)
                    LoopMode.MISSION_PLUS -> session.engine.runWithMission(task = actualTask, onStep = onStep)
                }

                val current = session.messages.value.toMutableList()
                // Translate result back to Chinese for US models
                val displayResult = if (doTranslate) translator.toChinese(result) else result

                val runningIndex = current.indexOfLast {
                    it is ChatMessageUi.AgentWithTrace && it.isRunning
                }
                if (runningIndex >= 0) {
                    current[runningIndex] = ChatMessageUi.AgentWithTrace(
                        finalContent = displayResult,
                        traces = traces.toList(),
                        isRunning = false
                    )
                } else {
                    current.add(ChatMessageUi.Agent(displayResult))
                }

                val suggestion = checkMissingPlugin(result)
                if (suggestion != null && pluginViewModel != null) {
                    current.add(ChatMessageUi.Suggestion(suggestion))
                    pluginViewModel.suggestPluginForCommand(result)
                }

                session.messages.value = current
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal coroutine cancellation — re-throw to maintain cancellation chain
                throw e
            } catch (e: Throwable) {
                // Safety net: catch OOM, unexpected runtime errors, etc.
                // Prevents process crash — degrades gracefully to error message
                com.mengpaw.kernel.KernelLog.w("AgentViewModel", "Task execution failed: ${e.message}")
                val current = session.messages.value.toMutableList()
                val runningIndex = current.indexOfLast {
                    it is ChatMessageUi.AgentWithTrace && it.isRunning
                }
                val errorMsg = if (e is OutOfMemoryError) {
                    "⚠️ 内存不足，任务已中断。请清理会话历史后重试。"
                } else {
                    "⚠️ 执行出错：${e.message?.take(120) ?: "未知错误"}"
                }
                if (runningIndex >= 0) {
                    current[runningIndex] = ChatMessageUi.AgentWithTrace(
                        finalContent = errorMsg,
                        traces = emptyList(),
                        isRunning = false
                    )
                } else {
                    current.add(ChatMessageUi.Agent(errorMsg))
                }
                session.messages.value = current
                session.isRunning.value = false
            }
        }
    }

    fun stopAgent() { activeSession().engine.stop() }

    // ── Trigger task: silent background execution ────────────────────

    /**
     * Called by TriggerEngine.onFire when a CRON/LIFETIME trigger fires.
     *
     * Submits the trigger action as a background task. The agent decides how
     * to handle it based on its workspace rules (trigger.md / agents.md):
     * - Whether to work silently or chat visibly
     * - Whether to push a notification banner via [notify.banner]
     * - What level to use (info/warn/error)
     *
     * Users can edit their agent's trigger.md to customize or disable banners.
     */
    fun submitTriggerTask(trigger: com.mengpaw.kernel.trigger.TriggerEngine.Trigger) {
        val targetAgent = "MengPaw"
        val session = sessions.getOrPut(targetAgent) { createSession(targetAgent, null) }

        // Don't interrupt a running agent; queue to inbox for later pickup
        if (session.isRunning.value) {
            val inbox = java.io.File(com.mengpaw.kernel.DataPaths.AGENT_INBOX)
            inbox.mkdirs()
            java.io.File(inbox, "trigger_${trigger.id}_${System.currentTimeMillis()}.md").writeText(
                "# 触发器任务\n- ID: ${trigger.id}\n- 类型: ${trigger.type}\n- Cron: ${trigger.config}\n- 时间: ${
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                }\n\n${trigger.action}\n"
            )
            return
        }

        // Minimal prompt — behavior governed by trigger.md workspace rules.
        // Agent can read trigger.md via agent.cli to see the default behavior spec.
        val prompt = "[触发器任务 · ${trigger.type}] ${trigger.action}\n(行为规范: workspace/trigger.md)"

        // Light system note so user knows something happened
        session.messages.value = session.messages.value + ChatMessageUi.System(
            "⏰ ${trigger.action.take(40)}..."
        )

        viewModelScope.launch {
            kotlinx.coroutines.delay(200)
            submitTask(prompt, maxSteps = 20)
        }
    }

    // ── Translation middleware (auto for US models) ────────────────────

    private val translator = com.mengpaw.kernel.llm.TranslateMiddleware()

    // ── Retract & Quote ─────────────────────────────────────────────────

    /** Retract the last user message: stop agent, remove user+agent msgs, return text to input. */
    fun retractLastUserMessage(): String? {
        stopAgent()
        val msgs = activeSession().messages.value.toMutableList()
        // Find last user message
        val lastUserIdx = msgs.indexOfLast { it is ChatMessageUi.User }
        if (lastUserIdx < 0) return null
        val userMsg = msgs[lastUserIdx] as ChatMessageUi.User
        // Remove user message and everything after it (agent responses)
        val keep = msgs.take(lastUserIdx)
        activeSession().messages.value = keep
        return userMsg.content
    }

    /** Build a quoted reference string for Agent context. */
    fun formatQuote(msg: ChatMessageUi): String {
        return when (msg) {
            is ChatMessageUi.User -> "> 用户说: ${msg.content.take(200)}"
            is ChatMessageUi.Agent -> "> Agent 回复: ${msg.content.take(200)}"
            is ChatMessageUi.AgentWithTrace -> "> Agent 回复: ${msg.finalContent.take(200)}"
            else -> ""
        }
    }

    /** Whether the given message is the last user message (retractable). */
    fun isLastUserMessage(msg: ChatMessageUi): Boolean {
        val msgs = activeSession().messages.value
        val lastUser = msgs.lastOrNull { it is ChatMessageUi.User }
        return msg == lastUser
    }

    // ── Session History ─────────────────────────────────────────────────

    /** A recorded chat session (persists across newSession() calls and app restarts). */
    data class SessionRecord(
        val id: String,
        val title: String,
        val preview: String,
        val timestamp: Long,
        val messageCount: Int,
        val compacted: Boolean = false,
        val compactedSummary: String = "",
        val agentName: String = "",
        val framework: String? = null     // null = local agent, non-null = remote framework name
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("title", title)
            put("preview", preview)
            put("timestamp", timestamp)
            put("messageCount", messageCount)
            put("compacted", compacted)
            put("compactedSummary", compactedSummary)
            put("agentName", agentName)
            if (framework != null) put("framework", framework)
        }

        companion object {
            fun fromJson(obj: JSONObject): SessionRecord = SessionRecord(
                id = obj.optString("id", ""),
                title = obj.optString("title", ""),
                preview = obj.optString("preview", ""),
                timestamp = obj.optLong("timestamp", 0L),
                messageCount = obj.optInt("messageCount", 0),
                compacted = obj.optBoolean("compacted", false),
                compactedSummary = obj.optString("compactedSummary", ""),
                agentName = obj.optString("agentName", ""),
                framework = if (obj.has("framework")) obj.getString("framework") else null
            )
        }
    }

    private val _sessionHistory = MutableStateFlow<List<SessionRecord>>(emptyList())
    val sessionHistory: StateFlow<List<SessionRecord>> = _sessionHistory.asStateFlow()

    /** JSON file path for session persistence. */
    private val sessionHistoryFile: File
        get() = File(com.mengpaw.kernel.DataPaths.BASE, "session_history.json")

    /** Load session history from disk. Called once at init. */
    private fun loadSessionHistory(): List<SessionRecord> {
        return try {
            val file = sessionHistoryFile
            if (file.exists()) {
                val text = file.readText()
                if (text.isNotBlank()) {
                    val arr = JSONArray(text)
                    (0 until arr.length()).map { i -> SessionRecord.fromJson(arr.getJSONObject(i)) }
                } else emptyList()
            } else emptyList()
        } catch (e: Exception) {
            com.mengpaw.kernel.KernelLog.w("AgentViewModel", "Corrupted session_history.json, resetting: ${e.message}")
            // Delete corrupted file so next save starts fresh
            try { sessionHistoryFile.delete() } catch (_: Exception) {}
            emptyList()
        }
    }

    /** Persist session history to disk. Uses atomic write to prevent corruption on crash. */
    private fun saveSessionHistory() {
        try {
            val file = sessionHistoryFile
            val arr = JSONArray()
            _sessionHistory.value.forEach { arr.put(it.toJson()) }
            // Atomic write: tmp file then rename — avoids partial writes on crash
            file.parentFile?.mkdirs()
            val tmp = java.io.File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(arr.toString(2))
            tmp.renameTo(file)
            if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }
        } catch (e: Exception) {
            com.mengpaw.kernel.KernelLog.w("AgentViewModel", "Failed to save session history: ${e.message}")
        }
    }

    /** Start a new session for a specific agent (switches to it if needed). */
    fun newSessionFor(agentName: String, framework: String? = null) {
        val target = if (framework != null) "$framework/$agentName" else agentName
        if (_activeAgent.value != target) {
            switchAgent(target)
        }
        if (!sessions.containsKey(target)) {
            createAgent(agentName, framework)
        }
        newSession()
    }

    /** Auto-save current session and start a new one. */
    fun newSession() {
        stopAgent() // Stop running agent before clearing messages
        val msgs = activeSession().messages.value.filter { it !is ChatMessageUi.System }
        if (msgs.isNotEmpty()) {
            val firstUser = msgs.firstOrNull { it is ChatMessageUi.User }
            val title = (firstUser as? ChatMessageUi.User)?.content?.take(40) ?: "新会话"
            val preview = msgs.last().let {
                when (it) {
                    is ChatMessageUi.Agent -> it.content.take(60)
                    is ChatMessageUi.User -> it.content.take(60)
                    else -> ""
                }
            }
            val currentAgent = _activeAgent.value
            val record = SessionRecord(
                id = "sess_${System.currentTimeMillis()}",
                title = title, preview = preview,
                timestamp = System.currentTimeMillis(),
                messageCount = msgs.size,
                agentName = currentAgent,
                framework = activeSession().framework
            )
            _sessionHistory.value = (_sessionHistory.value + record).takeLast(100)
            saveSessionHistory()
        }
        activeSession().messages.value = listOf(ChatMessageUi.Agent("新会话已创建。"))
    }

    /** Compact a session — keep summary, mark as read-only. */
    fun compactSession(id: String) {
        _sessionHistory.value = _sessionHistory.value.map {
            if (it.id == id) it.copy(compacted = true, compactedSummary = "已压缩: ${it.preview.take(100)}")
            else it
        }
        saveSessionHistory()
    }

    /** Repair a session — fixes truncated markdown / unclosed syntax caused by abnormal interruption. */
    fun repairSession(id: String) {
        val session = sessions.values.firstOrNull()
        if (session == null) return
        val msgs = session.messages.value.toMutableList()
        var changed = false
        for (i in msgs.indices) {
            val msg = msgs[i]
            if (msg is ChatMessageUi.Agent) {
                var text = msg.content
                // Fix unclosed code fences ```
                val fenceCount = text.count { it == '`' } / 3
                if (fenceCount % 2 != 0) {
                    text = text.trimEnd() + "\n```"
                    changed = true
                }
                // Fix unclosed bold **
                val boldCount = text.split("**").size - 1
                if (boldCount % 2 != 0) {
                    text = text.trimEnd() + "**"
                    changed = true
                }
                // Fix unclosed italics *
                val italicCount = text.replace("**", "").count { it == '*' }
                if (italicCount % 2 != 0) {
                    text = text.trimEnd() + "*"
                    changed = true
                }
                if (changed) msgs[i] = ChatMessageUi.Agent(text)
            }
        }
        if (changed) {
            session.messages.value = msgs
            // Mark session record as repaired
            _sessionHistory.value = _sessionHistory.value.map {
                if (it.id == id) it.copy(compactedSummary = "已修复: ${it.preview.take(60)}") else it
            }
            saveSessionHistory()
        }
    }

    /** Delete a session record. */
    fun deleteSession(id: String) {
        _sessionHistory.value = _sessionHistory.value.filter { it.id != id }
        saveSessionHistory()
    }

    /** Toggle visibility of compacted sessions. */
    private val _hideCompacted = MutableStateFlow(false)
    val hideCompacted: StateFlow<Boolean> = _hideCompacted.asStateFlow()
    fun toggleHideCompacted() { _hideCompacted.value = !_hideCompacted.value }

    /** Get sessions for the current agent (excluding compacted if hidden). */
    fun getSessions(): List<SessionRecord> {
        val all = _sessionHistory.value.sortedByDescending { it.timestamp }
        return if (_hideCompacted.value) all.filter { !it.compacted } else all
    }

    /** Sessions grouped by agent name (local + framework). */
    data class AgentSessionGroup(
        val agentName: String,
        val framework: String?,        // null = local, non-null = remote framework
        val sessions: List<SessionRecord>
    )

    /** Sessions grouped by local agents (framework == null), sorted by most recent. */
    fun getLocalAgentGroups(): List<AgentSessionGroup> {
        val all = _sessionHistory.value
            .filter { !_hideCompacted.value || !it.compacted }
        return all
            .filter { it.framework == null }
            .groupBy { it.agentName.ifBlank { "MengPaw" } }
            .map { (name, sessions) -> AgentSessionGroup(name, null, sessions.sortedByDescending { it.timestamp }) }
            .sortedByDescending { it.sessions.firstOrNull()?.timestamp ?: 0L }
    }

    /** Sessions grouped by framework → agent, for the frameworks section. */
    fun getFrameworkGroups(): List<Pair<String, List<AgentSessionGroup>>> {
        val all = _sessionHistory.value
            .filter { !_hideCompacted.value || !it.compacted }
        return all
            .filter { it.framework != null }
            .groupBy { it.framework!! }
            .mapValues { (_, sessions) ->
                sessions.groupBy { it.agentName.ifBlank { "Agent" } }
                    .map { (name, s) -> AgentSessionGroup(name, sessions.first().framework, s.sortedByDescending { it.timestamp }) }
                    .sortedByDescending { it.sessions.firstOrNull()?.timestamp ?: 0L }
            }
            .toList()
            .sortedByDescending { (_, groups) -> groups.maxOfOrNull { it.sessions.firstOrNull()?.timestamp ?: 0L } ?: 0L }
    }

    /** All known framework names (even those without sessions yet, from SidebarContent contacts). */
    fun knownFrameworks(): List<String> = sessions.values
        .mapNotNull { it.framework }
        .distinct()

    // ── Internals ──

    private fun bindActiveSession() {
        ensureDefaultSession()
        val session = sessions[_activeAgentName] ?: return
        _activeAgent.value = _activeAgentName

        // FIX U1: Reactively bind session.messages → _messages so UI updates on every message change
        messageBindingJob?.cancel()
        messageBindingJob = viewModelScope.launch {
            session.messages.collect { msgs -> _messages.value = msgs }
        }

        // Re-bind state observer to the new engine
        stateObserverJob?.cancel()
        stateObserverJob = viewModelScope.launch {
            session.engine.state.collect { state ->
                when (state) {
                    is AgentState.Idle -> {
                        session.isRunning.value = false; _isRunning.value = false
                        session.inputEnabled.value = true; _inputEnabled.value = true
                        com.mengpaw.plugin.pad.PadPlugin.updateState(com.mengpaw.plugin.pad.PadPlugin.DotState.IDLE)
                    }
                    is AgentState.Running -> {
                        session.isRunning.value = true; _isRunning.value = true
                        session.inputEnabled.value = false; _inputEnabled.value = false
                        com.mengpaw.plugin.pad.PadPlugin.updateState(com.mengpaw.plugin.pad.PadPlugin.DotState.WORKING)
                    }
                    is AgentState.Finished -> {
                        session.isRunning.value = false; _isRunning.value = false
                        session.inputEnabled.value = true; _inputEnabled.value = true
                        com.mengpaw.plugin.pad.PadPlugin.updateState(com.mengpaw.plugin.pad.PadPlugin.DotState.IDLE)
                    }
                    is AgentState.Error -> {
                        session.isRunning.value = false; _isRunning.value = false
                        session.inputEnabled.value = true; _inputEnabled.value = true
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
        // Restore persisted session history
        _sessionHistory.value = loadSessionHistory()
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
    /** Stable unique ID for LazyColumn key — prevents animation/state bugs during streaming. */
    abstract val stableId: String
    data class User(val content: String) : ChatMessageUi() {
        override val stableId get() = "u_${content.hashCode()}"
    }
    data class Agent(val content: String) : ChatMessageUi() {
        override val stableId get() = "a_${content.hashCode()}"
    }
    data class AgentWithTrace(
        val finalContent: String,
        val traces: List<AgentTrace>,
        val isRunning: Boolean = false
    ) : ChatMessageUi() {
        override val stableId get() = "t_${traces.size}_${finalContent.hashCode()}"
    }
    data class System(val content: String) : ChatMessageUi() {
        override val stableId get() = "s_${content.hashCode()}"
    }
    data class Suggestion(val suggestion: PluginSuggestion) : ChatMessageUi() {
        override val stableId get() = "sg_${suggestion.pluginId}"
    }
}
