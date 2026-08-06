// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.namespace

/**
 * Agent theme data — light mode only.
 *
 * Dark mode always uses the default DarkColorScheme and is not customizable.
 * When custom light mode colors are set, all other colors (text, borders,
 * containers) are derived automatically by ArcoTheme from these 3 base values.
 */
data class AgentTheme(
    val primary: Long = 0xFF0E4397,
    val surface: Long = 0xFFFFFFFF,
    val containerLight: Long = 0xFFE7EEF8
) {
    fun toMarkdown(): String = """
# Agent 主题配色

> Agent 可自由修改以下色值。填写十六进制颜色码（如 `#0E4397`）。
> 自定义主题仅影响亮色模式。深色模式始终使用默认配色方案。

## 色值表
| 角色 | 色值 | 说明 |
|------|------|------|
| primary | `#${primary.toString(16).takeLast(6).uppercase()}` | 主色（按钮、链接、强调） |
| surface | `#${surface.toString(16).takeLast(6).uppercase()}` | 页面背景色 |
| containerLight | `#${containerLight.toString(16).takeLast(6).uppercase()}` | 卡片/容器背景 |

## 配色建议
- primary 建议亮度 40-60%，饱和度 60-90%
- surface 使用接近白色（#FFFFFF ~ #F5F5F5）的浅色
- containerLight 比 surface 略深，使用低饱和度中性色
- 参考: https://m3.material.io/theme-builder

## 修改命令
```
self.theme primary=#FF6B35 surface=#FFF8F0
```
""".trimIndent()

    companion object {
        fun fromFile(f: java.io.File): AgentTheme {
            if (!f.exists()) return AgentTheme()
            val text = try { f.readText() } catch (_: Exception) { "" }
            fun readHex(key: String, default: Long): Long {
                val m = Regex("$key.*?#([0-9A-Fa-f]{6})").find(text)
                return m?.groupValues?.get(1)?.toLongOrNull(16)?.let { 0xFF000000 or it } ?: default
            }
            return AgentTheme(
                primary = readHex("primary", 0xFF0E4397),
                surface = readHex("surface", 0xFFFFFFFF),
                containerLight = readHex("containerLight", 0xFFE8F3FF),
            )
        }
    }
}
