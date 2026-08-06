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
 * 缓冲每轮结束 (onStep → resetRound) 清空, 避免 "Action:" 标记跨轮残留。
 * 所有状态访问均在监视器内完成 (跨线程: engine 回调线程 / 播放协程 / 主协程)。
 */
internal class StreamPlaybackBuffer {

    // 节奏参数 (原 AgentViewModel 私有常量, 逻辑不变)
    private val STREAM_PLAYBACK_INTERVAL_MS = 50L  // 播放 tick 间隔
    private val STREAM_PLAYBACK_TARGET_TICKS = 50  // 长文目标 ~2.5s 播完 (50ms × 50)

    private val buf = StringBuilder()   // LLM 原始增量缓冲 (engine 线程写)
    private var played = 0              // 已播放原始字符数 (播放协程推进, resetRound 清零)
    private var finished = false        // run() 已返回, 不会再增量 — 播放器播完即退
    private var announcedTools = 0      // 已宣布的 Action 行数 (resetRound 清零)
    private var scannedUpTo = 0         // 增量扫描水位 (resetRound 随 buffer 清零)

    /** tick 结果: 无新内容 / 流已结束应退场 / 有新文本可播放. */
    internal sealed class Tick {
        object NothingNew : Tick()
        object Done : Tick()
        data class Text(val text: String) : Tick()
    }

    /**
     * 追加增量并扫描工具行 (原 onDelta 同步段, 逻辑不变):
     * 只扫描"上次水位后可能完整的新行" — 从上一条换行处起扫, 跨界行完整可见;
     * 新匹配必然在本次增量内结束 (range.last ≥ 水位), 已宣布的旧行被过滤。
     * 完整行才宣布 — 避免 "Action: l" 半截工具名误报; "Action Input:" 不匹配
     * 因为要求冒号紧跟 Action。流式到达时行尾 \n 落地即命中。
     * @return 新宣布的最后一个工具名 (无则 null)
     */
    fun append(delta: String): String? {
        var newTool: String? = null
        synchronized(this) {
            buf.append(delta)
            val start = buf.lastIndexOf('\n', scannedUpTo - 1) + 1
            val newMatches = ACTION_LINE_REGEX.findAll(buf, start)
                .filter { it.range.last >= scannedUpTo }
                .toList()
            scannedUpTo = buf.length
            if (newMatches.isNotEmpty()) {
                announcedTools += newMatches.size
                newTool = newMatches.last().groupValues[1]
            }
        }
        return newTool
    }

    /** 当前缓冲的显示文本 (锁外调用 — 锁纪律同 onStep/播放协程). */
    fun displayText(): String = synchronized(this) {
        computeStreamDisplayText(buf.toString())
    }

    /** 播放协程单 tick 消费 — 原播放循环同步段, 逻辑不变. */
    fun tick(): Tick = synchronized(this) {
        val total = buf.length
        if (played >= total) {
            if (finished) Tick.Done else Tick.NothingNew
        } else {
            // 节奏自适应: 每 tick 消费 ceil(剩余/目标tick数) 字符 —
            // 长文 ~2.5s 播完, 短文逐字, 播速不随突发到达暴涨
            val quantum = maxOf(1,
                (total - played + STREAM_PLAYBACK_TARGET_TICKS - 1) /
                STREAM_PLAYBACK_TARGET_TICKS)
            val end = minOf(total, played + quantum)
            played = end
            Tick.Text(computeStreamDisplayText(buf.substring(0, end)))
        }
    }

    /** 工具轮结束 → 清空流式缓冲与播放进度, 下一轮从头累积 (原 onStep 同步段). */
    fun resetRound() = synchronized(this) {
        buf.clear()
        played = 0
        announcedTools = 0        // 下一轮工具提前通知重新计数
        scannedUpTo = 0           // 增量扫描水位随缓冲清零 (P2 修复)
    }

    /** run() 已返回 — 标记流结束, 播放器播完剩余缓冲即退. */
    fun finish() = synchronized(this) { finished = true }

    /** 兜底 flush: 播放器异常退出时推完剩余; 正常播完时返回 null (逻辑不变). */
    fun flushText(): String? = synchronized(this) {
        computeStreamDisplayText(buf.toString()).takeIf {
            played < buf.length && it.isNotBlank()
        }
    }

    /** 最终轮思考全文提取 (Thought 段至 Final Answer 前, 逻辑不变). */
    fun extractFinalThought(): String = synchronized(this) {
        buf.toString().substringAfter("Thought:", "")
            .substringBefore("Final Answer:", "").trim()
    }

    /**
     * 启动播放协程 — 每 STREAM_PLAYBACK_INTERVAL_MS 把未播放增量推给 UI (打字机)。
     * v0.28.5: 必须用 Dispatchers.Default — SSE 突发到达时(如服务端缓存回放)
     * readUTF8Line 从不挂起, 主线程被读取循环占死, Main 调度的播放协程会被饿死
     * (实测 846 chunks/166ms 突发 → UI-PUSH 零输出)。
     * @param push 文本推送回调 (锁外调用 — 内部可能再同步写消息列表)
     */
    fun launchPlayback(scope: CoroutineScope, push: (String) -> Unit): Job =
        scope.launch(Dispatchers.Default) {
            try {
                while (true) {
                    kotlinx.coroutines.delay(STREAM_PLAYBACK_INTERVAL_MS)
                    when (val r = tick()) {
                        Tick.Done -> return@launch // 已播完且流结束 → 退场
                        Tick.NothingNew -> {}      // 无新内容, 本 tick 不推送
                        is Tick.Text -> {
                            if (r.text.isBlank()) continue // 工具轮样板: 不显示
                            push(r.text)
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
