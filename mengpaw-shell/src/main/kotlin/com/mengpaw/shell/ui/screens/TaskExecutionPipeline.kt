// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.session.AttachmentData
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import com.mengpaw.shell.ui.screens.model.ExecutionMode
import com.mengpaw.shell.ui.screens.model.InputTag
import com.mengpaw.shell.ui.screens.model.PendingTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * 任务执行流水线 (自 AgentViewModel 拆出 — delegate-object 模式):
 * submitTask 主链路 / 待办队列 / 自动翻译中间件。
 * 依赖经构造器注入: 活动会话桥接 (chat.activeSession)、引擎工厂 (buildSwarmRoles)、
 * 落盘服务、标签管理器、聊天状态控制器、活动 Agent 名桥接。
 */
internal class TaskExecutionPipeline(
    private val scope: CoroutineScope,
    private val sessionFactory: AgentSessionFactory,
    private val sessionPersistence: SessionPersistenceService,
    private val inputTagManager: InputTagManager,
    private val chat: SessionChatController,
    private val getActiveAgentName: () -> String,
) {

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
            runBangCommand(scope, chat.activeSession(), original = task, command = trimmedTask.removePrefix("!").trimStart())
            return
        }
        val session = chat.activeSession()
        // ── Evolution: 用户纠正识别 (钩子归系统 → 用户反应档案, 用户分身数据源) ──
        // v0.28.6: fire-and-forget 出 Main — reactions.md 文件读写不阻塞发送链
        scope.launch(Dispatchers.IO) {
            try { detectCorrection(task, agentRef, session, getActiveAgentName()) } catch (_: Exception) {}
        }
        // Snapshot both state values atomically to avoid TOCTOU race
        val sessionRunning = session.isRunning.value
        val isRunning = chat.isRunningFlow.value
        if (sessionRunning || isRunning) {
            chat.pendingTasksFlow.value = chat.pendingTasksFlow.value + PendingTask(task, maxSteps, executionMode, agentRef, attachments)
            session.messages.value = session.messages.value + ChatMessageUi.User(task, attachments)
            return
        }

        session.messages.value = session.messages.value + ChatMessageUi.User(task, attachments)
        // 用户消息落盘 — 与回复完成落盘配对, 事件驱动无需 30s 定时
        sessionPersistence.saveCurrentSession()

        scope.launch {
            // ── 执行模式分发变量（在 try 外，catch 中也需要）────
            val savedLoopMode = inputTagManager.loopMode
            val modePrefix = executionMode?.prefix
            // v0.3x 步骤气泡: 当前运行 step 气泡 (每 ReAct 步骤独立气泡, 非单运行气泡+traces)
            // P2 修复: 局部 var 被三线程共享 → @Volatile 追踪器 (见 RunningStepTracker)
            val bubbles = StepBubbleWriter(session, modePrefix, agentRef)
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
                bubbles.preinsert(1, "思考中...")
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
                        getActiveAgentName(), keywords
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
                val streamBuffer = StreamPlaybackBuffer()
                // 工具提前通知 (v0.29.2, Reasonix ③ 对标): 流式中完整 "Action: <tool>" 行
                // 出现即推送 — 不等工具执行完成 (onStep), 消除工具轮流式空屏

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
                        bubbles.merge(trace)
                    } else {
                        lastCompletedStep = trace.step
                        bubbles.complete(trace)
                    }
                    // 工具轮结束 → 清空流式缓冲与播放进度, 下一轮从头累积
                    // (旧实现 buffer 跨轮永不清空, "Action:" 一旦出现即永久过滤后续纯文本增量)
                    streamBuffer.resetRound()
                }

                // onDelta (engine 线程回调): 只累积, 不推送 — 节奏由播放协程控制
                var firstDelta = true
                val onDelta: (String) -> Unit = { delta ->
                    if (firstDelta) { firstDelta = false; KernelLog.d("MengPawLatency", "T3 first-delta") }
                    // 工具提前通知 (P2 修复: 原每 delta 对整段缓冲 findAll 重扫 → O(n²);
                    // 现只扫描"上次水位后可能完整的新行" — 从上一条换行处起扫, 跨界行完整可见;
                    // 新匹配必然在本次增量内结束(range.last ≥ 水位), 已宣布的旧行被过滤)。
                    // 完整行才宣布 — 避免 "Action: l" 半截工具名误报; "Action Input:" 不匹配
                    // 因为要求冒号紧跟 Action。流式到达时行尾 \n 落地即命中。
                    val newTool = streamBuffer.append(delta)
                    // 锁外推送 (锁纪律同 onStep/播放协程: push 不在监视器内调用)
                    newTool?.let { tool ->
                        // v0.3x: 工具轮思考过程已流式可见 — 仅当缓冲里尚无 Thought 内容时
                        // (极端短思考) 才兜底宣布, 避免宣布行替换掉正在播放的 Thought 轨迹
                        val base = streamBuffer.displayText()
                        if (base.isBlank()) {
                            val cur = bubbles.tracker.ref
                            if (cur != null) bubbles.push(cur.step, cur.thought, cur.action, "$EXECUTING_TOOL_PREFIX$tool…")
                        }
                    }
                }

                // 播放协程: 每 STREAM_PLAYBACK_INTERVAL_MS 把未播放增量推给 UI (打字机)
                // v0.28.5: 必须用 Dispatchers.Default — SSE 突发到达时(如服务端缓存回放)
                // readUTF8Line 从不挂起, 主线程被读取循环占死, Main 调度的播放协程会被饿死
                // (实测 846 chunks/166ms 突发 → UI-PUSH 零输出)
                playbackJob = streamBuffer.launchPlayback(scope) { text ->
                    val cur = bubbles.tracker.ref
                    if (cur != null) bubbles.push(cur.step, cur.thought, cur.action, text)
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
                        autoTag?.let { inputTagManager.addTag(it) }
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
                streamBuffer.finish()
                if (!doTranslate) playbackJob?.join()
                playbackJob?.cancel()
                if (!doTranslate) {
                    // 兜底 flush: 播放器异常退出时推完剩余; 正常播完时此处无操作
                    val flushText = streamBuffer.flushText()
                    if (flushText != null) {
                        val cur = bubbles.tracker.ref
                        if (cur != null) bubbles.push(cur.step, cur.thought, cur.action, flushText)
                    }
                }

                // Translate result back to Chinese for US models
                val displayResult = if (doTranslate) translator.toChinese(result) else result

                applyFinalResult(session, bubbles, streamBuffer, displayResult, result, modePrefix, agentRef, pluginViewModel)
                inputTagManager.loopMode = savedLoopMode
                processNextPending()

                // ── 回复完成立即落盘: 滑掉后台任务时 onCleared 不保证执行,
                // 30s 定时可能未到 → 最后一次回复会丢; 完成即保存堵住此洞 ──
                sessionPersistence.saveCurrentSession()

                // ── 自动摘要：对话结束后提取关键信息存入 memory ──
                launchAutoSummary(scope, session, task, displayResult, getActiveAgentName())
                // ── 自动摘要结束 ──
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal coroutine cancellation — re-throw to maintain cancellation chain
                playbackJob?.cancel()
                playbackJob?.join()
                throw e
            } catch (e: Throwable) {
                // Safety net: catch OOM, unexpected runtime errors, etc.
                // Prevents process crash — degrades gracefully to error message
                applyError(e, session, bubbles, savedLoopMode, playbackJob, modePrefix, agentRef,
                    chat, inputTagManager, sessionPersistence) { processNextPending() }
            }
        }
    }

    fun removePendingTask(index: Int) {
        chat.pendingTasksFlow.value = chat.pendingTasksFlow.value.toMutableList().also { if (index in it.indices) it.removeAt(index) }
    }

    fun clearPendingTasks() {
        chat.pendingTasksFlow.value = emptyList()
    }

    private fun processNextPending() {
        val pending = chat.pendingTasksFlow.value
        if (pending.isNotEmpty()) {
            val next = pending.first()
            chat.pendingTasksFlow.value = pending.drop(1)
            submitTask(next.text, maxSteps = next.maxSteps,
                executionMode = next.executionMode, agentRef = next.agentRef,
                attachments = next.attachments)
        }
    }
}
