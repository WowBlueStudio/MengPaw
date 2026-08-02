// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mengpaw.kernel.AgentEngine
import com.mengpaw.kernel.AgentState
import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.PromptEngine
import com.mengpaw.shell.ui.screens.model.AgentSession
import com.mengpaw.shell.ui.screens.model.AgentTrace
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import com.mengpaw.shell.ui.screens.model.ExecutionMode
import com.mengpaw.shell.ui.screens.model.InputTag
import com.mengpaw.shell.ui.screens.model.PendingTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ViewModel for the main agent chat screen.
 * Manages multiple agent sessions — each agent has its own AgentEngine and message history.
 */
class AgentViewModel : ViewModel() {

    override fun onCleared() {
        super.onCleared()
        sessionPersistence.saveCurrentSession()
        // Unwire static trigger callback to prevent ViewModel memory leak
        com.mengpaw.shell.service.AgentRuntime.unwireTriggers()
        sessions.values.forEach { session ->
            try { (session.provider as? java.io.Closeable)?.close() } catch (_: Exception) {}
        }
    }

    // ── Multi-session store ──
    private val sessions = mutableMapOf<String, AgentSession>()

    // Track which agents have completed the bootstrap startup flow.
    // Prevents re-triggering on every config change.
    private val bootstrappedAgents = mutableSetOf<String>()

    // ── Active agent state ──
    private var _activeAgentName = "MengPaw"

    // ── Helper services ─────────────────────────────────────────────────

    private val inputTagManager = InputTagManager()

    private val sessionPersistence = SessionPersistenceService(
        sessions = sessions,
        viewModelScope = viewModelScope,
        getActiveAgentName = { _activeAgentName },
        onSwitchAgent = { switchAgent(it) },
        onStopAgent = { stopAgent() },
        onCreateAgent = { name, framework -> createAgent(name, framework) },
    )

    private val sessionFactory = AgentSessionFactory(
        sessions = sessions,
        viewModelScope = viewModelScope,
        bootstrappedAgents = bootstrappedAgents,
        onSubmitTask = { task, maxSteps -> submitTask(task, maxSteps = maxSteps) },
        onSwitchAgent = { switchAgent(it) },
    )

    // ── Delegated state from helpers ──

    val activeTags: StateFlow<List<InputTag>> = inputTagManager.activeTags

    val sessionHistory: StateFlow<List<SessionPersistenceService.SessionRecord>>
        get() = sessionPersistence.sessionHistory
    val hideCompacted: StateFlow<Boolean> get() = sessionPersistence.hideCompacted
    val hideArchived: StateFlow<Boolean> get() = sessionPersistence.hideArchived

    // ── Delegated tag methods ──

    fun addTag(tag: InputTag) = inputTagManager.addTag(tag)
    fun removeTag(tag: InputTag) = inputTagManager.removeTag(tag)
    fun clearTags() = inputTagManager.clearTags()
    fun agentNamesForMention(): List<Pair<String, String?>> = inputTagManager.agentNamesForMention(sessions)

    /** Delegate loopMode to InputTagManager. */
    var loopMode: LoopMode
        get() = inputTagManager.loopMode
        set(v) { inputTagManager.loopMode = v }

    // ── Delegated session persistence methods ──

    fun getSessions(): List<SessionPersistenceService.SessionRecord> = sessionPersistence.getSessions()
    fun getLocalAgentGroups(): List<SessionPersistenceService.AgentSessionGroup> =
        sessionPersistence.getLocalAgentGroups()
    fun getFrameworkGroups(): List<Pair<String, List<SessionPersistenceService.AgentSessionGroup>>> =
        sessionPersistence.getFrameworkGroups()
    fun knownFrameworks(): List<String> = sessionPersistence.knownFrameworks()
    fun newSessionFor(agentName: String, framework: String? = null) =
        sessionPersistence.newSessionFor(agentName, framework)
    fun newSession() = sessionPersistence.newSession()
    fun switchToSession(record: SessionPersistenceService.SessionRecord) =
        sessionPersistence.switchToSession(record)
    fun compactSession(id: String) = sessionPersistence.compactSession(id)
    fun repairSession(id: String) = sessionPersistence.repairSession(id)
    fun deleteSession(id: String) = sessionPersistence.deleteSession(id)
    fun toggleHideCompacted() = sessionPersistence.toggleHideCompacted()
    fun toggleHideArchived() = sessionPersistence.toggleHideArchived()

    // ── Delegated factory methods ──

    fun createAgent(name: String, framework: String? = null) =
        sessionFactory.createAgent(name, framework)

    fun createAgentWithDetails(
        name: String, workspaceFolder: String, intro: String, framework: String? = null
    ) = sessionFactory.createAgentWithDetails(name, workspaceFolder, intro, framework)

    // ── Active session helpers ──

    private fun activeSession(): AgentSession {
        sessionFactory.ensureDefaultSession()
        return sessions.getOrPut(_activeAgentName) { sessionFactory.createSession(_activeAgentName, null) }
    }

    // ── Observable state (backed by active session) ──
    private val _messages = MutableStateFlow<List<ChatMessageUi>>(emptyList())
    val messages: StateFlow<List<ChatMessageUi>> = _messages.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _inputEnabled = MutableStateFlow(true)
    val inputEnabled: StateFlow<Boolean> = _inputEnabled.asStateFlow()

    private val _pendingTasks = MutableStateFlow<List<PendingTask>>(emptyList())
    val pendingTasks: StateFlow<List<PendingTask>> = _pendingTasks.asStateFlow()

    private val _activeAgent = MutableStateFlow("MengPaw")
    val activeAgent: StateFlow<String> = _activeAgent.asStateFlow()

    /** Provider/model label for the active agent (shown under agent name). */
    val activeSessionLabel: String get() = activeSession().providerLabel

    /** All agent names currently in the session map. */
    val agentNames: Set<String> get() = sessions.keys

    private var stateObserverJob: Job? = null
    private var messageBindingJob: Job? = null

    /**
     * Apply a pre-created LLM provider to the active agent.
     * Called by AgentRuntime on IO thread — lightweight, no network calls.
     */
    fun applyConfiguration(
        endpoint: String,
        apiKey: String,
        model: String,
        provider: LlmProvider,
        agentLang: PromptEngine.AgentLanguage = PromptEngine.AgentLanguage.CHINESE
    ) {
        sessionFactory.globalEndpoint = endpoint
        sessionFactory.globalApiKey = apiKey
        sessionFactory.globalModel = model
        sessionFactory.globalAgentLang = agentLang

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
        // Inject provider into TribePlugin for LLM routing (tribe.route / fleet)
        try { com.mengpaw.plugin.hermes.TribePlugin.llmProvider = provider } catch (_: Exception) {}
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

    /**
     * 部落收件箱 + 命令集指纹轮询：每 5s 检查待处理部落任务数和
     * Agent Tools 命令集目录指纹，变化时刷新 system prompt（让 Agent 感知新任务/新命令集）。
     * 由 MengPawApp 启动时调用一次。
     */
    fun startTribeInboxRefresh() {
        viewModelScope.launch {
            var last = -1
            var lastToolsFp = -1L
            while (true) {
                kotlinx.coroutines.delay(5000)
                val n = try {
                    com.mengpaw.plugin.hermes.TribeInboxWatcher.pendingCount(_activeAgentName)
                } catch (_: Exception) { 0 }
                val fp = try {
                    com.mengpaw.plugin.agenttools.AgentToolsSummary.fingerprint(_activeAgentName)
                } catch (_: Exception) { 0L }
                if (n != last || fp != lastToolsFp) {
                    last = n
                    lastToolsFp = fp
                    try { activeEngine()?.refreshSystemPrompt() } catch (_: Exception) {}
                }
            }
        }
    }

    /** Get (endpoint, model) for an agent. */
    fun agentConfig(name: String): Pair<String, String> {
        val s = sessions[name]
        return (s?.endpoint ?: "") to (s?.modelName ?: "")
    }

    /** Switch to a different agent. Stops old agent engine to prevent orphaned execution. */
    fun switchAgent(name: String, framework: String? = null) {
        if (name == _activeAgentName) return
        val agentDir = File(com.mengpaw.kernel.DataPaths.AGENTS, name)
        // Framework agent without local workspace: bootstrap with boost.md
        if (!agentDir.exists() && !sessions.containsKey(name)) {
            if (framework == null) return // local agent must have workspace on disk
            agentDir.mkdirs()
            com.mengpaw.kernel.agent.AgentDocs.bootstrap(name,
                if (sessionFactory.globalAgentLang == com.mengpaw.kernel.llm.PromptEngine.AgentLanguage.CHINESE) "zh" else "en")
            sessions[name] = sessionFactory.createSession(name, framework)
            // Auto-start boost.md onboarding for first-time framework agent
            sessionFactory.autoStartAgent(name, name)
            _activeAgentName = name
            bindActiveSession()
            return
        }
        stopAgent() // Stop old agent engine before switching
        // Reset old session state
        val old = sessions[_activeAgentName]
        old?.isRunning?.value = false
        // Create session if directory exists but session doesn't (e.g., agent created externally)
        if (!sessions.containsKey(name) && agentDir.exists() && agentDir.isDirectory) {
            sessions[name] = sessionFactory.createSession(name, null)
        }
        _activeAgentName = name
        bindActiveSession()
    }

    /** Update Agent language without re-creating the engine. */
    fun setAgentLanguage(lang: PromptEngine.AgentLanguage) {
        sessionFactory.globalAgentLang = lang
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

    /**
     * Submit a task to the currently active agent.
     * Uses the active [inputTagManager.loopMode] to select engine execution strategy.
     */
    fun submitTask(
        task: String,
        pluginViewModel: PluginViewModel? = null,
        maxSteps: Int = 50,
        executionMode: ExecutionMode? = null,
        agentRef: String? = null
    ) {
        if (task.isBlank()) return
        // ── Bang 命令: "!cmd" 绕过 Agent 直接执行 — 完整文本(含 ! 前缀)保留在用户消息 ──
        val trimmedTask = task.trimStart()
        if (trimmedTask.startsWith("!")) {
            runBangCommand(original = task, command = trimmedTask.removePrefix("!").trimStart())
            return
        }
        // ── Evolution: 用户纠正识别 (钩子归系统 → 用户反应档案, 用户分身数据源) ──
        detectCorrection(task, agentRef)
        val session = activeSession()
        // Snapshot both state values atomically to avoid TOCTOU race
        val sessionRunning = session.isRunning.value
        val isRunning = _isRunning.value
        if (sessionRunning || isRunning) {
            _pendingTasks.value = _pendingTasks.value + PendingTask(task, maxSteps, executionMode, agentRef)
            session.messages.value = session.messages.value + ChatMessageUi.User(task)
            return
        }

        session.messages.value = session.messages.value + ChatMessageUi.User(task)

        viewModelScope.launch {
            // ── 执行模式分发变量（在 try 外，catch 中也需要）────
            val savedLoopMode = inputTagManager.loopMode
            val modePrefix = executionMode?.prefix
            var runningMsgIndex = -1     // fast‑path index; verified against ref before use
            var runningMsgRef: ChatMessageUi.AgentWithTrace? = null // identity guard for concurrent insertions
            try {
                // /Mission /Goal: 临时覆盖 loopMode
                if (executionMode == ExecutionMode.MISSION) {
                    inputTagManager.loopMode = LoopMode.MISSION
                } else if (executionMode == ExecutionMode.GOAL) {
                    inputTagManager.loopMode = LoopMode.GOAL
                }

                // /Dream: 后台执行 — 直接 LLM 调用，不触发主引擎状态变化
                if (executionMode == ExecutionMode.SILENT) {
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
                    inputTagManager.loopMode = savedLoopMode
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

                // 直接传递用户任务，不包装回溯摘要。Agent 通过对话历史自然感知上下文。
                val contextPrefix = actualTask

                val traces = mutableListOf<AgentTrace>()

                // Track running message for O(1) updates + identity guard against concurrent insertions
                val runningMsg = ChatMessageUi.AgentWithTrace(
                    finalContent = "思考中...",
                    traces = emptyList(),
                    isRunning = true,
                    executionMode = modePrefix,
                    agentRef = agentRef
                )
                runningMsgRef = runningMsg
                runningMsgIndex = session.messages.value.size
                session.messages.value = session.messages.value + runningMsg

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
                    session.messages.update { current ->
                        val mutable = current.toMutableList()
                        val idx = resolveRunningIndex(mutable, runningMsgIndex, runningMsgRef)
                        if (idx >= 0) {
                            mutable[idx] = ChatMessageUi.AgentWithTrace(
                                "思考中...", traces.toList(),
                                isRunning = true, executionMode = modePrefix, agentRef = agentRef
                            )
                        }
                        mutable
                    }
                }

                // Reset stale state from previous runs before starting
                session.engine.resetLoopDetection()
                try { session.engine.stop() } catch (_: Exception) {}

                // Execute via the appropriate engine mode
                val finalTask = recallPrefix + contextPrefix
                // ── 自动复杂度检测: 无斜杠命令时评估是否升级模式 ──
                if (executionMode == null) {
                    val detected = detectComplexity(actualTask)
                    if (detected != LoopMode.REACT && inputTagManager.activeTags.value.none { it is InputTag.Mode }) {
                        // 自动升级: 添加 UI 标签 (复用 AssistChip 体系)
                        val autoTag = when (detected) {
                            LoopMode.GOAL -> InputTag.Mode(ExecutionMode.GOAL)
                            LoopMode.MISSION -> InputTag.Mode(ExecutionMode.MISSION)
                            else -> null
                        }
                        autoTag?.let { addTag(it) }
                        // 临时覆盖 loopMode 用于本轮分发
                        if (detected == LoopMode.GOAL || detected == LoopMode.MISSION) {
                            inputTagManager.loopMode = detected
                        }
                    }
                }

                // Mode dispatch: map slash command + loopMode to the correct engine method
                val result = when {
                    executionMode == ExecutionMode.PLAN -> session.engine.runWithPlan(task = finalTask, onStep = onStep)
                    executionMode == ExecutionMode.MISSION -> session.engine.runWithMission(task = finalTask, onStep = onStep)
                    executionMode == ExecutionMode.GOAL -> session.engine.runWithGoal(task = finalTask, maxTurns = 20, onStep = onStep)
                    // ── 显式斜杠命令结束, 以下为 loopMode 分发 ──
                    inputTagManager.loopMode == LoopMode.REACT -> session.engine.run(task = finalTask, maxSteps = 50, onStep = onStep)
                    inputTagManager.loopMode == LoopMode.GOAL -> session.engine.runWithGoal(task = finalTask, maxTurns = 20, onStep = onStep)
                    inputTagManager.loopMode == LoopMode.MISSION || inputTagManager.loopMode == LoopMode.FLEET -> session.engine.runWithFleet(task = finalTask, onStep = onStep)
                    inputTagManager.loopMode == LoopMode.SWARM -> session.engine.runWithSwarm(task = finalTask, onStep = onStep)
                    else -> session.engine.run(task = finalTask, maxSteps = 50, onStep = onStep)
                }

                // Translate result back to Chinese for US models
                val displayResult = if (doTranslate) translator.toChinese(result) else result

                session.messages.update { current ->
                    val mutable = current.toMutableList()
                    val idx = resolveRunningIndex(mutable, runningMsgIndex, runningMsgRef)
                    if (idx >= 0) {
                        mutable[idx] = ChatMessageUi.AgentWithTrace(
                            finalContent = displayResult,
                            traces = traces.toList(),
                            isRunning = false,
                            executionMode = modePrefix,
                            agentRef = agentRef
                        )
                    } else {
                        mutable.add(ChatMessageUi.Agent(displayResult,
                            executionMode = modePrefix, agentRef = agentRef))
                    }

                    val suggestion = checkMissingPlugin(result)
                    if (suggestion != null && pluginViewModel != null) {
                        mutable.add(ChatMessageUi.Suggestion(suggestion))
                        pluginViewModel.suggestPluginForCommand(result)
                    }
                    mutable
                }
                inputTagManager.loopMode = savedLoopMode
                processNextPending()

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
                KernelLog.w("AgentViewModel", "Task execution failed: ${e.message}")
                // Stop engine to prevent stale state on retry
                try { session.engine.stop() } catch (_: Exception) {}
                val errorMsg = if (e is OutOfMemoryError) {
                    "⚠️ 内存不足，任务已中断。请清理会话历史后重试。"
                } else {
                    "⚠️ 执行出错：${e.message?.take(120) ?: "未知错误"} — 已完成的工作已自动记录，继续对话可恢复进度。"
                }
                session.messages.update { current ->
                    val mutable = current.toMutableList()
                    val idx = resolveRunningIndex(mutable, runningMsgIndex, runningMsgRef)
                    if (idx >= 0) {
                        mutable[idx] = ChatMessageUi.AgentWithTrace(
                            finalContent = errorMsg,
                            traces = emptyList(),
                            isRunning = false,
                            executionMode = modePrefix,
                            agentRef = agentRef
                        )
                    } else {
                        mutable.add(ChatMessageUi.Agent(errorMsg,
                            executionMode = modePrefix, agentRef = agentRef))
                    }
                    mutable
                }
                // 恢复原始 loopMode
                inputTagManager.loopMode = savedLoopMode
                // Fully sync all running/input state
                session.isRunning.value = false
                _isRunning.value = false
                session.inputEnabled.value = true
                _inputEnabled.value = true
                processNextPending()
            }
        }
    }

    /**
     * Execute a "!command" locally — bypasses the agent/LLM entirely.
     * The user message (with the "!" prefix) stays in history verbatim;
     * the result is appended as a CommandResult bubble.
     */
    private fun runBangCommand(original: String, command: String) {
        val session = activeSession()
        // 原子 CAS 入列用户消息, 保留含 ! 的完整原文
        session.messages.update { it + ChatMessageUi.User(original) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                session.engine.executeCommand(command)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ExecutionResult.fail(e.message ?: "未知错误", errorCode = ErrorCodes.ERR_INTERNAL)
            }
            val truncated = (if (result.success) result.output else result.error ?: "命令执行失败")
                .let { if (it.length > 4000) it.take(4000) + "\n\n...(输出过长, 已截断)" else it }
            session.messages.update { it + ChatMessageUi.CommandResult(truncated, isError = !result.success) }
        }
    }

    fun stopAgent() { activeSession().engine.stop() }

    fun removePendingTask(index: Int) {
        _pendingTasks.value = _pendingTasks.value.toMutableList().also { if (index in it.indices) it.removeAt(index) }
    }

    fun clearPendingTasks() {
        _pendingTasks.value = emptyList()
    }

    private fun processNextPending() {
        val pending = _pendingTasks.value
        if (pending.isNotEmpty()) {
            val next = pending.first()
            _pendingTasks.value = pending.drop(1)
            submitTask(next.text, maxSteps = next.maxSteps,
                executionMode = next.executionMode, agentRef = next.agentRef)
        }
    }

    // ── Trigger task: silent background execution ────────────────────

    /**
     * Called by TriggerEngine.onFire when a CRON/SCHEDULE trigger fires.
     */
    fun submitTriggerTask(trigger: com.mengpaw.kernel.trigger.TriggerEngine.Trigger) {
        val targetAgent = "MengPaw"
        val session = sessions.getOrPut(targetAgent) { sessionFactory.createSession(targetAgent, null) }

        // Don't interrupt a running agent; queue to inbox for later pickup
        if (session.isRunning.value) {
            val inbox = File(com.mengpaw.kernel.DataPaths.AGENT_INBOX)
            inbox.mkdirs()
            File(inbox, "trigger_${trigger.id}_${System.currentTimeMillis()}.md").writeText(
                "# 触发器任务\n- ID: ${trigger.id}\n- 类型: ${trigger.type}\n- Cron: ${trigger.config}\n- 时间: ${
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                }\n\n${trigger.action}\n"
            )
            return
        }

        // Minimal prompt — behavior governed by HEARTBEAT.md workspace rules.
        val prompt = "[触发器任务 · ${trigger.type}] ${trigger.action}\n(行为规范: 阅读 HEARTBEAT.md 获取执行细则)"

        // Light system note so user knows something happened
        session.messages.value = session.messages.value + ChatMessageUi.System(
            "⏰ ${trigger.action.take(40)}..."
        )

        viewModelScope.launch {
            kotlinx.coroutines.delay(200)
            submitTask(prompt, maxSteps = 20)
        }
    }

    // ── Browser extract task: 网页转 Markdown 提炼 ─────────────────────

    /**
     * 浏览器「提炼网页要点」请求 — 后台静默执行。
     * 任务脚本已在 inbox (browser_extract_<taskId>.md), 提示词引用该文件;
     * 会话忙碌时 submitTask 自动入 pending 队列。
     */
    fun submitBrowserExtract(url: String, taskId: String) {
        val targetAgent = "MengPaw"
        val session = sessions.getOrPut(targetAgent) { sessionFactory.createSession(targetAgent, null) }

        // Light system note so user knows something happened
        session.messages.value = session.messages.value + ChatMessageUi.System(
            "🌐 正在提炼网页要点: ${url.take(40)}..."
        )

        viewModelScope.launch {
            kotlinx.coroutines.delay(200)
            submitTask(
                "[浏览器网页提炼任务 · $taskId]\n任务脚本: agent.read ${com.mengpaw.kernel.DataPaths.AGENT_INBOX}/browser_extract_$taskId.md\n按脚本步骤执行, 完成后删除该任务文件。",
                maxSteps = 20
            )
        }
    }

    // ── Translation middleware (auto for US models) ────────────────────

    private val translator = com.mengpaw.kernel.llm.TranslateMiddleware()

    // ── Evolution: 用户纠正识别 ─────────────────────────────────────────

    /** 纠正信号词表 — 短消息(≤80 字) + 负向措辞 = 用户纠正/否定. */
    private val CORRECTION_KEYWORDS = listOf(
        "不对", "错了", "不是这个", "不是这样", "做错了", "理解错了", "说错了",
        "重做", "重新做", "重新来", "再来一次", "再想想", "重新想",
        "算了", "别这样", "停一下", "不对吧", "没听懂", "我说的不是"
    )

    /**
     * 规则版纠正识别 — 命中后把"上一条 Agent 回复 + 用户纠正"切片写入用户反应档案,
     * 供 Agent 在 L3 用户视角提问时检索(用户分身数据源)。
     * 规则版先行; 后续可升级为 LLM 语义识别。
     */
    private fun detectCorrection(task: String, agentRef: String?) {
        try {
            if (task.length > 80) return
            if (CORRECTION_KEYWORDS.none { task.contains(it) }) return
            // 上下文切片: 最近一条 Agent 回复
            val snippet = activeSession().messages.value.asReversed()
                .firstNotNullOfOrNull { msg ->
                    when (msg) {
                        is ChatMessageUi.Agent -> msg.content
                        is ChatMessageUi.AgentWithTrace -> msg.finalContent
                        else -> null
                    }
                }?.take(200) ?: ""
            com.mengpaw.kernel.evolution.EvolutionHook.recordCorrection(
                agentName = agentRef ?: _activeAgentName,
                correction = task.take(200),
                contextSnippet = snippet,
                task = task.take(300)
            )
        } catch (_: Exception) { /* 识别永不崩溃 */ }
    }

    // ── Retract & Quote ─────────────────────────────────────────────────

    /** Retract the last user message: stop agent, remove user+agent msgs, return text to input. */
    fun retractLastUserMessage(): String? {
        stopAgent()
        // ── Evolution: 撤回 = 用户否定上次回答, 记入用户反应档案 ──
        com.mengpaw.kernel.evolution.EvolutionHook.recordCorrection(
            agentName = _activeAgentName,
            correction = "(用户撤回上一条消息)",
            contextSnippet = "",
            task = ""
        )
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
            is ChatMessageUi.CommandResult -> "> 命令输出: ${msg.content.take(200)}"
            else -> ""
        }
    }

    /** Whether the given message is the last user message (retractable). */
    fun isLastUserMessage(msg: ChatMessageUi): Boolean {
        val msgs = activeSession().messages.value
        val lastUser = msgs.lastOrNull { it is ChatMessageUi.User }
        return msg == lastUser
    }

    // ── Internals ──

    private fun bindActiveSession() {
        sessionFactory.ensureDefaultSession()
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
        sessionFactory.ensureDefaultSession()
        bindActiveSession()
        // Restore persisted session history
        sessionPersistence.loadSessionHistory()
        // ── Orphan cleanup: remove records whose session file no longer exists ──
        sessionPersistence.cleanupOrphanSessions()
        // ── Dedup: merge records with same title+agent that are likely duplicates ──
        sessionPersistence.dedupSessionHistory()
        // Restore last active session messages
        if (!sessionPersistence.restoreCurrentSession()) {
            // Only show welcome if no saved session
        }
        // Start periodic auto-save
        sessionPersistence.scheduleAutoSave()

        // ── Observe session lifecycle events for recovery hints ──
        viewModelScope.launch {
            com.mengpaw.kernel.session.SessionEventBus.events.collect { event ->
                when (event.kind) {
                    com.mengpaw.kernel.session.SessionEventBus.EventKind.RUN_INTERRUPTED,
                    com.mengpaw.kernel.session.SessionEventBus.EventKind.LLM_CALL_ERROR -> {
                        // Show a brief recovery hint in the message stream
                        val hint = when {
                            event.summary.contains("timeout", ignoreCase = true) ||
                                event.summary.contains("超时", ignoreCase = true) ||
                                event.summary.contains("timed out", ignoreCase = true) ->
                                "ℹ️ 连接短暂中断，已自动记录恢复点。继续对话即可。"
                            event.summary.contains("consecutive", ignoreCase = true) ||
                                event.payload["consecutive"] == "true" ->
                                "ℹ️ 连续错误，建议清理会话历史或检查 API 配置后重试。"
                            else ->
                                "ℹ️ 执行中断，已自动恢复。继续发送消息即可。"
                        }
                        val session = sessions[_activeAgentName]
                        if (session != null && !_isRunning.value) {
                            session.messages.value = session.messages.value + ChatMessageUi.System(hint)
                        }
                    }
                    com.mengpaw.kernel.session.SessionEventBus.EventKind.SESSION_RECOVERED -> {
                        val session = sessions[_activeAgentName]
                        if (session != null) {
                            session.messages.value = session.messages.value +
                                ChatMessageUi.System("🔄 已从上次中断处恢复，继续执行。")
                        }
                    }
                    else -> { /* no UI action needed */ }
                }
            }
        }
    }

    // ── Running-message index resolution with identity guard ──────────
    /**
     * Resolves the index of the currently running [AgentWithTrace] message.
     * Uses the fast-path [cachedIndex] verified by referential identity against [cachedRef];
     * falls back to linear scan only when a concurrent insertion (e.g. notifyAgentMessage)
     * shifted the list. Returns -1 if no running message is found.
     */
    private fun resolveRunningIndex(
        list: List<ChatMessageUi>,
        cachedIndex: Int,
        cachedRef: ChatMessageUi.AgentWithTrace?
    ): Int {
        // Fast path: identity match at cached index (O(1) — normal case)
        if (cachedIndex in list.indices && list[cachedIndex] === cachedRef) return cachedIndex
        // Slow path: concurrent insertion shifted the list — identity scan (O(n))
        cachedRef?.let { ref ->
            val found = list.indexOfFirst { it === ref }
            if (found >= 0) return found
        }
        // Last resort: scan by type (shouldn't happen unless ref was GC'd)
        return list.indexOfLast { it is ChatMessageUi.AgentWithTrace && it.isRunning }
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
            "memory" to PluginSuggestion("memory", "agent.memory", "Memory System", "三轨记忆 (内核): keep/record/read/search/stats/write", "agent.memory.*"),
            "skill" to PluginSuggestion("skill", "skill-plugin", "Skill System", "可复用的 Agent 剧本系统", "skill.*"),
            "ui" to PluginSuggestion("ui", "ui-plugin", "UI Automation", "界面操控：click, swipe, input 等", "ui.*"),
            "proc" to PluginSuggestion("proc", "proc-plugin", "Process Management", "进程管理：ps, kill, exec", "proc.*"),
            "clipboard" to PluginSuggestion("clipboard", "clipboard-plugin", "Clipboard", "剪贴板操作", "clipboard.*"),
        )

        return knownPlugins[namespace]
    }
}
