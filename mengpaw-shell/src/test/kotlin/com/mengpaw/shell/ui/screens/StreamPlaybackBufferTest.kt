// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StreamPlaybackBuffer 轮次队列回归测试 (v0.36.3 P1)。
 *
 * 背景 BUG: 原实现 onStep → resetRound 立即清空缓冲, 前几轮 ReAct 思考若工具
 * 毫秒级完成 (流式突发 + 工具立即返回), 50ms 节拍的播放协程还没播完整轮思考
 * 即被清空 → UI 只显示 1~3 字。改为轮次队列后: sealRound 只封口不清空, 未播
 * 文本按序播完再进下一轮 ("动画序列")。
 *
 * 本测试锁住: ① 封口后未播文本不丢失、完整播出; ② 两轮按序依次播放 (先全部
 * 播完第一轮再播第二轮); ③ 封口后新增量自动开新轮 (每轮缓冲独立, Action 标记
 * 不跨轮残留); ④ finish 后全部播完返回 Done。
 */
class StreamPlaybackBufferTest {

    /** 同步 drain 全部 tick 结果 — 单元测试不依赖真实时间节拍。 */
    private fun drain(buffer: StreamPlaybackBuffer): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        var guard = 0
        while (guard++ < 10_000) {
            when (val r = buffer.tick()) {
                StreamPlaybackBuffer.Tick.Done -> return out
                StreamPlaybackBuffer.Tick.NothingNew -> break // 无输入且未完成 — 防死循环
                is StreamPlaybackBuffer.Tick.Text -> out.add(r.roundId to r.text)
            }
        }
        return out
    }

    @Test
    fun `sealRound后未播文本不丢失完整播出`() {
        val buffer = StreamPlaybackBuffer()
        val raw = "Thought: 需要查找北京天气\nAction: search\nAction Input: {\"q\":\"天气\"}\n"

        val announce = buffer.append(raw)
        assertEquals(0L, announce?.roundId)
        assertEquals("search", announce?.tool)
        buffer.sealRound() // 快工具轮: 文本刚突发完即封口
        buffer.finish()

        val pushed = drain(buffer)
        assertTrue("封口后必须仍有文本播出", pushed.isNotEmpty())
        assertEquals("封口后全部增量仍属同一轮", setOf(0L), pushed.map { it.first }.toSet())
        assertEquals("完整思考必须最终播出 (工具行由 ProcessTool 承载, 不入 thought)",
            " 需要查找北京天气", pushed.last().second)
    }

    @Test
    fun `两轮思考按序依次播放`() {
        val buffer = StreamPlaybackBuffer()
        val r1 = "Thought: 第一轮思考\nAction: memory\nAction Input: {}\n"
        val r2 = "Thought: 第二轮思考\nAction: search\nAction Input: {}\n"

        assertEquals(0L, buffer.append(r1)?.roundId)
        buffer.sealRound()
        val announce2 = buffer.append(r2)
        assertEquals(1L, announce2?.roundId)
        buffer.finish()

        val pushed = drain(buffer)
        val round1 = pushed.filter { it.first == 0L }
        val round2 = pushed.filter { it.first == 1L }
        assertTrue("第一轮必须至少推送一次", round1.isNotEmpty())
        assertTrue("第二轮必须至少推送一次", round2.isNotEmpty())
        assertEquals("第一轮完整文本必须播出 (不含工具行)", " 第一轮思考", round1.last().second)
        assertEquals("第二轮完整文本必须播出 (不含工具行)", " 第二轮思考", round2.last().second)
        assertTrue("动画序列: 第一轮全部播完才播第二轮",
            pushed.indexOfFirst { it.first == 1L } > pushed.indexOfLast { it.first == 0L })
    }

    @Test
    fun `封口后新增量自动开新轮`() {
        val buffer = StreamPlaybackBuffer()

        buffer.append("Thought: 第一轮\nAction: a\nAction Input: {}\n")
        buffer.sealRound()
        val announce = buffer.append("Thought: 第二轮\nAction: b\nAction Input: {}\n")

        assertEquals("封口后新增量必须开新轮", 1L, announce?.roundId)
        assertEquals("b", announce?.tool)
    }

    @Test
    fun `半截Action行不宣布_流式逐字不误报`() {
        val buffer = StreamPlaybackBuffer()

        // v0.37.3 回归: 模型逐字流式输出工具名, 无换行落地前不得宣布
        assertEquals(null, buffer.append("Thought: 查询\nAction: agent"))
        assertEquals(null, buffer.append(".memory"))
        assertEquals(null, buffer.append(".m"))
        // 换行落地后只宣布一次完整工具名
        val announce = buffer.append("id\nAction Input: {}\n")
        assertNotNull(announce)
        assertEquals("agent.memory.mid", announce?.tool)
        // 继续增量不得重复宣布同一行
        assertEquals(null, buffer.append("后续文本"))
    }

    @Test
    fun `finish后播完全部轮次返回Done`() {
        val buffer = StreamPlaybackBuffer()

        buffer.append("Thought: 最终思考\nFinal Answer: 答案")
        buffer.finish()

        var done = false
        var guard = 0
        while (guard++ < 10_000) {
            val r = buffer.tick()
            if (r is StreamPlaybackBuffer.Tick.Done) {
                done = true
                break
            }
            if (r is StreamPlaybackBuffer.Tick.NothingNew) break
        }
        assertTrue("全部播完后必须返回 Done", done)
    }

    @Test
    fun `flushText返回未播文本及轮次id`() {
        val buffer = StreamPlaybackBuffer()

        buffer.append("Thought: 第一轮思考\nAction: a\nAction Input: {}\n")
        buffer.sealRound()

        val flush = buffer.flushText()
        assertNotNull("未播文本必须可 flush", flush)
        assertEquals(0L, flush?.roundId)
        assertEquals(" 第一轮思考", flush?.tool)
    }

    @Test
    fun `finish封口全部轮次_未走onStep的轮也能播完`() {
        val buffer = StreamPlaybackBuffer()

        buffer.append("Thought: 第一轮\nAction: a\nAction Input: {}\n")
        buffer.sealRound()
        // 模拟引擎截断路径: 第二轮未走 onStep sealRound 就 run() 返回
        buffer.append("Thought: 第二轮未封口\nFinal Answer: 答案")
        buffer.finish()

        val pushed = drain(buffer)
        assertTrue("未 sealRound 的第二轮必须仍播出", pushed.any { it.first == 1L })
        assertTrue("最终答案必须播出", pushed.last().second.contains("答案"))
    }
}
