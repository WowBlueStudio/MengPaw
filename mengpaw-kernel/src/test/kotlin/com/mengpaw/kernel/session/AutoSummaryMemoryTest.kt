// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import org.junit.Assert.*
import org.junit.Test

/**
 * P1-5 自动摘要 → 中期记忆 核心逻辑单测 (纯函数 + 幂等护栏)。
 *
 * 压缩路径本身依赖 LLM (compressIfNeeded 内部 summarizeMessagesStructured),
 * 不在单测范围 — 调用点 writeCompactionSummaryToMidTerm 是薄封装
 * (序号登记 + 队列追加), 核心规则 (条目格式 / 幂等去重) 在此锁定。
 */
class AutoSummaryMemoryTest {

    @Test
    fun `buildEntry produces blockquote marker line and preserves summary`() {
        val summary = "目标: 修复登录\n进展: 已定位 bug\n下一步: 提交 PR"
        val entry = AutoSummaryMemory.buildEntry(summary, "ab12cd34", 1)

        assertTrue("条目应以 blockquote 标注来源", entry.startsWith("> [自动摘要 · 会话 ab12cd34 #1]"))
        assertTrue("条目应含会话标识", entry.contains("会话 ab12cd34"))
        assertTrue("条目应含折叠次数", entry.contains("#1"))
        assertTrue("条目应保留摘要全文", entry.contains(summary))
        assertTrue("空摘要不得产出条目", AutoSummaryMemory.buildEntry("   ", "x", 1).isEmpty())
    }

    @Test
    fun `marker is unique per session and per compact round`() {
        val sameRound = AutoSummaryMemory.marker("s1", 1)
        assertNotEquals("同会话不同轮次标记应不同", sameRound, AutoSummaryMemory.marker("s1", 2))
        assertNotEquals("不同会话标记应不同", sameRound, AutoSummaryMemory.marker("s2", 1))
        assertEquals("同会话同轮次标记应相同 (去重依据)", sameRound, AutoSummaryMemory.marker("s1", 1))
    }

    @Test
    fun `writtenGuard assigns unique ordinal per session and round`() {
        val guard = AutoSummaryMemory.WrittenGuard()
        val s1a = guard.nextOrdinal("s1")
        val s1b = guard.nextOrdinal("s1")
        val s2a = guard.nextOrdinal("s2")

        assertEquals("首会话首轮序号应为 1", 1, s1a)
        assertEquals("同会话第二轮序号应递增", 2, s1b)
        assertEquals("不同会话独立计数", 1, s2a)
        assertNotEquals("同会话不同轮次序号不得重复", s1a, s1b)
    }

    @Test
    fun `writtenGuard blocks duplicate write of same session round (idempotent)`() {
        val guard = AutoSummaryMemory.WrittenGuard()

        // 首轮: 放行
        val round1 = guard.nextOrdinal("s1")
        assertTrue("首轮应放行", guard.shouldWrite("s1", round1))
        // 同轮次重复提交 (并发双压兜底场景): 拦截 — 同会话同轮压缩只写一次
        assertFalse("同会话同轮次重复登记应被拦截", guard.shouldWrite("s1", round1))
        // 新一轮: 放行
        val round2 = guard.nextOrdinal("s1")
        assertTrue("新轮次应放行", guard.shouldWrite("s1", round2))
        assertFalse("新轮次重复提交同样被拦截", guard.shouldWrite("s1", round2))
        // 不同会话互不影响
        val otherRound = guard.nextOrdinal("s2")
        assertTrue("不同会话首轮应放行", guard.shouldWrite("s2", otherRound))
        assertFalse("不同会话同轮次去重同样生效", guard.shouldWrite("s2", otherRound))
    }
}
