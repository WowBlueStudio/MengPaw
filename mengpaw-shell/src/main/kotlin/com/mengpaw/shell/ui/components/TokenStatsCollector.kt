// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

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
        // Keep last 90 days max
        if (records.size > 90) records.removeAt(0)
    }

    /** Get daily records for the last N days. */
    fun dailyRecords(days: Int = 14): List<DayRecord> =
        records.takeLast(days)

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

    /** All distinct model names seen so far. */
    fun allModels(): List<String> =
        records.flatMap { it.modelTokens.keys }.distinct().sorted()

    /** Total cache-hit tokens saved. */
    fun totalCacheSaved(): Long = records.sumOf { it.cacheHitTokens }

    /** Estimated USD saved (cache hits × ~$0.14/1M tokens). */
    fun estimatedSavingsUsd(): Double = totalCacheSaved() * 0.0001372
}
