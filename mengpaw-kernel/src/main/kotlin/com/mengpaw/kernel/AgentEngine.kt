// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel

import com.mengpaw.kernel.agent.AgentDocManager
import com.mengpaw.kernel.agent.AgentExecutor
import com.mengpaw.kernel.agent.AgentMiddleware
import com.mengpaw.kernel.AgentState
import com.mengpaw.kernel.agent.MemoryRecord
import com.mengpaw.kernel.agent.PostCallMiddleware
import com.mengpaw.kernel.agent.ScrollContextManager
import com.mengpaw.kernel.cli.*
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.error.ErrorType
import com.mengpaw.kernel.llm.*
import com.mengpaw.kernel.plugin.PluginExecutor
import com.mengpaw.kernel.plugin.PluginManager
import com.mengpaw.kernel.plugin.PluginMarketplaceClient
import com.mengpaw.kernel.security.IntegrityProvider
import com.mengpaw.kernel.security.NoOpIntegrityProvider
import com.mengpaw.kernel.security.Sanitizer
import com.mengpaw.kernel.session.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout

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
    var integrityProvider: IntegrityProvider = NoOpIntegrityProvider
        set(value) {
            field = value
            pipelineManager.integrityProvider = value
        }

    init {
        // Wire real PluginManager into AgentDocManager so CLI.md generation sees installed plugins
        agentDocManager.pluginManager = pluginManager
        // Wire cache invalidation: when AgentDocs modifies workspace, invalidate PromptEngine cache
        com.mengpaw.kernel.agent.AgentDocs.onDocChanged = { name, filePath ->
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

    // ── Persistent conversation session (Claude Code pattern) ──────────
    // Instead of creating a new Session per run(), reuse the same session
    // so the LLM sees full conversation history across multiple user messages.
    @Volatile private var conversationSessionId: String? = null

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

    /** Update agent state flow (used by sub-executors for progress reporting). */
    internal fun updateAgentState(state: AgentState) { _state.value = state }

    /** Update agent output flow (used by sub-executors for progress reporting). */
    internal fun updateAgentOutput(output: String) { _output.value = output }

    val cacheHitTokens: Long get() = llmRequestBuilder.cumulativeCacheHitTokens
    val cacheMissTokens: Long get() = llmRequestBuilder.cumulativeCacheMissTokens
    val cacheHitRatio: Double get() {
        val total = cacheHitTokens + cacheMissTokens
        return if (total > 0) cacheHitTokens.toDouble() / total else 0.0
    }
    val estimatedSavingsUsd: Double get() = cacheHitTokens * 0.0001372
    val cacheStrategyLabel: String get() = CacheStrategy.labelFor(llmRequestBuilder.cacheStrategy)

    fun configureCacheStrategy(endpoint: String) {
        llmRequestBuilder.cacheStrategy = CacheStrategy.forProvider(endpoint)
    }

    /** Delegate to PipelineManager. */
    fun getActiveNamespaces(): List<String> = pipelineManager.getActiveNamespaces()

    /** Access the plugin manager for settings display. */
    fun getPluginManager(): PluginManager = pluginManager

    /** Invalidate cached pipeline when plugins change. Call after plugin install/uninstall. */
    fun invalidatePipeline() { pipelineManager.invalidatePipeline() }

    /** Reset loop detection state — call before each new task. */
    fun resetLoopDetection() = promptEngine.resetLoopDetection()

    companion object {
        /** Single source of truth: generated from gradle.properties mengpaw.version. */
        val CORE_VERSION: String get() = MengPawVersion.FRAMEWORK
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
        if (ratio < PipelineManager.COMPACT_RATIO) { return snipStaleToolResults(sessionId, currentStep) > 0 }
        val estimatedFoldTokens = (promptTokens * 0.3).toInt()
        if (ratio < PipelineManager.COMPACT_FORCE_RATIO && estimatedFoldTokens < PipelineManager.MIN_FOLD_TOKENS) return false
        sessionManager.compressIfNeeded(llmProvider)
        consecutiveCompacts++
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

    fun setAgentIdentity(name: String, framework: String?, model: String) {
        agentName = name
        this.framework = framework
        this.modelName = model
        toolResultManager = ToolResultManager(name)
        sessionManager.agentName = name
        rebuildSystemPrompt()
    }

    fun setAgentLanguage(lang: PromptEngine.AgentLanguage) {
        if (lang != agentLanguage) { agentLanguage = lang; rebuildSystemPrompt() }
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

    suspend fun run(task: String, maxSteps: Int = 50, onStep: ((TraceStep) -> Unit)? = null): String {
        val guardedTask = if (com.mengpaw.kernel.security.PromptFirewall.checkUserPrompt(task) != null)
            com.mengpaw.kernel.security.PromptFirewall.wrapWithDefense(task) else task
        return runReActLoop(task = guardedTask, maxSteps = maxSteps, onStep = onStep)
    }

    // ── Goal Mode (delegated to GoalModeExecutor) ────────────────────

    /**
     * Goal-mode execution with RubricGate auto-completion detection.
     * Delegates to [GoalModeExecutor].
     */
    suspend fun runWithGoal(
        task: String, maxTurns: Int = 20, maxTokensBudget: Int = 300_000,
        onStep: ((TraceStep) -> Unit)? = null
    ): String = goalModeExecutor.runWithGoal(task, maxTurns, maxTokensBudget, onStep)

    /**
     * Internal ReAct loop with optional context prefix.
     * Shared by run() and runWithGoal() to avoid session-creation overhead.
     */
    internal suspend fun runReActLoop(
        task: String,
        maxSteps: Int,
        contextPrefix: String = "",
        onStep: ((TraceStep) -> Unit)? = null
    ): String {
        ErrorCollector.init()

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
        val context = ExecutionContext(sessionId = session.id, agentName = agentName)

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
        sessionManager.addMessage(session.id, Message("user", task))
        if (contextPrefix.isNotBlank()) {
            sessionManager.addMessage(session.id, Message("system", contextPrefix))
        }

        try {
            val job = kotlinx.coroutines.currentCoroutineContext()[Job]
            runningJob = job
            var consecutiveContinueCount = 0 // Tracks needsContinue without action
            var consecutiveFailures = 0       // Tracks consecutive tool failures
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
                val llmResponse = llmProvider.completeWithMessages(conversation)
                // 利用 LLM 等待窗口刚刚结束的间隙刷盘中期记忆 (I/O 成本隐藏)
                com.mengpaw.kernel.agent.AgentDocs.flushMidTermMemoryQueue()
                val sanitized = Sanitizer.sanitize(llmResponse)

                val totalChars = llmRequestBuilder.currentSystemPrompt.length +
                    sessionManager.getStructuredHistory(session.id).sumOf { (it["content"]?.length ?: 0) }
                val estimatedTokens = (totalChars * llmRequestBuilder.calibratedTokPerChar).toInt()
                llmRequestBuilder.lastPromptTokens = estimatedTokens
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

                if (parsed.action != null) {
                    val commandLine = "${parsed.action.name} ${parsed.action.parameters.values.joinToString(" ")}"

                    if (promptEngine.detectLoop(commandLine)) {
                        ErrorCollector.report(ErrorType.LOOP_DETECTED, "AgentEngine", commandLine,
                            sessionId = session.id, agentName = agentName)
                        val errorMsg = localizedError("loop_detected", commandLine, agentLanguage)
                        sessionManager.addMessage(session.id, Message("assistant", errorMsg))
                        _state.value = AgentState.Error(errorMsg)
                        onStep?.invoke(TraceStep(step + 1, parsed.thought, commandLine, errorMsg))
                        return errorMsg
                    }

                    val result = try {
                        kotlinx.coroutines.withTimeout(60_000L) { pipelineManager.buildPipeline().execute(commandLine, context) }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        ExecutionResult.fail("命令超时 (60s): $commandLine。请检查网络连接或尝试其他方式。", errorCode = ErrorCodes.ERR_INTERNAL)
                    }
                    if (!result.success) {
                        consecutiveFailures++
                        ErrorCollector.report(ErrorType.TOOL_CALL_FAILED, "AgentEngine",
                            "$commandLine → ${result.error}", sessionId = session.id, agentName = agentName,
                            metadata = mapOf("errorCode" to (result.errorCode ?: ""), "command" to commandLine))
                    } else {
                        consecutiveFailures = 0
                    }
                    // Detect failure loop: 5+ consecutive failures → agent is stuck
                    if (promptEngine.trackResult(result.success)) {
                        val errorMsg = localizedError("consecutive_failures", "5", agentLanguage)
                        sessionManager.addMessage(session.id, Message("assistant", errorMsg))
                        _state.value = AgentState.Error(errorMsg)
                        onStep?.invoke(TraceStep(step + 1, parsed.thought, commandLine, errorMsg))
                        return errorMsg
                    }
                    var rawObservation = if (result.success) result.output else "Error: ${result.error}"
                    // ── QwenPaw-style tool result pruning ──
                    rawObservation = toolResultManager.pruneToolResult(commandLine, rawObservation, step + 1)
                    onStep?.invoke(TraceStep(step + 1, parsed.thought, commandLine, rawObservation))

                    val observationEntry = "Command: $commandLine\nResult: $rawObservation"
                    sessionManager.addMessage(session.id, Message("assistant", observationEntry))
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
        maxRetriesPerSubtask: Int = 2, onStep: ((TraceStep) -> Unit)? = null
    ): String = missionModeExecutor.runWithMission(task, maxSubtasks, maxStepsPerSubtask, maxRetriesPerSubtask, onStep)

    // ── Fleet Mode (reserved — delegates to Mission for now) ──────────

    /**
     * Fleet-mode: multi-agent fleet coordination for distributed execution.
     * Currently delegates to [runWithMission]; will gain ACP-based cross-device
     * task distribution, parallel worker dispatch, and fleet-level synthesis.
     */
    suspend fun runWithFleet(
        task: String, maxSubtasks: Int = 5, maxStepsPerSubtask: Int = 12,
        maxRetriesPerSubtask: Int = 2, onStep: ((TraceStep) -> Unit)? = null
    ): String = runWithMission(task, maxSubtasks, maxStepsPerSubtask, maxRetriesPerSubtask, onStep)

    // ── Plan Mode (delegated to PlanModeExecutor) ─────────────────────

    /**
     * Plan-mode: structured plan generation and step-by-step execution.
     * Delegates to [PlanModeExecutor].
     */
    suspend fun runWithPlan(task: String, maxStepsPerPlanStep: Int = 5, onStep: ((TraceStep) -> Unit)? = null): String =
        planModeExecutor.runWithPlan(task, maxStepsPerPlanStep, onStep)

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
        // ★ Integrity gate: terminal latch (matching OpenClaw assertSqliteIntegrity)
        // If session data is corrupted, block LLM calls with a warning instead of
        // letting the model act on potentially garbage history.
        if (integrityFailed) {
            KernelLog.w("AgentEngine", "Integrity latch active — blocking LLM call")
            return listOf(mapOf("role" to "system", "content" to "Session data integrity issue detected. " +
                "Please use agent.repair or start a new conversation to continue."))
        }
        sessionManager.compressIfNeeded(llmProvider, specificSessionId = sessionId)
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
            return llmRequestBuilder.buildMessages(
                listOf(mapOf("role" to "system", "content" to llmRequestBuilder.currentSystemPrompt)) +
                    mutableMessages,
                injectCacheAnnotations = true
            )
        }

        return llmRequestBuilder.buildMessages(nonSystemHistory, injectCacheAnnotations = true)
    }

    private fun recordTaskMemory(task: String, result: String) {
        try {
            val entry = MemoryRecord(
                id = "mem-${System.currentTimeMillis().toString().takeLast(6)}",
                date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()),
                title = task.take(60),
                keywords = task.split(" ").filter { it.length > 1 }.take(5),
                content = result.take(500))
            agentDocManager.updateMemory(entry)
        } catch (e: Exception) {
            KernelLog.w("AgentEngine", "Failed to record task memory: ${e.message}")
        }
    }
}
