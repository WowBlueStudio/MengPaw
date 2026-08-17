// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.cli.CommandSearch
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.LinuxCommandExecutor
import com.mengpaw.kernel.llm.*
import com.mengpaw.kernel.session.*

/**
 * AgentEngine 运行时内核 — 会话恢复/命令执行/上下文折叠/任务记忆 + 子运行时编排。
 * 拆自 AgentEngine (400 行文件拆分): ReAct 主循环拆至 [AgentReActLoop],
 * 对话构建与完整性门闩拆至 [AgentConversation]。所有可变状态仍由
 * AgentEngine 持有, 本类只搬移方法体, 逻辑零改动。
 */
internal class AgentRuntime(private val engine: AgentEngine) {

    /** 对话构建 + 完整性门闩 (拆自本类)。 */
    internal val conversation = AgentConversation(engine)

    /** ReAct 主循环 (拆自本类)。 */
    private val reactLoop = AgentReActLoop(engine, this, conversation)

    // ── Command completion listeners (UI 实时刷新钩子) ────────────
    // 任何命令执行完毕 (bang "!" 或 ReAct 循环内) → 通知监听器。
    // UI (设置页) 据此重扫 全局工具/智能体工具/智能体技能/插件 列表 — 与 AgentDocs 文档监听同构。
    private val commandListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** 注册命令完成监听器 — 命令执行完毕后回调 (无参数, UI 只关心"变了"这个事实)。 */
    internal fun addCommandListener(listener: () -> Unit) { commandListeners.add(listener) }

    /** 移除命令完成监听器。 */
    internal fun removeCommandListener(listener: () -> Unit) { commandListeners.remove(listener) }

    internal fun notifyCommandExecuted() {
        commandListeners.forEach { listener ->
            try { listener() } catch (_: Exception) {}
        }
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
    internal fun restoreConversation(
        externalSessionId: String,
        messages: List<Pair<String, String>>,
        lastWasInterrupted: Boolean,
        previousEngineSessionId: String? = null
    ) {
        // Create a new engine session (SessionManager is in-memory, always empty after restart)
        val session = engine.getSessionManager().createSession(
            task = "restored after process death",
            agentId = engine.agentName
        )
        // Push all messages into the engine session so the LLM sees full history
        for ((role, content) in messages) {
            engine.getSessionManager().addMessage(session.id, Message(role, content))
        }
        // Set conversationSessionId so runReActLoop() reuses this session
        engine.conversationSessionId = session.id

        // ── Checkpoint recovery ──
        // If we have the previous engine session ID, try to find its last checkpoint.
        // This gives us diagnostic context about where the interrupted run was.
        var checkpointStep = 0
        if (previousEngineSessionId != null) {
            val ckpt = engine.checkpointManager.loadLatestSync(previousEngineSessionId)
            if (ckpt != null) {
                checkpointStep = ckpt.step
                engine.getSessionManager().recordSessionEvent(session.id, SessionEventBus.SessionEvent(
                    kind = SessionEventBus.EventKind.SESSION_RECOVERED,
                    sessionId = session.id,
                    agentName = engine.agentName,
                    summary = "Checkpoint found: step ${ckpt.step}, task: ${ckpt.remainingTask.take(60)}",
                    payload = mapOf("prevSessionId" to previousEngineSessionId, "step" to ckpt.step.toString())
                ))
            }
        }

        // If the last run was interrupted, set up recovery for the next user message
        if (lastWasInterrupted) {
            val summary = conversation.extractCompletedToolSummaries(session.id)
            engine.getSessionManager().recordInterruptedTurn(
                sessionId = session.id,
                completedTools = summary,
                interruptedTools = emptyList(),
                hasPartialText = false,
                hasPartialReasoning = false
            )
            engine.getSessionManager().recordSessionEvent(session.id, SessionEventBus.SessionEvent(
                kind = SessionEventBus.EventKind.RUN_INTERRUPTED,
                sessionId = session.id,
                agentName = engine.agentName,
                summary = "Session restored after process death (was at step $checkpointStep)"
            ))
        }
    }

    /**
     * Execute a user-typed bang command ("!cmd") — bypasses the LLM/ReAct loop.
     * Routes through the full CLI pipeline (parse → rate limit → security → integrity → execute → audit).
     * Unknown CLI commands (e.g. "!echo hi") fall back to the sandboxed shell executor.
     */
    internal suspend fun executeCommand(
        commandLine: String,
        scope: String = "system",
        workDir: String? = null
    ): ExecutionResult {
        val ctx = ExecutionContext(
            sessionId = engine.conversationSessionId ?: "ui-bang",
            agentName = engine.agentName,
            workDir = workDir ?: bangWorkDir(),
            scope = scope
        )
        val result = engine.getPipelineManager().buildPipeline().execute(commandLine, ctx)
        // 命令执行完毕 → 通知监听器 (UI 实时刷新 Tools/Skills/Plugins 列表)
        notifyCommandExecuted()
        // Fallback 仅限真"命令不存在"（Pipeline 的 Unknown command 错误）— 命令存在但参数
        // 错误（如 agent.session.delete 目标不存在也返回 ERR_NOT_FOUND）不得落 shell 兜底, 否则
        // 显示 "command not found" 掩盖真实错误（错误码二义性修复）
        val unknownCommand = result.errorCode == ErrorCodes.ERR_NOT_FOUND &&
            result.error?.startsWith("Unknown command") == true
        if (result.success || !unknownCommand) return result
        // Linux 命令通道: 与 ReAct 循环同一套监控 (CommandMonitor → SecurityPolicy → 会话池)
        return LinuxCommandExecutor.execute(commandLine, ctx, allowUserConfirm = true)
    }

    /** Work directory for bang commands — agent workspace, created if missing (prevents ProcessBuilder ERR_IO). */
    private fun bangWorkDir(): String {
        val dir = java.io.File(DataPaths.AGENTS, "${engine.agentName}/workspace")
        return if (dir.mkdirs() || dir.exists()) dir.absolutePath else DataPaths.BASE
    }

    /**
     * 列出当前引擎管线中可执行的全部 CLI 命令（名称 + 描述），供 "!" 命令补全下拉使用。
     * 读本引擎 registry（PipelineManager 持有）— 不再依赖 SelfExecutor.commandRegistry
     * 全局指针（多 Agent 场景最后构建者赢的串扰问题）。
     */
    internal fun listCommands(): List<CommandInfo> {
        engine.getPipelineManager().buildPipeline() // 幂等; 确保本引擎 registry 已构建
        val descMap = CommandSearch.all().associate { it.fullName to it.description }
        return engine.getPipelineManager().listCommands().sorted().map { name ->
            // self. 前缀兜底: BuiltinCommandIndex 里 notify.message/banner 缺 self. 前缀（既有数据缺口）
            CommandInfo(name, descMap[name] ?: descMap[name.removePrefix("self.")] ?: "")
        }
    }

    /**
     * Start a new conversation — resets the persistent session.
     * Call when user taps "新会话" in UI.
     * Old session remains in SessionManager for history browsing.
     */
    internal fun newConversation() {
        engine.conversationSessionId = null
        consecutiveCompacts = 0
        compactStuck = false
        engine.getPromptEngine().resetLoopDetection()
    }

    internal var consecutiveCompacts = 0
    internal var compactStuck = false

    /** 重置折叠状态 (rebuildSystemPrompt 调用 — 会话/身份变更时清零)。 */
    internal fun resetCompactState() {
        consecutiveCompacts = 0; compactStuck = false
    }

    suspend fun run(
        task: String, maxSteps: Int = 50, onStep: ((AgentEngine.TraceStep) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null,
        attachments: List<AttachmentData> = emptyList(),
        onReasoning: ((String) -> Unit)? = null
    ): String {
        // P0 注入防护: 任务入口静默剥离精确注入模式 (本地输入 + 远程委托 inbox 任务统一)
        val guardedTask = com.mengpaw.kernel.security.UntrustedContent.sanitizeForAgent(task)
        return engine.runReActLoop(task = guardedTask, maxSteps = maxSteps, onStep = onStep, onDelta = onDelta,
            attachments = attachments, onReasoning = onReasoning)
    }

    /**
     * Internal ReAct loop with optional context prefix.
     * Shared by run() and runWithGoal() to avoid session-creation overhead.
     */
    internal suspend fun runReActLoop(
        task: String,
        maxSteps: Int,
        contextPrefix: String = "",
        onStep: ((AgentEngine.TraceStep) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null,
        attachments: List<AttachmentData> = emptyList(),
        onReasoning: ((String) -> Unit)? = null
    ): String = reactLoop.runReActLoop(task, maxSteps, contextPrefix, onStep, onDelta, attachments, onReasoning)

    internal fun recordTaskMemory(task: String, result: String) {
        // 单轨记忆 (v0.22.0): 任务记忆写入三轨中期 memory_{date}.md (梦境读中期的输入面)
        try {
            com.mengpaw.kernel.agent.AgentDocs.appendMidTermMemory(engine.agentName, "任务: ${task.take(60)}\n结果: ${result.take(500)}")
        } catch (e: Exception) {
            KernelLog.w("AgentEngine", "Failed to record task memory: ${e.message}")
        }
    }

    /**
     * Snip stale tool results from conversation history.
     * Replaces old observation messages (step < currentStep-3) with compressed markers
     * to free context window space without losing the fact that a tool was called.
     *
     * @return number of messages snipped.
     */
    private fun snipStaleToolResults(sessionId: String, currentStep: Int): Int {
        val threshold = currentStep - 3
        if (threshold <= 0) return 0

        // P2 修复: 就地改写消息必须走 SessionManager 监视器 (replaceMessages 与
        // addMessage/compressIfNeeded 同锁) — 旧实现直接改 session.messages[i],
        // 与并行 worker 的 addMessage 或后台预压缩在同一列表上无锁竞态。
        val count = engine.getSessionManager().replaceMessages(
            sessionId,
            predicate = { msg ->
                msg.role == "assistant" && msg.content.startsWith("Command:") &&
                    msg.content.length > 120
            },
            transform = { msg ->
                val cmdName = msg.content.substringBefore("\n").take(50)
                msg.copy(content = "[snip] $cmdName ... (result compressed, step < $threshold)")
            }
        )
        if (count > 0) {
            // Update session state to reflect modified messages
            engine.getSessionManager().addMessage(sessionId, Message(
                "system", "[snip — $count old tool results compressed to free context]"))
        }
        return count
    }

    internal suspend fun maybeFoldContext(sessionId: String, promptTokens: Int, currentStep: Int = 0): Boolean {
        if (compactStuck) return false
        val ratio = estimateContextRatio(promptTokens)
        if (ratio < PipelineManager.SOFT_COMPACT_RATIO) { consecutiveCompacts = 0; compactStuck = false; return false }
        if (ratio < PipelineManager.TOOL_SNIP_RATIO) return false
        // 折叠主阈值按模型档位（默认 0.9 / 保守模型 0.8 — setAgentIdentity 时设置）
        if (ratio < engine.compactRatio) { return snipStaleToolResults(sessionId, currentStep) > 0 }
        val estimatedFoldTokens = (promptTokens * 0.3).toInt()
        if (ratio < PipelineManager.COMPACT_FORCE_RATIO && estimatedFoldTokens < PipelineManager.MIN_FOLD_TOKENS) return false
        // P1 修复: 折叠只压缩本会话（并行 worker 会话不抢占 activeSessionId,
        // 但显式传参更稳 — 防压缩错会话）; 仅在压缩实际发生时累加计数器
        if (engine.getSessionManager().compressIfNeeded(engine.getLlmProvider(), specificSessionId = sessionId)) {
            consecutiveCompacts++
        }
        if (consecutiveCompacts >= 2) {
            compactStuck = true
            val msg = when (engine.agentLanguage) {
                PromptEngine.AgentLanguage.CHINESE -> "上下文窗口不足以容纳当前对话。自动折叠已暂停。建议手动清理历史或增大模型的 context_window 设置。"
                PromptEngine.AgentLanguage.ENGLISH -> "Context window too small for current conversation. Auto-compaction paused. Consider clearing history or increasing the model's context_window."
            }
            engine.getSessionManager().addMessage(sessionId, Message("system", msg))
        }
        return true
    }

    private fun estimateContextRatio(promptTokens: Int): Double = promptTokens / PipelineManager.DEFAULT_CONTEXT_WINDOW.toDouble()

    private fun estimateTokens(text: String): Int = (text.length * engine.llmRequestBuilder.calibratedTokPerChar).toInt()
}
