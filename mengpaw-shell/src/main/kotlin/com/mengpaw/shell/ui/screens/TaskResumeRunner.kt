// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.KernelLog
import com.mengpaw.shell.ui.screens.model.AgentSession
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ── 断点续跑任务执行 (P0-3/P2-10) — 自 TaskExecutionPipeline 拆出 (400 行红线) ──
// **复用** submitTask 的既有 UI 管道 (同一个 ThinkingProcessWriter + BubbleStreamCoordinator
// + 同一条落盘/错误兜底路径), 只替换引擎入口 (resumeInterrupted) 且不追加用户气泡。
// 不做气泡重建: 恢复时 trace 已折叠展示, 续跑产生的新一轮思考/工具/答案由协调器写入同一容器。

/**
 * 续跑请求处理器 — 装配到 [SessionPersistenceService.onResumeRequested], 由
 * AgentViewModel 在构造流水线后接线; provider 就绪时 ([AgentViewModel.applyConfiguration])
 * 由 `firePendingResume()` 调用。返回 true = 已受理。
 */
internal fun TaskExecutionPipeline.resumeRequestHandler(): (ResumeRequest) -> Boolean =
    { request -> submitTask(task = request.task.orEmpty(), maxSteps = 50, resume = true); true }

/**
 * 续跑执行体 — 由 [TaskExecutionPipeline.submitTask] (resume = true) 调用。
 *
 * 与普通任务路径的唯一差异:
 * - 入口是 `engine.resumeInterrupted` (会话历史与步号由内核按检查点复原);
 * - 不追加用户气泡, 不过翻译/记忆召回中间件 (历史已含任务, 且续跑文本已是用户语言);
 * - 引擎返回 null (判定失效: 锁被占/检查点已消费) 时收回空思考容器, 不产出空白答案气泡。
 */
internal fun TaskExecutionPipeline.runResumeTask(
    session: AgentSession,
    agentRef: String?,
    maxSteps: Int
) {
    val scope = this.scope
    scope.launch {
        val savedLoopMode = inputTagManager.loopMode
        val writer = ThinkingProcessWriter(session, null, agentRef)
        var playbackJob: Job? = null
        var coordinator: BubbleStreamCoordinator? = null
        try {
            writer.start()
            coordinator = BubbleStreamCoordinator(writer)
            val onStep: (com.mengpaw.kernel.AgentEngine.TraceStep) -> Unit = { trace ->
                coordinator.onStep(
                    action = trace.action,
                    observation = trace.observation,
                    isError = trace.observation?.startsWith("Error [") == true
                )
            }
            val onDelta: (String) -> Unit = { delta -> coordinator.onDelta(delta) }
            val onReasoning: (String) -> Unit = { delta -> coordinator.onReasoning(delta) }
            playbackJob = coordinator.launchPlayback(scope)

            // Reset stale state from previous runs before starting (与普通路径一致)
            session.engine.resetLoopDetection()
            try { session.engine.stop() } catch (_: Exception) {}

            val result = session.engine.resumeInterrupted(
                maxSteps = maxSteps, onStep = onStep, onDelta = onDelta, onReasoning = onReasoning
            )
            if (result == null) {
                // 判定失效 (锁被抢/检查点已被消费): 收回空容器, 不产出空白答案气泡
                KernelLog.i("AgentViewModel", "续跑未启动 (无可用 RUNNING 检查点), 已取消本次续跑")
                coordinator.finish()
                playbackJob?.cancel()
                session.messages.value = session.messages.value.filterNot {
                    it is ChatMessageUi.ThinkingProcess && it.isRunning
                }
                return@launch
            }
            coordinator.ensureFinalAnswer()
            coordinator.finish()
            playbackJob?.join()
            playbackJob?.cancel()
            // 定型 + 插件建议与普通路径同一函数 (不重建气泡)
            applyFinalResult(writer, result, result, null, agentRef, pluginViewModel = null)
            inputTagManager.loopMode = savedLoopMode
            processNextPending()
            sessionPersistence.saveCurrentSession()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消路径同样收口 (与普通路径一致): 截断兜底 + 折叠容器并提示已停止
            playbackJob?.cancel()
            playbackJob?.join()
            coordinator?.finish()
            if (session.messages.value.any { it is ChatMessageUi.ThinkingProcess }) {
                writer.fail("已停止执行")
            }
            throw e
        } catch (e: Throwable) {
            coordinator?.finish()
            applyError(e, session, writer, savedLoopMode, playbackJob, null, agentRef,
                chat, inputTagManager, sessionPersistence) { processNextPending() }
        }
    }
}
