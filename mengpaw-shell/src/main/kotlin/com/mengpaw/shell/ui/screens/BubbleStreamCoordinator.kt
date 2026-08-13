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
    /** 触发最终答案时的轮次 — 该轮及之后路由到 FinalAnswer, 之前的思考轮按序播完。 */
    @Volatile
    private var finalAnswerRoundId = Long.MAX_VALUE
    /** 行首锚定: 只有独立成行的 "Final Answer:" 才算最终答案轮 —
     *  思考里 "我需要给出 Final Answer: xxx"(行中字样)不误判 (v0.37.3)。 */
    private val finalAnswerLine = Regex("""(?m)^\s*Final Answer:""")
    /** 待显示工具行: roundId → 工具列表。工具行等该轮思考播完才挂入 step (顺序化显示)。 */
    private val pendingTools = mutableMapOf<Long, MutableList<PendingTool>>()
    /** 最近宣布工具行的轮次 — onStep 的观察挂载目标。 */
    private var lastToolRound = -1L

    /** 一轮内待显示的工具: 先有命令名 (announce), 工具完成后挂完整命令行与观察。 */
    internal class PendingTool(val command: String) {
        var actionLine: String? = null
        var observation: String? = null
        var isError: Boolean = false
    }

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
        // 工具行不立即显示 — 存 pending, 该轮思考播完时 flush (顺序化显示)
        newTool?.let {
            lastToolRound = it.roundId
            pendingTools.getOrPut(it.roundId) { mutableListOf() }.add(PendingTool(it.tool))
        }
        if (!finalAnswerStarted.get() &&
            finalAnswerLine.containsMatchIn(delta) &&
            !streamBuffer.currentRoundHasTool()
        ) {
            finalAnswerStarted.set(true)
            finalAnswerRoundId = streamBuffer.currentRoundId()
            writer.beginFinalAnswer()
        }
        return newTool
    }

    /**
     * 工具轮完成 (engine 回调) — 观察全文挂载 + 当前流式轮次封口。
     * 封口语义: 不再接收该轮增量, 但未播文本保留给播放协程按序播完。
     */
    fun onStep(action: String?, observation: String?, isError: Boolean) {
        // 观察挂到最近宣布工具轮的 pending 工具 (工具行尚未入 step)
        if (lastToolRound >= 0) {
            pendingTools[lastToolRound]?.lastOrNull()?.apply {
                actionLine = action
                this.observation = observation
                this.isError = isError
            }
        }
        streamBuffer.sealRound()
    }

    /** 播放协程 — 按轮次把增量路由到思考步骤或最终答案气泡。 */
    fun launchPlayback(scope: CoroutineScope): Job =
        streamBuffer.launchPlayback(scope) { roundId, text ->
            // 顺序化显示: 最终答案轮之前的思考轮按序播完 (含工具行), 只有
            // 最终答案轮及之后才进 FinalAnswer 气泡 — 不再"思考未播完就乱序"
            if (finalAnswerStarted.get() && roundId >= finalAnswerRoundId) {
                writer.pushFinal(text)
            } else {
                writer.pushThought(text, roundId)
                // 该轮思考播完 → 挂上该轮工具行 (含观察), 与实际执行顺序一致
                if (streamBuffer.isRoundFullyPlayed(roundId)) flushRound(roundId)
            }
        }

    /** run() 已返回 — 标记流结束 + 封口全部轮次 (截断路径也能播完)。 */
    fun finish() = streamBuffer.finish()

    /** 播放器异常退出兜底 — 推完剩余缓冲。 */
    fun flushRemaining() {
        // 播放器异常退出兜底: 未播完轮次的工具行也要落地 (否则工具调用丢失)
        pendingTools.keys.toList().forEach { flushRound(it) }
        val flushText = streamBuffer.flushText() ?: return
        if (finalAnswerStarted.get() && flushText.roundId >= finalAnswerRoundId) writer.pushFinal(flushText.tool)
        else writer.pushThought(flushText.tool, flushText.roundId)
    }

    /** 引擎返回兜底闭环 — 流式未检测到 Final Answer 时强制折叠容器并建答案气泡。 */
    fun ensureFinalAnswer() {
        if (finalAnswerStarted.compareAndSet(false, true)) {
            // 引擎返回兜底: 无最终答案轮标记 — 剩余全部按思考轮播完
            finalAnswerRoundId = Long.MAX_VALUE
            writer.beginFinalAnswer()
        }
    }

    /** 该轮思考播完 — 把 pending 工具行 (含观察) 挂入对应 step。 */
    private fun flushRound(roundId: Long) {
        val tools = pendingTools.remove(roundId) ?: return
        tools.forEach { t ->
            writer.addTool(t.command, roundId)
            t.actionLine?.let { line ->
                writer.completeTool(line, t.observation.orEmpty(), t.isError)
            }
        }
    }
}
