// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.components

import com.mengpaw.kernel.DataPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 面板图标顺序持久化 — 原子写入 JSON，启动时恢复。 */
object PanelOrderStore {
    private const val FILE_NAME = "panel_order.json"

    private val file: File get() = File(DataPaths.CONFIG, FILE_NAME)

    data class PanelOrder(
        val modes: List<String> = listOf("mission", "research", "translate", "silent"),
        val plugins: List<String> = emptyList()
    )

    fun load(): PanelOrder {
        return try {
            if (!file.exists() || file.length() == 0L) return PanelOrder()
            val text = file.readText()
            if (text.isBlank()) return PanelOrder()
            val obj = JSONObject(text)
            PanelOrder(
                modes = obj.optJSONArray("modes")?.let { arr ->
                    (0 until arr.length()).map { i -> arr.getString(i) }
                } ?: listOf("mission", "research", "translate", "silent"),
                plugins = obj.optJSONArray("plugins")?.let { arr ->
                    (0 until arr.length()).map { i -> arr.getString(i) }
                } ?: emptyList()
            )
        } catch (_: Exception) { PanelOrder() }
    }

    fun save(order: PanelOrder) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            val obj = JSONObject().apply {
                put("modes", JSONArray(order.modes))
                put("plugins", JSONArray(order.plugins))
            }
            tmp.writeText(obj.toString(2))
            tmp.renameTo(file)
            if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }
}
