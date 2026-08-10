// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.agent.AgentDocManager
import com.mengpaw.kernel.agent.AgentExecutor
import com.mengpaw.kernel.agent.AgentMiddleware
import com.mengpaw.kernel.AgentState
import com.mengpaw.kernel.agent.PostCallMiddleware
import com.mengpaw.kernel.agent.ScrollContextManager
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.llm.*
import com.mengpaw.kernel.plugin.PluginExecutor
import com.mengpaw.kernel.plugin.PluginManager
import com.mengpaw.kernel.plugin.PluginMarketplaceClient
import com.mengpaw.kernel.security.IntegrityProvider
import com.mengpaw.kernel.security.NoOpIntegrityProvider
import com.mengpaw.kernel.session.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job

/** Command completion entry — name + functional hint for the "!" dropdown. */
data class CommandInfo(val name: String, val description: String)

/**
 * Agent 引擎 — 状态机/会话/引导 + 各模式执行器编排。
 * ReAct 循环/对话构建/完整性/命令执行已拆至 [AgentRuntime]。
 */
class AgentEngine(
    llmProvider: LlmProvider,
    private val pluginManager: PluginManager = PluginManager(),
    private val sessionManager: SessionManager = SessionManager(),
    private val promptEngine: PromptEngine = PromptEngine(),
    private val agentDocManager: AgentDocManager = AgentDocManager(),
    @Volatile private var middleware: AgentMiddleware = AgentMiddleware.NoOp,
    internal val postCallMiddleware: PostCallMiddleware = PostCallMiddleware.NoOp,
    val scrollContext: ScrollContextManager? = null,
    internal val checkpointManager: CheckpointManager = CheckpointManager(),
    /** Additional namespaces to register alongside built-ins (e.g. "sys" → SysExecutor.commands). */
    private val additionalNamespaces: Map<String, Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult>> = emptyMap()
) {
    // ── Sub-managers and executors (declared before init for initialization order) ──
    private val marketplaceClient = PluginMarketplaceClient()
    private val pluginExecutor = PluginExecutor(pluginManager, marketplaceClient)
    private val agentExecutor = AgentExecutor(agentDocManager)
    private val pipelineManager = PipelineManager(pluginManager, pluginExecutor, agentExecutor, additionalNamespaces)
    internal var toolResultManager = ToolResultManager("agent")
    private val goalModeExecutor = GoalModeExecutor(this)
    private val planModeExecutor = PlanModeExecutor(this, pipelineManager, sessionManager, promptEngine)
    private val swarmModeExecutor = SwarmModeExecutor(this)
    var integrityProvider: IntegrityProvider = NoOpIntegrityProvider
        set(value) {
            field = value
            pipelineManager.integrityProvider = value
        }

    /** 运行时内核 — ReAct 循环/对话/完整性/命令执行 (拆自本类)。 */
    private val runtime = AgentRuntime(this)

    init {
        // Wire real PluginManager into AgentDocManager so CLI.md generation sees installed plugins
        agentDocManager.pluginManager = pluginManager
        // Wire cache invalidation: when AgentDocs modifies workspace, invalidate PromptEngine cache
        com.mengpaw.kernel.agent.AgentDocs.addDocListener { name, filePath ->
            promptEngine.invalidateDocCache(name, filePath)
        }
        // 构建命令搜索索引 (BM25 + 双语同义词表) — 一次性初始化
        com.mengpaw.kernel.cli.BuiltinCommandIndex.buildAll()
        // 注入火种引擎引用 — swarm.run 命令自主触发 Swarm/Fleet (v0.35.5)
        com.mengpaw.kernel.agent.SwarmExecutor.attachEngine(this)
        // Sync integrity provider to pipeline manager
        pipelineManager.integrityProvider = integrityProvider
    }

    /** The active LLM provider. Can be updated after construction (e.g. when user configures API key). */
    @Volatile private var llmProvider: LlmProvider = llmProvider

    internal val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    @Volatile internal var runningJob: Job? = null

    internal val _output = MutableStateFlow<String>("")
    val output: StateFlow<String> = _output.asStateFlow()

    internal val llmRequestBuilder = LlmRequestBuilder(systemPrompt = promptEngine.buildSystemPrompt())

    // ── Persistent conversation session (Claude Code pattern) ──────────
    // Instead of creating a new Session per run(), reuse the same session
    // so the LLM sees full conversation history across multiple user messages.
    @Volatile internal var conversationSessionId: String? = null

    /** Exposed for persistence in current_session.json — survives process death via disk save. */
    fun currentConversationId(): String? = conversationSessionId

    /** Public access to the active session ID (for plugins like memory-twin to populate CapabilityCard.runtime.currentSessionId). */
    val activeSessionId: String? get() = conversationSessionId

    /** Whether the agent is currently executing a task (for CapabilityCard.runtime.isBusy). */
    val isExecuting: Boolean get() = _state.value !is AgentState.Idle

    /** 注册命令完成监听器 — 命令执行完毕后回调 (无参数, UI 只关心"变了"这个事实)。 */
    fun addCommandListener(listener: () -> Unit) { runtime.addCommandListener(listener) }

    /** 移除命令完成监听器。 */
    fun removeCommandListener(listener: () -> Unit) { runtime.removeCommandListener(listener) }

    /** Check integrity of the current session. Returns false if terminal latch is active. */
    fun checkIntegrity(sessionId: String? = null): Boolean = runtime.conversation.checkIntegrity(sessionId)

    /** Attempt to repair integrity. Returns true if repair succeeded and latch is released. */
    fun repairIntegrity(sessionId: String? = null): Boolean = runtime.conversation.repairIntegrity(sessionId)

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
        runtime.restoreConversation(externalSessionId, messages, lastWasInterrupted, previousEngineSessionId)
    }

    /** Replace the LLM provider at runtime (e.g. after user configures API key). */
    fun updateLlmProvider(provider: LlmProvider) {
        llmProvider = provider
    }

    /** Expose the current LLM provider for delegation to sub-executors. */
    internal fun getLlmProvider(): LlmProvider = llmProvider

    /** Expose sub-managers for delegation to sub-executors (swarm/plan). */
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
    ): ExecutionResult = runtime.executeCommand(commandLine, scope, workDir)

    /**
     * 列出当前引擎管线中可执行的全部 CLI 命令（名称 + 描述），供 "!" 命令补全下拉使用。
     * 读本引擎 registry（PipelineManager 持有）— 不再依赖 SelfExecutor.commandRegistry
     * 全局指针（多 Agent 场景最后构建者赢的串扰问题）。
     */
    fun listCommands(): List<CommandInfo> = runtime.listCommands()

    /** Reset loop detection state — call before each new task. */
    fun resetLoopDetection() = promptEngine.resetLoopDetection()

    companion object {
        /** Single source of truth: generated from gradle.properties mengpaw.version. */
        val CORE_VERSION: String get() = MengPawVersion.FRAMEWORK

        /** 零待命并行 worker 会话 scope — 不注入主循环省察引导。 */
        // v0.34.4 Mission 并入 Swarm — 保留 "mission" 仅为历史会话数据兼容
        // （旧版本 mission scope 的 worker 会话仍按零待命处理）
        internal val WORKER_SCOPES = setOf("mission", "swarm")
    }

    /**
     * Start a new conversation — resets the persistent session.
     * Call when user taps "新会话" in UI.
     * Old session remains in SessionManager for history browsing.
     */
    fun newConversation() = runtime.newConversation()

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

    private fun rebuildSystemPrompt() {
        runtime.resetCompactState()
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
    ): String = runtime.run(task, maxSteps, onStep, onDelta, attachments)

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
    ): String = runtime.runReActLoop(task, maxSteps, contextPrefix, onStep, onDelta, attachments)

    // ── 火种模式 (Swarm Mode) — 规划器拆解 → 并行 Worker → Verifier → 合成器 ──

    /**
     * 火种模式 (Swarm Mode): "星星之火，可以燎原" — 一个任务点燃众多 Worker 的燎原之势。
     * Swarm 是进化版的 Mission（v0.34.4 起 Mission 并入 Swarm）。
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
    internal suspend fun buildConversation(sessionId: String): List<Map<String, String>> =
        runtime.conversation.buildConversation(sessionId)
}
