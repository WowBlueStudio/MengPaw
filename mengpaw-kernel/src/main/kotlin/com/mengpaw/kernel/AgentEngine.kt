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
import com.mengpaw.kernel.security.SecurityPolicy
import com.mengpaw.kernel.session.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job

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
    }

    /** The active LLM provider. Can be updated after construction (e.g. when user configures API key). */
    @Volatile private var llmProvider: LlmProvider = llmProvider

    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    @Volatile private var runningJob: Job? = null

    private val _output = MutableStateFlow<String>("")
    val output: StateFlow<String> = _output.asStateFlow()

    private val llmRequestBuilder = LlmRequestBuilder(systemPrompt = promptEngine.buildSystemPrompt())

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

    companion object {
        const val CORE_VERSION = "0.7.2"
        private const val SOFT_COMPACT_RATIO = 0.50
        const val TOOL_SNIP_RATIO = 0.60
        const val COMPACT_RATIO = 0.80
        const val COMPACT_FORCE_RATIO = 0.90
        const val MIN_FOLD_TOKENS = 400
        const val DEFAULT_CONTEXT_WINDOW = 131_072
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
        cachedPipeline = pipeline
        return pipeline
    }

    data class TraceStep(val step: Int, val thought: String, val action: String?, val observation: String?)

    suspend fun run(task: String, maxSteps: Int = 50, onStep: ((TraceStep) -> Unit)? = null): String {
        return runReActLoop(task = task, maxSteps = maxSteps, onStep = onStep)
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
        val session = com.mengpaw.kernel.agent.GoalSession(
            goal = task, maxIterations = maxTurns, maxTokens = maxTokensBudget
        )
        val evaluator = com.mengpaw.kernel.agent.RubricEvaluator()
        val turnResults = mutableListOf<String>()

        for (turn in 0 until maxTurns) {
            if (!session.active) break
            session.iteration = turn + 1

            // FIX: Accumulate context from previous turns so RubricGate has full picture
            val previousContext = if (turnResults.isNotEmpty()) {
                "\n## 前轮结果摘要\n" + turnResults.joinToString("\n---\n") { it.take(500) }
            } else ""

            // Build goal-aware prompt with accumulated context
            val goalPrompt = if (turn == 0) {
                "## 目标模式\n你的任务是完成以下目标。持续工作直到目标达成：\n\n**目标**: ${session.goal}\n\n使用 Thought → Action → Final Answer 格式。完成后给出 Final Answer。"
            } else {
                "## 目标模式 (第 ${turn + 1}/$maxTurns 轮)\n目标: ${session.goal}\n\n上次反馈: ${session.lastFeedback.ifEmpty { "无" }}\n\n继续工作，基于前轮结果改进。$previousContext"
            }

            // FIX: Run ReAct loop inline instead of calling run() which creates a fresh session.
            // This preserves context across goal turns.
            val result = runReActLoop(
                task = "$goalPrompt\n\n$task",
                maxSteps = 50,
                contextPrefix = previousContext,
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
        val session = sessionManager.createSession(task)
        val context = ExecutionContext(sessionId = session.id, agentName = agentName)
        _state.value = AgentState.Running(task, 0, maxSteps)
        _output.value = ""

        sessionManager.addMessage(session.id, Message("user", task))
        if (contextPrefix.isNotBlank()) {
            sessionManager.addMessage(session.id, Message("system", contextPrefix))
        }

        try {
            val job = kotlinx.coroutines.currentCoroutineContext()[Job]
            runningJob = job

            for (step in 0 until maxSteps) {
                runningJob?.let { if (!it.isActive) throw kotlinx.coroutines.CancellationException("Agent stopped") }
                _state.value = AgentState.Running(task, step + 1, maxSteps)

                val conversation = buildConversation(session.id)
                val llmResponse = llmProvider.completeWithMessages(conversation)
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
                    _state.value = AgentState.Finished(answer)
                    recordTaskMemory(task, answer)
                    return answer
                }

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

                    val result = buildPipeline().execute(commandLine, context)
                    if (!result.success) {
                        ErrorCollector.report(ErrorType.TOOL_CALL_FAILED, "AgentEngine",
                            "$commandLine → ${result.error}", sessionId = session.id, agentName = agentName,
                            metadata = mapOf("errorCode" to (result.errorCode ?: ""), "command" to commandLine))
                    }
                    val observation = if (result.success) result.output else "Error: ${result.error}"
                    onStep?.invoke(TraceStep(step + 1, parsed.thought, commandLine, observation))

                    val observationEntry = "Command: $commandLine\nResult: $observation"
                    sessionManager.addMessage(session.id, Message("assistant", observationEntry))
                } else {
                    onStep?.invoke(TraceStep(step + 1, parsed.thought, null, null))
                }
            }

            val msg = localizedError("max_steps", maxSteps.toString())
            sessionManager.addMessage(session.id, Message("assistant", msg))
            _state.value = AgentState.Finished(msg)
            return msg
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
    suspend fun runWithMission(
        task: String, maxSubtasks: Int = 5, maxStepsPerSubtask: Int = 10,
        onStep: ((TraceStep) -> Unit)? = null
    ): String {
        // Step 1: Decompose task into subtasks
        val decomposePrompt = """
将以下复杂任务分解为 $maxSubtasks 个以内可独立执行的子任务。
每个子任务应该是一个完整、可验证的工作单元。

复杂任务: $task

请按以下格式输出（每行一个子任务）：
- [子任务描述] | 预期结果
""".trimIndent()

        val decomposeResult = try {
            llmProvider.complete(decomposePrompt)
        } catch (e: Exception) {
            // Fallback: treat as single task
            return run(task, maxStepsPerSubtask * maxSubtasks, onStep)
        }

        val subtasks = decomposeResult.lines()
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

        if (subtasks.isEmpty()) {
            return run(task, maxStepsPerSubtask * maxSubtasks, onStep)
        }

        // Step 2: Execute each subtask
        val results = mutableListOf<String>()
        for (subtask in subtasks) {
            subtask.status = com.mengpaw.kernel.agent.SubtaskStatus.RUNNING
            _state.value = AgentState.Running("Mission: ${subtask.description}", 0, 0)

            val workerResult = try {
                run(subtask.description, maxSteps = maxStepsPerSubtask, onStep = onStep)
            } catch (e: Exception) {
                "Error: ${e.message}"
            }

            subtask.output = workerResult
            subtask.status = if (workerResult.contains("Final Answer:", ignoreCase = true) ||
                !workerResult.startsWith("Error:")) {
                com.mengpaw.kernel.agent.SubtaskStatus.DONE
            } else {
                com.mengpaw.kernel.agent.SubtaskStatus.FAILED
            }

            // Step 3: Verify
            if (subtask.status == com.mengpaw.kernel.agent.SubtaskStatus.DONE) {
                val verifyPrompt = "验证以下子任务是否成功完成。回答 PASS 或 FAIL。\n子任务: ${subtask.description}\n预期: ${subtask.expectedOutcome}\n输出: ${workerResult.take(1000)}"
                try {
                    val verifyResult = llmProvider.complete(verifyPrompt)
                    if (verifyResult.trim().uppercase().startsWith("PASS")) {
                        subtask.status = com.mengpaw.kernel.agent.SubtaskStatus.VERIFIED
                        subtask.verifierNote = "PASS"
                    } else {
                        subtask.verifierNote = verifyResult.take(100)
                    }
                } catch (_: Exception) {
                    subtask.verifierNote = "Verification skipped"
                }
            }
            results.add("[${subtask.status}] ${subtask.description}: ${workerResult.take(200)}")
        }

        // Step 4: Compose final report
        val verified = subtasks.count { it.status == com.mengpaw.kernel.agent.SubtaskStatus.VERIFIED }
        val failed = subtasks.count { it.status == com.mengpaw.kernel.agent.SubtaskStatus.FAILED }
        return buildString {
            appendLine("## Mission 完成: $task")
            appendLine("子任务: ${subtasks.size} | 已验证: $verified | 失败: $failed")
            appendLine()
            subtasks.forEach { st ->
                val icon = when (st.status) {
                    com.mengpaw.kernel.agent.SubtaskStatus.VERIFIED -> "✅"
                    com.mengpaw.kernel.agent.SubtaskStatus.DONE -> "👍"
                    com.mengpaw.kernel.agent.SubtaskStatus.FAILED -> "❌"
                    else -> "⬜"
                }
                appendLine("$icon **${st.description}**: ${st.output.take(200)}")
            }
        }
    }

    private fun localizedError(key: String, detail: String): String = when (agentLanguage) {
        PromptEngine.AgentLanguage.CHINESE -> when (key) {
            "loop_detected" -> "错误：检测到命令循环 — '$detail' 已重复 3+ 次"
            "max_steps" -> "已达到最大步数 ($detail)，未获得最终答案"
            "agent_error" -> "Agent 错误：$detail"
            "no_plan" -> "无法为任务生成计划：$detail"
            else -> detail
        }
        PromptEngine.AgentLanguage.ENGLISH -> when (key) {
            "loop_detected" -> "Error: Detected command loop — '$detail' repeated 3+ times"
            "max_steps" -> "Max steps ($detail) reached without final answer"
            "agent_error" -> "Agent error: $detail"
            "no_plan" -> "Could not generate a plan for: $detail"
            else -> detail
        }
    }

    suspend fun runWithPlan(task: String, maxStepsPerPlanStep: Int = 5): String {
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
        sessionManager.compressIfNeeded(llmProvider)
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
        } catch (e: Exception) { }
    }
}
