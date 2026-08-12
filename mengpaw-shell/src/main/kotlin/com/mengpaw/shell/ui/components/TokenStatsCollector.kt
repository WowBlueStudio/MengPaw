// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.components

import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Lightweight token usage collector, records per-model daily totals + cache hits.
 *
 * Data stored as simple CSV lines in `DataPaths.BASE/token_stats.csv`:
 *   date,model,tokens,cacheHitTokens
 */
object TokenStatsCollector {

    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val today: String get() = fmt.format(Date())

    /** 历史保留上限 (v0.37.1 用户定案): 月口径 24 月 ≈ 730 天, 支撑日 90 天/周 50 周/月 24 月。 */
    private const val RETENTION_MONTHS = 24

    data class DayRecord(
        val date: String,
        val modelTokens: Map<String, Long>,     // model → token count
        val cacheHitTokens: Long,
        val totalTokens: Long
    )

    data class WeeklySummary(
        val weekLabel: String,                  // "W29" or "07/14-07/20"
        val totalTokens: Long,
        val cacheHitTokens: Long,
        val modelTokens: Map<String, Long>
    )

    private var records = mutableListOf<DayRecord>()
    private val csvFile: File
        get() = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "token_stats.csv")

    /** 保留截止日期 — 早于此日期的记录滚动淘汰。 */
    private fun retentionCutoff(): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, -RETENTION_MONTHS)
        return fmt.format(cal.time)
    }

    /** Load persisted records on startup. */
    fun load() {
        try {
            if (!csvFile.exists()) return
            records.clear()
            csvFile.readLines().forEach { line ->
                val parts = line.split(",")
                if (parts.size >= 3) {
                    val date = parts[0]
                    val model = parts[1]
                    val tokens = parts[2].toLongOrNull() ?: 0
                    val cache = parts.getOrNull(3)?.toLongOrNull() ?: 0
                    val existing = records.find { it.date == date }
                    if (existing != null) {
                        val updated = existing.modelTokens.toMutableMap()
                        updated[model] = (updated[model] ?: 0) + tokens
                        val idx = records.indexOf(existing)
                        records[idx] = existing.copy(
                            modelTokens = updated,
                            cacheHitTokens = existing.cacheHitTokens + cache,
                            totalTokens = existing.totalTokens + tokens
                        )
                    } else {
                        records.add(DayRecord(date, mapOf(model to tokens), cacheHitTokens = cache, totalTokens = tokens))
                    }
                }
            }
            records.removeAll { it.date < retentionCutoff() }
        } catch (_: Exception) { }
    }

    private fun save() {
        try {
            csvFile.parentFile?.mkdirs()
            val lines = records.flatMap { r ->
                r.modelTokens.map { (model, tokens) ->
                    val cache = if (r.modelTokens.size == 1) r.cacheHitTokens else (r.cacheHitTokens / r.modelTokens.size)
                    "${r.date},$model,$tokens,$cache"
                }
            }
            csvFile.writeText(lines.joinToString("\n"))
        } catch (_: Exception) { }
    }

    /** Record token usage for a single LLM call. */
    fun record(model: String, tokens: Int, cacheHit: Boolean, cacheHitTokens: Int = 0) {
        val day = today
        val existing = records.find { it.date == day }
        if (existing != null) {
            val updated = existing.modelTokens.toMutableMap()
            updated[model] = (updated[model] ?: 0) + tokens
            val idx = records.indexOf(existing)
            records[idx] = existing.copy(
                modelTokens = updated,
                cacheHitTokens = existing.cacheHitTokens + if (cacheHit) cacheHitTokens else 0,
                totalTokens = existing.totalTokens + tokens
            )
        } else {
            records.add(DayRecord(day, mapOf(model to tokens.toLong()),
                cacheHitTokens = if (cacheHit) cacheHitTokens.toLong() else 0,
                totalTokens = tokens.toLong()))
        }
        // v0.37.1: 保留上限 90 天 → 24 月 (用户定案: 日 90 天/周 50 周/月 24 月),
        // 按日期清理而非固定 removeAt(0) (防 CSV 乱序误删)。
        records.removeAll { it.date < retentionCutoff() }
        save()
    }

    /** Get daily records for the last N days. */
    fun dailyRecords(days: Int = 14): List<DayRecord> =
        records.takeLast(days)

    /**
     * 连续日序列 (v0.37.1 重构) — 最近 [days] 天 (默认 90) 逐日生成, 无记录的天补
     * 0 值占位 (信息完整性: 中间没用量的区间条形也必须可见, 不跳空)。
     * 无记录返回空列表。
     */
    fun dailySeries(days: Int = 90): List<DayRecord> {
        if (records.isEmpty()) return emptyList()
        val byDate = records.associateBy { it.date }
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        val todayStr = today
        val result = mutableListOf<DayRecord>()
        var guard = 0
        while (guard < days) {
            val date = fmt.format(cal.time)
            result.add(byDate[date] ?: DayRecord(date, emptyMap(), 0, 0))
            if (date == todayStr) break
            cal.add(Calendar.DAY_OF_YEAR, 1)
            guard++
        }
        return result
    }

    /** Aggregate into weekly summaries. */
    fun weeklyRecords(weeks: Int = 12): List<WeeklySummary> {
        val result = mutableListOf<WeeklySummary>()
        val cal = Calendar.getInstance()
        cal.time = Date()
        for (w in 0 until weeks) {
            val end = fmt.format(cal.time)
            cal.add(Calendar.DAY_OF_YEAR, -7)
            val start = fmt.format(cal.time)
            val weekRecords = records.filter { it.date in start..end }
            if (weekRecords.isEmpty()) {
                cal.add(Calendar.DAY_OF_YEAR, -7) // adjust for next iteration
                continue
            }
            val mergedModels = mutableMapOf<String, Long>()
            var total = 0L
            var cache = 0L
            weekRecords.forEach { r ->
                r.modelTokens.forEach { (m, t) -> mergedModels[m] = (mergedModels[m] ?: 0) + t }
                total += r.totalTokens
                cache += r.cacheHitTokens
            }
            val weekLabel = "${start.substring(5)}-${end.substring(5)}"
            result.add(WeeklySummary(weekLabel, total, cache, mergedModels))
        }
        return result.reversed()
    }

    /**
     * 连续周序列 (v0.37.1 重构) — 最近 [weeks] 周 (默认 50) 逐周生成, 空周补 0 值
     * 占位 (不跳空; 用户定案: 中间没用量的区间条形必须可见)。无记录返回空列表。
     */
    fun weeklySeries(weeks: Int = 50): List<WeeklySummary> {
        if (records.isEmpty()) return emptyList()
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        cal.add(Calendar.DAY_OF_YEAR, -(weeks - 1) * 7)
        val todayStr = today
        val result = mutableListOf<WeeklySummary>()
        var guard = 0
        while (guard < weeks) {
            val start = fmt.format(cal.time)
            val endCal = cal.clone() as Calendar
            endCal.add(Calendar.DAY_OF_YEAR, 6)
            val end = fmt.format(endCal.time)
            val weekRecords = records.filter { it.date in start..end }
            val mergedModels = mutableMapOf<String, Long>()
            var total = 0L
            var cache = 0L
            weekRecords.forEach { r ->
                r.modelTokens.forEach { (m, t) -> mergedModels[m] = (mergedModels[m] ?: 0) + t }
                total += r.totalTokens
                cache += r.cacheHitTokens
            }
            result.add(WeeklySummary("${start.substring(5)}-${end.substring(5)}", total, cache, mergedModels))
            if (end >= todayStr) break
            cal.add(Calendar.DAY_OF_YEAR, 7)
            guard++
        }
        return result
    }

    /** Aggregate into monthly summaries. */
    fun monthlyRecords(months: Int = 6): List<WeeklySummary> {
        val result = mutableListOf<WeeklySummary>()
        val cal = Calendar.getInstance()
        cal.time = Date()
        for (m in 0 until months) {
            val monthLabel = "${cal.get(Calendar.YEAR)}-${(cal.get(Calendar.MONTH) + 1).toString().padStart(2, '0')}"
            val monthRecords = records.filter { it.date.startsWith(monthLabel) }
            val mergedModels = mutableMapOf<String, Long>()
            var total = 0L
            var cache = 0L
            monthRecords.forEach { r ->
                r.modelTokens.forEach { (md, t) -> mergedModels[md] = (mergedModels[md] ?: 0) + t }
                total += r.totalTokens
                cache += r.cacheHitTokens
            }
            val label = "${monthLabel.substring(5)}月"
            result.add(WeeklySummary(label, total, cache, mergedModels))
            cal.add(Calendar.MONTH, -1)
        }
        return result.reversed()
    }

    /**
     * 连续月序列 (v0.37.1 重构) — 最近 [months] 个月 (默认 24) 逐月生成, 空月补 0 值
     * 占位 (不跳空; 用户定案: 中间没用量的月份条形必须可见)。无记录返回空列表。
     */
    fun monthlySeries(months: Int = 24): List<WeeklySummary> {
        if (records.isEmpty()) return emptyList()
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.add(Calendar.MONTH, -(months - 1))
        val todayStr = today.substring(0, 7)
        val result = mutableListOf<WeeklySummary>()
        var guard = 0
        while (guard < months) {
            val monthLabel = "${cal.get(Calendar.YEAR)}-${(cal.get(Calendar.MONTH) + 1).toString().padStart(2, '0')}"
            val monthRecords = records.filter { it.date.startsWith(monthLabel) }
            val mergedModels = mutableMapOf<String, Long>()
            var total = 0L
            var cache = 0L
            monthRecords.forEach { r ->
                r.modelTokens.forEach { (md, t) -> mergedModels[md] = (mergedModels[md] ?: 0) + t }
                total += r.totalTokens
                cache += r.cacheHitTokens
            }
            result.add(WeeklySummary("${monthLabel.substring(5)}月", total, cache, mergedModels))
            if (monthLabel >= todayStr) break
            cal.add(Calendar.MONTH, 1)
            guard++
        }
        return result
    }

    /** All distinct model names seen so far. */
    fun allModels(): List<String> =
        records.flatMap { it.modelTokens.keys }.distinct().sorted()

    /** Total cache-hit tokens saved. */
    fun totalCacheSaved(): Long = records.sumOf { it.cacheHitTokens }

    /** 全部历史 token 总量 (v0.37.1 重构: 统计卡不再按最近 14 天口径, 与图表全量一致)。 */
    fun totalTokens(): Long = records.sumOf { it.totalTokens }

    /** Estimated USD saved (cache hits × ~$0.14/1M tokens). */
    fun estimatedSavingsUsd(): Double = totalCacheSaved() * 0.0001372
}
