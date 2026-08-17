// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.KernelLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 流式文本中的完整工具调用行 (多行锚定, 行尾必须 \n 落地) — 半截工具名不匹配, 避免误报.
 *  v0.37.3 修复: 原 `$` 在多行模式下也匹配"字符串末尾" — 模型逐字流式输出
 *  "Action: agent" 未写完 (无换行) 就被判为完整行, 每来一个字符误报一次。 */
internal val ACTION_LINE_REGEX = Regex("""(?m)^Action:\s*([\w.+\-]+)\s*\n""")

/**
 * 流式显示策略 (纯函数):
 *  - 含行首 "Final Answer:" → 只显示标记后的答案部分
 *  - 含行首 "Action:" → 隐藏 Thought/Action 样板, 只显示其后思考过程
 *    (工具行由 ProcessTool 承载, 参数 JSON 不刷屏)
 *  - 含行首 "Thought:" → 隐藏思考样板, 只显示其后内容
 *  - 无任何标记 → 流式显示全文 (parse Rule 3 纯文本答案)
 * 全部标记行首锚定 — 正文里复述 "Final Answer:" / "Action:" / "Thought:" 字样
 * (行中) 不再误截断 (v0.40.1 与协调器 Final Answer 检测同一口径)。
 */
internal fun computeStreamDisplayText(text: String): String {
    val finalRe = Regex("""(?m)^\s*Final Answer[:：]\s*""")
    val actionRe = Regex("""(?m)^\s*Action[:：]""")
    val thoughtRe = Regex("""(?m)^\s*Thought[:：]\s*""")
    return when {
        finalRe.containsMatchIn(text) -> {
            val m = finalRe.find(text) ?: return text
            text.substring(m.range.last + 1).trimStart()
        }
        actionRe.containsMatchIn(text) -> {
            // 工具轮: Thought 后内容截断在 Action Input/Action 前 — 思考过程可见,
            // 工具行本身由 ProcessTool 承载, 思考文本里不重复出现 "Action: xxx"。
            (thoughtRe.find(text)?.let { text.substring(it.range.last + 1) } ?: text)
                .substringBefore("\nAction Input:", text)
                .substringBefore("\nAction:", text)
                .trimEnd()
        }
        thoughtRe.containsMatchIn(text) -> {
            val m = thoughtRe.find(text) ?: return text
            text.substring(m.range.last + 1)
        }
        else -> text           // 纯文本答案流
    }
}

/**
 * 流式缓冲 (v0.40.2 重构, 替代 v0.40.1 轮次队列)。
 *
 * v0.40.1 的轮次队列 / skipRound / snapshotRounds / flushText / thoughtShown /
 * stepClosed 五套状态互相牵扯, 是"思考中... xxs 单独气泡计时不停 + 一轮思考后
 * 停止 + 思考不展开"三症状的结构性根因。本版只保留两个职责:
 *  1. 当前轮原始增量累积 + 完整 Action 行扫描 (思考一次性显示的原料);
 *  2. 最终答案轮流式文本 (播放协程打字机的唯一服务对象)。
 * 思考轮不再经过播放协程; 封口 (onStep) 后新增量自动开新轮, 轮次 id 单调递增
 * 供 ThinkingProcessWriter 按 roundId 路由同轮思考/工具。
 * 跨线程纪律: 所有状态访问 synchronized (engine 回调线程 / 播放协程 / 主协程)。
 */
internal class StreamPlaybackBuffer {

    private var roundId = 0L
    private val raw = StringBuilder()
    private var scannedUpTo = 0
    private var sealed = false
    private var finished = false

    /** append 检测到的完整工具调用行: 所属轮次 id + 工具名。 */
    internal data class ToolAnnounce(val roundId: Long, val tool: String)

    /**
     * 追加增量并扫描完整工具行:
     * 只扫描"上次水位后可能完整的新行" — 从上一条换行处起扫, 跨界行完整可见;
     * 新匹配必然在本次增量内结束 (range.last ≥ 水位), 已宣布的旧行被过滤。
     * 封口 (sealRound) 后新增量自动开新轮, 保证每轮缓冲独立 (v0.28.3 根因修复)。
     * @return 新宣布的最后一个工具调用 (含轮次 id, 无则 null)
     */
    fun append(delta: String): ToolAnnounce? = synchronized(this) {
        if (sealed) {
            roundId++
            raw.setLength(0)
            scannedUpTo = 0
            sealed = false
        }
        raw.append(delta)
        val start = raw.lastIndexOf('\n', scannedUpTo - 1) + 1
        val newMatches = ACTION_LINE_REGEX.findAll(raw, start)
            .filter { it.range.last >= scannedUpTo }
            .toList()
        scannedUpTo = raw.length
        if (newMatches.isEmpty()) null
        else ToolAnnounce(roundId, newMatches.last().groupValues[1])
    }

    /** 当前累积轮次 id — 供 writer 把本轮思考/工具路由到同一 step。 */
    fun currentRoundId(): Long = synchronized(this) { roundId }

    /** 当前轮完整原始文本 — 思考一次性显示 / Final Answer 检测用。 */
    fun currentRawText(): String = synchronized(this) { raw.toString() }

    /** 当前轮全部完整 Action 行工具名 (按文本顺序) — 截断兜底 / Final Answer 误判防护。 */
    fun announcedToolNames(): List<String> = synchronized(this) {
        ACTION_LINE_REGEX.findAll(raw).map { it.groupValues[1] }.toList()
    }

    /** 当前轮是否已被 onStep 封口 — finish 兜底据此判断工具行是否已挂载。 */
    fun isCurrentRoundSealed(): Boolean = synchronized(this) { sealed }

    /**
     * 最终答案流式文本: 有行首 "Final Answer:" 标记 → 标记后内容;
     * 无标记 (纯文本 / Thought-only 引擎返回兜底) → 整轮显示文本。
     * 播放协程每 tick 取全量覆盖推送, 形成打字机观感。
     */
    fun finalAnswerText(): String = synchronized(this) {
        computeStreamDisplayText(raw.toString())
    }

    /** 工具轮结束 (onStep) → 封口当前轮: 后续增量开新轮。幂等。 */
    fun sealRound() = synchronized(this) { sealed = true }

    /** run() 已返回 — 不再接收增量; 播放协程推完最终文本即退场。 */
    fun finish() = synchronized(this) { finished = true }

    fun isFinished(): Boolean = synchronized(this) { finished }

    /**
     * 启动播放协程 — 每 50ms 把最终答案文本 (覆盖式) 推给 UI。
     * 思考轮不经过播放器 (协调器已一次性显示); 最终答案轮保留打字机 (v0.40.1 定案)。
     * 必须用 Dispatchers.Default — SSE 突发到达时主线程被读取循环占死 (v0.28.5 实测)。
     * @param push 文本推送回调 (锁外调用 — 内部可能再同步写消息列表)
     */
    fun launchPlayback(scope: CoroutineScope, push: (String) -> Unit): Job =
        scope.launch(Dispatchers.Default) {
            var lastPushed: String? = null
            try {
                while (true) {
                    kotlinx.coroutines.delay(50L)
                    val text = finalAnswerText()
                    if (text != lastPushed) {
                        lastPushed = text
                        if (text.isNotBlank()) push(text)
                    }
                    // 收流后最终文本不再变化 — 推完即退, 不拖 main 协程 join
                    if (isFinished() && finalAnswerText() == lastPushed) return@launch
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 播放器永不让 join() 抛异常 — 只记录, 静默退出
                KernelLog.w("AgentViewModel", "Stream playback exit: ${e.message?.take(80)}")
            }
        }
}
