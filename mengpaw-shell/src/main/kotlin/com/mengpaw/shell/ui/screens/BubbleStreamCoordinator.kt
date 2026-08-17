// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 流式气泡编排协调器 (v0.40.2 重构, 替代 v0.40.1 实现)。
 *
 * v0.40.1 简化显示 (思考一次性显示 + 最终答案流式 + 收到最终折叠) 实现失败,
 * 用户复现三症状: ① "思考中... xxs" 单独气泡计时不停; ② 一轮思考后停止,
 * 无最终答案; ③ 思考过程运行中不展开。根因是 finalAnswerRoundId / thoughtShown /
 * stepClosed / pendingTools / 播放路由多套状态互相牵扯, 播放协程对最终答案的
 * 推送被 "raw 含 Final Answer 字样" 条件拦截, 引擎返回兜底又把无标记最终轮
 * 误写进思考容器 — 任一路径脱轨, FinalAnswer 气泡就永远 blank + isRunning=true。
 *
 * 本版把编排收敛为三件事, 其余状态全部删除:
 *  - 思考轮: 完整 Action 行检测到 (onDelta) 或 onStep 时, 整轮思考一次性写入容器;
 *  - 工具行: onStep 时按顺序挂入当前 step 并带观察 (思考先出现, 调用后出现);
 *  - 最终答案: 行首 "Final Answer:" 或引擎返回 (ensureFinalAnswer) 时折叠思考
 *    容器并创建答案气泡; 播放协程无条件流式推送最终轮文本; run() 返回后由
 *    applyFinalResult 定型 — 任何路径 (含截断/异常) 都不会残留运行态气泡。
 * v0.40.3+ (全厂商思维链分流): 思维链 (OpenAI 兼容系 reasoning_content /
 * Anthropic thinking_delta, 字段以各厂商官方文档为唯一准则) 经 [onReasoning]
 * 独立通道累积 — 绝不进 StreamPlaybackBuffer / ReAct 检测。否则思维链里的
 * "Final Answer:" / "Action:" 字样会误判标记 (用户 v0.40.1/0.40.2 三症状根因)。
 * 思维链在 content 到达时作为该轮思考一次性显示 (比 content 的 "Thought:" 更完整),
 * content 的 Thought 不再覆盖。
 * 行为由 BubbleStreamCoordinatorTest 锁死: 思考含 "Final Answer:" 字样不误判、
 * 截断路径兜底、纯文本最终轮进答案气泡而非思考容器。
 */
internal class BubbleStreamCoordinator(
    private val writer: ThinkingProcessWriter,
    val streamBuffer: StreamPlaybackBuffer = StreamPlaybackBuffer()
) {
    private val finalAnswerStarted = AtomicBoolean(false)
    /** 行首锚定 (中英冒号) — 只有独立成行的 "Final Answer:" 才算最终答案轮;
     *  思考正文里复述该字样 (行中) 不误判; 同轮已宣布 Action 行时也不判 (v0.37.3)。 */
    private val finalAnswerLine = Regex("""(?m)^\s*Final Answer[:：]""")
    /** 当前轮思考是否已一次性显示 — 幂等守卫 (仅 engine 回调 / 主协程访问)。 */
    private var thoughtShownRound = -1L
    /** 当前 LLM 调用 (轮) 的思维链累积 (v0.40.3) — 与 content 分流, 独立显示。 */
    private val reasoning = StringBuilder()
    /** 当前轮思维链是否已写入思考容器 — 幂等守卫。 */
    private var reasoningShownRound = -1L

    val isFinalAnswerStarted: Boolean get() = finalAnswerStarted.get()

    /**
     * 全厂商思维链增量 (engine 回调线程) — 独立通道累积,
     * 不参与 Action 扫描 / Final Answer 检测 / 最终答案流式。
     */
    fun onReasoning(delta: String) {
        synchronized(this) { reasoning.append(delta) }
    }

    /**
     * 流式增量 (engine 回调线程): 累积 + 工具轮思考一次性显示 + Final Answer 检测。
     * Final Answer 双重判据 (v0.37.3): 当前轮原始文本含行首 "Final Answer:" **且**
     * 本轮未宣布工具行 — 思考轮里出现该字样 (同轮有 Action) 不误判; 真实最终答案轮
     * 无 Action, 正常触发。
     * @return 新宣布的完整工具调用行 (无则 null)
     */
    fun onDelta(delta: String): StreamPlaybackBuffer.ToolAnnounce? {
        val newTool = streamBuffer.append(delta)
        // content 到达 → 本轮思维链已完整, 一次性写入该轮思考 (思维链先显示)
        flushReasoning(streamBuffer.currentRoundId())
        // 工具轮: 完整 Action 行落地 → 该轮思考已完整, 一次性显示 (取消逐字打字机)
        newTool?.let { if (!finalAnswerStarted.get()) showThoughtOnce(it.roundId) }
        // 检查累积原文而非单个 delta — "Final" / " Answer:" 跨 chunk 拆分也能命中
        if (!finalAnswerStarted.get() &&
            finalAnswerLine.containsMatchIn(streamBuffer.currentRawText()) &&
            streamBuffer.announcedToolNames().isEmpty()
        ) {
            flushReasoning(streamBuffer.currentRoundId())
            finalAnswerStarted.set(true)
            writer.beginFinalAnswer()
        }
        return newTool
    }

    /**
     * 工具轮完成 (engine 回调): 纯思考轮 (无 Action 行) 在此一次性显示思考;
     * 工具行 (含观察全文) 挂入当前 step; 封口当前轮。多 Action 批的多次 onStep
     * 幂等 (思考守卫跳过, 工具行逐个追加)。最终答案轮无工具 — 防御性忽略。
     */
    fun onStep(action: String?, observation: String?, isError: Boolean) {
        if (finalAnswerStarted.get()) return
        val roundId = streamBuffer.currentRoundId()
        // 纯 content 轮 (无 Action 行): 思维链在此一次性显示
        flushReasoning(roundId)
        showThoughtOnce(roundId)
        action?.let { line ->
            val name = line.trim().split(" ").firstOrNull() ?: ""
            writer.addTool(name, roundId)
            writer.completeTool(line, observation.orEmpty(), isError)
        }
        streamBuffer.sealRound()
        // 本轮结束 — 清空思维链, 下一 LLM 调用的思维链开新轮累积
        synchronized(this) { reasoning.setLength(0) }
    }

    /** 播放协程 — 只服务最终答案轮 (打字机): 每 tick 把整轮最终文本覆盖式推给
     *  FinalAnswer 气泡; 思考轮不经过播放器。收流 (finish) 后推完即退。 */
    fun launchPlayback(scope: CoroutineScope): Job =
        streamBuffer.launchPlayback(scope) { text ->
            if (finalAnswerStarted.get()) writer.pushFinal(text)
        }

    /**
     * run() 已返回 — 结束流 + 截断兜底:
     *  - 未走 onStep 的工具轮 (引擎异常/截断): 思考一次性显示 + 工具行补挂 (无观察);
     *  - 无 Final Answer 标记的最终轮 (parse Rule 3/4): 兜底闭环 — 折叠思考容器 +
     *    创建答案气泡, 最终文本由播放协程流式推送, applyFinalResult 定型。
     * 幂等: ensureFinalAnswer 已触发时只做封口。
     */
    fun finish() {
        if (!finalAnswerStarted.get()) {
            val roundId = streamBuffer.currentRoundId()
            flushReasoning(roundId)
            // 截断的工具轮 (已宣布 Action 行但未走 onStep): 思考 + 工具行补挂
            if (!streamBuffer.isCurrentRoundSealed() &&
                streamBuffer.announcedToolNames().isNotEmpty()
            ) {
                showThoughtOnce(roundId)
                streamBuffer.announcedToolNames().forEach { writer.addTool(it, roundId) }
            }
            // 无标记最终轮兜底闭环 — 最终文本已由缓冲持有, 播放器流式输出
            if (finalAnswerStarted.compareAndSet(false, true)) {
                flushReasoning(streamBuffer.currentRoundId())
                writer.beginFinalAnswer()
            }
        }
        streamBuffer.finish()
    }

    /** 引擎返回兜底闭环 — 流式未检测到 Final Answer 时强制折叠容器并建答案气泡。 */
    fun ensureFinalAnswer() {
        if (finalAnswerStarted.compareAndSet(false, true)) {
            flushReasoning(streamBuffer.currentRoundId())
            writer.beginFinalAnswer()
        }
    }

    /**
     * 把当前思维链一次性写入当前轮思考 step — 思维链即最终思考过程。
     * pushThought 按 roundId 覆盖同一步, 故交错到达时后续增量重推即更新 (v0.40.4),
     * 不再"每轮只推一次"丢弃迟到增量; 跨轮由 onStep 的 reasoning.setLength(0) 清空隔断。
     */
    private fun flushReasoning(roundId: Long) {
        synchronized(this) {
            if (reasoning.isEmpty()) return
            reasoningShownRound = roundId
        }
        writer.pushThought(reasoning.toString(), roundId)
    }

    /** 取当前轮完整原始文本一次性写入思考 step (幂等, 每轮只执行一次)。 */
    private fun showThoughtOnce(roundId: Long) {
        synchronized(this) {
            if (thoughtShownRound == roundId) return
            thoughtShownRound = roundId
        }
        // 该轮思考已由思维链承载 → content 的 "Thought:" 不覆盖 (思维链更完整)
        if (reasoningShownRound == roundId) return
        streamBuffer.currentRawText()
            .let(::computeStreamDisplayText)
            .takeIf { it.isNotBlank() }
            ?.let { writer.pushThought(it, roundId) }
    }
}
