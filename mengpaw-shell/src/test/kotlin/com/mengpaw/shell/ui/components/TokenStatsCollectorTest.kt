// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * TokenStatsCollector.dailySeries/totalTokens 单测 (v0.37.1 重构)。
 *
 * 覆盖: 连续日序列补 0 值占位 (不跳空)、空记录返回空、全量总量口径。
 * records 为私有静态, 经反射注入构造跨天数据 (与 PermissionExecutorTest 同模式)。
 */
class TokenStatsCollectorTest {

    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun dateOffset(daysAgo: Int): String {
        val cal = Calendar.getInstance()
        cal.time = Date()
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return fmt.format(cal.time)
    }

    /** 构造 n 个月前同一日 (日超当月上限自动截断) 的日期字符串。 */
    private fun dateInMonth(monthOffset: Int, day: Int = 10): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, monthOffset)
        cal.set(Calendar.DAY_OF_MONTH, day.coerceAtMost(cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
        return fmt.format(cal.time)
    }

    private fun setRecords(records: List<TokenStatsCollector.DayRecord>) {
        val field = TokenStatsCollector::class.java.getDeclaredField("records")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val target = field.get(TokenStatsCollector) as MutableList<TokenStatsCollector.DayRecord>
        target.clear()
        target.addAll(records)
    }

    private fun record(date: String, model: String, tokens: Long): TokenStatsCollector.DayRecord =
        TokenStatsCollector.DayRecord(
            date = date,
            modelTokens = mapOf(model to tokens),
            cacheHitTokens = if (tokens > 0) tokens / 10 else 0,
            totalTokens = tokens
        )

    @Test
    fun 连续日序列中间零值天不跳空() {
        setRecords(listOf(
            record(dateOffset(2), "gpt-4o", 100),
            record(dateOffset(0), "gpt-4o", 200)
        ))
        val series = TokenStatsCollector.dailySeries()
        assertEquals("跨度应覆盖前天到今天共 3 天", 3, series.size)
        assertEquals("首日应为前天", dateOffset(2), series[0].date)
        assertEquals(100L, series[0].totalTokens)
        assertEquals("中间无记录的天必须补 0 占位", 0L, series[1].totalTokens)
        assertEquals("末日应为今天", dateOffset(0), series[2].date)
        assertEquals(200L, series[2].totalTokens)
    }

    @Test
    fun 无记录时序列为空() {
        setRecords(emptyList())
        assertTrue("空记录应返回空列表", TokenStatsCollector.dailySeries().isEmpty())
    }

    @Test
    fun 当天首条记录序列仅一天() {
        setRecords(listOf(record(dateOffset(0), "gpt-4o", 50)))
        val series = TokenStatsCollector.dailySeries()
        assertEquals(1, series.size)
        assertEquals(dateOffset(0), series[0].date)
        assertEquals(50L, series[0].totalTokens)
    }

    @Test
    fun 总用量为全部历史口径() {
        setRecords(listOf(
            record(dateOffset(30), "gpt-4o", 1000),
            record(dateOffset(3), "gpt-4o", 2000),
            record(dateOffset(0), "gpt-4o-mini", 3000)
        ))
        assertEquals("totalTokens 应为全部历史总和", 6000L, TokenStatsCollector.totalTokens())
    }

    @Test
    fun 连续周序列中间零值周不跳空() {
        setRecords(listOf(
            record(dateOffset(14), "gpt-4o", 500),
            record(dateOffset(0), "gpt-4o", 800)
        ))
        val series = TokenStatsCollector.weeklySeries()
        assertTrue("两周数据跨度应至少 3 周", series.size >= 3)
        assertTrue("中间空周必须补 0 占位", series.any { it.totalTokens == 0L })
        assertEquals("末周应含本周数据", 800L, series.last().totalTokens)
    }

    @Test
    fun 连续月序列中间零值月不跳空() {
        setRecords(listOf(
            record(dateInMonth(-2), "gpt-4o", 700),
            record(dateInMonth(0), "gpt-4o", 900)
        ))
        val series = TokenStatsCollector.monthlySeries()
        assertEquals("上上月到本月应恰 3 个月", 3, series.size)
        assertEquals("中间空月必须补 0 占位", 0L, series[1].totalTokens)
        assertEquals("首月应为上上月", 700L, series[0].totalTokens)
        assertEquals("末月应为本月", 900L, series[2].totalTokens)
    }
}
