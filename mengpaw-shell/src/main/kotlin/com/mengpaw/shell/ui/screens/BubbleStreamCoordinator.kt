// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 流式气泡编排协调器 (v0.37.3 拆分)。
 *
 * 背景: 思考气泡"卡在第 1 轮"反复出现 (v0.28.3 / v0.36.2 / v0.36.3 / v0.37.3 四次修复),
 * 根因是编排胶水散落在 TaskExecutionPipeline 300 行大函数里 — finalAnswerStarted 全局
 * 标记、轮次封口、播放路由三处共享状态靠"调用顺序恰好正确"维持, 跨三个线程
 * (engine 回调 / 播放协程 / 主协程), 任何顺序变化就出新缺陷。
 *
 * 本类把"流式增量 → 思考/工具/最终答案气泡"的编排收敛为五个入口, 全部状态
 * (finalAnswerStarted / 轮次封口 / 播放路由) 在此内部管理, TaskExecutionPipeline
 * 只做调用, 不再自己捏状态。行为由 BubbleStreamCoordinatorTest 锁死:
 * 思考含 "Final Answer:" 字样不误判、截断路径播完、多轮按序。
 */
internal class BubbleStreamCoordinator(
    private val writer: ThinkingProcessWriter,
    val streamBuffer: StreamPlaybackBuffer = StreamPlaybackBuffer()
) {
    private val finalAnswerStarted = AtomicBoolean(false)
    /** 行首锚定: 只有独立成行的 "Final Answer:" 才算最终答案轮 —
     *  思考里 "我需要给出 Final Answer: xxx"(行中字样)不误判 (v0.37.3)。 */
    private val finalAnswerLine = Regex("""(?m)^\s*Final Answer:""")

    val isFinalAnswerStarted: Boolean get() = finalAnswerStarted.get()

    /**
     * 流式增量 (engine 回调线程) — 累积 + 工具行即时插入 + Final Answer 检测。
     * Final Answer 检测双重判据 (v0.37.3): 当前增量含 "Final Answer:" **且**当前轮
     * 未宣布工具行 — 思考轮里出现该字样 (同轮有 Action) 不误判; 真实最终答案轮
     * 无 Action, 正常触发。误判会把后续思考全部改道 FinalAnswer 气泡 (历史缺陷)。
     * @return 新宣布的完整工具调用行 (无则 null)
     */
    fun onDelta(delta: String): StreamPlaybackBuffer.ToolAnnounce? {
        val newTool = streamBuffer.append(delta)
        newTool?.let { writer.addTool(it.tool, it.roundId) }
        if (!finalAnswerStarted.get() &&
            finalAnswerLine.containsMatchIn(delta) &&
            !streamBuffer.currentRoundHasTool()
        ) {
            finalAnswerStarted.set(true)
            writer.beginFinalAnswer()
        }
        return newTool
    }

    /**
     * 工具轮完成 (engine 回调) — 观察全文挂载 + 当前流式轮次封口。
     * 封口语义: 不再接收该轮增量, 但未播文本保留给播放协程按序播完。
     */
    fun onStep(action: String?, observation: String?, isError: Boolean) {
        writer.completeTool(action.orEmpty(), observation.orEmpty(), isError)
        streamBuffer.sealRound()
    }

    /** 播放协程 — 按轮次把增量路由到思考步骤或最终答案气泡。 */
    fun launchPlayback(scope: CoroutineScope): Job =
        streamBuffer.launchPlayback(scope) { roundId, text ->
            if (finalAnswerStarted.get()) writer.pushFinal(text) else writer.pushThought(text, roundId)
        }

    /** run() 已返回 — 标记流结束 + 封口全部轮次 (截断路径也能播完)。 */
    fun finish() = streamBuffer.finish()

    /** 播放器异常退出兜底 — 推完剩余缓冲。 */
    fun flushRemaining() {
        val flushText = streamBuffer.flushText() ?: return
        if (finalAnswerStarted.get()) writer.pushFinal(flushText.tool)
        else writer.pushThought(flushText.tool, flushText.roundId)
    }

    /** 引擎返回兜底闭环 — 流式未检测到 Final Answer 时强制折叠容器并建答案气泡。 */
    fun ensureFinalAnswer() {
        if (finalAnswerStarted.compareAndSet(false, true)) {
            writer.beginFinalAnswer()
        }
    }
}
