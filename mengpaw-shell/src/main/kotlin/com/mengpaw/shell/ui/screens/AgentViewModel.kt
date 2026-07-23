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
import com.mengpaw.kernel.llm.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        saveCurrentSession()
        sessions.values.forEach { session ->
            try { (session.provider as? java.io.Closeable)?.close() } catch (_: Exception) {}
        }
    }

    /** Autosave active session every 30s and on pause. */
    private val autoSaveJob = kotlinx.coroutines.Job()

    private fun scheduleAutoSave() {
        viewModelScope.launch {
            kotlinx.coroutines.delay(30_000)
            saveCurrentSession()
            scheduleAutoSave()
        }
    }

    /** Track current session ID for per-session save. */
    private var currentSessionId: String = ""

    /** Persist active session messages so they survive process death. */
    private fun saveCurrentSession() {
        try {
            val session = sessions[_activeAgentName] ?: return
            val msgs = session.messages.value.filter { it !is ChatMessageUi.System }
            if (msgs.isEmpty()) return
            val arr = messagesToJson(msgs)
            val file = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "current_session.json")
            atomicWriteJson(file, arr)
            // Also save to per-session file if we have an active session ID
            if (currentSessionId.isNotBlank()) {
                saveSessionById(currentSessionId, msgs)
            }
        } catch (_: Exception) {}
    }

    /** Save a specific session's messages to a per-session file. */
    private fun saveSessionById(sessionId: String, msgs: List<ChatMessageUi>) {
        try {
            val nonSystem = msgs.filter { it !is ChatMessageUi.System }
            if (nonSystem.isEmpty()) return
            val dir = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "sessions")
            dir.mkdirs()
            val file = java.io.File(dir, "$sessionId.json")
            val arr = messagesToJson(nonSystem)
            atomicWriteJson(file, arr)
        } catch (_: Exception) {}
    }

    /** Load a session's messages from its per-session file. */
    private fun loadSessionMessages(sessionId: String): List<ChatMessageUi> {
        try {
            val file = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "sessions/$sessionId.json")
            if (!file.exists()) return emptyList()
            val text = file.readText()
            if (text.isBlank()) return emptyList()
            return jsonToMessages(org.json.JSONArray(text))
        } catch (_: Exception) { return emptyList() }
    }

    private fun messagesToJson(msgs: List<ChatMessageUi>): org.json.JSONArray {
        val arr = org.json.JSONArray()
        msgs.forEach { msg ->
            val obj = org.json.JSONObject()
            when (msg) {
                is ChatMessageUi.User -> { obj.put("type", "user"); obj.put("text", msg.content) }
                is ChatMessageUi.Agent -> {
                    obj.put("type", "agent"); obj.put("text", msg.content)
                    msg.executionMode?.let { obj.put("executionMode", it) }
                    msg.agentRef?.let { obj.put("agentRef", it) }
                }
                is ChatMessageUi.AgentWithTrace -> {
                    obj.put("type", "agent_trace"); obj.put("text", msg.finalContent)
                    val traceArr = org.json.JSONArray()
                    msg.traces.forEach { t ->
                        val tObj = org.json.JSONObject()
                        tObj.put("step", t.step); tObj.put("thought", t.thought)
                        tObj.put("action", t.action ?: ""); tObj.put("observation", t.observation ?: "")
                        traceArr.put(tObj)
                    }
                    obj.put("traces", traceArr)
                    msg.executionMode?.let { obj.put("executionMode", it) }
                    msg.agentRef?.let { obj.put("agentRef", it) }
                }
                else -> return@forEach
            }
            arr.put(obj)
        }
        return arr
    }

    private fun jsonToMessages(arr: org.json.JSONArray): List<ChatMessageUi> {
        val msgs = mutableListOf<ChatMessageUi>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val type = obj.optString("type", "")
            val txt = obj.optString("text", "")
            if (txt.isBlank()) continue
            when (type) {
                "user" -> msgs.add(ChatMessageUi.User(txt))
                "agent" -> msgs.add(ChatMessageUi.Agent(txt,
                    executionMode = obj.optString("executionMode", "").ifEmpty { null },
                    agentRef = obj.optString("agentRef", "").ifEmpty { null }))
                "agent_trace" -> {
                    val traces = mutableListOf<AgentTrace>()
                    val traceArr = obj.optJSONArray("traces")
                    if (traceArr != null) for (j in 0 until traceArr.length()) {
                        val t = traceArr.getJSONObject(j)
                        traces.add(AgentTrace(t.optInt("step", 0), t.optString("thought", ""),
                            t.optString("action", "").ifEmpty { null },
                            t.optString("observation", "").ifEmpty { null }))
                    }
                    msgs.add(ChatMessageUi.AgentWithTrace(txt, traces, isRunning = false,
                        executionMode = obj.optString("executionMode", "").ifEmpty { null },
                        agentRef = obj.optString("agentRef", "").ifEmpty { null }))
                }
            }
        }
        return msgs
    }

    private fun atomicWriteJson(file: java.io.File, arr: org.json.JSONArray) {
        file.parentFile?.mkdirs()
        val tmp = java.io.File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(arr.toString(2))
        tmp.renameTo(file)
        if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }
    }

    /** Restore last session messages from disk. Returns true if restored. */
    private fun restoreCurrentSession(): Boolean {
        return try {
            val file = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "current_session.json")
            if (!file.exists()) return false
            val text = file.readText()
            if (text.isBlank()) return false
            val arr = org.json.JSONArray(text)
            val msgs = jsonToMessages(arr)
            if (msgs.isNotEmpty()) {
                // Skip restoration if session ended with an error (corrupted state)
                val lastMsg = msgs.lastOrNull()
                val endsWithError = lastMsg is ChatMessageUi.Agent && lastMsg.content.startsWith("⚠️ 执行出错")
                if (endsWithError) {
                    try { file.delete() } catch (_: Exception) {}
                    return false
                }
                activeSession().messages.value = msgs
                // Also add to sidebar history
                val preview = msgs.firstOrNull()?.let {
                    when (it) {
                        is ChatMessageUi.User -> it.content.take(40)
                        is ChatMessageUi.Agent -> it.content.take(40)
                        else -> ""
                    }
                } ?: ""
                val record = SessionRecord(
                    id = "sess_restored", title = preview, preview = preview,
                    timestamp = file.lastModified(), messageCount = msgs.size,
                    agentName = _activeAgentName
                )
                _sessionHistory.value = (_sessionHistory.value.filter { it.id != "sess_restored" } + record).takeLast(100)
                saveSessionHistory()
            }
            msgs.isNotEmpty()
        } catch (_: Exception) {
            // Delete corrupted file so next save starts fresh
            try { java.io.File(com.mengpaw.kernel.DataPaths.BASE, "current_session.json").delete() } catch (_: Exception) {}
            false
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
        if (globalApiKey.isBlank()) UnconfiguredLlmProvider()
        else try { AdaptiveLlmProvider(globalEndpoint, globalApiKey, globalModel) } catch (_: Exception) { UnconfiguredLlmProvider() }

    private fun createProviderForSession(endpoint: String, apiKey: String, model: String): LlmProvider =
        if (apiKey.isBlank()) UnconfiguredLlmProvider()
        else try { AdaptiveLlmProvider(endpoint, apiKey, model) }
        catch (e: Exception) {
            com.mengpaw.kernel.KernelLog.w("AgentViewModel", "Cannot create real provider, using unconfigured: ${e.message}")
            UnconfiguredLlmProvider()
        }

    private fun createSession(name: String, framework: String?): AgentSession {
        val model = globalModel.ifBlank { "unknown" }
        val provider = defaultProvider()

        // Scroll context manager — eviction index + recall per agent
        val scroll = ScrollContextManager(name)

        // Memory middleware: inject essential agent docs only (soul + agents)
        val memoryMw = AgentMiddleware { prompt, agentName ->
            val soul = com.mengpaw.kernel.agent.AgentDocs.readSoulDoc(agentName)
            if (soul.isNotBlank() && soul !in prompt) {
                "$prompt\n\n## 智能体身份\n\n$soul"
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
            it.integrityProvider = com.mengpaw.core.security.IntegrityGuard.globalInstance
            it.setAgentIdentity(name, framework, model)
            it.setAgentLanguage(globalAgentLang)
            it.configureCacheStrategy(globalEndpoint)
        }

        val msgs = MutableStateFlow<List<ChatMessageUi>>(
            if (globalApiKey.isBlank())
                listOf(ChatMessageUi.System("欢迎使用 MengPaw。请先进入设置 → 框架设置，配置 API Key 和模型。"))
            else
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
     * Apply a pre-created LLM provider to the active agent.
     * Called by AgentRuntime on IO thread — lightweight, no network calls.
     */
    fun applyConfiguration(
        endpoint: String,
        apiKey: String,
        model: String,
        provider: com.mengpaw.kernel.llm.LlmProvider,
        agentLang: PromptEngine.AgentLanguage = PromptEngine.AgentLanguage.CHINESE
    ) {
        globalEndpoint = endpoint
        globalApiKey = apiKey
        globalModel = model
        globalAgentLang = agentLang

        sessions.values.forEach { session ->
            try { (session.provider as? java.io.Closeable)?.close() } catch (_: Exception) { }
            session.provider = provider
            session.engine.updateLlmProvider(provider)
            session.modelName = model
            session.endpoint = endpoint
            session.apiKey = apiKey
            session.engine.setAgentIdentity(session.name, session.framework, model)
            session.engine.setAgentLanguage(agentLang)
            session.engine.configureCacheStrategy(endpoint)
        }
        bindActiveSession()
    }

    /** Set a system message for the loading state. Called from AgentRuntime. */
    fun setInitializingMessage(text: String) {
        val session = sessions[_activeAgentName] ?: return
        session.messages.value = listOf(ChatMessageUi.System(text))
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
        // Verify agent exists (directory on disk or existing session) before switching
        val agentDir = java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, name)
        if (!agentDir.exists() && !sessions.containsKey(name)) return
        stopAgent() // Stop old agent engine before switching
        // Reset old session state
        val old = sessions[_activeAgentName]
        old?.isRunning?.value = false
        // Create session if directory exists but session doesn't (e.g., agent created externally)
        if (!sessions.containsKey(name) && agentDir.exists() && agentDir.isDirectory) {
            sessions[name] = createSession(name, null)
        }
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

    // ── Active input tags (slash commands + @mentions) ───────────────

    private val _activeTags = MutableStateFlow<List<InputTag>>(emptyList())
    val activeTags: StateFlow<List<InputTag>> = _activeTags.asStateFlow()

    /** 添加标签，同类型替换旧值。 */
    fun addTag(tag: InputTag) {
        val current = _activeTags.value.toMutableList()
        when (tag) {
            is InputTag.Mode -> {
                current.removeAll { it is InputTag.Mode }
                when (tag.mode) {
                    ExecutionMode.MISSION -> loopMode = LoopMode.MISSION
                    else -> {} // RESEARCH/TRANSLATE/DREAM 不改变 loopMode
                }
            }
            is InputTag.AgentRef -> {
                current.removeAll { it is InputTag.AgentRef && it.agentName == tag.agentName }
            }
        }
        current.add(tag)
        _activeTags.value = current
    }

    /** 移除标签，模式标签移除时回退到 GOAL。 */
    fun removeTag(tag: InputTag) {
        _activeTags.value = _activeTags.value.filter { it != tag }
        if (tag is InputTag.Mode && _activeTags.value.none { it is InputTag.Mode }) {
            loopMode = LoopMode.GOAL
        }
    }

    /** 清除所有标签。 */
    fun clearTags() {
        _activeTags.value = emptyList()
        loopMode = LoopMode.GOAL
    }

    /** 获取可用于 @mention 的 Agent 列表（本地 + 框架）。 */
    fun agentNamesForMention(): List<Pair<String, String?>> {
        return sessions.keys.map { it to sessions[it]?.framework }
    }

    /**
     * Submit a task to the currently active agent.
     * Uses the active [loopMode] to select engine execution strategy.
     */
    fun submitTask(
        task: String,
        pluginViewModel: PluginViewModel? = null,
        maxSteps: Int = 50,
        executionMode: ExecutionMode? = null,
        agentRef: String? = null
    ) {
        if (task.isBlank() || _isRunning.value) return
        val session = activeSession()
        if (session.isRunning.value) return

        session.messages.value = session.messages.value + ChatMessageUi.User(task)

        viewModelScope.launch {
            // ── 执行模式分发变量（在 try 外，catch 中也需要）────
            val savedLoopMode = loopMode
            val modePrefix = executionMode?.prefix
            try {
                // /Mission: 临时覆盖 loopMode
                if (executionMode == ExecutionMode.MISSION) {
                    loopMode = LoopMode.MISSION
                }

                // /Dream: 后台执行 — 直接 LLM 调用，不触发主引擎状态变化
                if (executionMode == ExecutionMode.DREAM) {
                    val dreamTask = task
                    launch {
                        try {
                            val dreamPrompt = """
[后台 Dream 任务 — 静默执行，完成后仅推送结果]

任务：$dreamTask

请用简洁的方式完成任务，并将结果整理为一段摘要。
""".trimIndent()
                            val dreamResult = session.provider.complete(dreamPrompt)
                            session.messages.value = session.messages.value +
                                ChatMessageUi.System("💤 Dream 完成:\n\n${dreamResult.take(500)}")
                        } catch (e: Exception) {
                            session.messages.value = session.messages.value +
                                ChatMessageUi.System("💤 Dream 异常: ${e.message?.take(120) ?: "未知错误"}")
                        }
                    }
                    // 不添加 AgentWithTrace，不锁定输入
                    loopMode = savedLoopMode
                    return@launch
                }

                // Auto-translate for English-optimized models (saves ~40% tokens)
                val doTranslate = translator.shouldTranslate(session.modelName)
                val translatedTask = if (doTranslate) translator.toEnglish(task) else task
                var actualTask = if (doTranslate && translatedTask != task) translatedTask else task

                // /Research /Translate: 包装提示词（在翻译之后）
                actualTask = when (executionMode) {
                    ExecutionMode.RESEARCH -> """
深度研究模式 — 请执行以下研究任务：

1. 多角度搜索相关信息（优先使用 Tavily / 网络搜索）
2. 交叉验证每条信息的可靠性
3. 给出结构化的综合结论，附信息来源

研究课题：$actualTask
""".trimIndent()
                    ExecutionMode.TRANSLATE -> "翻译以下内容：\n\n$actualTask"
                    else -> actualTask
                }
                // ── 模式分发结束 ─────────────────────────────────

                // ── 跨会话召回：匹配相关记忆 ──
                val keywords = actualTask.split(Regex("[\\s，。！？,.!?：:()（）]+"))
                    .map { it.trim() }.filter { it.length >= 2 }
                    .filterNot { it in setOf("的", "是", "我", "你", "他", "她", "the", "a", "an", "is", "are", "to", "of", "in", "请", "帮", "一个", "这个", "那个") }
                    .take(5)
                val recalledMemory = withContext(Dispatchers.IO) {
                    com.mengpaw.kernel.agent.AgentDocs.recallMemory(
                        _activeAgentName, keywords
                    )
                }
                val recallPrefix = if (recalledMemory.isNotBlank()) "$recalledMemory\n\n---\n\n" else ""

                // Build conversation history context (exclude system messages + current task)
                val historyMsgs = session.messages.value.filter {
                    it !is ChatMessageUi.System && it !is ChatMessageUi.AgentWithTrace
                }
                val contextPrefix = if (historyMsgs.size > 1) {
                    // Inject previous conversation so Agent sees context
                    "## 先前对话\n" + historyMsgs.dropLast(1).joinToString("\n") { msg ->
                        when (msg) {
                            is ChatMessageUi.User -> "用户: ${msg.content.take(500)}"
                            is ChatMessageUi.Agent -> "助手: ${msg.content.take(500)}"
                            else -> ""
                        }
                    }.take(3000) + "\n\n---\n\n新任务: $actualTask"
                } else actualTask

                val traces = mutableListOf<AgentTrace>()

                session.messages.value = session.messages.value + ChatMessageUi.AgentWithTrace(
                    finalContent = "思考中...",
                    traces = emptyList(),
                    isRunning = true,
                    executionMode = modePrefix,
                    agentRef = agentRef
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
                        cur[ri] = ChatMessageUi.AgentWithTrace("思考中...", traces.toList(),
                            isRunning = true, executionMode = modePrefix, agentRef = agentRef)
                        session.messages.value = cur
                    }
                }

                // Reset stale state from previous runs before starting
                session.engine.resetLoopDetection()
                try { session.engine.stop() } catch (_: Exception) {}

                // Execute via the appropriate engine mode
                val finalTask = recallPrefix + contextPrefix
                val result = when (loopMode) {
                    LoopMode.GOAL -> session.engine.run(task = finalTask, maxSteps = maxSteps, onStep = onStep)
                    LoopMode.MISSION -> session.engine.runWithMission(task = finalTask, onStep = onStep)
                    LoopMode.MISSION_PLUS -> session.engine.runWithMission(task = finalTask, onStep = onStep)
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
                        isRunning = false,
                        executionMode = modePrefix,
                        agentRef = agentRef
                    )
                } else {
                    current.add(ChatMessageUi.Agent(displayResult,
                        executionMode = modePrefix, agentRef = agentRef))
                }

                val suggestion = checkMissingPlugin(result)
                if (suggestion != null && pluginViewModel != null) {
                    current.add(ChatMessageUi.Suggestion(suggestion))
                    pluginViewModel.suggestPluginForCommand(result)
                }

                session.messages.value = current
                loopMode = savedLoopMode

                // ── 自动摘要：对话结束后提取关键信息存入 memory ──
                launch {
                    try {
                        val summaryPrompt = """
提取以下对话中用户提到的关键信息（偏好、需求、决策、技术环境），
用 1-2 句中文摘要，只提取值得长期记住的事实。

用户消息: ${task.take(300)}
助手结果: ${displayResult.take(300)}

摘要：""".trimIndent()
                        val summary = session.provider.complete(summaryPrompt).take(200)
                        if (summary.isNotBlank() && summary.length > 10) {
                            withContext(Dispatchers.IO) {
                                com.mengpaw.kernel.agent.AgentDocs.appendMemory(_activeAgentName, summary)
                            }
                        }
                    } catch (_: Exception) {}
                }
                // ── 自动摘要结束 ──
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal coroutine cancellation — re-throw to maintain cancellation chain
                throw e
            } catch (e: Throwable) {
                // Safety net: catch OOM, unexpected runtime errors, etc.
                // Prevents process crash — degrades gracefully to error message
                com.mengpaw.kernel.KernelLog.w("AgentViewModel", "Task execution failed: ${e.message}")
                // Stop engine to prevent stale state on retry
                try { session.engine.stop() } catch (_: Exception) {}
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
                        isRunning = false,
                        executionMode = modePrefix,
                        agentRef = agentRef
                    )
                } else {
                    current.add(ChatMessageUi.Agent(errorMsg,
                        executionMode = modePrefix, agentRef = agentRef))
                }
                session.messages.value = current
                // 恢复原始 loopMode
                loopMode = savedLoopMode
                // Fully sync all running/input state
                session.isRunning.value = false
                _isRunning.value = false
                session.inputEnabled.value = true
                _inputEnabled.value = true
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
            // Auto-indexed title: 会话 #N per agent
            val existingCount = _sessionHistory.value.count { it.agentName == _activeAgentName }
            val title = "会话 #${existingCount + 1}"
            val preview = msgs.last().let {
                when (it) {
                    is ChatMessageUi.Agent -> it.content.take(60)
                    is ChatMessageUi.User -> it.content.take(60)
                    else -> ""
                }
            }
            val currentAgent = _activeAgent.value
            val sessId = "sess_${System.currentTimeMillis()}"
            // Save messages to per-session file so switching works
            saveSessionById(sessId, msgs)
            currentSessionId = sessId
            val record = SessionRecord(
                id = sessId,
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

    /** Switch to a saved session, restoring its messages. */
    fun switchToSession(record: SessionRecord) {
        // Save current session first
        val currentMsgs = activeSession().messages.value.filter { it !is ChatMessageUi.System }
        if (currentMsgs.isNotEmpty()) {
            saveSessionById("sess_current_${_activeAgentName}", currentMsgs)
        }
        // Switch agent if needed
        if (record.agentName.isNotBlank() && record.agentName != _activeAgentName) {
            val target = if (record.framework != null) "${record.framework}/${record.agentName}" else record.agentName
            switchAgent(target)
        }
        // Load saved messages
        val loaded = loadSessionMessages(record.id)
        if (loaded.isNotEmpty()) {
            currentSessionId = record.id
            activeSession().messages.value = loaded
        } else {
            currentSessionId = ""
            activeSession().messages.value = listOf(
                ChatMessageUi.Agent("已切换到「${record.title}」，但该会话暂无已保存的消息记录。")
            )
        }
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
                    }
                    is AgentState.Running -> {
                        session.isRunning.value = true; _isRunning.value = true
                        session.inputEnabled.value = false; _inputEnabled.value = false
                    }
                    is AgentState.Finished -> {
                        session.isRunning.value = false; _isRunning.value = false
                        session.inputEnabled.value = true; _inputEnabled.value = true
                    }
                    is AgentState.Error -> {
                        session.isRunning.value = false; _isRunning.value = false
                        session.inputEnabled.value = true; _inputEnabled.value = true
                        session.messages.value = session.messages.value + ChatMessageUi.Agent("⚠️ ${state.message}")
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
        // Restore last active session messages
        if (!restoreCurrentSession()) {
            // Only show welcome if no saved session
        }
        // Start periodic auto-save
        scheduleAutoSave()
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

// ── Data types ──

/**
 * Placeholder provider shown when no API key is configured.
 * Returns a helpful message instead of fake simulated responses.
 */
class UnconfiguredLlmProvider : LlmProvider {
    override suspend fun complete(prompt: String): String =
        "请先配置 API Key：打开设置 → 框架设置 → 选择服务商 → 粘贴 API Key → 退出设置。"

    override suspend fun completeWithMessages(messages: List<Map<String, String>>): String =
        complete("")

    override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String {
        val msg = complete(prompt)
        msg.forEach { onToken(it.toString()) }
        return msg
    }

    override fun info(): ProviderInfo = ProviderInfo(
        "未配置", "none", ProviderType.LOCAL
    )

    override var lastUsage: TokenUsage? = null
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
    data class Agent(
        val content: String,
        val executionMode: String? = null,
        val agentRef: String? = null
    ) : ChatMessageUi() {
        override val stableId get() = "a_${content.hashCode()}_${executionMode ?: ""}_${agentRef ?: ""}"
    }
    data class AgentWithTrace(
        val finalContent: String,
        val traces: List<AgentTrace>,
        val isRunning: Boolean = false,
        val executionMode: String? = null,
        val agentRef: String? = null
    ) : ChatMessageUi() {
        override val stableId get() = "t_${traces.size}_${finalContent.hashCode()}_${executionMode ?: ""}_${agentRef ?: ""}"
    }
    data class System(val content: String) : ChatMessageUi() {
        override val stableId get() = "s_${content.hashCode()}"
    }
    data class Suggestion(val suggestion: PluginSuggestion) : ChatMessageUi() {
        override val stableId get() = "sg_${suggestion.pluginId}"
    }
}

/** 执行模式 — 用户通过 /命令 主动触发，非自动检测。 */
enum class ExecutionMode(val label: String, val prefix: String) {
    MISSION("Mission", "/Mission"),
    RESEARCH("Research", "/Research"),
    TRANSLATE("Translate", "/Translate"),
    DREAM("Dream", "/Dream");
}

/** 输入框标签 — 斜杠命令或 @mention 的活跃状态。 */
sealed class InputTag {
    abstract val label: String
    data class Mode(val mode: ExecutionMode) : InputTag() {
        override val label get() = mode.prefix
    }
    data class AgentRef(val agentName: String) : InputTag() {
        override val label get() = "@$agentName"
    }
}
