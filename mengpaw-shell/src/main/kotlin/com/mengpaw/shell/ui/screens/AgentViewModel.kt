// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mengpaw.kernel.AgentEngine
import com.mengpaw.kernel.CommandInfo
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.PromptEngine
import com.mengpaw.kernel.session.AttachmentData
import com.mengpaw.shell.ui.localization.AppStrings
import com.mengpaw.shell.ui.screens.model.AgentSession
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import com.mengpaw.shell.ui.screens.model.ExecutionMode
import com.mengpaw.shell.ui.screens.model.InputTag
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * ViewModel for the main agent chat screen.
 * Manages multiple agent sessions — each agent has its own AgentEngine and message history.
 *
 * 拆分说明 (400 行文件拆分项目): 数据逻辑按职责外移至同包 internal 控制器 —
 * SessionChatController (聊天状态流/绑定), TaskExecutionPipeline (submitTask 主链路),
 * TaskExecutionHelpers (纠正/建议/摘要/错误兜底), SessionMessageCenter (消息注入/撤回),
 * AgentTaskInbox (触发器/浏览器/部落轮询), StreamPlaybackBuffer (流式打字机),
 * StepBubbleWriter (步骤气泡), StreamStepTracker (前缀/正则/索引守卫)。
 * 公开 API (属性/方法签名) 零变化, 消费方 (MainScreen/AppRoot/AgentRuntime 等) 无需改动。
 */
class AgentViewModel : ViewModel() {

    // ── 高危操作确认队列 (v0.34.3 分级系统) — kernel UserConfirmBus 请求 → UI 弹窗 ──
    private val confirmListener = com.mengpaw.kernel.security.UserConfirmBus.Listener { request ->
        confirmQueue.update { it.apply { addLast(request) } }
        true
    }
    val confirmQueue = MutableStateFlow<ArrayDeque<com.mengpaw.kernel.security.UserConfirmBus.ConfirmRequest>>(ArrayDeque())

    init {
        com.mengpaw.kernel.security.UserConfirmBus.registerListener(confirmListener)
    }

    override fun onCleared() {
        super.onCleared()
        com.mengpaw.kernel.security.UserConfirmBus.unregisterListener(confirmListener)
        sessionPersistence.saveCurrentSession()
        sessionPersistence.flushSaveQueue()   // v0.28.6: 等异步落盘队列完成 (1s 兜底)
        // Unwire static trigger callback to prevent ViewModel memory leak
        com.mengpaw.shell.service.AgentRuntime.unwireTriggers()
        sessions.values.forEach { session ->
            try { (session.provider as? java.io.Closeable)?.close() } catch (_: Exception) {}
        }
    }

    /** 高危确认弹窗结果回传 (MainScreen 调用) — 允许/拒绝当前队首请求。 */
    fun respondConfirm(allowed: Boolean) {
        val req = confirmQueue.value.firstOrNull() ?: return
        com.mengpaw.kernel.security.UserConfirmBus.respond(req.id, allowed)
        confirmQueue.update { it.apply { removeFirst() } }
    }

    // ── Multi-session store ──
    private val sessions = mutableMapOf<String, AgentSession>()

    // Track which agents have completed the bootstrap startup flow.
    // Prevents re-triggering on every config change.
    private val bootstrappedAgents = mutableSetOf<String>()

    // ── Active agent state ──
    private var _activeAgentName = DEFAULT_AGENT_NAME

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

    private val chatController = SessionChatController(
        sessions = sessions,
        sessionFactory = sessionFactory,
        scope = viewModelScope,
        getActiveAgentName = { _activeAgentName },
    )

    private val pipeline = TaskExecutionPipeline(
        scope = viewModelScope,
        sessionFactory = sessionFactory,
        sessionPersistence = sessionPersistence,
        inputTagManager = inputTagManager,
        chat = chatController,
        getActiveAgentName = { _activeAgentName },
    )

    private val messageCenter = SessionMessageCenter(
        sessions = sessions,
        getActiveSession = { activeSession() },
        getActiveAgentName = { _activeAgentName },
    )

    private val taskInbox = AgentTaskInbox(
        scope = viewModelScope,
        sessions = sessions,
        sessionFactory = sessionFactory,
        getActiveAgentName = { _activeAgentName },
        onSubmitTask = { task, steps -> pipeline.submitTask(task, maxSteps = steps) },
    )

    // ── Delegated state from helpers ──

    val activeTags: StateFlow<List<InputTag>> = inputTagManager.activeTags

    val sessionHistory: StateFlow<List<SessionPersistenceService.SessionRecord>>
        get() = sessionPersistence.sessionHistory
    val hideCompacted: StateFlow<Boolean> get() = sessionPersistence.hideCompacted
    val hideArchived: StateFlow<Boolean> get() = sessionPersistence.hideArchived

    // ── Observable state (backed by active session) ──
    val messages: StateFlow<List<ChatMessageUi>> get() = chatController.messages
    val isRunning: StateFlow<Boolean> get() = chatController.isRunning
    val inputEnabled: StateFlow<Boolean> get() = chatController.inputEnabled
    val pendingTasks: StateFlow<List<com.mengpaw.shell.ui.screens.model.PendingTask>> get() = chatController.pendingTasks
    val activeAgent: StateFlow<String> get() = chatController.activeAgent

    /** All agent names currently in the session map. */
    val agentNames: Set<String> get() = sessions.keys

    // ── Delegated tag methods ──

    fun addTag(tag: InputTag) = inputTagManager.addTag(tag)
    fun removeTag(tag: InputTag) = inputTagManager.removeTag(tag)
    fun clearTags() = inputTagManager.clearTags()
    fun agentNamesForMention(): List<Pair<String, String?>> = inputTagManager.agentNamesForMention(sessions)

    /** ! 命令补全候选 — 当前 Agent 引擎的命令 + 功能描述（组合期安全: 直读 map, 不触发会话创建）. */
    fun bangCommands(): List<CommandInfo> =
        sessions[_activeAgentName]?.engine?.listCommands() ?: emptyList()

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

    private fun activeSession(): AgentSession = chatController.activeSession()

    /** Provider/model label for the active agent (shown under agent name). */
    fun activeSessionLabel(strings: AppStrings): String = activeSession().providerLabel(strings)

    /**
     * Apply a pre-created LLM provider to the active agent.
     * Called by AgentRuntime on IO thread — lightweight, no network calls.
     */
    fun applyConfiguration(
        endpoint: String,
        apiKey: String,
        model: String,
        provider: LlmProvider,
        agentLang: PromptEngine.AgentLanguage = PromptEngine.AgentLanguage.CHINESE,
        swarmRoles: Map<String, SavedProvider>? = null
    ) {
        sessionFactory.globalEndpoint = endpoint
        sessionFactory.globalApiKey = apiKey
        sessionFactory.globalModel = model
        sessionFactory.globalAgentLang = agentLang
        if (swarmRoles != null) sessionFactory.globalSwarmRoles = swarmRoles

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
        chatController.bind()
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
            chatController.bind()
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
        chatController.bind()
    }

    /** Update Agent language without re-creating the engine. */
    fun setAgentLanguage(lang: PromptEngine.AgentLanguage) {
        sessionFactory.globalAgentLang = lang
        sessions.values.forEach { it.engine.setAgentLanguage(lang) }
    }

    // ── Delegated task entry points ──

    fun submitTask(
        task: String,
        pluginViewModel: PluginViewModel? = null,
        maxSteps: Int = 50,
        executionMode: ExecutionMode? = null,
        agentRef: String? = null,
        attachments: List<AttachmentData> = emptyList()
    ) {
        pipeline.submitTask(task, pluginViewModel, maxSteps, executionMode, agentRef, attachments)
    }

    fun stopAgent() { messageCenter.stopAgent() }

    fun removePendingTask(index: Int) = pipeline.removePendingTask(index)

    fun clearPendingTasks() = pipeline.clearPendingTasks()

    /**
     * Called by TriggerEngine.onFire when a CRON/SCHEDULE trigger fires.
     */
    fun submitTriggerTask(trigger: com.mengpaw.kernel.trigger.TriggerEngine.Trigger) =
        taskInbox.submitTriggerTask(trigger)

    /**
     * 浏览器「提炼网页要点」请求 — 后台静默执行。
     */
    fun submitBrowserExtract(url: String, taskId: String) =
        taskInbox.submitBrowserExtract(url, taskId)

    /**
     * 部落收件箱 + 命令集指纹轮询: 由 MengPawApp 启动时调用一次。
     */
    fun startTribeInboxRefresh() = taskInbox.startTribeInboxRefresh()

    /** 设置页实时同步: 自动翻译开关 (opt-in). */
    fun setAutoTranslate(enabled: Boolean) = pipeline.setAutoTranslate(enabled)

    // ── Delegated message helpers ──

    /** Set a system message for the loading state. Called from AgentRuntime. */
    fun setInitializingMessage(text: String) = messageCenter.setInitializingMessage(text)

    /** Inject an Agent-pushed notification into the chat message list. */
    fun notifyAgentMessage(text: String) = messageCenter.notifyAgentMessage(text)

    /** Update the system banner text (for localization). */
    fun setBanner(text: String) = messageCenter.setBanner(text)

    /** 当前活动 Agent 的模型名 (v0.33.0+: 语音按钮能力判定用). */
    fun activeModelName(): String = messageCenter.activeModelName()

    /** Retract the last user message: stop agent, remove user+agent msgs, return text to input. */
    fun retractLastUserMessage(): String? = messageCenter.retractLastUserMessage()

    /** Build a quoted reference string for Agent context. */
    fun formatQuote(msg: ChatMessageUi): String = messageCenter.formatQuote(msg)

    /** Whether the given message is the last user message (retractable). */
    fun isLastUserMessage(msg: ChatMessageUi): Boolean = messageCenter.isLastUserMessage(msg)

    init {
        sessionFactory.ensureDefaultSession()
        chatController.bind()
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
        // 事件驱动落盘: 用户消息发出时 + 回复完成时 — 无定时器 (30s 定时已移除)

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
                                "连接短暂中断，已自动记录恢复点。继续对话即可。"
                            event.summary.contains("consecutive", ignoreCase = true) ||
                                event.payload["consecutive"] == "true" ->
                                "连续错误，建议清理会话历史或检查 API 配置后重试。"
                            else ->
                                "执行中断，已自动恢复。继续发送消息即可。"
                        }
                        val session = sessions[_activeAgentName]
                        if (session != null && !chatController.isRunningFlow.value) {
                            session.messages.value = session.messages.value + ChatMessageUi.System(hint)
                        }
                    }
                    com.mengpaw.kernel.session.SessionEventBus.EventKind.SESSION_RECOVERED -> {
                        val session = sessions[_activeAgentName]
                        if (session != null) {
                            session.messages.value = session.messages.value +
                                ChatMessageUi.System("已从上次中断处恢复，继续执行。")
                        }
                    }
                    else -> { /* no UI action needed */ }
                }
            }
        }
    }
}
