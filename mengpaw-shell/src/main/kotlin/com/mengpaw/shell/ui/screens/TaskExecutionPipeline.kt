// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.session.AttachmentData
import com.mengpaw.shell.ui.screens.model.AgentSession
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
            // v0.34.3 气泡 UI 重构: 思考过程容器 + 最终答案 (ThinkingProcessWriter)
            val writer = ThinkingProcessWriter(session, modePrefix, agentRef)
            var playbackJob: Job? = null // 流式播放协程句柄 (try 外声明, catch 路径可取消)
            var coordinator: BubbleStreamCoordinator? = null // 异常路径收口用
            try {
                // /Goal /Fleet: 临时覆盖 loopMode
                if (executionMode == ExecutionMode.GOAL) {
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

                // ── 过程容器前置 (v0.34.3): 用户发送后立即看到思考容器, 4-13s 等待期有活动反馈 ──
                writer.start()
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

                // /Research: 包装提示词（在翻译之后）
                actualTask = when (executionMode) {
                    ExecutionMode.RESEARCH -> """
深度研究模式 — 请执行以下研究任务：

1. 多角度搜索相关信息（优先使用 Tavily / 网络搜索）
2. 交叉验证每条信息的可靠性
3. 给出结构化的综合结论，附信息来源

研究课题：$actualTask
""".trimIndent()
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
                // v0.37.3: 流式气泡编排收敛到独立协调器 (finalAnswerStarted/轮次封口/
                // 播放路由三处共享状态此前散落在本函数, 反复触发"卡在第 1 轮"缺陷)
                coordinator = BubbleStreamCoordinator(writer)
                // 工具提前通知 (v0.29.2, Reasonix ③ 对标): 流式中完整 "Action: <tool>" 行
                // 出现即推送 — 不等工具执行完成 (onStep), 消除工具轮流式空屏

                // Shared step callback: 固化当前 step + 批内合并
                // v0.46.1: 用量记录移出 onStep — 由内核 TokenUsageRegistry 回调统一记录
                // (根治 swarm/fleet/plan/goal 模式; 此处不再读共享 lastUsage, 避免依赖时序与模式差异)
                val onStep: (com.mengpaw.kernel.AgentEngine.TraceStep) -> Unit = { trace ->
                    // v0.34.3: 工具完成 → 挂观察全文 + 成败 (失败红字渲染)
                    coordinator.onStep(
                        action = trace.action,
                        observation = trace.observation,
                        isError = trace.observation?.startsWith("Error [") == true
                    )
                }

                // onDelta (engine 线程回调): 只累积, 不推送 — 节奏由播放协程控制
                var firstDelta = true
                val onDelta: (String) -> Unit = { delta ->
                    if (firstDelta) { firstDelta = false; KernelLog.d("MengPawLatency", "T3 first-delta") }
                    // 工具提前通知 + Final Answer 检测收敛在协调器内 (v0.37.3)
                    coordinator.onDelta(delta)
                }

                // 全厂商思维链分流 (v0.40.4): reasoning_content (OpenAI 兼容系) /
                // Anthropic thinking_delta 等一律走独立回调 → 协调器思维链展示;
                // 绝不混入 onDelta (否则思维链里的 "Final Answer:"/"Action:" 字样会
                // 误判 ReAct 标记, v0.40.1/0.40.2 三症状根因)
                val onReasoning: (String) -> Unit = { delta -> coordinator.onReasoning(delta) }

                // 播放协程: 每 STREAM_PLAYBACK_INTERVAL_MS 把未播放增量推给 UI (打字机)
                // v0.28.5: 必须用 Dispatchers.Default — SSE 突发到达时(如服务端缓存回放)
                // readUTF8Line 从不挂起, 主线程被读取循环占死, Main 调度的播放协程会被饿死
                // (实测 846 chunks/166ms 突发 → UI-PUSH 零输出)
                playbackJob = coordinator.launchPlayback(scope)

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
                            LoopMode.SWARM -> InputTag.Mode(ExecutionMode.SWARM)
                            LoopMode.FLEET -> InputTag.Mode(ExecutionMode.FLEET)
                            else -> null
                        }
                        autoTag?.let { inputTagManager.addTag(it) }
                        // 临时覆盖 loopMode 用于本轮分发
                        if (detected == LoopMode.GOAL || detected == LoopMode.SWARM || detected == LoopMode.FLEET) {
                            inputTagManager.loopMode = detected
                        }
                    }
                }

                // Mode dispatch: map slash command + loopMode to the correct engine method
                val result = when {
                    executionMode == ExecutionMode.PLAN -> session.engine.runWithPlan(task = finalTask, onStep = onStep, onDelta = onDelta, onReasoning = onReasoning)
                    executionMode == ExecutionMode.GOAL -> session.engine.runWithGoal(task = finalTask, maxTurns = 20, onStep = onStep, onDelta = onDelta, onReasoning = onReasoning)
                    // ── 显式斜杠命令结束, 以下为 loopMode 分发 ──
                    // v0.33.0+: REACT 主链路透传附件 (历史经 getStructuredHistory 挂二进制键);
                    // 目标模式 (GOAL/FLEET/SWARM) 执行器签名不含附件 — 附件不传 (注释: P2)
                    inputTagManager.loopMode == LoopMode.REACT -> session.engine.run(task = finalTask, maxSteps = 50, onStep = onStep, onDelta = onDelta, attachments = attachments, onReasoning = onReasoning)
                    inputTagManager.loopMode == LoopMode.GOAL -> session.engine.runWithGoal(task = finalTask, maxTurns = 20, onStep = onStep, onDelta = onDelta, onReasoning = onReasoning)
                    inputTagManager.loopMode == LoopMode.SWARM ->
                        session.engine.runWithSwarm(task = finalTask, roles = sessionFactory.buildSwarmRoles(), onStep = onStep, onDelta = onDelta, onReasoning = onReasoning)
                    inputTagManager.loopMode == LoopMode.FLEET ->
                        session.engine.runWithFleet(task = finalTask, roles = sessionFactory.buildSwarmRoles(), onStep = onStep, onDelta = onDelta, onReasoning = onReasoning)
                    else -> session.engine.run(task = finalTask, maxSteps = 50, onStep = onStep, onDelta = onDelta, attachments = attachments, onReasoning = onReasoning)
                }

                // ── 思考容器闭环兜底 (v0.36.2 P1): 引擎返回但流式从未检测到 "Final Answer:" 标记 ──
                // 内核 PromptEngine.parse 规则 3/4 把无标记的纯文本自然回答 / Thought-only 也判为
                // 最终答案。引擎返回即兜底闭环: 折叠容器 + 创建 FinalAnswer 气泡, 最终文本由
                // 播放协程流式推送, applyFinalResult 定型 (v0.40.2 不再把最终轮误写进思考容器)。
                coordinator.ensureFinalAnswer()

                // ── 尾段: run() 已返回 — 截断兜底 + 标记流结束, 等待播放器把最终答案
                // 按节奏播完 (打字机收尾, 最长 ~2.5s); join 防 Default 线程晚到 tick 覆盖最终消息
                // (doTranslate 开启时跳过等待: 最终 replace 整段替换为中文,
                //  英文逐字播放无意义, 旧实现同样跳过尾段)
                coordinator.finish()
                if (!doTranslate) playbackJob?.join()
                playbackJob?.cancel()

                // Translate result back to Chinese for US models
                val displayResult = if (doTranslate) translator.toChinese(result) else result

                applyFinalResult(writer, displayResult, result, modePrefix, agentRef, pluginViewModel)
                // 会话结束兜底: Evolution Agent 分析触发 (v0.37.3) — 新失败累计达
                // 批次阈值时生成进化报告, 子 Agent 产出放用户气泡侧 (User 消息),
                // 主 Agent 参考 evolution 技能审阅采纳
                maybeDeliverEvolutionReport(session)
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
                // P1 修复 (2026-08-18): 用户点停止按钮 / 切换智能体 / 撤回消息 →
                // engine.stop() 取消 run(), 原实现只取消播放协程就 re-throw,
                // 思考容器残留 isRunning=true 恒显"思考中…"转圈。
                // 取消路径同样收口: finish 截断兜底 + fail 折叠容器并提示已停止。
                // 撤回路径 (retractLastUserMessage) 已删除容器 → 跳过提示, 防追加多余气泡。
                coordinator?.finish()
                if (session.messages.value.any { it is ChatMessageUi.ThinkingProcess }) {
                    writer.fail("已停止执行")
                }
                throw e
            } catch (e: Throwable) {
                // Safety net: catch OOM, unexpected runtime errors, etc.
                // Prevents process crash — degrades gracefully to error message
                // v0.40.2: 异常路径同样收口 — 未走 onStep 的工具轮补挂思考+工具行,
                // 缓冲标记结束, 再交给 applyError 把错误写入答案气泡
                coordinator?.finish()
                applyError(e, session, writer, savedLoopMode, playbackJob, modePrefix, agentRef,
                    chat, inputTagManager, sessionPersistence) { processNextPending() }
                // 异常结束同样兜底触发进化分析 (失败也是进化原料)
                maybeDeliverEvolutionReport(session)
            }
        }
    }

    /** 会话结束兜底: 静默分支进化 (v0.44) — 处理进化队列, 不再把报告当 User 消息丢进聊天。 */
    private suspend fun maybeDeliverEvolutionReport(session: AgentSession) {
        try {
            session.engine.runEvolutionBranches()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {}
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
