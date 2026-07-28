// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel

import com.mengpaw.kernel.agent.AgentDocManager
import com.mengpaw.kernel.agent.AgentExecutor
import com.mengpaw.kernel.agent.AgentMiddleware
import com.mengpaw.kernel.agent.MemoryRecord
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
import com.mengpaw.kernel.security.Sanitizer
import com.mengpaw.kernel.session.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout

sealed class AgentState {
    data object Idle : AgentState()
    data class Running(val task: String, val step: Int, val maxSteps: Int) : AgentState()
    data class Finished(val result: String) : AgentState()
    data class Error(val message: String) : AgentState()
}

data class PlanStep(
    val index: Int,
    val description: String,
    val action: String,
    val expectedOutcome: String,
    var status: PlanStepStatus = PlanStepStatus.PENDING
)

enum class PlanStepStatus { PENDING, RUNNING, COMPLETED, FAILED }

data class TaskPlan(
    val task: String,
    val steps: List<PlanStep>,
    val createdAt: Long = System.currentTimeMillis()
) {
    val totalSteps: Int get() = steps.size
    val completedSteps: Int get() = steps.count { it.status == PlanStepStatus.COMPLETED }
    val isComplete: Boolean get() = steps.all { it.status == PlanStepStatus.COMPLETED }
}

class AgentEngine(
    llmProvider: LlmProvider,
    private val pluginManager: PluginManager = PluginManager(),
    private val sessionManager: SessionManager = SessionManager(),
    private val promptEngine: PromptEngine = PromptEngine(),
    private val agentDocManager: AgentDocManager = AgentDocManager(),
    private val middleware: AgentMiddleware = AgentMiddleware.NoOp,
    private val postCallMiddleware: PostCallMiddleware = PostCallMiddleware.NoOp,
    val scrollContext: ScrollContextManager? = null,
    /** Additional namespaces to register alongside built-ins (e.g. "sys" → SysExecutor.commands). */
    private val additionalNamespaces: Map<String, Map<String, suspend (List<String>, ExecutionContext) -> com.mengpaw.kernel.cli.ExecutionResult>> = emptyMap()
) {
    init {
        // Wire real PluginManager into AgentDocManager so CLI.md generation sees installed plugins
        agentDocManager.pluginManager = pluginManager
        // Wire cache invalidation: when AgentDocs modifies workspace, invalidate PromptEngine cache
        com.mengpaw.kernel.agent.AgentDocs.onDocChanged = { name, filePath ->
            promptEngine.invalidateDocCache(name, filePath)
        }
        // 构建命令搜索索引 (BM25 + 双语同义词表) — 一次性初始化
        com.mengpaw.kernel.cli.BuiltinCommandIndex.buildAll()
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

    /** Replace the LLM provider at runtime (e.g. after user configures API key). */
    fun updateLlmProvider(provider: LlmProvider) {
        llmProvider = provider
    }

    val cacheHitTokens: Long get() = llmRequestBuilder.cumulativeCacheHitTokens
    val cacheMissTokens: Long get() = llmRequestBuilder.cumulativeCacheMissTokens
    val cacheHitRatio: Double get() {
        val total = cacheHitTokens + cacheMissTokens
        return if (total > 0) cacheHitTokens.toDouble() / total else 0.0
    }
    val estimatedSavingsUsd: Double get() = cacheHitTokens * 0.0001372
    val cacheStrategyLabel: String get() = CacheStrategy.labelFor(llmRequestBuilder.cacheStrategy)

    /** Context usage stats for UI display. */
    data class ContextUsage(
        val usedTokens: Int,
        val maxTokens: Int,
        val ratio: Double
    )

    /** Get current LLM context window usage. */
    fun getContextUsage(): ContextUsage {
        val sessionId = conversationSessionId ?: return ContextUsage(0, 0, 0.0)
        val session = sessionManager.getSession(sessionId) ?: return ContextUsage(0, 0, 0.0)
        val totalChars = llmRequestBuilder.currentSystemPrompt.length +
            sessionManager.getStructuredHistory(session.id).sumOf { (it["content"]?.length ?: 0) }
        val estimatedTokens = (totalChars * llmRequestBuilder.calibratedTokPerChar).toInt()
        return ContextUsage(
            usedTokens = estimatedTokens,
            maxTokens = DEFAULT_CONTEXT_WINDOW,
            ratio = estimateContextRatio(estimatedTokens)
        )
    }

    fun configureCacheStrategy(endpoint: String) {
        llmRequestBuilder.cacheStrategy = CacheStrategy.forProvider(endpoint)
    }

    /** List all active CLI namespaces (built-in + plugins) for settings display. */
    fun getActiveNamespaces(): List<String> {
        val namespaces = mutableSetOf("self", "agent", "plugin")
        additionalNamespaces.keys.forEach { namespaces.add(it) }
        pluginManager.getActivePlugins().forEach { plugin ->
            val ns = plugin.metadata.id.removeSuffix("-plugin").removeSuffix("-ext")
            namespaces.add(ns)
        }
        return namespaces.sorted()
    }

    /** Access the plugin manager for settings display. */
    fun getPluginManager(): PluginManager = pluginManager

    /** Reset loop detection state — call before each new task. */
    fun resetLoopDetection() = promptEngine.resetLoopDetection()

    companion object {
        /** Single source of truth: generated from gradle.properties mengpaw.version. */
        val CORE_VERSION: String get() = MengPawVersion.FRAMEWORK
        private const val SOFT_COMPACT_RATIO = 0.50
        const val TOOL_SNIP_RATIO = 0.60
        const val COMPACT_RATIO = 0.80
        const val COMPACT_FORCE_RATIO = 0.90
        const val MIN_FOLD_TOKENS = 400
        const val DEFAULT_CONTEXT_WINDOW = 131_072

        // ── QwenPaw-style tool result pruning thresholds ──
        /** Recent steps (≤3): generous threshold before offloading to disk. */
        private const val TOOL_SNIPPET_RECENT_BYTES = 30_000
        /** Older steps: aggressive truncation, keep only snippet + file path. */
        private const val TOOL_SNIPPET_OLD_BYTES = 2_000
        /** Auto-clean tool result files older than this (days). */
        private const val TOOL_RESULT_RETENTION_DAYS = 5L

        /** Regex to detect "Final Answer:" marker in streaming LLM output. */
        private val FINAL_ANSWER_REGEX = Regex("""(?i)Final Answer[:：]""")
    }

    /**
     * QwenPaw-style tool result offloading.
     * Long tool outputs (> threshold) are saved to disk; only a snippet stays in context.
     * Two-tier: recent steps get higher threshold, older steps get aggressive pruning.
     */
    private fun pruneToolResult(commandLine: String, rawOutput: String, step: Int): String {
        val threshold = if (step <= 3) TOOL_SNIPPET_RECENT_BYTES else TOOL_SNIPPET_OLD_BYTES
        if (rawOutput.length <= threshold) return rawOutput

        val fileUuid = java.util.UUID.randomUUID().toString().take(8)
        val dir = java.io.File(com.mengpaw.kernel.DataPaths.toolResultsDir(agentName)).also { it.mkdirs() }
        val file = java.io.File(dir, "$fileUuid.txt")
        return try {
            file.writeText(rawOutput)
            val snippet = rawOutput.take(threshold / 2)
            "$snippet\n... [完整输出 (${rawOutput.length} 字节): tool_results/$fileUuid.txt — 用 agent.read 查阅]"
        } catch (_: Exception) {
            rawOutput.take(threshold)
        }
    }

    /** Clean up old tool result cache files. Called periodically. */
    private fun cleanupOldToolResults() {
        try {
            val dir = java.io.File(com.mengpaw.kernel.DataPaths.toolResultsDir(agentName))
            if (!dir.exists()) return
            val cutoff = System.currentTimeMillis() - TOOL_RESULT_RETENTION_DAYS * 24 * 3600 * 1000L
            dir.listFiles()?.forEach { f ->
                if (f.lastModified() < cutoff) f.delete()
            }
        } catch (_: Exception) {}
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

    private fun estimateContextRatio(promptTokens: Int): Double = promptTokens / DEFAULT_CONTEXT_WINDOW.toDouble()

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
            sessionManager.addMessage(sessionId, com.mengpaw.kernel.session.Message(
                "system", "[snip — $count old tool results compressed to free context]"))
        }
        return count
    }

    private suspend fun maybeFoldContext(sessionId: String, promptTokens: Int, currentStep: Int = 0): Boolean {
        if (compactStuck) return false
        val ratio = estimateContextRatio(promptTokens)
        if (ratio < SOFT_COMPACT_RATIO) { consecutiveCompacts = 0; compactStuck = false; return false }
        if (ratio < TOOL_SNIP_RATIO) return false
        if (ratio < COMPACT_RATIO) { return snipStaleToolResults(sessionId, currentStep) > 0 }
        val estimatedFoldTokens = (promptTokens * 0.3).toInt()
        if (ratio < COMPACT_FORCE_RATIO && estimatedFoldTokens < MIN_FOLD_TOKENS) return false
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
        agentName = name; this.framework = framework; this.modelName = model
        sessionManager.agentName = name
        rebuildSystemPrompt()
    }

    fun setAgentLanguage(lang: PromptEngine.AgentLanguage) {
        if (lang != agentLanguage) { agentLanguage = lang; rebuildSystemPrompt() }
    }

    private fun rebuildSystemPrompt() {
        consecutiveCompacts = 0; compactStuck = false
        promptEngine.resetLoopDetection()
        val base = promptEngine.buildSystemPrompt(lang = agentLanguage, agentName = agentName, framework = framework, modelName = modelName)
        val processed = middleware.onSystemPrompt(base, agentName)
        llmRequestBuilder.updateSystemPrompt(processed)
    }

    private val marketplaceClient = PluginMarketplaceClient()
    private val pluginExecutor = PluginExecutor(pluginManager, marketplaceClient)
    private val agentExecutor = AgentExecutor(agentDocManager)

    // FIX: Cache pipeline to avoid rebuilding CommandRegistry on every command execution.
    // Rebuilt only when plugins are installed/uninstalled (via invalidatePipeline).
    @Volatile private var cachedPipeline: Pipeline? = null

    /** Invalidate cached pipeline when plugins change. Call after plugin install/uninstall. */
    fun invalidatePipeline() { cachedPipeline = null }

    /** Integrity provider for path-level file protection; set after construction for Android. */
    var integrityProvider: com.mengpaw.kernel.security.IntegrityProvider = com.mengpaw.kernel.security.NoOpIntegrityProvider

    private fun buildPipeline(): Pipeline {
        cachedPipeline?.let { return it }
        val registry = CommandRegistry()

        // Expose registry for self.tools command
        SelfExecutor.commandRegistry = registry

        // Built-in: self namespace (always available)
        registry.registerNamespace("self", SelfExecutor.commands)

        // Built-in: plugin namespace (always available)
        registry.registerNamespace("plugin", pluginExecutor.commands)

        // Built-in: agent namespace (always available)
        registry.registerNamespace("agent", agentExecutor.commands)

        // Additional namespaces (e.g. "sys" from Android adapter)
        additionalNamespaces.forEach { (ns, commands) ->
            registry.registerNamespace(ns, commands)
        }

        // Dynamic: register all active plugin commands
        pluginManager.getActivePlugins().forEach { plugin ->
            val ns = plugin.metadata.id.removeSuffix("-plugin").removeSuffix("-ext")
            plugin.commands.forEach { (name, handler) ->
                registry.register("$ns.$name", handler)
            }
        }

        pluginManager.bindRegistry(registry)
        val pipeline = Pipeline(registry = registry)
        pipeline.integrityProvider = integrityProvider
        cachedPipeline = pipeline
        return pipeline
    }

    data class TraceStep(val step: Int, val thought: String, val action: String?, val observation: String?)

    suspend fun run(task: String, maxSteps: Int = 50, onStep: ((TraceStep) -> Unit)? = null): String {
        val guardedTask = if (com.mengpaw.kernel.security.PromptFirewall.checkUserPrompt(task) != null)
            com.mengpaw.kernel.security.PromptFirewall.wrapWithDefense(task) else task
        return runReActLoop(task = guardedTask, maxSteps = maxSteps, onStep = onStep)
    }

    // ── Goal Mode (ported from QwenPaw GoalMode) ─────────────────────

    /**
     * Goal-mode execution with RubricGate auto-completion detection.
     *
     * Each turn: inject goal prompt → run ReAct loop → evaluate completion via LLM.
     * Stops when RubricGate returns SATISFIED or max iterations exhausted.
     */
    suspend fun runWithGoal(
        task: String, maxTurns: Int = 20, maxTokensBudget: Int = 300_000,
        onStep: ((TraceStep) -> Unit)? = null
    ): String {
        val guardedTask = if (com.mengpaw.kernel.security.PromptFirewall.checkUserPrompt(task) != null)
            com.mengpaw.kernel.security.PromptFirewall.wrapWithDefense(task) else task
        val session = com.mengpaw.kernel.agent.GoalSession(
            goal = guardedTask, maxIterations = maxTurns, maxTokens = maxTokensBudget
        )
        val evaluator = com.mengpaw.kernel.agent.RubricEvaluator()
        val turnResults = mutableListOf<String>()

        for (turn in 0 until maxTurns) {
            if (!session.active) break
            session.iteration = turn + 1

            // Build goal-aware prompt — RubricGate feedback is the only signal needed between turns.
            // Prior turn results are NOT replayed; replaying biases the agent toward repeating old work.
            val goalPrompt = if (turn == 0) {
                "## 目标\n${session.goal}\n\n使用 Thought → Action → Final Answer 格式。自然对话，不要主动汇报进度或回溯历史——除非用户询问。"
            } else {
                "## 目标 (第 ${turn + 1}/$maxTurns 轮)\n${session.goal}\n\n反馈: ${session.lastFeedback.ifEmpty { "无" }}"
            }

            // Run ReAct loop — no prior context injection; RubricGate feedback is sufficient
            val result = runReActLoop(
                task = "$goalPrompt\n\n$guardedTask",
                maxSteps = 50,
                onStep = onStep
            )
            turnResults.add(result)

            // Budget gate: estimate tokens from result length
            session.tokensUsed += result.length / 4  // rough char→token estimate
            if (session.tokensUsed >= maxTokensBudget) {
                session.active = false
                session.lastVerdict = "Token budget exceeded"
                break
            }

            // RubricGate: LLM-based completion evaluation on every turn
            val evalPrompt = evaluator.buildPrompt(session.goal, result)
            try {
                val evalResult = llmProvider.complete(evalPrompt)
                val satisfied = evalResult.trim().uppercase().startsWith("YES")
                if (satisfied) {
                    session.lastVerdict = "SATISFIED"
                    session.active = false
                } else {
                    session.lastVerdict = "NEEDS_REVISION"
                    session.lastFeedback = evalResult.take(200)
                }
            } catch (_: Exception) {
                // LLM eval failed — fall back to heuristic
                if (result.contains("Final Answer:", ignoreCase = true)) {
                    session.lastVerdict = "SATISFIED (heuristic)"
                    session.active = false
                }
            }
        }

        return if (!session.active && session.lastVerdict.startsWith("SATISFIED")) {
            "目标已完成: ${session.goal}\n\n" + turnResults.lastOrNull().orEmpty()
        } else {
            "目标未完成 (${session.iteration}/${maxTurns} 轮): ${session.goal}\n\n最后结果:\n" +
                turnResults.lastOrNull().orEmpty()
        }
    }

    /**
     * Internal ReAct loop with optional context prefix.
     * Shared by run() and runWithGoal() to avoid session-creation overhead.
     */
    private suspend fun runReActLoop(
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
                // ── 流式 LLM 调用: 实时推送 Final Answer tokens 到 _output ──
                _output.value = ""
                var isFinalAnswerDetected = false
                val streamingBuffer = StringBuilder()

                llmProvider.completeStreamingWithMessages(conversation).collect { token ->
                    streamingBuffer.append(token)
                    if (isFinalAnswerDetected) {
                        _output.value = _output.value + token
                    } else {
                        val text = streamingBuffer.toString()
                        FINAL_ANSWER_REGEX.find(text)?.let { match ->
                            isFinalAnswerDetected = true
                            val after = text.substring(match.range.last + 1)
                            if (after.isNotBlank()) _output.value = after
                        }
                    }
                }
                val llmResponse = streamingBuffer.toString()
                // 利用 LLM 等待窗口刚刚结束的间隙刷盘 (I/O 成本隐藏)
                com.mengpaw.kernel.agent.AgentDocs.flushMidTermMemoryQueue()
                val sanitized = Sanitizer.sanitize(llmResponse)

                val totalChars = llmRequestBuilder.currentSystemPrompt.length +
                    sessionManager.getStructuredHistory(session.id).sumOf { (it["content"]?.length ?: 0) }
                val estimatedTokens = (totalChars * llmRequestBuilder.calibratedTokPerChar).toInt()
                llmRequestBuilder.lastPromptTokens = estimatedTokens
                llmRequestBuilder.calibrateFromUsage(estimatedTokens, totalChars)

                val postResult = postCallMiddleware.onPostCall(sanitized, step + 1, totalChars, estimatedTokens)
                sessionManager.addMessage(session.id, Message("assistant", postResult.text))
                // _output already contains the streamed final answer text — don't overwrite with postResult.text

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
                    _state.value = AgentState.Finished(answer)
                    com.mengpaw.kernel.agent.AgentDocs.flushMidTermMemoryQueue()
                    recordTaskMemory(task, answer)
                    // Periodic cleanup of old tool result cache files
                    if (java.lang.Math.random() < 0.1) cleanupOldToolResults()
                    return answer
                }

                // Handle needsContinue: model output Thought but no Action
                // Inject a continue prompt instead of stopping
                if (parsed.needsContinue) {
                    consecutiveContinueCount++
                    if (consecutiveContinueCount >= 2) {
                        // Model keeps thinking without acting — force finalize
                        val msg = localizedError("max_steps", maxSteps.toString())
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
                        val errorMsg = localizedError("loop_detected", commandLine)
                        sessionManager.addMessage(session.id, Message("assistant", errorMsg))
                        _state.value = AgentState.Error(errorMsg)
                        onStep?.invoke(TraceStep(step + 1, parsed.thought, commandLine, errorMsg))
                        return errorMsg
                    }

                    val result = try {
                        kotlinx.coroutines.withTimeout(60_000L) { buildPipeline().execute(commandLine, context) }
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
                        val errorMsg = localizedError("consecutive_failures", "5")
                        sessionManager.addMessage(session.id, Message("assistant", errorMsg))
                        _state.value = AgentState.Error(errorMsg)
                        onStep?.invoke(TraceStep(step + 1, parsed.thought, commandLine, errorMsg))
                        return errorMsg
                    }
                    var rawObservation = if (result.success) result.output else "Error: ${result.error}"
                    // ── QwenPaw-style tool result pruning ──
                    rawObservation = pruneToolResult(commandLine, rawObservation, step + 1)
                    onStep?.invoke(TraceStep(step + 1, parsed.thought, commandLine, rawObservation))

                    val observationEntry = "Command: $commandLine\nResult: $rawObservation"
                    sessionManager.addMessage(session.id, Message("assistant", observationEntry))
                } else {
                    onStep?.invoke(TraceStep(step + 1, parsed.thought, null, null))
                }
                step++
            }

            val msg = localizedError("max_steps", maxSteps.toString())
            sessionManager.addMessage(session.id, Message("assistant", msg))
            _state.value = AgentState.Finished(msg)
            return msg
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Re-throw to respect coroutine cancellation contract
            throw e
        } catch (e: Exception) {
            ErrorCollector.report(ErrorType.AGENT_CRASH, "AgentEngine.runReActLoop", e.message ?: "(no message)",
                throwable = e, sessionId = session.id, agentName = agentName)
            val errorMsg = localizedError("agent_error", e.message ?: e::class.simpleName ?: "unknown")
            sessionManager.addMessage(session.id, Message("assistant", errorMsg))
            _state.value = AgentState.Error(errorMsg)
            return errorMsg
        }
    }

    // ── Mission Mode (ported from QwenPaw MissionMode) ────────────────

    /**
     * Mission-mode: decompose → worker execution → verification.
     * Uses the LLM to decompose the task, then runs each subtask sequentially.
     */
    /**
     * Mission mode — Claude Code style decomposition with parallel workers,
     * verification, retry on failure, and LLM synthesis.
     */
    suspend fun runWithMission(
        task: String, maxSubtasks: Int = 5, maxStepsPerSubtask: Int = 12,
        maxRetriesPerSubtask: Int = 2, onStep: ((TraceStep) -> Unit)? = null
    ): String {
        val guardedTask = if (com.mengpaw.kernel.security.PromptFirewall.checkUserPrompt(task) != null)
            com.mengpaw.kernel.security.PromptFirewall.wrapWithDefense(task) else task

        // Step 1: Structured decomposition — LLM produces JSON subtask list
        val decomposePrompt = """
You are decomposing a complex task into independent subtasks for parallel execution.

Task: $guardedTask

Output a JSON array of subtasks. Each subtask has:
- "id": short kebab-case id
- "desc": what to do (one sentence, actionable)
- "criteria": how to verify success (one sentence, concrete)

Rules:
- Maximum $maxSubtasks subtasks
- Each subtask must be independently executable (no cross-dependencies)
- Order from most critical to least

Output ONLY the JSON array, no other text:
[{"id":"...","desc":"...","criteria":"..."}]
""".trimIndent()

        val decomposeResult = try {
            llmProvider.complete(decomposePrompt)
        } catch (e: Exception) {
            return run(task, maxStepsPerSubtask * maxSubtasks, onStep)
        }

        // Parse JSON subtasks
        val subtasks = try {
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val jsonStr = decomposeResult.trim().substringAfter("[").substringBeforeLast("]").let { "[$it]" }
            val array = json.parseToJsonElement(jsonStr)
            (array as? kotlinx.serialization.json.JsonArray)?.map { el ->
                val obj = el as? kotlinx.serialization.json.JsonObject ?: return@map null
                com.mengpaw.kernel.agent.MissionSubtask(
                    id = (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "task-?",
                    description = (obj["desc"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "?",
                    expectedOutcome = (obj["criteria"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                )
            }?.filterNotNull()?.take(maxSubtasks) ?: emptyList()
        } catch (e: Exception) {
            // Fallback: simple line parsing
            decomposeResult.lines()
                .filter { it.trimStart().startsWith("-") || it.trimStart().startsWith("*") }
                .take(maxSubtasks)
                .mapIndexed { i, line ->
                    val parts = line.removePrefix("-").removePrefix("*").trim().split("|", limit = 2)
                    com.mengpaw.kernel.agent.MissionSubtask(
                        id = "task-${i + 1}",
                        description = parts.getOrElse(0) { "Subtask ${i + 1}" }.trim(),
                        expectedOutcome = parts.getOrElse(1) { "" }.trim()
                    )
                }
        }

        if (subtasks.isEmpty()) {
            return run(task, maxStepsPerSubtask * maxSubtasks, onStep)
        }

        _state.value = AgentState.Running("Mission: ${subtasks.size} subtasks", 0, subtasks.size)

        // Step 2: Sequential execution with retry+verify per subtask
        val results = mutableListOf<String>()
        for ((i, subtask) in subtasks.withIndex()) {
            _state.value = AgentState.Running("Mission: ${i + 1}/${subtasks.size}", i + 1, subtasks.size)
            val result = executeSubtask(subtask, maxStepsPerSubtask, maxRetriesPerSubtask, onStep)
            results.add(result)
        }

        // Step 3: LLM synthesis of all results
        val verified = subtasks.count { it.status == com.mengpaw.kernel.agent.SubtaskStatus.VERIFIED }
        val failed = subtasks.count { it.status == com.mengpaw.kernel.agent.SubtaskStatus.FAILED }
        val parts = subtasks.joinToString("\n") { st ->
            val icon = when (st.status) {
                com.mengpaw.kernel.agent.SubtaskStatus.VERIFIED -> "✅"
                com.mengpaw.kernel.agent.SubtaskStatus.DONE -> "👍"
                com.mengpaw.kernel.agent.SubtaskStatus.FAILED -> "❌"
                else -> "⬜"
            }
            "$icon ${st.description}: ${st.output.take(300)}"
        }
        val synthesisPrompt = """
Synthesize the following Mission results into a clear, structured final report.

Original task: $guardedTask
Subtask results ($verified verified, $failed failed of ${subtasks.size}):

$parts

Provide a concise summary with:
1. What was accomplished
2. Key findings or outputs
3. Any remaining issues (if $failed > 0)
""".trimIndent()

        val synthesis = try {
            llmProvider.complete(synthesisPrompt)
        } catch (_: Exception) {
            parts
        }

        return buildString {
            appendLine("## Mission: $task")
            appendLine("子任务: ${subtasks.size} | ✅ $verified | 👍 ${subtasks.filter { it.status == com.mengpaw.kernel.agent.SubtaskStatus.DONE }.size} | ❌ $failed")
            appendLine()
            appendLine(synthesis)
        }
    }

    /** Execute a single subtask with verification and retry. */
    private suspend fun executeSubtask(
        subtask: com.mengpaw.kernel.agent.MissionSubtask,
        maxSteps: Int, maxRetries: Int,
        onStep: ((TraceStep) -> Unit)? = null
    ): String {
        var retries = 0
        var lastVerifierFeedback = ""

        while (retries <= maxRetries) {
            subtask.status = com.mengpaw.kernel.agent.SubtaskStatus.RUNNING

            // Build task prompt — include verifier feedback on retry
            val taskPrompt = if (retries > 0 && lastVerifierFeedback.isNotBlank()) {
                buildString {
                    append(subtask.description)
                    append("\n\n## 质量审查反馈（第 $retries 次）\n")
                    append("上一轮未通过验证，请根据以下审查意见改进：\n\n")
                    append(lastVerifierFeedback)
                    append("\n\n请修正上述问题后重新执行。原任务：${subtask.description}")
                }
            } else {
                subtask.description
            }

            val workerResult = try {
                run(taskPrompt, maxSteps = maxSteps, onStep = onStep)
            } catch (e: Exception) {
                "Error: ${e.message}"
            }

            subtask.output = workerResult
            val isHardError = workerResult.startsWith("Error:") ||
                workerResult.startsWith("已达到最大步数") ||
                workerResult.startsWith("Max steps")

            if (isHardError) {
                lastVerifierFeedback = "Worker execution error: ${workerResult.take(300)}"
                retries++
                continue
            }

            // ── Strict Verifier (Worker-Verifier pattern) ──
            val verifierPrompt = """
You are a strict quality verifier. Review the worker agent's output against the success criteria.

**Success criteria**: ${subtask.expectedOutcome.ifBlank { "Complete the task: ${subtask.description}" }}

**Worker output**:
${workerResult.take(2000)}

**Analysis rules**:
- Check if the output actually fulfills the criteria (not just mentions it)
- Check for factual errors, incomplete data, or vague hand-waving
- A "Final Answer" that says "I cannot do this" without trying alternatives = FAIL
- Partial completion with clear next steps = FAIL (must retry to complete)

Respond in this exact format:

VERDICT: <PASS or FAIL>
ANALYSIS: <1-3 sentences on what was checked and whether it meets criteria>
FIX: <if FAIL, give the worker concrete, actionable instructions for the retry. Be specific — name which tool to use, what data to look for, what approach to try differently>
""".trimIndent()

            try {
                val verifyResult = llmProvider.complete(verifierPrompt)
                val verdict = verifyResult.lines()
                    .find { it.trimStart().startsWith("VERDICT:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()?.uppercase() ?: "PASS"

                if (verdict == "PASS") {
                    subtask.status = com.mengpaw.kernel.agent.SubtaskStatus.VERIFIED
                    subtask.verifierNote = verifyResult.lines()
                        .find { it.trimStart().startsWith("ANALYSIS:", ignoreCase = true) }
                        ?.substringAfter(":")?.trim() ?: "PASS"
                    return workerResult
                } else {
                    // FAIL — extract analysis and fix instructions for the worker
                    lastVerifierFeedback = buildString {
                        val analysis = verifyResult.lines()
                            .find { it.trimStart().startsWith("ANALYSIS:", ignoreCase = true) }
                            ?.substringAfter(":")?.trim()
                        val fix = verifyResult.lines()
                            .find { it.trimStart().startsWith("FIX:", ignoreCase = true) }
                            ?.substringAfter(":")?.trim()
                        if (analysis != null) { append("问题: $analysis\n") }
                        if (fix != null) { append("修复建议: $fix") }
                        if (isBlank()) { append(verifyResult.take(300)) }
                    }
                    subtask.verifierNote = "FAIL: ${lastVerifierFeedback.take(150)}"
                    retries++
                }
            } catch (_: Exception) {
                // Verification unavailable — accept result without verification
                subtask.status = com.mengpaw.kernel.agent.SubtaskStatus.DONE
                return workerResult
            }
        }

        subtask.status = com.mengpaw.kernel.agent.SubtaskStatus.FAILED
        return lastVerifierFeedback.ifBlank { subtask.output }
    }

    private fun localizedError(key: String, detail: String): String = when (agentLanguage) {
        PromptEngine.AgentLanguage.CHINESE -> when (key) {
            "loop_detected" -> "错误：检测到命令循环 — '$detail' 已重复 3+ 次"
            "consecutive_failures" -> "错误：连续 $detail 次命令执行失败，Agent 可能陷入困境。请检查网络、权限或换个方式提问。"
            "max_steps" -> "已达到最大步数 ($detail)，未获得最终答案"
            "agent_error" -> "Agent 错误：$detail"
            "no_plan" -> "无法为任务生成计划：$detail"
            else -> detail
        }
        PromptEngine.AgentLanguage.ENGLISH -> when (key) {
            "loop_detected" -> "Error: Detected command loop — '$detail' repeated 3+ times"
            "consecutive_failures" -> "Error: $detail consecutive command failures. Agent may be stuck. Check network, permissions, or rephrase."
            "max_steps" -> "Max steps ($detail) reached without final answer"
            "agent_error" -> "Agent error: $detail"
            "no_plan" -> "Could not generate a plan for: $detail"
            else -> detail
        }
    }

    suspend fun runWithPlan(task: String, maxStepsPerPlanStep: Int = 5, onStep: ((TraceStep) -> Unit)? = null): String {
        _state.value = AgentState.Running(task, 0, 0)
        _output.value = ""

        val plan = generatePlan(task)
        if (plan.steps.isEmpty()) {
            val msg = localizedError("no_plan", task)
            _state.value = AgentState.Error(msg)
            return msg
        }

        _output.value = formatPlanSummary(plan)

        val results = mutableListOf<String>()
        for (step in plan.steps) {
            step.status = PlanStepStatus.RUNNING
            _state.value = AgentState.Running("[Step ${step.index + 1}/${plan.totalSteps}] ${step.description}", step.index + 1, plan.totalSteps)
            try {
                val stepResult = executePlanStep(step, maxStepsPerPlanStep)
                results.add("[OK] Step ${step.index + 1}: ${stepResult}")
                step.status = PlanStepStatus.COMPLETED
            } catch (e: Exception) {
                ErrorCollector.report(ErrorType.AGENT_CRASH, "AgentEngine.runWithPlan",
                    "Step ${step.index + 1}: ${step.description}", throwable = e, agentName = agentName)
                results.add("[FAIL] Step ${step.index + 1}: ${e.message}")
                step.status = PlanStepStatus.FAILED
            }
            _output.value = "${results.joinToString("\n")}\nProgress: ${plan.completedSteps}/${plan.totalSteps} steps done"
        }

        val summary = buildString {
            appendLine("=== Task Plan Execution Complete ===")
            appendLine("Task: ${plan.task}")
            appendLine("Steps: ${plan.completedSteps}/${plan.totalSteps} completed")
            appendLine()
            results.forEach { appendLine(it) }
            val failed = plan.steps.filter { it.status == PlanStepStatus.FAILED }
            if (failed.isNotEmpty()) {
                appendLine()
                appendLine("WARNING: ${failed.size} step(s) failed:")
                failed.forEach { appendLine("  - ${it.description}") }
            }
        }

        _state.value = AgentState.Finished(summary)
        return summary
    }

    suspend fun generatePlan(task: String): TaskPlan {
        val planPrompt = listOf(mapOf("role" to "user", "content" to """
                Decompose the following task into a step-by-step execution plan.
                Your response must use ONLY the following format, one step per line:

                STEP <N>: <description> | ACTION: <cli-command> | EXPECT: <expected outcome>

                Rules:
                - Number steps starting from 1
                - Each ACTION must be a single CLI command (e.g. fs.cat /path)
                - Keep the total to 3-7 steps
                - Do NOT include any other text before or after the plan

                Task: $task
            """.trimIndent()))
        val response = llmProvider.completeWithMessages(planPrompt)
        return parsePlan(task, response)
    }

    private fun parsePlan(task: String, text: String): TaskPlan {
        val stepRegex = Regex("""STEP\s*(\d+)\s*:\s*(.+?)\s*\|\s*ACTION\s*:\s*(.+?)\s*\|\s*EXPECT\s*:\s*(.+)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        val steps = stepRegex.findAll(text).map { match ->
            val (num, desc, action, expected) = match.destructured
            PlanStep(index = num.toIntOrNull() ?: 0, description = desc.trim(), action = action.trim(), expectedOutcome = expected.trim())
        }.toList().sortedBy { it.index }
        return TaskPlan(task = task, steps = steps.mapIndexed { i, s -> s.copy(index = i) })
    }

    private suspend fun executePlanStep(step: PlanStep, maxSteps: Int): String {
        val stepSession = sessionManager.createSession("PlanStep: ${step.description}")
        val context = ExecutionContext(sessionId = stepSession.id)
        sessionManager.addMessage(stepSession.id, Message("system",
            "Execute this single step: ${step.description}\nPlanned action: ${step.action}\nExpected outcome: ${step.expectedOutcome}"))
        for (iteration in 0 until maxSteps) {
            val conversation = buildConversation(stepSession.id)
            val llmResponse = llmProvider.completeWithMessages(conversation)
            val sanitized = Sanitizer.sanitize(llmResponse)
            sessionManager.addMessage(stepSession.id, Message("assistant", sanitized))
            val parsed = promptEngine.parse(sanitized)
            if (parsed.isFinal) return parsed.thought
            if (parsed.action != null) {
                val cmd = "${parsed.action.name} ${parsed.action.parameters.values.joinToString(" ")}"
                val result = buildPipeline().execute(cmd, context)
                val observation = if (result.success) result.output else "Error: ${result.error}"
                sessionManager.addMessage(stepSession.id, Message("assistant", "Command: $cmd\nResult: $observation"))
            }
        }
        return "Step completed (max iterations reached): ${step.description}"
    }

    private fun formatPlanSummary(plan: TaskPlan): String = buildString {
        appendLine("=== Task Plan ===")
        appendLine("Task: ${plan.task}")
        appendLine("Steps: ${plan.totalSteps}")
        plan.steps.forEach { step ->
            appendLine("  ${step.index + 1}. ${step.description}")
            appendLine("     Action: ${step.action}")
            appendLine("     Expect: ${step.expectedOutcome}")
        }
    }

    fun stop() { _state.value = AgentState.Idle; runningJob?.cancel(); runningJob = null }

    private suspend fun buildConversation(sessionId: String): List<Map<String, String>> {
        val didCompact = sessionManager.compressIfNeeded(llmProvider, specificSessionId = sessionId)
        if (didCompact) {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val dialogRef = "dialog/$today.jsonl"
            sessionManager.addMessage(sessionId, Message("system",
                "[上下文已压缩] 完整历史已归档至 $dialogRef。如需查阅历史细节，使用命令: agent.read.archive $dialogRef"))
        }
        val history = sessionManager.getStructuredHistory(sessionId)
        val nonSystemHistory = if (history.isNotEmpty() && history[0]["role"] == "system") history.drop(1) else history
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
