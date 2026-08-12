// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.KernelLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 流式显示策略 (自 AgentViewModel.computeStreamDisplayText 拆出 — 纯函数):
 *  - 含 "Final Answer:" → 只显示标记后的答案部分
 *  - 含 "Action:"(工具轮) → 流式显示 Thought 思考过程 + Action 命令行,
 *    Action Input 大参数截断 (由执行后 trace 行承载) — v0.3x 演进:
 *    原设计 (v0.28.5) 工具轮样板全隐藏, 思考过程不可见, 执行中只有
 *    "思考中..." 占位; 现让 Agent 的推理轨迹全程流式可见
 *  - 含 "Thought:" → 隐藏思考样板, 只显示其后内容 (thought-only 轮)
 *  - 无任何标记 → 流式显示全文 (parse Rule 3 纯文本答案, 必须流式显示)
 */
internal fun computeStreamDisplayText(text: String): String {
    val hasFinal = text.contains("Final Answer:", ignoreCase = true)
    val hasAction = text.contains("Action:", ignoreCase = true)
    val hasThought = text.contains("Thought:", ignoreCase = true)
    return when {
        hasFinal -> text.substringAfter("Final Answer:", text)
        hasAction -> {
            // 工具轮: Thought 后内容截断在 Action Input 前 — 思考过程 +
            // Action 命令行流式可见, 参数 JSON 不刷屏 (流式中途 Input 未
            // 到达时完整显示; 一旦出现即截断)
            text.substringAfter("Thought:", text)
                .substringBefore("\nAction Input:", text)
        }
        hasThought -> text.substringAfter("Thought:", text)
        else -> text           // 纯文本答案流
    }
}

/**
 * 流式播放器缓冲 (自 AgentViewModel.submitTask 拆出):
 * onDelta 只累积, 独立播放协程按节奏消费 — 打字机观感 (v0.28.5)。
 * 根因: DeepSeek 端点在 ~200ms 内突发全部增量, onDelta 直推 + 50ms 节流
 * → 只推 3 次, 观感 = 整段弹出。方案: buffer 累积原始增量; 播放协程每 50ms
 * 消费未播放部分, 节奏自适应 (长文 ~2.5s 播完, 短文逐字)。
 * v0.36.3 轮次队列改造: 原实现 onStep → resetRound 立即清空缓冲, 若该轮工具
 * 毫秒级完成, 播放协程尚未播完整轮思考即被清空 → 前几轮思考只显示 1~3 字。
 * 现改为每轮独立 Round (id 单调递增), onStep → sealRound 只封口不清空:
 * 播放协程按序播完一轮再进下一轮 ("动画序列"), 未播文本不丢失; 封口后
 * 新到的增量自动开新轮, 避免 "Action:" 标记跨轮残留 (v0.28.3 根因1)。
 * 所有状态访问均在监视器内完成 (跨线程: engine 回调线程 / 播放协程 / 主协程)。
 */
internal class StreamPlaybackBuffer {

    // 节奏参数 (原 AgentViewModel 私有常量, 逻辑不变)
    private val STREAM_PLAYBACK_INTERVAL_MS = 50L  // 播放 tick 间隔
    private val STREAM_PLAYBACK_TARGET_TICKS = 50  // 长文目标 ~2.5s 播完 (50ms × 50)

    /** 一轮 ReAct LLM 输出的独立缓冲 — 封口 (sealRound) 前可继续追加增量。 */
    private class Round(val id: Long) {
        val raw = StringBuilder()   // 本轮 LLM 原始增量 (engine 线程写)
        var played = 0              // 本轮已播放原始字符数 (播放协程推进)
        var finished = false        // sealRound/finish 已封口 — 不会再收到本轮增量
        var announcedTools = 0      // 本轮已宣布的 Action 行数
        var scannedUpTo = 0         // 本轮增量扫描水位
    }

    /** 轮次队列: 队头是当前播放目标, 队尾是当前增量累积目标。 */
    private val rounds = mutableListOf(Round(id = 0))
    private var nextRoundId = 1L
    private var finished = false    // run() 已返回, 不会再增量 — 播放器播完即退

    /** append 检测到的完整工具调用行: 所属轮次 id + 工具名。 */
    internal data class ToolAnnounce(val roundId: Long, val tool: String)

    /** tick 结果: 无新内容 / 流已结束应退场 / 有新文本可播放. */
    internal sealed class Tick {
        object NothingNew : Tick()
        object Done : Tick()
        data class Text(val roundId: Long, val text: String) : Tick()
    }

    /**
     * 追加增量并扫描工具行 (原 onDelta 同步段, 逻辑不变):
     * 只扫描"上次水位后可能完整的新行" — 从上一条换行处起扫, 跨界行完整可见;
     * 新匹配必然在本次增量内结束 (range.last ≥ 水位), 已宣布的旧行被过滤。
     * 完整行才宣布 — 避免 "Action: l" 半截工具名误报; "Action Input:" 不匹配
     * 因为要求冒号紧跟 Action。流式到达时行尾 \n 落地即命中。上一轮已封口
     * (sealRound) 而新增量到达 → 自动开新轮, 保证每轮缓冲独立。
     * @return 新宣布的最后一个工具调用 (含轮次 id, 无则 null)
     */
    fun append(delta: String): ToolAnnounce? {
        var announce: ToolAnnounce? = null
        synchronized(this) {
            // 队尾已封口 (含播放协程已弹空队列的窗口期) → 新增量开新轮
            if (rounds.lastOrNull()?.finished != false) {
                rounds.add(Round(id = nextRoundId++))
            }
            val round = rounds.last()
            round.raw.append(delta)
            val start = round.raw.lastIndexOf('\n', round.scannedUpTo - 1) + 1
            val newMatches = ACTION_LINE_REGEX.findAll(round.raw, start)
                .filter { it.range.last >= round.scannedUpTo }
                .toList()
            round.scannedUpTo = round.raw.length
            if (newMatches.isNotEmpty()) {
                round.announcedTools += newMatches.size
                announce = ToolAnnounce(round.id, newMatches.last().groupValues[1])
            }
        }
        return announce
    }

    /** 当前累积轮次的显示文本 (锁外调用 — 锁纪律同 onStep/播放协程). */
    fun displayText(): String = synchronized(this) {
        rounds.lastOrNull()?.let { computeStreamDisplayText(it.raw.toString()) } ?: ""
    }

    /** 当前累积轮次 id — 供 UI 侧把本轮增量路由到对应思考 step。 */
    fun currentRoundId(): Long = synchronized(this) {
        rounds.lastOrNull()?.id ?: 0L
    }

    /** 播放协程单 tick 消费 — 原播放循环同步段, 逻辑不变. */
    fun tick(): Tick = synchronized(this) {
        // 弹出队头已播完且已封口的轮次 — 未播完的轮次保留, 后续 tick 继续播
        while (rounds.firstOrNull()?.let { it.finished && it.played >= it.raw.length } == true) {
            rounds.removeAt(0)
        }
        val head = rounds.firstOrNull()
        if (head == null) {
            return if (finished) Tick.Done else Tick.NothingNew
        }
        val total = head.raw.length
        if (head.played >= total) {
            // 队头未封口: 流式增量可能仍在到达, 等待下一 tick
            if (finished && head.finished) Tick.Done else Tick.NothingNew
        } else {
            // 节奏自适应: 每 tick 消费 ceil(剩余/目标tick数) 字符 —
            // 长文 ~2.5s 播完, 短文逐字, 播速不随突发到达暴涨
            val quantum = maxOf(1,
                (total - head.played + STREAM_PLAYBACK_TARGET_TICKS - 1) /
                STREAM_PLAYBACK_TARGET_TICKS)
            val end = minOf(total, head.played + quantum)
            head.played = end
            Tick.Text(head.id, computeStreamDisplayText(head.raw.substring(0, end)))
        }
    }

    /**
     * 工具轮结束 (onStep) → 封口当前轮: 不再接收该轮增量, 但保留未播文本
     * 供播放协程按序播完 (原 resetRound 立即清空会丢文本, 已弃用)。
     * 幂等: 多 Action 批的多次 onStep 重复调用无害。
     */
    fun sealRound() = synchronized(this) {
        rounds.lastOrNull()?.finished = true
    }

    /** run() 已返回 — 标记流结束 + 封口当前轮, 播放器播完剩余缓冲即退. */
    fun finish() = synchronized(this) {
        finished = true
        rounds.lastOrNull()?.finished = true
    }

    /** 兜底 flush: 播放器异常退出时推完剩余; 正常播完时返回 null (逻辑不变). */
    fun flushText(): ToolAnnounce? = synchronized(this) {
        val head = rounds.firstOrNull() ?: return null
        if (head.played < head.raw.length) {
            computeStreamDisplayText(head.raw.toString())
                .takeIf { it.isNotBlank() }
                ?.let { ToolAnnounce(head.id, it) }
        } else null
    }

    /** 最终轮思考全文提取 (Thought 段至 Final Answer 前, 逻辑不变). */
    fun extractFinalThought(): String = synchronized(this) {
        rounds.lastOrNull()?.let {
            it.raw.toString().substringAfter("Thought:", "")
                .substringBefore("Final Answer:", "").trim()
        } ?: ""
    }

    /**
     * 启动播放协程 — 每 STREAM_PLAYBACK_INTERVAL_MS 把未播放增量推给 UI (打字机)。
     * v0.28.5: 必须用 Dispatchers.Default — SSE 突发到达时(如服务端缓存回放)
     * readUTF8Line 从不挂起, 主线程被读取循环占死, Main 调度的播放协程会被饿死
     * (实测 846 chunks/166ms 突发 → UI-PUSH 零输出)。
     * @param push (roundId, text) 文本推送回调 — roundId 供 UI 路由到对应思考 step
     *   (锁外调用 — 内部可能再同步写消息列表)
     */
    fun launchPlayback(scope: CoroutineScope, push: (Long, String) -> Unit): Job =
        scope.launch(Dispatchers.Default) {
            try {
                while (true) {
                    kotlinx.coroutines.delay(STREAM_PLAYBACK_INTERVAL_MS)
                    when (val r = tick()) {
                        Tick.Done -> return@launch // 已播完且流结束 → 退场
                        Tick.NothingNew -> {}      // 无新内容, 本 tick 不推送
                        is Tick.Text -> {
                            if (r.text.isBlank()) continue // 工具轮样板: 不显示
                            push(r.roundId, r.text)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 播放器永不让 join() 抛异常 — 只记录, 静默退出
                KernelLog.w("AgentViewModel", "Stream playback exit: ${e.message?.take(80)}")
            }
        }
}
