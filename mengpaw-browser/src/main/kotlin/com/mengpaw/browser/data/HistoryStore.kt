// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.browser.data

import android.content.Context

/** Simple history store with 30-day retention and in-memory cache. */
class HistoryStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("mp_history", Context.MODE_PRIVATE)
    private val cutoffTime: Long get() = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
    private var cached: List<Entry>? = null
    private var cacheTimestamp = 0L

    data class Entry(val url: String, val title: String, val time: Long) {
        val daysLeft: Int get() = maxOf(0, ((30L * 24 * 60 * 60 * 1000 - (System.currentTimeMillis() - time)) / (24 * 60 * 60 * 1000)).toInt())
        val countdown: String get() = "${daysLeft}d"
    }

    fun record(url: String, title: String) {
        val entries = all().toMutableList()
        entries.add(0, Entry(url.take(500), title.take(100), System.currentTimeMillis()))
        val pruned = entries.filter { it.time > cutoffTime }.take(MAX_ENTRIES)
        p.edit().putString("entries", pruned.joinToString("|") { encode(it) }).apply()
        cached = pruned; cacheTimestamp = System.currentTimeMillis()
    }

    fun all(): List<Entry> {
        val now = System.currentTimeMillis()
        if (cached != null && now - cacheTimestamp < CACHE_TTL_MS) return cached ?: emptyList()
        val raw = p.getString("entries", "") ?: ""
        val entries = if (raw.isBlank()) emptyList()
        else raw.split("|").mapNotNull { decode(it) }.filter { it.time > cutoffTime }
        cached = entries; cacheTimestamp = now
        return entries
    }

    fun clear() { p.edit().remove("entries").apply(); cached = emptyList() }

    companion object {
        private const val MAX_ENTRIES = 500
        private const val CACHE_TTL_MS = 5000L
    }

    private fun encode(e: Entry): String {
        val obj = org.json.JSONObject()
        obj.put("u", e.url)
        obj.put("t", e.title)
        obj.put("ts", e.time)
        return obj.toString()
    }
    private fun decode(s: String): Entry? = try {
        val obj = org.json.JSONObject(s)
        Entry(obj.getString("u"), obj.optString("t", ""), obj.getLong("ts"))
    } catch (e: Exception) {
        android.util.Log.w("HistoryStore", "Failed to decode history entry", e)
        null
    }
}
