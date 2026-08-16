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
 *
 * v0.40.1 简化显示流程 (用户定案):
 *  - 思考阶段: 取消逐字流式打字机 — 工具轮在流式检测到完整 Action 行时
 *    一次性显示整轮思考 (思考先出现), 工具行在 onStep (工具完成) 时按顺序
 *    挂入并带观察; 纯思考轮 (无 Action) 在 onStep 时一次性显示。
 *  - 最终答案: 收到最终答案 (Final Answer 检测 / 引擎返回兜底) 时折叠思考
 *    容器并创建答案气泡, 最终回复仍由播放协程流式输出 (打字机保留)。
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
    /** 任意位置标记 (大小写/中英冒号) — 判断最终轮原始文本是否已输出答案标记。 */
    private val finalAnswerAny = Regex("""(?i)final answer[:：]""")
    /** 已一次性显示思考的轮次 — onDelta (工具行完整) / onStep / finish 兜底幂等去重。
     *  仅在引擎回调线程 (主协程) 访问, 播放协程不触碰。 */
    private val thoughtShown = mutableSetOf<Long>()
    /** 已走 onStep 封口的轮次 — 工具行 (含观察) 已挂入 step; finish 兜底据此
     *  为"思考已显示但工具未挂"的截断轮补挂工具行。 */
    private val stepClosed = mutableSetOf<Long>()

    val isFinalAnswerStarted: Boolean get() = finalAnswerStarted.get()

    /**
     * 流式增量 (engine 回调线程) — 累积 + 思考一次性显示 + Final Answer 检测。
     * Final Answer 检测双重判据 (v0.37.3): 当前增量含 "Final Answer:" **且**当前轮
     * 未宣布工具行 — 思考轮里出现该字样 (同轮有 Action) 不误判; 真实最终答案轮
     * 无 Action, 正常触发。误判会把后续思考全部改道 FinalAnswer 气泡 (历史缺陷)。
     * @return 新宣布的完整工具调用行 (无则 null)
     */
    fun onDelta(delta: String): StreamPlaybackBuffer.ToolAnnounce? {
        val newTool = streamBuffer.append(delta)
        // 工具轮: 流式检测到完整 Action 行时, 该轮思考已完整 — 一次性显示 (取消打字机),
        // 工具行等 onStep (工具完成) 再挂, 保证"思考先出现, 调用后出现"。
        newTool?.let { t ->
            if (!finalAnswerStarted.get() && thoughtShown.add(t.roundId)) {
                showThoughtOnce(t.roundId)
            }
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
     * 纯思考轮 (无 Action 行) 在此一次性显示思考; 工具轮补挂观察。
     * 封口后该轮快进跳过播放 (思考已一次性显示, 不逐字)。
     */
    fun onStep(action: String?, observation: String?, isError: Boolean) {
        val roundId = streamBuffer.currentRoundId()
        // 纯思考轮 (模型只输出 Thought, 无 Action 行): 之前未显示, 这里一次性显示
        if (thoughtShown.add(roundId)) {
            showThoughtOnce(roundId)
        }
        action?.let { line ->
            val name = line.trim().split(" ").firstOrNull() ?: ""
            writer.addTool(name, roundId)
            writer.completeTool(line, observation.orEmpty(), isError)
        }
        stepClosed.add(roundId)
        streamBuffer.skipRound(roundId)
        streamBuffer.sealRound()
    }

    /** 播放协程 — 思考轮已一次性显示并快进, 只对最终答案轮做流式打字机输出。 */
    fun launchPlayback(scope: CoroutineScope): Job =
        streamBuffer.launchPlayback(scope) { roundId, text ->
            if (finalAnswerStarted.get() && roundId >= finalAnswerRoundId) {
                // 标记未到达前丢弃思考段: 最终轮中途 tick 的文本可能只有
                // "Thought: ..." (无 Final Answer 标记), 不推入答案气泡避免闪现
                if (streamBuffer.roundRawText(roundId)?.let { finalAnswerAny.containsMatchIn(it) } == true) {
                    writer.pushFinal(text)
                }
            }
        }

    /** run() 已返回 — 标记流结束 + 封口全部轮次; 截断路径 (引擎异常/未走 onStep
     *  的轮次) 兜底一次性显示思考与工具行。 */
    fun finish() {
        streamBuffer.finish()
        streamBuffer.snapshotRounds().forEach { (roundId, raw) ->
            // 最终答案轮不进思考容器 (已由播放器流式输出)
            if (finalAnswerStarted.get() && roundId >= finalAnswerRoundId) return@forEach
            if (thoughtShown.add(roundId)) {
                computeStreamDisplayText(raw)
                    .takeIf { it.isNotBlank() }
                    ?.let { writer.pushThought(it, roundId) }
            }
            // 工具行兜底: 已宣布 Action 行但引擎异常未走 onStep 的轮, 补挂工具行
            // (无观察, 折叠显示 "…")。thoughtShown 守卫不可复用 — 思考可能已由
            // onDelta 显示而工具未挂, 须按 stepClosed 独立判断。
            if (roundId !in stepClosed) {
                streamBuffer.roundToolNames(roundId).forEach { writer.addTool(it, roundId) }
            }
        }
    }

    /** 播放器异常退出兜底 — 推完剩余缓冲。 */
    fun flushRemaining() {
        val flushText = streamBuffer.flushText() ?: return
        if (finalAnswerStarted.get() && flushText.roundId >= finalAnswerRoundId) writer.pushFinal(flushText.tool)
        else if (thoughtShown.add(flushText.roundId)) writer.pushThought(flushText.tool, flushText.roundId)
    }

    /** 引擎返回兜底闭环 — 流式未检测到 Final Answer 时强制折叠容器并建答案气泡。 */
    fun ensureFinalAnswer() {
        if (finalAnswerStarted.compareAndSet(false, true)) {
            // 引擎返回兜底: 无最终答案轮标记 — 剩余全部按思考轮播完
            finalAnswerRoundId = Long.MAX_VALUE
            writer.beginFinalAnswer()
        }
    }

    /** 取该轮完整原始文本一次性写入思考 step (幂等, 由 thoughtShown 保证只执行一次)。 */
    private fun showThoughtOnce(roundId: Long) {
        streamBuffer.roundRawText(roundId)?.let { raw ->
            computeStreamDisplayText(raw)
                .takeIf { it.isNotBlank() }
                ?.let { writer.pushThought(it, roundId) }
        }
    }
}
