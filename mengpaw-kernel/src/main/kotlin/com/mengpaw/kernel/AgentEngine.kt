// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.agent.AgentDocManager
import com.mengpaw.kernel.agent.AgentDocs
import com.mengpaw.kernel.agent.AgentExecutor
import com.mengpaw.kernel.agent.AgentMiddleware
import com.mengpaw.kernel.AgentState
import com.mengpaw.kernel.agent.PostCallMiddleware
import com.mengpaw.kernel.agent.ScrollContextManager
import com.mengpaw.kernel.cli.*
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.error.ErrorType
import com.mengpaw.kernel.llm.*
import com.mengpaw.kernel.namespace.SelfExecutor
import com.mengpaw.kernel.plugin.PluginExecutor
import com.mengpaw.kernel.plugin.PluginManager
import com.mengpaw.kernel.plugin.PluginMarketplaceClient
import com.mengpaw.kernel.security.IntegrityProvider
import com.mengpaw.kernel.security.NoOpIntegrityProvider
import com.mengpaw.kernel.security.Sanitizer
import com.mengpaw.kernel.security.SecurityPolicy
import com.mengpaw.kernel.session.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

/** Command completion entry — name + functional hint for the "!" dropdown. */
data class CommandInfo(val name: String, val description: String)

class AgentEngine(
    llmProvider: LlmProvider,
    private val pluginManager: PluginManager = PluginManager(),
    private val sessionManager: SessionManager = SessionManager(),
    private val promptEngine: PromptEngine = PromptEngine(),
    private val agentDocManager: AgentDocManager = AgentDocManager(),
    @Volatile private var middleware: AgentMiddleware = AgentMiddleware.NoOp,
    private val postCallMiddleware: PostCallMiddleware = PostCallMiddleware.NoOp,
    val scrollContext: ScrollContextManager? = null,
    private val checkpointManager: CheckpointManager = CheckpointManager(),
    /** Additional namespaces to register alongside built-ins (e.g. "sys" → SysExecutor.commands). */
    private val additionalNamespaces: Map<String, Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult>> = emptyMap()
) {
    // ── Sub-managers and executors (declared before init for initialization order) ──
    private val marketplaceClient = PluginMarketplaceClient()
    private val pluginExecutor = PluginExecutor(pluginManager, marketplaceClient)
    private val agentExecutor = AgentExecutor(agentDocManager)
    private val pipelineManager = PipelineManager(pluginManager, pluginExecutor, agentExecutor, additionalNamespaces)
    private var toolResultManager = ToolResultManager("agent")
    private val goalModeExecutor = GoalModeExecutor(this)
    private val missionModeExecutor = MissionModeExecutor(this)
    private val planModeExecutor = PlanModeExecutor(this, pipelineManager, sessionManager, promptEngine)
    private val swarmModeExecutor = SwarmModeExecutor(this)
    var integrityProvider: IntegrityProvider = NoOpIntegrityProvider
        set(value) {
            field = value
            pipelineManager.integrityProvider = value
        }

    init {
        // Wire real PluginManager into AgentDocManager so CLI.md generation sees installed plugins
        agentDocManager.pluginManager = pluginManager
        // Wire cache invalidation: when AgentDocs modifies workspace, invalidate PromptEngine cache
        com.mengpaw.kernel.agent.AgentDocs.addDocListener { name, filePath ->
            promptEngine.invalidateDocCache(name, filePath)
        }
        // 构建命令搜索索引 (BM25 + 双语同义词表) — 一次性初始化
        com.mengpaw.kernel.cli.BuiltinCommandIndex.buildAll()
        // Sync integrity provider to pipeline manager
        pipelineManager.integrityProvider = integrityProvider
    }

    /** The active LLM provider. Can be updated after construction (e.g. when user configures API key). */
    @Volatile private var llmProvider: LlmProvider = llmProvider

    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    @Volatile private var runningJob: Job? = null

    private val _output = MutableStateFlow<String>("")
    val output: StateFlow<String> = _output.asStateFlow()

    private val llmRequestBuilder = LlmRequestBuilder(systemPrompt = promptEngine.buildSystemPrompt())

    /**
     * 后台预压缩作用域 (v0.28.6) — 独立于 runningJob, 随引擎生灭。
     * 刻意不在 stop() 取消: submitTask 每轮先 stop, 取消会杀死在途压缩
     * (浪费一次 LLM 调用 + 历史永远压不下去)。
     */
    private val compressionScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    // ── Persistent conversation session (Claude Code pattern) ──────────
    // Instead of creating a new Session per run(), reuse the same session
    // so the LLM sees full conversation history across multiple user messages.
    @Volatile private var conversationSessionId: String? = null

    // ── Command completion listeners (UI 实时刷新钩子) ────────────
    // 任何命令执行完毕 (bang "!" 或 ReAct 循环内) → 通知监听器。
    // UI (设置页) 据此重扫 全局工具/智能体工具/智能体技能/插件 列表 — 与 AgentDocs 文档监听同构。
    private val commandListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** 注册命令完成监听器 — 命令执行完毕后回调 (无参数, UI 只关心"变了"这个事实)。 */
    fun addCommandListener(listener: () -> Unit) { commandListeners.add(listener) }

    /** 移除命令完成监听器。 */
    fun removeCommandListener(listener: () -> Unit) { commandListeners.remove(listener) }

    private fun notifyCommandExecuted() {
        commandListeners.forEach { listener ->
            try { listener() } catch (_: Exception) {}
        }
    }

    // ── Evolution (Agent 进化系统) ─────────────────────────────────
    /** 待注入的金字塔省察引导片段 (失败后生成, 下次 LLM 调用消费). */
    @Volatile private var pendingGuideFragment: String? = null
    /** 本会话已注入引导次数 (限流, 防刷屏). */
    private var guideInjections = 0

    /** Exposed for persistence in current_session.json — survives process death via disk save. */
    fun currentConversationId(): String? = conversationSessionId

    /** Public access to the active session ID (for plugins like memory-twin to populate CapabilityCard.runtime.currentSessionId). */
    val activeSessionId: String? get() = conversationSessionId

    /** Whether the agent is currently executing a task (for CapabilityCard.runtime.isBusy). */
    val isExecuting: Boolean get() = _state.value !is AgentState.Idle

    // ── Integrity terminal latch (matching OpenClaw terminal latch pattern) ──
    // Once tripped, blocks further LLM calls until the session is repaired.
    @Volatile private var integrityFailed: Boolean = false

    /** Check integrity of the current session. Returns false if terminal latch is active. */
    fun checkIntegrity(sessionId: String? = null): Boolean {
        if (integrityFailed) return false
        val sid = sessionId ?: conversationSessionId ?: return true
        if (!sessionManager.checkSessionIntegrity(sid)) {
            // v0.28.7 自动修复(幂等): 清理空白 assistant 消息等可修项 → 重查。
            // 修好则放行不锁死 — 空响应触发的 latch 不应阻塞后续轮次。
            if (sessionManager.repairSessionIntegrity(sid) && sessionManager.checkSessionIntegrity(sid)) {
                KernelLog.w("AgentEngine", "Integrity auto-repaired for session $sid — latch not engaged")
                return true
            }
            integrityFailed = true
            KernelLog.w("AgentEngine", "Integrity check failed for session $sid — terminal latch engaged")
            return false
        }
        return true
    }

    /** Attempt to repair integrity. Returns true if repair succeeded and latch is released. */
    fun repairIntegrity(sessionId: String? = null): Boolean {
        val sid = sessionId ?: conversationSessionId ?: return false
        if (sessionManager.repairSessionIntegrity(sid)) {
            // Re-check after repair
            if (sessionManager.checkSessionIntegrity(sid)) {
                integrityFailed = false
                KernelLog.i("AgentEngine", "Integrity repaired for session $sid — latch released")
                return true
            }
        }
        return false
    }

    /**
     * Restore conversation state after process death.
     *
     * Android kills processes without notice (no cleanup, no finalizers).
     * When the app restarts:
     * 1. AgentViewModel restores UI messages from current_session.json
     * 2. We must ALSO push those messages back into the engine's SessionManager
     * 3. And restore conversationSessionId so the engine doesn't start a fresh session
     *
     * Without this, the LLM sees zero history on the next user message —
     * all previous conversation context is lost.
     *
     * @param externalSessionId the UI-level session ID (from current_session.json)
     * @param messages the restored ChatMessageUi-derived messages in role/content format
     * @param lastWasInterrupted if the last run was cut short (isRunning=true at death)
     * @param previousEngineSessionId the engine session ID from before death (for checkpoint lookup)
     */
    fun restoreConversation(
        externalSessionId: String,
        messages: List<Pair<String, String>>,
        lastWasInterrupted: Boolean,
        previousEngineSessionId: String? = null
    ) {
        // Create a new engine session (SessionManager is in-memory, always empty after restart)
        val session = sessionManager.createSession(
            task = "restored after process death",
            agentId = agentName
        )
        // Push all messages into the engine session so the LLM sees full history
        for ((role, content) in messages) {
            sessionManager.addMessage(session.id, Message(role, content))
        }
        // Set conversationSessionId so runReActLoop() reuses this session
        conversationSessionId = session.id

        // ── Checkpoint recovery ──
        // If we have the previous engine session ID, try to find its last checkpoint.
        // This gives us diagnostic context about where the interrupted run was.
        var checkpointStep = 0
        if (previousEngineSessionId != null) {
            val ckpt = checkpointManager.loadLatestSync(previousEngineSessionId)
            if (ckpt != null) {
                checkpointStep = ckpt.step
                sessionManager.recordSessionEvent(session.id, SessionEventBus.SessionEvent(
                    kind = SessionEventBus.EventKind.SESSION_RECOVERED,
                    sessionId = session.id,
                    agentName = agentName,
                    summary = "Checkpoint found: step ${ckpt.step}, task: ${ckpt.remainingTask.take(60)}",
                    payload = mapOf("prevSessionId" to previousEngineSessionId, "step" to ckpt.step.toString())
                ))
            }
        }

        // If the last run was interrupted, set up recovery for the next user message
        if (lastWasInterrupted) {
            val summary = extractCompletedToolSummaries(session.id)
            sessionManager.recordInterruptedTurn(
                sessionId = session.id,
                completedTools = summary,
                interruptedTools = emptyList(),
                hasPartialText = false,
                hasPartialReasoning = false
            )
            sessionManager.recordSessionEvent(session.id, SessionEventBus.SessionEvent(
                kind = SessionEventBus.EventKind.RUN_INTERRUPTED,
                sessionId = session.id,
                agentName = agentName,
                summary = "Session restored after process death (was at step $checkpointStep)"
            ))
        }
    }

    /** Replace the LLM provider at runtime (e.g. after user configures API key). */
    fun updateLlmProvider(provider: LlmProvider) {
        llmProvider = provider
    }

    /** Expose the current LLM provider for delegation to sub-executors. */
    internal fun getLlmProvider(): LlmProvider = llmProvider

    /** Expose sub-managers for delegation to sub-executors (swarm/mission/plan). */
    internal fun getSessionManager(): SessionManager = sessionManager

    internal fun getPipelineManager(): PipelineManager = pipelineManager

    internal fun getPromptEngine(): PromptEngine = promptEngine

    /**
     * Attach the current coroutine job so stop() can cancel a running swarm.
     * Swarm workers bypass runReActLoop, so runningJob would not attach itself.
     */
    internal fun attachRunningJob(job: Job?) { runningJob = job }

    /** Update agent state flow (used by sub-executors for progress reporting). */
    internal fun updateAgentState(state: AgentState) { _state.value = state }

    /** Update agent output flow (used by sub-executors for progress reporting). */
    internal fun updateAgentOutput(output: String) { _output.value = output }

    fun configureCacheStrategy(endpoint: String) {
        llmRequestBuilder.cacheStrategy = CacheStrategy.forProvider(endpoint)
    }

    /** Delegate to PipelineManager. */
    fun getActiveNamespaces(): List<String> = pipelineManager.getActiveNamespaces()

    /** Access the plugin manager for settings display. */
    fun getPluginManager(): PluginManager = pluginManager

    /** Invalidate cached pipeline when plugins change. Call after plugin install/uninstall. */
    fun invalidatePipeline() { pipelineManager.invalidatePipeline() }

    /**
     * Execute a user-typed bang command ("!cmd") — bypasses the LLM/ReAct loop.
     * Routes through the full CLI pipeline (parse → rate limit → security → integrity → execute → audit).
     * Unknown CLI commands (e.g. "!echo hi") fall back to the sandboxed shell executor.
     */
    suspend fun executeCommand(
        commandLine: String,
        scope: String = "system",
        workDir: String? = null
    ): ExecutionResult {
        val ctx = ExecutionContext(
            sessionId = conversationSessionId ?: "ui-bang",
            agentName = agentName,
            workDir = workDir ?: bangWorkDir(),
            scope = scope
        )
        val result = pipelineManager.buildPipeline().execute(commandLine, ctx)
        // 命令执行完毕 → 通知监听器 (UI 实时刷新 Tools/Skills/Plugins 列表)
        notifyCommandExecuted()
        // Fallback 仅限真"命令不存在"（Pipeline 的 Unknown command 错误）— 命令存在但参数
        // 错误（如 agent.read 缺失文件也返回 ERR_NOT_FOUND）不得落 shell 兜底, 否则
        // 显示 "command not found" 掩盖真实错误（错误码二义性修复）
        val unknownCommand = result.errorCode == ErrorCodes.ERR_NOT_FOUND &&
            result.error?.startsWith("Unknown command") == true
        if (result.success || !unknownCommand) return result
        // Fallback: bare shell commands through the sandbox (blacklist + metacharacter checks)
        if (!SecurityPolicy().isAllowed(commandLine)) {
            return ExecutionResult.fail(
                "Command '${commandLine.substringBefore(' ')}' is blocked by security policy",
                errorCode = ErrorCodes.ERR_PERMISSION_DENIED
            )
        }
        return DefaultCommandExecutor().execute(commandLine, ctx)
    }

    /** Work directory for bang commands — agent workspace, created if missing (prevents ProcessBuilder ERR_IO). */
    private fun bangWorkDir(): String {
        val dir = java.io.File(DataPaths.AGENTS, "$agentName/workspace")
        return if (dir.mkdirs() || dir.exists()) dir.absolutePath else DataPaths.BASE
    }

    /**
     * 列出当前引擎管线中可执行的全部 CLI 命令（名称 + 描述），供 "!" 命令补全下拉使用。
     * 读本引擎 registry（PipelineManager 持有）— 不再依赖 SelfExecutor.commandRegistry
     * 全局指针（多 Agent 场景最后构建者赢的串扰问题）。
     */
    fun listCommands(): List<CommandInfo> {
        pipelineManager.buildPipeline() // 幂等; 确保本引擎 registry 已构建
        val descMap = CommandSearch.all().associate { it.fullName to it.description }
        return pipelineManager.listCommands().sorted().map { name ->
            // self. 前缀兜底: BuiltinCommandIndex 里 notify.message/banner 缺 self. 前缀（既有数据缺口）
            CommandInfo(name, descMap[name] ?: descMap[name.removePrefix("self.")] ?: "")
        }
    }

    /** Reset loop detection state — call before each new task. */
    fun resetLoopDetection() = promptEngine.resetLoopDetection()

    companion object {
        /** Single source of truth: generated from gradle.properties mengpaw.version. */
        val CORE_VERSION: String get() = MengPawVersion.FRAMEWORK

        /** 零待命并行 worker 会话 scope — 不注入主循环省察引导。 */
        private val WORKER_SCOPES = setOf("mission", "swarm")
    }

    /**
     * Start a new conversation — resets the persistent session.
     * Call when user taps "新会话" in UI.
     * Old session remains in SessionManager for history browsing.
     */
    fun newConversation() {
        conversationSessionId = null
        consecutiveCompacts = 0
        compactStuck = false
        promptEngine.resetLoopDetection()
    }

    private var consecutiveCompacts = 0
    private var compactStuck = false

    private fun estimateContextRatio(promptTokens: Int): Double = promptTokens / PipelineManager.DEFAULT_CONTEXT_WINDOW.toDouble()

    private fun estimateTokens(text: String): Int = (text.length * llmRequestBuilder.calibratedTokPerChar).toInt()

    /**
     * Snip stale tool results from conversation history.
     * Replaces old observation messages (step < currentStep-3) with compressed markers
     * to free context window space without losing the fact that a tool was called.
     *
     * @return number of messages snipped.
     */
    private fun snipStaleToolResults(sessionId: String, currentStep: Int): Int {
        var count = 0
        val session = sessionManager.getSession(sessionId) ?: return 0
        val threshold = currentStep - 3
        if (threshold <= 0) return 0

        val messages = session.messages
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg.role == "assistant" && msg.content.startsWith("Command:") && msg.content.length > 120) {
                // FIX: Actually replace the message content instead of just appending a system note
                val cmdName = msg.content.substringBefore("\n").take(50)
                messages[i] = msg.copy(content = "[snip] $cmdName ... (result compressed, step < $threshold)")
                count++
            }
        }
        if (count > 0) {
            // Update session state to reflect modified messages
            sessionManager.addMessage(sessionId, Message(
                "system", "[snip — $count old tool results compressed to free context]"))
        }
        return count
    }

    private suspend fun maybeFoldContext(sessionId: String, promptTokens: Int, currentStep: Int = 0): Boolean {
        if (compactStuck) return false
        val ratio = estimateContextRatio(promptTokens)
        if (ratio < PipelineManager.SOFT_COMPACT_RATIO) { consecutiveCompacts = 0; compactStuck = false; return false }
        if (ratio < PipelineManager.TOOL_SNIP_RATIO) return false
        // 折叠主阈值按模型档位（默认 0.9 / 保守模型 0.8 — setAgentIdentity 时设置）
        if (ratio < compactRatio) { return snipStaleToolResults(sessionId, currentStep) > 0 }
        val estimatedFoldTokens = (promptTokens * 0.3).toInt()
        if (ratio < PipelineManager.COMPACT_FORCE_RATIO && estimatedFoldTokens < PipelineManager.MIN_FOLD_TOKENS) return false
        // P1 修复: 折叠只压缩本会话（并行 worker 会话不抢占 activeSessionId,
        // 但显式传参更稳 — 防压缩错会话）; 仅在压缩实际发生时累加计数器
        if (sessionManager.compressIfNeeded(llmProvider, specificSessionId = sessionId)) {
            consecutiveCompacts++
        }
        if (consecutiveCompacts >= 2) {
            compactStuck = true
            val msg = when (agentLanguage) {
                PromptEngine.AgentLanguage.CHINESE -> "上下文窗口不足以容纳当前对话。自动折叠已暂停。建议手动清理历史或增大模型的 context_window 设置。"
                PromptEngine.AgentLanguage.ENGLISH -> "Context window too small for current conversation. Auto-compaction paused. Consider clearing history or increasing the model's context_window."
            }
            sessionManager.addMessage(sessionId, Message("system", msg))
        }
        return true
    }

    var agentLanguage: PromptEngine.AgentLanguage = PromptEngine.AgentLanguage.CHINESE
        private set
    var agentName: String = "MengPaw"
        private set
    var framework: String? = null
        private set
    var modelName: String = "unknown"
        private set

    /** 折叠主阈值（模型档位）— 默认 0.9，保守模型 0.8（PipelineManager.compactRatioFor）。 */
    @Volatile
    var compactRatio: Double = PipelineManager.compactRatioFor("unknown")
        private set

    fun setAgentIdentity(name: String, framework: String?, model: String) {
        agentName = name
        this.framework = framework
        this.modelName = model
        compactRatio = PipelineManager.compactRatioFor(model)
        toolResultManager = ToolResultManager(name)
        sessionManager.agentName = name
        // FIX(自检报告 P0-2): 文档系统绑定到真实 Agent 工作区 — 此前 AgentDocManager
        // 固定 agent-001, agent.docs/cli/modes 全读错目录。模板写入 {AGENTS}/{name}/,
        // 此处同步后命令层与模板层目录一致。
        agentDocManager.bindAgent(name)
        rebuildSystemPrompt()
    }

    fun setAgentLanguage(lang: PromptEngine.AgentLanguage) {
        if (lang != agentLanguage) { agentLanguage = lang; rebuildSystemPrompt() }
    }

    /**
     * 预热 CLI.md — 幂等 (插件活跃数比对, 配置反复 apply 不重复写盘)。
     * 会话创建时调用, 使 CLI.md 在会话就绪时已落盘 (agent.cli / agent.read cli.md 即见)。
     */
    fun ensureCliDoc() {
        try { agentDocManager.ensureCliDoc() } catch (_: Exception) {}
    }

    private fun rebuildSystemPrompt() {
        consecutiveCompacts = 0; compactStuck = false
        promptEngine.resetLoopDetection()
        refreshSystemPrompt()
    }

    /**
     * 仅重算 system prompt（经 middleware 处理），不重置循环检测/compact 状态。
     * 可高频调用（如部落 inbox 状态变化时刷新提醒段落）。
     */
    fun refreshSystemPrompt() {
        val base = promptEngine.buildSystemPrompt(lang = agentLanguage, agentName = agentName, framework = framework, modelName = modelName)
        val processed = middleware.onSystemPrompt(base, agentName)
        llmRequestBuilder.updateSystemPrompt(processed)
    }

    /** 运行时替换 middleware（如部落收件箱提醒），并立即刷新 system prompt。 */
    fun setMiddleware(mw: AgentMiddleware) {
        middleware = mw
        refreshSystemPrompt()
    }

    data class TraceStep(val step: Int, val thought: String, val action: String?, val observation: String?)

    suspend fun run(
        task: String, maxSteps: Int = 50, onStep: ((TraceStep) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null,
        attachments: List<AttachmentData> = emptyList()
    ): String {
        val guardedTask = if (com.mengpaw.kernel.security.PromptFirewall.checkUserPrompt(task) != null)
            com.mengpaw.kernel.security.PromptFirewall.wrapWithDefense(task) else task
        return runReActLoop(task = guardedTask, maxSteps = maxSteps, onStep = onStep, onDelta = onDelta,
            attachments = attachments)
    }

    // ── Goal Mode (delegated to GoalModeExecutor) ────────────────────

    /**
     * Goal-mode execution with RubricGate auto-completion detection.
     * Delegates to [GoalModeExecutor].
     */
    suspend fun runWithGoal(
        task: String, maxTurns: Int = 20, maxTokensBudget: Int = 300_000,
        onStep: ((TraceStep) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null
    ): String = goalModeExecutor.runWithGoal(task, maxTurns, maxTokensBudget, onStep, onDelta)

    /**
     * Internal ReAct loop with optional context prefix.
     * Shared by run() and runWithGoal() to avoid session-creation overhead.
     */
    internal suspend fun runReActLoop(
        task: String,
        maxSteps: Int,
        contextPrefix: String = "",
        onStep: ((TraceStep) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null,
        attachments: List<AttachmentData> = emptyList()
    ): String {
        ErrorCollector.init()

        // ── Evolution: 钩子归系统 + 绩效反馈注入 ──
        com.mengpaw.kernel.evolution.EvolutionHook.install()
        guideInjections = 0
        pendingGuideFragment = com.mengpaw.kernel.evolution.EvolutionGuide.buildSessionBrief(agentName)

        // ── Persistent conversation (Claude Code pattern) ──
        // Reuse existing session across multiple user messages so the
        // LLM sees full conversation history, not just the current message.
        val session: Session
        // Snapshot volatile field to avoid TOCTOU race with newConversation()
        val currentSessionId = conversationSessionId
        if (currentSessionId != null) {
            val existing = sessionManager.getSession(currentSessionId)
            if (existing != null) {
                session = existing
            } else {
                // Session lost (e.g., process restart) — create new
                session = sessionManager.createSession(task)
                conversationSessionId = session.id
            }
        } else {
            session = sessionManager.createSession(task)
            conversationSessionId = session.id
        }
        sessionManager.agentName = agentName
        // FIX(自检报告 P0-2): workDir 指向 Agent 工作区而非 BASE — 此前 self.status 显示
        // /data/user/0/.../files (BASE), 与 agent.read/ls 的工作区基准是两套路径体系。
        val context = ExecutionContext(
            sessionId = session.id, agentName = agentName,
            workDir = "${com.mengpaw.kernel.DataPaths.AGENTS}/$agentName"
        )

        // ★ Integrity check after session creation — terminal latch blocks corrupt sessions
        if (!checkIntegrity(session.id)) {
            val errorMsg = localizedError("session_corrupted", session.id, agentLanguage)
            sessionManager.addMessage(session.id, Message("system", errorMsg))
            _state.value = AgentState.Error(errorMsg)
            return errorMsg
        }

        _state.value = AgentState.Running(task, 0, maxSteps)
        _output.value = ""

        // Append user message to existing conversation history (Claude Code pattern)
        // 结构化附件 (v0.33.0+): 由 getStructuredHistory 挂 _image/_audio_data 二进制键
        sessionManager.addMessage(session.id, Message("user", task, attachments = attachments))
        if (contextPrefix.isNotBlank()) {
            sessionManager.addMessage(session.id, Message("system", contextPrefix))
        }

        try {
            val job = kotlinx.coroutines.currentCoroutineContext()[Job]
            runningJob = job
            var consecutiveContinueCount = 0 // Tracks needsContinue without action
            var consecutiveFailures = 0       // Tracks consecutive tool failures
            var emptyResponseCount = 0        // Tracks empty LLM responses (retry once, then error)
            val originalMaxSteps = maxSteps
            var effectiveMax = maxSteps
            var step = 0
            var extended = false

            while (step < effectiveMax) {
                runningJob?.let { if (!it.isActive) throw kotlinx.coroutines.CancellationException("Agent stopped") }
                _state.value = AgentState.Running(task, step + 1, effectiveMax)

                // ── Adaptive step extension ──
                // If agent is still making productive progress near the limit, auto-extend
                if (!extended && step >= effectiveMax * 0.75 && consecutiveFailures == 0) {
                    val extendTo = minOf((effectiveMax * 1.5).toInt(), originalMaxSteps * 2)
                    if (extendTo > effectiveMax) {
                        effectiveMax = extendTo
                        extended = true
                    }
                }

                val conversation = buildConversation(session.id)
                // 流式调用: 增量 token 经 onDelta 实时透传 UI(打字机效果); 完整文本仍用于解析
                val llmResponse = if (onDelta != null)
                    llmProvider.completeStreamingWithMessages(conversation, onDelta)
                else llmProvider.completeWithMessages(conversation)
                // 利用 LLM 等待窗口刚刚结束的间隙刷盘中期记忆 (I/O 成本隐藏)
                com.mengpaw.kernel.agent.AgentDocs.flushMidTermMemoryQueue()
                val sanitized = Sanitizer.sanitize(llmResponse)

                // ── 空响应防御 (v0.28.7): DeepSeek 偶发空流 (SSE 零增量, S-DONE len=0) ──
                // 根因链: 空响应 → 空白 assistant 消息入库 → checkSessionIntegrity 失败 →
                // 完整性 terminal latch 锁死该会话后续所有轮次 ("会话数据完整性检查失败")。
                // 修复: 空响应不入库空白消息, 重试一次 (step 不递增); 仍空则写明确错误并终止。
                if (sanitized.isBlank()) {
                    emptyResponseCount++
                    if (emptyResponseCount >= 2) {
                        val errorMsg = localizedError("empty_response", "", agentLanguage)
                        sessionManager.addMessage(session.id, Message("assistant", errorMsg))
                        sessionManager.recordSessionEvent(session.id, SessionEventBus.SessionEvent(
                            kind = SessionEventBus.EventKind.LLM_CALL_ERROR,
                            sessionId = session.id,
                            agentName = agentName,
                            summary = "Empty LLM response after retry",
                            payload = mapOf("error" to "empty_response", "consecutive" to "true")
                        ))
                        _state.value = AgentState.Error(errorMsg)
                        return errorMsg
                    }
                    KernelLog.w("AgentEngine", "Empty LLM response at step $step — retrying once")
                    continue
                }
                emptyResponseCount = 0

                val totalChars = llmRequestBuilder.currentSystemPrompt.length +
                    sessionManager.getStructuredHistory(session.id).sumOf { (it["content"]?.length ?: 0) }
                val estimatedTokens = (totalChars * llmRequestBuilder.calibratedTokPerChar).toInt()
                llmRequestBuilder.calibrateFromUsage(estimatedTokens, totalChars)

                val postResult = postCallMiddleware.onPostCall(sanitized, step + 1, totalChars, estimatedTokens)
                sessionManager.addMessage(session.id, Message("assistant", postResult.text))
                _output.value = postResult.text

                if (postResult.shouldFold) {
                    scrollContext?.evictSpan(
                        seqLo = maxOf(0, step - 10), seqHi = step,
                        text = postResult.text.take(6000),
                        headline = postResult.foldReason ?: "Step ${step + 1} context eviction")
                    maybeFoldContext(session.id, estimatedTokens, step + 1)
                }

                val parsed = promptEngine.parse(sanitized)

                if (parsed.isFinal) {
                    val answer = parsed.thought
                    sessionManager.addMessage(session.id, Message("assistant", answer))
                    // No boundary message — the conversation continues naturally.
                    // The LLM sees full history: previous FinalAnswer + new user message = context.
                    sessionManager.recordSessionEvent(session.id, SessionEventBus.SessionEvent(
                        kind = SessionEventBus.EventKind.RUN_COMPLETED,
                        sessionId = session.id,
                        agentName = agentName,
                        summary = "Run completed at step ${step + 1}",
                        payload = mapOf("steps" to (step + 1).toString())
                    ))
                    _state.value = AgentState.Finished(answer)
                    com.mengpaw.kernel.agent.AgentDocs.flushMidTermMemoryQueue()
                    recordTaskMemory(task, answer)
                    // Periodic cleanup of old tool result cache files
                    if (java.lang.Math.random() < 0.1) toolResultManager.cleanupOldToolResults()
                    return answer
                }

                // Handle needsContinue: model output Thought but no Action
                // Inject a continue prompt instead of stopping
                if (parsed.needsContinue) {
                    consecutiveContinueCount++
                    if (consecutiveContinueCount >= 2) {
                        // Model keeps thinking without acting — force finalize
                        val msg = localizedError("max_steps", maxSteps.toString(), agentLanguage)
                        sessionManager.addMessage(session.id, Message("assistant", msg))
                        _state.value = AgentState.Finished(msg)
                        return msg
                    }
                    val continuePrompt = "继续。输出 Action: <命令> 和 Action Input: <参数>。"
                    sessionManager.addMessage(session.id, Message("user", continuePrompt))
                    continue
                }
                consecutiveContinueCount = 0 // Reset on successful action

                // ── 单次 LLM 输出可含多个 Action — 并行执行后合并 Observation ──
                // 并发纪律照搬 SwarmModeExecutor.runWorker: 共享可变状态
                // (detectLoop/trackResult/consecutiveFailures/ErrorCollector) 只在主协程串行更新
                // 同批去重: 相同命令(名称+参数)只执行一次 — 模型偶发重复输出同一 Action
                val actionList = (parsed.actions.ifEmpty { listOfNotNull(parsed.action) })
                    .distinctBy { "${it.name} ${it.parameters.values.joinToString(" ")}" }

                if (actionList.isNotEmpty()) {
                    // ── 组装命令行 + 参数格式门卫 (PARAM_FORMAT_ERROR) ──
                    // JSON 双轨制防护见 ToolCall.paramFormatError() — 命中即不执行, 直接返回格式错误。
                    val formattedCalls = actionList.map { call ->
                        Triple("${call.name} ${call.parameters.values.joinToString(" ")}", call.paramFormatError(), call)
                    }
                    val commandLines = formattedCalls.map { it.first }

                    // Loop detection on the first command (kept serial — shared mutable state)
                    if (promptEngine.detectLoop(commandLines.first())) {
                        val cmd = commandLines.first()
                        ErrorCollector.report(ErrorType.LOOP_DETECTED, "AgentEngine", cmd,
                            sessionId = session.id, agentName = agentName)
                        val errorMsg = localizedError("loop_detected", cmd, agentLanguage)
                        sessionManager.addMessage(session.id, Message("assistant", errorMsg))
                        _state.value = AgentState.Error(errorMsg)
                        onStep?.invoke(TraceStep(step + 1, parsed.thought, cmd, errorMsg))
                        return errorMsg
                    }

                    // ── 并行执行（结构化并发: async 内 withTimeout + pipeline.execute）──
                    val results = coroutineScope {
                        formattedCalls.map { (cmd, formatError, _) ->
                            async(KernelDispatchers.BACKGROUND) {
                                try {
                                    if (formatError != null) {
                                        ExecutionResult.fail(formatError, errorCode = ErrorCodes.PARAM_FORMAT_ERROR)
                                    } else {
                                        withTimeout(60_000L) { pipelineManager.buildPipeline().execute(cmd, context) }
                                    }
                                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                                    ExecutionResult.fail("命令超时 (60s): $cmd。请检查网络连接或尝试其他方式。", errorCode = ErrorCodes.ERR_INTERNAL)
                                }
                            }
                        }.awaitAll()
                    }
                    // 命令批次执行完毕 → 通知监听器 (UI 实时刷新 Tools/Skills/Plugins 列表)
                    notifyCommandExecuted()

                    // ── 合并后串行更新共享可变状态 + 组装 Observation ──
                    val observationEntries = mutableListOf<String>()
                    var anyFailure = false
                    results.forEachIndexed { i, result ->
                        val commandLine = commandLines[i]
                        if (!result.success) {
                            anyFailure = true
                            ErrorCollector.report(ErrorType.TOOL_CALL_FAILED, "AgentEngine",
                                "$commandLine → ${result.error}", sessionId = session.id, agentName = agentName,
                                metadata = mapOf("errorCode" to (result.errorCode ?: ""), "command" to commandLine))
                            // 进化省察: 生成金字塔引导片段, 下次 LLM 调用注入 (轻/深分级)
                            pendingGuideFragment = com.mengpaw.kernel.evolution.EvolutionGuide.buildFragment(
                                agentName = agentName, command = commandLine, message = result.error ?: "")
                        }
                        // errorCode 注入 Observation — 模型可见错误类型 (PARAM_FORMAT_ERROR/NETWORK_OFFLINE/...)
                        var rawObservation = if (result.success) {
                            result.output
                        } else {
                            result.errorCode?.let { "Error [$it]: ${result.error}" } ?: "Error: ${result.error}"
                        }
                        // ── QwenPaw-style tool result pruning ──
                        rawObservation = toolResultManager.pruneToolResult(commandLine, rawObservation, step + 1)
                        // 多 Action 并行: 思考只在第一个 Action 上呈现, 后续 Action 复用同一步序号
                        // (UI 对空 thought 渲染成纯工具行, 避免 N 条相同思考重复)
                        onStep?.invoke(TraceStep(step + 1, if (i == 0) parsed.thought else "", commandLine, rawObservation))
                        observationEntries.add("Command: $commandLine\nResult: $rawObservation")
                    }
                    // 连续失败统计与失败循环检测（串行，无竞争）
                    if (anyFailure) consecutiveFailures++ else consecutiveFailures = 0
                    if (promptEngine.trackResult(!anyFailure)) {
                        val errorMsg = localizedError("consecutive_failures", "5", agentLanguage)
                        sessionManager.addMessage(session.id, Message("assistant", errorMsg))
                        _state.value = AgentState.Error(errorMsg)
                        onStep?.invoke(TraceStep(step + 1, parsed.thought, commandLines.first(), errorMsg))
                        return errorMsg
                    }
                    // 合并为一条 assistant 消息（多 Action 的多个 Observation）
                    sessionManager.addMessage(session.id, Message("assistant", observationEntries.joinToString("\n\n")))
                } else {
                    onStep?.invoke(TraceStep(step + 1, parsed.thought, null, null))
                }
                step++
                // ── Checkpoint: persist progress every 5 steps ──
                if (step > 0 && step % 5 == 0) {
                    checkpointManager.save(Checkpoint(
                        sessionId = session.id,
                        step = step,
                        remainingTask = task,
                        context = mapOf("agentName" to agentName, "modelName" to modelName)
                    ))
                    checkpointManager.cleanup(session.id, keep = 3)
                }
            }

            val msg = localizedError("max_steps", maxSteps.toString(), agentLanguage)
            sessionManager.addMessage(session.id, Message("assistant", msg))
            sessionManager.recordSessionEvent(session.id, SessionEventBus.SessionEvent(
                kind = SessionEventBus.EventKind.RUN_COMPLETED,
                sessionId = session.id,
                agentName = agentName,
                summary = "Max steps ($effectiveMax) reached",
                payload = mapOf("steps" to step.toString(), "max" to effectiveMax.toString())
            ))
            _state.value = AgentState.Finished(msg)
            return msg
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Re-throw to respect coroutine cancellation contract
            throw e
        } catch (e: Exception) {
            // ★ Record completed tools as interrupted turn recovery (Reasonix Level 2)
            val completedTools = extractCompletedToolSummaries(session.id)
            sessionManager.recordInterruptedTurn(
                sessionId = session.id,
                completedTools = completedTools,
                interruptedTools = emptyList(),
                hasPartialText = false,
                hasPartialReasoning = false
            )

            // ★ Emit lifecycle events (matching OpenClaw session-state-events.ts)
            sessionManager.recordSessionEvent(session.id, SessionEventBus.SessionEvent(
                kind = SessionEventBus.EventKind.LLM_CALL_ERROR,
                sessionId = session.id,
                agentName = agentName,
                summary = e.message?.take(120) ?: "Unknown error",
                payload = mapOf("error" to (e.message?.take(200) ?: ""), "consecutive" to "true")
            ))
            sessionManager.recordSessionEvent(session.id, SessionEventBus.SessionEvent(
                kind = SessionEventBus.EventKind.RUN_INTERRUPTED,
                sessionId = session.id,
                agentName = agentName,
                summary = "Run interrupted after error: ${e.message?.take(80) ?: "unknown"}"
            ))

            ErrorCollector.report(ErrorType.AGENT_CRASH, "AgentEngine.runReActLoop", e.message ?: "(no message)",
                throwable = e, sessionId = session.id, agentName = agentName)
            val errorMsg = localizedError("agent_error", e.message ?: e::class.simpleName ?: "unknown", agentLanguage)
            sessionManager.addMessage(session.id, Message("assistant", errorMsg))
            _state.value = AgentState.Error(errorMsg)
            return errorMsg
        }
    }

    /**
     * Extract completed tool summaries from a session's history.
     * Scans the most recent assistant messages for Command: patterns.
     */
    private fun extractCompletedToolSummaries(sessionId: String): List<InterruptedToolSummary> {
        val msgs = sessionManager.getHistory(sessionId)
        val summaries = mutableListOf<InterruptedToolSummary>()
        for (msg in msgs.reversed()) {
            if (msg.localOnly) continue  // skip recovery metadata
            if (msg.role == "user") break // stop at user boundary
            if (msg.content.startsWith("Command:")) {
                val summary = extractToolSummary(msg.content)
                if (summary != null) summaries.add(summary)
            }
        }
        return summaries.takeLast(10) // keep only recent tools
    }

    // ── Mission Mode (delegated to MissionModeExecutor) ──────────────

    /**
     * Mission-mode: decompose -> worker execution -> verification.
     * Uses the LLM to decompose the task, then runs each subtask sequentially.
     * Delegates to [MissionModeExecutor].
     */
    suspend fun runWithMission(
        task: String, maxSubtasks: Int = 5, maxStepsPerSubtask: Int = 12,
        maxRetriesPerSubtask: Int = 2, maxParallel: Int = 4,
        onStep: ((TraceStep) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null
    ): String = missionModeExecutor.runWithMission(
        task, maxSubtasks, maxStepsPerSubtask, maxRetriesPerSubtask, maxParallel, onStep, onDelta)

    // ── 火种模式 (Swarm Mode) — 规划器拆解 → 并行 Worker → Verifier → 合成器 ──

    /**
     * 火种模式 (Swarm Mode): "星星之火，可以燎原" — 一个任务点燃众多 Worker 的燎原之势。
     *
     * 混合模型: [roles] 按角色注入 LlmProvider (planner/worker/verifier/synthesizer 可异模型),
     * 缺省回退引擎主 provider。JIT 看板三闸门: [maxTotalSteps] 总预算 + [maxParallel] WIP 并行
     * + [maxStepsPerSubtask] 单任务。Andon 失败协议: worker FAIL → 协调器决策 (重派/终止)。
     * 零待命 Worker: 独立 Session (scope="swarm") 用完即销毁, 不污染主会话与记忆。
     */
    suspend fun runWithSwarm(
        task: String,
        roles: Map<String, LlmProvider> = emptyMap(),
        maxSubtasks: Int = 5,
        maxParallel: Int = 4,
        maxStepsPerSubtask: Int = 12,
        maxRetriesPerSubtask: Int = 2,
        maxTotalSteps: Int = maxSubtasks * maxStepsPerSubtask,
        onStep: ((TraceStep) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null
    ): String = swarmModeExecutor.runWithSwarm(
        task, roles, maxSubtasks, maxParallel, maxStepsPerSubtask,
        maxRetriesPerSubtask, maxTotalSteps, onStep, onDelta
    )

    // ── Fleet Mode (转发到火种模式) ─────────────────────────────────

    /**
     * Fleet-mode: 多 Agent 并行编队协调 (v0.25+: 转发到 runWithSwarm)。
     * 角色级模型路由: [roles] 指定 planner/worker/verifier/synthesizer/worker.alt 异模型
     * （缺省回退主 provider）；Andon 重派自动切 worker.alt。
     * 报告头为 "## 火种模式:"。
     */
    suspend fun runWithFleet(
        task: String,
        roles: Map<String, LlmProvider> = emptyMap(),
        maxSubtasks: Int = 5, maxStepsPerSubtask: Int = 12,
        maxRetriesPerSubtask: Int = 2, onStep: ((TraceStep) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null
    ): String = runWithSwarm(
        task = task, roles = roles, maxSubtasks = maxSubtasks, maxParallel = 4,
        maxStepsPerSubtask = maxStepsPerSubtask, maxRetriesPerSubtask = maxRetriesPerSubtask,
        maxTotalSteps = maxSubtasks * maxStepsPerSubtask, onStep = onStep, onDelta = onDelta
    )

    // ── Plan Mode (delegated to PlanModeExecutor) ─────────────────────

    /**
     * Plan-mode: structured plan generation and step-by-step execution.
     * Delegates to [PlanModeExecutor].
     */
    suspend fun runWithPlan(
        task: String, maxStepsPerPlanStep: Int = 5,
        onStep: ((TraceStep) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null
    ): String = planModeExecutor.runWithPlan(task, maxStepsPerPlanStep, onStep, onDelta)

    /** Format a plan summary for display. Delegates to [PlanModeExecutor]. */
    fun formatPlanSummary(plan: TaskPlan): String = planModeExecutor.formatPlanSummary(plan)

    /** Generate a plan by asking the LLM to decompose the task. Delegates to [PlanModeExecutor]. */
    suspend fun generatePlan(task: String): TaskPlan = planModeExecutor.generatePlan(task, llmProvider)

    fun stop() { _state.value = AgentState.Idle; runningJob?.cancel(); runningJob = null }

    /**
     * Build the conversation message list for an LLM call.
     * Includes integrity gate, recovery block injection, and cache annotations.
     */
    internal suspend fun buildConversation(sessionId: String): List<Map<String, String>> {
        KernelLog.d("MengPawLatency", "BC-ENTER $sessionId msgs=${sessionManager.getSession(sessionId)?.messages?.size}")
        // ★ Integrity gate: terminal latch (matching OpenClaw assertSqliteIntegrity)
        // If session data is corrupted, block LLM calls with a warning instead of
        // letting the model act on potentially garbage history.
        if (integrityFailed) {
            KernelLog.w("AgentEngine", "Integrity latch active — blocking LLM call")
            return listOf(mapOf("role" to "system", "content" to "Session data integrity issue detected. " +
                "Please use agent.repair or start a new conversation to continue."))
        }
        // v0.28.6: 后台预压缩 (≥42 提前压, 不在请求前同步插 LLM 调用) + 同步兜底
        sessionManager.scheduleCompressionIfNeeded(sessionId, compressionScope, llmProvider)
        sessionManager.awaitCompressionIfNeeded(llmProvider, sessionId = sessionId)
        val history = sessionManager.getStructuredHistory(sessionId)
        val nonSystemHistory = if (history.isNotEmpty() && history[0]["role"] == "system") history.drop(1) else history

        // ★ Recovery block injection: if there's a pending interrupted turn from a prior
        // failed LLM call, inject the structured recovery block before the last user message.
        // Matching Reasonix [withInterruptedRecovery] in interrupted_recovery.go.
        val rawMessages = sessionManager.getSession(sessionId)?.messages ?: emptyList()
        val pendingRecovery = com.mengpaw.kernel.session.findPendingRecovery(rawMessages)
        if (pendingRecovery != null) {
            val block = com.mengpaw.kernel.session.buildInterruptedRecoveryBlock(pendingRecovery)
            // Prepend recovery block to the last user message
            val mutableMessages = nonSystemHistory.toMutableList()
            val lastUserIdx = mutableMessages.indexOfLast { it["role"] == "user" }
            if (lastUserIdx >= 0) {
                val lastUser = mutableMessages[lastUserIdx]
                mutableMessages[lastUserIdx] = mapOf(
                    "role" to "user",
                    "content" to "$block\n\n${lastUser["content"]}"
                )
            }
            // Consume the recovery after injection so it doesn't fire again
            sessionManager.consumePendingRecovery(sessionId)
            // Emit recovery event (matching OpenClaw session_state_notices pattern)
            sessionManager.recordSessionEvent(sessionId, SessionEventBus.SessionEvent(
                kind = SessionEventBus.EventKind.SESSION_RECOVERED,
                sessionId = sessionId,
                agentName = agentName,
                summary = "Recovery block injected: ${pendingRecovery.completedTools.size} tools completed"
            ))
            KernelLog.d("MengPawLatency", "BC-EXIT $sessionId recovery")
            return llmRequestBuilder.buildMessages(
                listOf(mapOf("role" to "system", "content" to llmRequestBuilder.currentSystemPrompt)) +
                    mutableMessages,
                injectCacheAnnotations = true
            )
        }

        // ── Evolution 省察引导注入: 金字塔提问片段 (限流 MAX_INJECTIONS/会话) ──
        // 追加到对话末尾而非 add(0) 前插 — 前插会使后续所有消息位移, 击穿整个
        // 前缀缓存 (prompt caching 按字节前缀命中); 末尾追加只增不改, 缓存前缀不受扰动,
        // 且"最新指令"语义更强（紧贴当前轮次）。
        // 只对主会话注入 — 并行 worker（mission/swarm 零待命会话）不消费主循环遗留的
        // 省察引导（防注入错目标会话）。
        val guide = if (sessionManager.getSession(sessionId)?.scope in WORKER_SCOPES) null
        else pendingGuideFragment
        if (guide != null && guideInjections < com.mengpaw.kernel.evolution.EvolutionGuide.MAX_INJECTIONS) {
            guideInjections++
            pendingGuideFragment = null
            val mutable = nonSystemHistory.toMutableList()
            mutable.add(mapOf("role" to "system", "content" to guide))
            KernelLog.d("MengPawLatency", "BC-EXIT $sessionId guide")
            return llmRequestBuilder.buildMessages(mutable, injectCacheAnnotations = true)
        }

        KernelLog.d("MengPawLatency", "BC-EXIT $sessionId normal")
        return llmRequestBuilder.buildMessages(nonSystemHistory, injectCacheAnnotations = true)
    }

    private fun recordTaskMemory(task: String, result: String) {
        // 单轨记忆 (v0.22.0): 任务记忆写入三轨中期 memory_{date}.md (梦境读中期的输入面)
        try {
            AgentDocs.appendMidTermMemory(agentName, "任务: ${task.take(60)}\n结果: ${result.take(500)}")
        } catch (e: Exception) {
            KernelLog.w("AgentEngine", "Failed to record task memory: ${e.message}")
        }
    }
}
