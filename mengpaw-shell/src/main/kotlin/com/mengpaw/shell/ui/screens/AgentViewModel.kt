// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mengpaw.kernel.AgentEngine
import com.mengpaw.kernel.AgentState
import com.mengpaw.kernel.CommandInfo
import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.PromptEngine
import com.mengpaw.kernel.session.AttachmentData
import com.mengpaw.shell.ui.screens.model.AgentSession
import com.mengpaw.shell.ui.screens.model.AgentTrace
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import com.mengpaw.shell.ui.screens.model.ExecutionMode
import com.mengpaw.shell.ui.localization.AppStrings
import com.mengpaw.shell.ui.screens.model.InputTag
import com.mengpaw.shell.ui.screens.model.PendingTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 工具执行中提示的前缀 (v0.29.2, Reasonix ③ 对标 — 工具调用提前通知):
 * AgentViewModel 流式检测到完整 "Action: <tool>" 行后, 用该前缀推送运行中气泡;
 * ChatBubbles.WaitingIndicator 依此前缀显示"正在执行 X… Ns"而非"思考中… Ns"。
 */
internal const val EXECUTING_TOOL_PREFIX = "正在执行 "

/** 流式文本中的完整工具调用行 (多行锚定, 行尾须完整) — 半截工具名不匹配, 避免误报. */
private val ACTION_LINE_REGEX = Regex("""(?m)^Action:\s*([\w.+\-]+)\s*$""")

/**
 * ViewModel for the main agent chat screen.
 * Manages multiple agent sessions — each agent has its own AgentEngine and message history.
 */
class AgentViewModel : ViewModel() {

    override fun onCleared() {
        super.onCleared()
        sessionPersistence.saveCurrentSession()
        sessionPersistence.flushSaveQueue()   // v0.28.6: 等异步落盘队列完成 (1s 兜底)
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

    // ── 流式播放器节奏 (v0.28.5): token 突发到达时仍逐段播放, 维持打字机观感 ──
    private val STREAM_PLAYBACK_INTERVAL_MS = 50L  // 播放 tick 间隔
    private val STREAM_PLAYBACK_TARGET_TICKS = 50  // 长文目标 ~2.5s 播完 (50ms × 50)

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
    fun activeSessionLabel(strings: AppStrings): String = activeSession().providerLabel(strings)

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
        session.messages.value = session.messages.value + ChatMessageUi.System(text)
    }

    /** Update the system banner text (for localization). */
    fun setBanner(text: String) {
        val current = activeSession().messages.value
        if (current.isNotEmpty() && current.first() is ChatMessageUi.System) {
            activeSession().messages.value = listOf(ChatMessageUi.System(text)) + current.drop(1)
        }
    }

    /** 当前活动 Agent 的模型名 (v0.33.0+: 语音按钮能力判定用)。 */
    fun activeModelName(): String = activeSession().modelName

    /**
     * Submit a task to the currently active agent.
     * Uses the active [inputTagManager.loopMode] to select engine execution strategy.
     */
    fun submitTask(
        task: String,
        pluginViewModel: PluginViewModel? = null,
        maxSteps: Int = 50,
        executionMode: ExecutionMode? = null,
        agentRef: String? = null,
        attachments: List<AttachmentData> = emptyList()
    ) {
        // v0.33.0+: 纯附件消息（语音）task 为空但带附件 — 放行
        if (task.isBlank() && attachments.isEmpty()) return
        KernelLog.d("MengPawLatency", "T0 submitTask ${task.take(30)}")
        // ── Bang 命令: "!cmd" 绕过 Agent 直接执行 — 完整文本(含 ! 前缀)保留在用户消息 ──
        val trimmedTask = task.trimStart()
        if (trimmedTask.startsWith("!")) {
            runBangCommand(original = task, command = trimmedTask.removePrefix("!").trimStart())
            return
        }
        val session = activeSession()
        // ── Evolution: 用户纠正识别 (钩子归系统 → 用户反应档案, 用户分身数据源) ──
        // v0.28.6: fire-and-forget 出 Main — reactions.md 文件读写不阻塞发送链
        viewModelScope.launch(Dispatchers.IO) {
            try { detectCorrection(task, agentRef, session) } catch (_: Exception) {}
        }
        // Snapshot both state values atomically to avoid TOCTOU race
        val sessionRunning = session.isRunning.value
        val isRunning = _isRunning.value
        if (sessionRunning || isRunning) {
            _pendingTasks.value = _pendingTasks.value + PendingTask(task, maxSteps, executionMode, agentRef, attachments)
            session.messages.value = session.messages.value + ChatMessageUi.User(task, attachments)
            return
        }

        session.messages.value = session.messages.value + ChatMessageUi.User(task, attachments)
        // 用户消息落盘 — 与回复完成落盘配对, 事件驱动无需 30s 定时
        sessionPersistence.saveCurrentSession()

        viewModelScope.launch {
            // ── 执行模式分发变量（在 try 外，catch 中也需要）────
            val savedLoopMode = inputTagManager.loopMode
            val modePrefix = executionMode?.prefix
            // v0.3x 步骤气泡: 当前运行 step 气泡 (每 ReAct 步骤独立气泡, 非单运行气泡+traces)
            var runningStepIndex = -1     // fast‑path index; verified against ref before use
            var runningStepRef: ChatMessageUi.AgentStep? = null // identity guard for concurrent insertions
            var lastCompletedStep = 0     // 最近固化的 step 号 (多 Action 批内合并判定)
            var playbackJob: Job? = null // 流式播放协程句柄 (try 外声明, catch 路径可取消)
            try {
                // /Mission /Goal /Fleet: 临时覆盖 loopMode
                if (executionMode == ExecutionMode.MISSION) {
                    inputTagManager.loopMode = LoopMode.MISSION
                } else if (executionMode == ExecutionMode.GOAL) {
                    inputTagManager.loopMode = LoopMode.GOAL
                } else if (executionMode == ExecutionMode.FLEET) {
                    inputTagManager.loopMode = LoopMode.FLEET
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
                                ChatMessageUi.System("Dream 完成:\n\n${dreamResult.take(500)}")
                        } catch (e: Exception) {
                            session.messages.value = session.messages.value +
                                ChatMessageUi.System("Dream 异常: ${e.message?.take(120) ?: "未知错误"}")
                        }
                    }
                    // 不添加 AgentWithTrace，不锁定输入
                    inputTagManager.loopMode = savedLoopMode
                    return@launch
                }

                // ── "思考中..."步骤气泡前置 (v0.28.6): 在翻译/召回/引擎准备之前插入,
                // 用户发送后立即看到反馈, 4-13s 等待期有活动气泡 (v0.3x: 首步占位) ──
                val runningMsg = ChatMessageUi.AgentStep(
                    step = 1, thought = "", action = null,
                    content = "思考中...", isRunning = true,
                    executionMode = modePrefix, agentRef = agentRef
                )
                runningStepRef = runningMsg
                runningStepIndex = session.messages.value.size
                session.messages.value = session.messages.value + runningMsg
                KernelLog.d("MengPawLatency", "T1 bubble")

                // Auto-translate for English-optimized models (saves ~40% tokens)
                // v0.28.6: 翻译与记忆召回并行发起 (async) — 气泡已前置, 不再串行阻塞
                val doTranslate = translator.shouldTranslate(session.modelName)
                val transDeferred = if (doTranslate) async(Dispatchers.IO) { translator.toEnglish(task) } else null

                // ── 跨会话召回：匹配相关记忆 (关键词从原文提取 — 中文词面对中文 memory.md 语义更优) ──
                val keywords = task.split(Regex("[\\s，。！？,.!?：:()（）]+"))
                    .map { it.trim() }.filter { it.length >= 2 }
                    .filterNot { it in setOf("的", "是", "我", "你", "他", "她", "the", "a", "an", "is", "are", "to", "of", "in", "请", "帮", "一个", "这个", "那个") }
                    .take(5)
                val memoryDeferred = async(Dispatchers.IO) {
                    com.mengpaw.kernel.agent.AgentDocs.recallMemory(
                        _activeAgentName, keywords
                    )
                }

                val translatedTask = transDeferred?.await() ?: task
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

                val recalledMemory = memoryDeferred.await()
                val recallPrefix = if (recalledMemory.isNotBlank()) "$recalledMemory\n\n---\n\n" else ""

                // 直接传递用户任务，不包装回溯摘要。Agent 通过对话历史自然感知上下文。
                val contextPrefix = actualTask

                // ── 流式播放器: onDelta 只累积, 独立协程按节奏播放 (v0.28.5) ──
                // 根因 (v0.28.4 彻查): DeepSeek 端点在 ~200ms 内突发全部增量,
                // onDelta 直推 + 50ms 节流 → 只推 3 次, 观感 = 整段弹出。
                // 方案: buffer 累积原始增量; 播放协程每 50ms 消费未播放部分,
                // 节奏自适应 (长文 ~2.5s 播完, 短文逐字), 模拟打字机观感。
                // 缓冲每轮结束(onStep)清空, 避免 "Action:" 标记跨轮残留 (v0.28.3 根因1)
                val streamBuf = StringBuilder()   // LLM 原始增量缓冲 (engine 线程写)
                var streamPlayed = 0              // 已播放原始字符数 (播放协程推进, onStep 清零)
                var streamFinished = false        // run() 已返回, 不会再增量 — 播放器播完即退
                                                  // (与 buffer 同监视器读写, 跨线程安全)
                // 工具提前通知 (v0.29.2, Reasonix ③ 对标): 流式中完整 "Action: <tool>" 行
                // 出现即推送 — 不等工具执行完成 (onStep), 消除工具轮流式空屏
                var announcedTools = 0            // 已宣布的 Action 行数 (onStep 清零)

                // ── 步骤气泡管理 (v0.3x): 每个 ReAct 步骤一个独立气泡 ──
                // 运行中: 流式文本 → 工具完成 (onStep) 固化 (思考折叠头 + 工具结果正文)
                // → 下一步占位; 最终答案 = 最后一步 (isFinal)。
                // 多 Action 批: 同 step 连续 onStep 合并 observation 到同一气泡。
                fun pushStepDisplay(step: Int, thought: String, action: String?, content: String, isFinal: Boolean = false) {
                    session.messages.update { current ->
                        val mutable = current.toMutableList()
                        val ridx = resolveRunningIndex(mutable, runningStepIndex, runningStepRef)
                        val newMsg = if (ridx >= 0) {
                            // 替换后同步 ref/index — 快路径恒命中 (v0.28.4)
                            (mutable[ridx] as ChatMessageUi.AgentStep).copy(
                                step = step, thought = thought, action = action,
                                content = content, isRunning = true, isFinal = isFinal
                            )
                        } else {
                            ChatMessageUi.AgentStep(
                                step, thought, action, content, isRunning = true, isFinal = isFinal,
                                executionMode = modePrefix, agentRef = agentRef
                            )
                        }
                        val target = if (ridx >= 0) ridx else mutable.size
                        runningStepRef = newMsg
                        runningStepIndex = target
                        if (ridx >= 0) mutable[ridx] = newMsg else mutable.add(newMsg)
                        mutable
                    }
                }

                /** 固化当前运行 step (思考折叠头 + 工具结果) 并创建下一步占位气泡。 */
                fun completeStep(trace: com.mengpaw.kernel.AgentEngine.TraceStep) {
                    session.messages.update { current ->
                        val mutable = current.toMutableList()
                        val ridx = resolveRunningIndex(mutable, runningStepIndex, runningStepRef)
                        if (ridx >= 0) {
                            mutable[ridx] = (mutable[ridx] as ChatMessageUi.AgentStep).copy(
                                thought = trace.thought, action = trace.action,
                                content = trace.observation ?: "", isRunning = false
                            )
                        }
                        val next = ChatMessageUi.AgentStep(
                            trace.step + 1, "", null, "思考中...", isRunning = true,
                            executionMode = modePrefix, agentRef = agentRef
                        )
                        runningStepRef = next
                        runningStepIndex = mutable.size
                        mutable.add(next)
                        mutable
                    }
                }

                /** 多 Action 批内合并: 同 step 后续 observation 追加到已固化气泡。 */
                fun mergeBatchObservation(trace: com.mengpaw.kernel.AgentEngine.TraceStep) {
                    session.messages.update { current ->
                        val mutable = current.toMutableList()
                        val idx = mutable.indexOfLast { it is ChatMessageUi.AgentStep && it.step == trace.step && !it.isRunning }
                        if (idx >= 0) {
                            val prev = mutable[idx] as ChatMessageUi.AgentStep
                            val obs = if (prev.content.isBlank()) (trace.observation ?: "")
                                else prev.content + "\n\n" + (trace.observation ?: "")
                            mutable[idx] = prev.copy(
                                content = obs,
                                thought = if (prev.thought.isBlank()) trace.thought else prev.thought
                            )
                        }
                        mutable
                    }
                }

                // Shared step callback: 固化当前 step + 批内合并 + token stats
                val onStep: (com.mengpaw.kernel.AgentEngine.TraceStep) -> Unit = { trace ->
                    session.provider.lastUsage?.let { usage ->
                        com.mengpaw.shell.ui.components.TokenStatsCollector.record(
                            model = session.modelName,
                            tokens = usage.totalTokens,
                            cacheHit = usage.cacheHitTokens > 0,
                            cacheHitTokens = usage.cacheHitTokens
                        )
                    }
                    if (trace.step == lastCompletedStep) {
                        mergeBatchObservation(trace)
                    } else {
                        lastCompletedStep = trace.step
                        completeStep(trace)
                    }
                    // 工具轮结束 → 清空流式缓冲与播放进度, 下一轮从头累积
                    // (旧实现 buffer 跨轮永不清空, "Action:" 一旦出现即永久过滤后续纯文本增量)
                    synchronized(streamBuf) {
                        streamBuf.clear()
                        streamPlayed = 0
                        announcedTools = 0        // 下一轮工具提前通知重新计数
                    }
                }

                // ── 流式显示策略 (缓冲每轮结束被清空, 轮间互不污染):
                //  - 含 "Final Answer:" → 只显示标记后的答案部分
                //  - 含 "Action:"(工具轮) → 流式显示 Thought 思考过程 + Action 命令行,
                //    Action Input 大参数截断 (由执行后 trace 行承载) — v0.3x 演进:
                //    原设计 (v0.28.5) 工具轮样板全隐藏, 思考过程不可见, 执行中只有
                //    "思考中..." 占位; 现让 Agent 的推理轨迹全程流式可见
                //  - 含 "Thought:" → 隐藏思考样板, 只显示其后内容 (thought-only 轮)
                //  - 无任何标记 → 流式显示全文 (parse Rule 3 纯文本答案, 必须流式显示)
                fun computeStreamDisplayText(text: String): String {
                    val hasFinal = text.contains("Final Answer:", ignoreCase = true)
                    val hasAction = text.contains("Action:", ignoreCase = true)
                    val hasThought = text.contains("Thought:", ignoreCase = true)
                    return when {
                        hasFinal -> text.substringAfter("Final Answer:", text)
                        hasAction -> {
                            // 工具轮: Thought 后内容截断在 Action Input 前 — 思考过程 +
                            // Action 命令行流式可见, 参数 JSON 不刷屏 (流式中途 Input 未
                            // 到达时完整显示; 一旦出现即截断)
                            text.substringAfter("Thought:", text)
                                .substringBefore("\nAction Input:", text)
                        }
                        hasThought -> text.substringAfter("Thought:", text)
                        else -> text           // 纯文本答案流
                    }
                }

                // onDelta (engine 线程回调): 只累积, 不推送 — 节奏由播放协程控制
                var firstDelta = true
                val onDelta: (String) -> Unit = { delta ->
                    if (firstDelta) { firstDelta = false; KernelLog.d("MengPawLatency", "T3 first-delta") }
                    var newTool: String? = null
                    synchronized(streamBuf) {
                        streamBuf.append(delta)
                        // 工具提前通知: 扫描已累积缓冲中的完整 "Action: <tool>" 行 (多行锚定,
                        // 完整行才宣布 — 避免 "Action: l" 半截工具名误报; "Action Input:" 不匹配
                        // 因为要求冒号紧跟 Action)。流式到达时行尾 \n 落地即命中。
                        val matches = ACTION_LINE_REGEX.findAll(streamBuf).toList()
                        if (matches.size > announcedTools) {
                            announcedTools = matches.size
                            newTool = matches.last().groupValues[1]
                        }
                    }
                    // 锁外推送 (锁纪律同 onStep/播放协程: pushStepDisplay 不在监视器内调用)
                    newTool?.let { tool ->
                        // v0.3x: 工具轮思考过程已流式可见 — 仅当缓冲里尚无 Thought 内容时
                        // (极端短思考) 才兜底宣布, 避免宣布行替换掉正在播放的 Thought 轨迹
                        val base = synchronized(streamBuf) {
                            computeStreamDisplayText(streamBuf.toString())
                        }
                        if (base.isBlank()) {
                            val cur = runningStepRef
                            if (cur != null) pushStepDisplay(cur.step, cur.thought, cur.action, "$EXECUTING_TOOL_PREFIX$tool…")
                        }
                    }
                }

                // 播放协程: 每 STREAM_PLAYBACK_INTERVAL_MS 把未播放增量推给 UI (打字机)
                // v0.28.5: 必须用 Dispatchers.Default — SSE 突发到达时(如服务端缓存回放)
                // readUTF8Line 从不挂起, 主线程被读取循环占死, Main 调度的播放协程会被饿死
                // (实测 846 chunks/166ms 突发 → UI-PUSH 零输出)
                playbackJob = viewModelScope.launch(Dispatchers.Default) {
                    try {
                        while (true) {
                            kotlinx.coroutines.delay(STREAM_PLAYBACK_INTERVAL_MS)
                            val displayText = synchronized(streamBuf) {
                                val total = streamBuf.length
                                if (streamPlayed >= total) {
                                    if (streamFinished) return@launch // 已播完且流结束 → 退场
                                    null // 无新内容, 本 tick 不推送
                                } else {
                                    // 节奏自适应: 每 tick 消费 ceil(剩余/目标tick数) 字符 —
                                    // 长文 ~2.5s 播完, 短文逐字, 播速不随突发到达暴涨
                                    val quantum = maxOf(1,
                                        (total - streamPlayed + STREAM_PLAYBACK_TARGET_TICKS - 1) /
                                        STREAM_PLAYBACK_TARGET_TICKS)
                                    val end = minOf(total, streamPlayed + quantum)
                                    streamPlayed = end
                                    computeStreamDisplayText(streamBuf.substring(0, end))
                                }
                            } ?: continue
                            if (displayText.isBlank()) continue // 工具轮样板: 不显示
                            val cur = runningStepRef
                            if (cur != null) pushStepDisplay(cur.step, cur.thought, cur.action, displayText)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // 播放器永不让 join() 抛异常 — 只记录, 静默退出
                        KernelLog.w("AgentViewModel", "Stream playback exit: ${e.message?.take(80)}")
                    }
                }

                // Reset stale state from previous runs before starting
                KernelLog.d("MengPawLatency", "T2 before-dispatch")
                session.engine.resetLoopDetection()
                try { session.engine.stop() } catch (_: Exception) {}

                // Execute via the appropriate engine mode
                val finalTask = recallPrefix + contextPrefix
                // ── 自动复杂度检测: 无斜杠命令时评估是否升级模式 ──
                // v0.33.0+: 带附件（图片/语音）时不自动升级 — 目标模式执行器不接收附件,
                // 升级会导致附件静默丢失
                if (executionMode == null && attachments.isEmpty()) {
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
                    executionMode == ExecutionMode.PLAN -> session.engine.runWithPlan(task = finalTask, onStep = onStep, onDelta = onDelta)
                    executionMode == ExecutionMode.MISSION -> session.engine.runWithMission(task = finalTask, onStep = onStep, onDelta = onDelta)
                    executionMode == ExecutionMode.GOAL -> session.engine.runWithGoal(task = finalTask, maxTurns = 20, onStep = onStep, onDelta = onDelta)
                    // ── 显式斜杠命令结束, 以下为 loopMode 分发 ──
                    // v0.33.0+: REACT 主链路透传附件 (历史经 getStructuredHistory 挂二进制键);
                    // 目标模式 (GOAL/MISSION/FLEET/SWARM) 执行器签名不含附件 — 附件不传 (注释: P2)
                    inputTagManager.loopMode == LoopMode.REACT -> session.engine.run(task = finalTask, maxSteps = 50, onStep = onStep, onDelta = onDelta, attachments = attachments)
                    inputTagManager.loopMode == LoopMode.GOAL -> session.engine.runWithGoal(task = finalTask, maxTurns = 20, onStep = onStep, onDelta = onDelta)
                    inputTagManager.loopMode == LoopMode.MISSION || inputTagManager.loopMode == LoopMode.FLEET ->
                        session.engine.runWithFleet(task = finalTask, roles = sessionFactory.buildSwarmRoles(), onStep = onStep, onDelta = onDelta)
                    inputTagManager.loopMode == LoopMode.SWARM ->
                        session.engine.runWithSwarm(task = finalTask, roles = sessionFactory.buildSwarmRoles(), onStep = onStep, onDelta = onDelta)
                    else -> session.engine.run(task = finalTask, maxSteps = 50, onStep = onStep, onDelta = onDelta, attachments = attachments)
                }

                // ── 尾段: run() 已返回 — 标记流结束, 等待播放器把剩余缓冲按节奏播完
                // (打字机收尾, 最长 ~2.5s); join 防 Default 线程晚到 tick 覆盖最终消息
                // (doTranslate 开启时跳过等待: 最终 replace 整段替换为中文,
                //  英文逐字播放无意义, 旧实现同样跳过尾段)
                synchronized(streamBuf) { streamFinished = true }
                if (!doTranslate) playbackJob?.join()
                playbackJob?.cancel()
                if (!doTranslate) {
                    // 兜底 flush: 播放器异常退出时推完剩余; 正常播完时此处无操作
                    val flushText = synchronized(streamBuf) {
                        computeStreamDisplayText(streamBuf.toString()).takeIf {
                            streamPlayed < streamBuf.length && it.isNotBlank()
                        }
                    }
                    if (flushText != null) {
                        val cur = runningStepRef
                        if (cur != null) pushStepDisplay(cur.step, cur.thought, cur.action, flushText)
                    }
                }

                // Translate result back to Chinese for US models
                val displayResult = if (doTranslate) translator.toChinese(result) else result

                session.messages.update { current ->
                    val mutable = current.toMutableList()
                    val idx = resolveRunningIndex(mutable, runningStepIndex, runningStepRef)
                    if (idx >= 0) {
                        val prev = mutable[idx] as ChatMessageUi.AgentStep
                        // final 轮无 onStep — 思考从流式缓冲提取 (Thought 段全文, 完整可见)
                        val finalThought = synchronized(streamBuf) {
                            streamBuf.toString().substringAfter("Thought:", "")
                                .substringBefore("Final Answer:", "").trim()
                        }
                        val newMsg = prev.copy(
                            content = displayResult, thought = finalThought,
                            isRunning = false, isFinal = true
                        )
                        runningStepRef = newMsg
                        runningStepIndex = idx
                        mutable[idx] = newMsg
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

                // ── 回复完成立即落盘: 滑掉后台任务时 onCleared 不保证执行,
                // 30s 定时可能未到 → 最后一次回复会丢; 完成即保存堵住此洞 ──
                sessionPersistence.saveCurrentSession()

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
                playbackJob?.cancel()
                playbackJob?.join()
                throw e
            } catch (e: Throwable) {
                // Safety net: catch OOM, unexpected runtime errors, etc.
                // Prevents process crash — degrades gracefully to error message
                KernelLog.w("AgentViewModel", "Task execution failed: ${e.message}")
                // Stop engine to prevent stale state on retry
                try { session.engine.stop() } catch (_: Exception) {}
                // 终止播放协程并等待 — 防止 Default 线程晚到 tick 覆盖错误消息
                playbackJob?.cancel()
                playbackJob?.join()
                val errorMsg = if (e is OutOfMemoryError) {
                    "内存不足，任务已中断。请清理会话历史后重试。"
                } else {
                    "执行出错：${e.message?.take(120) ?: "未知错误"} — 已完成的工作已自动记录，继续对话可恢复进度。"
                }
                session.messages.update { current ->
                    val mutable = current.toMutableList()
                    val idx = resolveRunningIndex(mutable, runningStepIndex, runningStepRef)
                    if (idx >= 0) {
                        val newMsg = (mutable[idx] as ChatMessageUi.AgentStep).copy(
                            content = errorMsg, isRunning = false)
                        runningStepRef = newMsg
                        runningStepIndex = idx
                        mutable[idx] = newMsg
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
                // 错误回复同样立即落盘 (进程死亡恢复点)
                sessionPersistence.saveCurrentSession()
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
                executionMode = next.executionMode, agentRef = next.agentRef,
                attachments = next.attachments)
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

        // Minimal prompt — behavior governed by workspace rule files (CRON → heartbeat.md, Truman Show → trumanshow.md).
        val ruleFile = if (trigger.type == com.mengpaw.kernel.trigger.TriggerEngine.TriggerType.CRON) "heartbeat.md" else "trumanshow.md"
        val prompt = "[触发器任务 · ${trigger.type}] ${trigger.action}\n(行为规范: 阅读 $ruleFile 获取执行细则)"

        // Light system note so user knows something happened
        session.messages.value = session.messages.value + ChatMessageUi.System(
            "${trigger.action.take(40)}..."
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
            "正在提炼网页要点: ${url.take(40)}..."
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
    // v0.28.6: opt-in — 默认关闭, 仅用户主动开启才加载 Google 翻译 (读取 auto_translate 配置文件)

    private val translator = com.mengpaw.kernel.llm.TranslateMiddleware().apply {
        enabled = try {
            java.io.File(com.mengpaw.kernel.DataPaths.CONFIG, "auto_translate")
                .takeIf { it.exists() }?.readText()?.trim() == "true"
        } catch (_: Exception) { false }
    }

    /** 设置页实时同步: 自动翻译开关 (opt-in). */
    fun setAutoTranslate(enabled: Boolean) { translator.enabled = enabled }

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
    private fun detectCorrection(task: String, agentRef: String?, session: AgentSession) {
        try {
            if (task.length > 80) return
            if (CORRECTION_KEYWORDS.none { task.contains(it) }) return
            // 上下文切片: 最近一条 Agent 回复
            val snippet = session.messages.value.asReversed()
                .firstNotNullOfOrNull { msg ->
                    when (msg) {
                        is ChatMessageUi.Agent -> msg.content
                        is ChatMessageUi.AgentWithTrace -> msg.finalContent
                        is ChatMessageUi.AgentStep -> msg.content
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
            is ChatMessageUi.AgentStep -> "> Agent 步骤: ${msg.content.take(200)}"
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
                        // FIX(双弹): 引擎错误路径返回 errorMsg 经 run() 尾段写入 running 消息,
                        // 此处 Error 监听再次追加 → 同源错误消息连弹两条。幂等检查防重。
                        val last = session.messages.value.lastOrNull()
                        val alreadyShown = (last is ChatMessageUi.Agent && last.content == state.message) ||
                            (last is ChatMessageUi.AgentWithTrace && last.finalContent == state.message) ||
                            (last is ChatMessageUi.AgentStep && last.content == state.message)
                        if (!alreadyShown) {
                            session.messages.value = session.messages.value + ChatMessageUi.Agent(state.message)
                        }
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
                        if (session != null && !_isRunning.value) {
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

    // ── Running-message index resolution with identity guard ──────────
    /**
     * Resolves the index of the currently running [ChatMessageUi.AgentStep] message.
     * Uses the fast-path [cachedIndex] verified by referential identity against [cachedRef];
     * falls back to linear scan only when a concurrent insertion (e.g. notifyAgentMessage)
     * shifted the list. Returns -1 if no running message is found.
     */
    private fun resolveRunningIndex(
        list: List<ChatMessageUi>,
        cachedIndex: Int,
        cachedRef: ChatMessageUi.AgentStep?
    ): Int {
        // Fast path: identity match at cached index (O(1) — normal case)
        if (cachedIndex in list.indices && list[cachedIndex] === cachedRef) return cachedIndex
        // Slow path: concurrent insertion shifted the list — identity scan (O(n))
        cachedRef?.let { ref ->
            val found = list.indexOfFirst { it === ref }
            if (found >= 0) return found
        }
        // Last resort: scan by type (shouldn't happen unless ref was GC'd)
        return list.indexOfLast { it is ChatMessageUi.AgentStep && it.isRunning }
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
