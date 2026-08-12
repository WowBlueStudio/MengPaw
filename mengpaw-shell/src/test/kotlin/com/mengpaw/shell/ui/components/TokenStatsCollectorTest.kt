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
        assertEquals("日口径固定回溯 90 天", 90, series.size)
        assertTrue("中间无记录的天必须补 0 占位", series.any { it.totalTokens == 0L })
        assertEquals("末日应为今天", dateOffset(0), series.last().date)
        assertEquals("今天数据应为 200", 200L, series.last().totalTokens)
        assertEquals("前天数据应为 100", 100L, series.firstOrNull { it.date == dateOffset(2) }?.totalTokens)
    }

    @Test
    fun 无记录时序列为空() {
        setRecords(emptyList())
        assertTrue("空记录应返回空列表", TokenStatsCollector.dailySeries().isEmpty())
    }

    @Test
    fun 当天首条记录日序列仍为90天() {
        setRecords(listOf(record(dateOffset(0), "gpt-4o", 50)))
        val series = TokenStatsCollector.dailySeries()
        assertEquals("日口径固定回溯 90 天", 90, series.size)
        assertEquals("末日应为今天", dateOffset(0), series.last().date)
        assertEquals(50L, series.last().totalTokens)
        assertTrue("其余天补 0 占位", series.dropLast(1).all { it.totalTokens == 0L })
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
        assertEquals("周口径固定回溯 50 周", 50, series.size)
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
        assertEquals("月口径固定回溯 24 个月", 24, series.size)
        assertTrue("中间空月必须补 0 占位", series.any { it.totalTokens == 0L })
        assertEquals("末月应为本月且含今日数据", 900L, series.last().totalTokens)
        assertEquals("上上月数据应为 700", 700L, series.firstOrNull { it.totalTokens > 0 && it.totalTokens != 900L }?.totalTokens)
    }
}
