// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StreamPlaybackBuffer v0.40.2 重构测试。
 *
 * v0.40.1 轮次队列 (sealRound 不清空 + skipRound + flushText + snapshotRounds) 全部
 * 删除 — 思考轮不经过播放器, 缓冲只保留: ① 当前轮累积 + 完整 Action 行扫描;
 * ② 最终答案流式文本。本测试锁住: 逐字增量不误报、封口后自动开新轮、
 * 最终文本三形态 (标记/纯文本/Thought-only)、收流后播放器推完即退。
 */
class StreamPlaybackBufferTest {

    @Test
    fun `完整Action行宣布_逐字增量不误报_封口后开新轮`() {
        val buffer = StreamPlaybackBuffer()

        // v0.37.3 回归: 模型逐字流式输出工具名, 无换行落地前不得宣布
        assertEquals(null, buffer.append("Thought: 查询\nAction: agent"))
        assertEquals(null, buffer.append(".memory"))
        assertEquals(null, buffer.append(".m"))
        // 换行落地后只宣布一次完整工具名
        val announce = buffer.append("id\nAction Input: {}\n")
        assertNotNull(announce)
        assertEquals(0L, announce?.roundId)
        assertEquals("agent.memory.mid", announce?.tool)
        // 继续增量不得重复宣布同一行
        assertEquals(null, buffer.append("后续文本"))

        // 封口 (onStep) 后新增量自动开新轮 — Action 标记不跨轮残留
        buffer.sealRound()
        val announce2 = buffer.append("Thought: 第二轮\nAction: b\nAction Input: {}\n")
        assertEquals(1L, announce2?.roundId)
        assertEquals("b", announce2?.tool)
        assertTrue(!buffer.isCurrentRoundSealed())
    }

    @Test
    fun `finalAnswerText_标记只取标记后_纯文本取全文_Thought-only剥离样板`() {
        val buffer = StreamPlaybackBuffer()
        buffer.append("Thought: 查询完成\nFinal Answer: 北京晴")
        assertEquals("北京晴", buffer.finalAnswerText().trim())

        buffer.sealRound()
        buffer.append("纯文本答案")
        assertEquals("纯文本答案", buffer.finalAnswerText())

        buffer.sealRound()
        buffer.append("Thought: 只思考")
        assertEquals("只思考", buffer.finalAnswerText())
    }

    @Test
    fun `finalAnswerText_标记跨chunk拆分仍命中`() {
        val buffer = StreamPlaybackBuffer()
        buffer.append("Thought: 总结\nFinal ")
        buffer.append("Answer: 完整答案")
        assertEquals("完整答案", buffer.finalAnswerText().trim())
    }

    @Test
    fun `播放器收流后推完最终文本自动退出`() = runBlocking {
        val buffer = StreamPlaybackBuffer()
        val pushed = mutableListOf<String>()
        val job = buffer.launchPlayback(this) { pushed.add(it) }

        buffer.append("Final Answer: 结论")
        buffer.finish()
        job.join()

        assertTrue("收流后必须把最终文本推给 UI", pushed.isNotEmpty())
        assertEquals("结论", pushed.last().trim())
    }

    @Test
    fun `空最终文本收流后播放器直接退出`() = runBlocking {
        val buffer = StreamPlaybackBuffer()
        val job = buffer.launchPlayback(this) { }
        buffer.finish()
        job.join()
    }
}
