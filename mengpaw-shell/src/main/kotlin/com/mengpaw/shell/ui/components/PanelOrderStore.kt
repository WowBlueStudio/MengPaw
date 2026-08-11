// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.components

import com.mengpaw.kernel.DataPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 面板图标顺序持久化 — 原子写入 JSON，启动时恢复。 */
object PanelOrderStore {
    private const val FILE_NAME = "panel_order.json"

    private val file: File get() = File(DataPaths.CONFIG, FILE_NAME)

    // v0.34.4: Mission 并入 Swarm — 默认面板不再含 mission; v0.36: /Translate 移除
    private val DEFAULT_MODES = listOf("goal", "swarm", "fleet", "plan", "research", "silent")

    data class PanelOrder(
        // v0.34.3: 补 fleet; v0.34.4: 去 mission (并入 Swarm)
        val modes: List<String> = DEFAULT_MODES,
        val plugins: List<String> = emptyList()
    )

    fun load(): PanelOrder {
        return try {
            if (!file.exists() || file.length() == 0L) return PanelOrder()
            val text = file.readText()
            if (text.isBlank()) return PanelOrder()
            val obj = JSONObject(text)
            val storedModes = obj.optJSONArray("modes")?.let { arr ->
                    (0 until arr.length()).map { i -> arr.getString(i) }
                } ?: DEFAULT_MODES
            // 迁移: 旧列表可能含 mission/translate → 过滤 (Mission 并入 Swarm, /Translate v0.36 移除);
            // 缺 fleet → 插入 (防老用户看不到 Fleet)
            val cleaned = storedModes.filter { it != "mission" && it != "translate" }
            val modes = if ("fleet" in cleaned) cleaned
                else listOf("goal", "swarm", "fleet") + cleaned.filter { it != "swarm" }
            PanelOrder(
                modes = modes,
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
