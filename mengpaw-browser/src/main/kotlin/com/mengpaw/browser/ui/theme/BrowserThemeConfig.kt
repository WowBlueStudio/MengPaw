// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.ui.theme

/** Theme config loaded from first Agent's theme.md. Falls back to default blue. */
object BrowserThemeConfig {
    data class Config(val primary: Long, val surface: Long)

    fun load(ctx: android.content.Context? = null): Config {
        try {
            val agentsDir = java.io.File(com.mengpaw.kernel.DataPaths.AGENTS)
            // 仅真 Agent 工作区参与主题发现 — 统一判定 (v0.34.x: default/系统目录不读)
            val dirs = agentsDir.listFiles()
                ?.filter { it.isDirectory && com.mengpaw.kernel.DataPaths.isAgentWorkspaceDir(it.name) }
                ?.sortedBy { it.name } ?: emptyList()
            for (dir in dirs) {
                val themeFile = java.io.File(dir, "theme.md")
                if (themeFile.exists()) {
                    val text = themeFile.readText()
                    val primary = Regex("primary.*?#([0-9A-Fa-f]{6})").find(text)?.groupValues?.get(1)?.toLongOrNull(16)?.let { 0xFF000000 or it } ?: 0xFF0E4397
                    val surface = Regex("surface.*?#([0-9A-Fa-f]{6})").find(text)?.groupValues?.get(1)?.toLongOrNull(16)?.let { 0xFF000000 or it } ?: 0xFFFFFFFF
                    return Config(primary, surface)
                }
            }
        } catch (_: Exception) {}
        return Config(0xFF0E4397, 0xFFFFFFFF)
    }
}
