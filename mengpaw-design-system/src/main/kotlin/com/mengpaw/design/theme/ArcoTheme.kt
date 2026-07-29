// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.design.theme

import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.mengpaw.design.tokens.ArcoColors

/**
 * Arco Design light color scheme mapped to Material3 ColorScheme.
 */
private val LightColorScheme = lightColorScheme(
    primary = ArcoColors.Blue6,
    onPrimary = ArcoColors.TextInverse,
    primaryContainer = ArcoColors.Blue1,
    onPrimaryContainer = ArcoColors.Blue9,

    secondary = ArcoColors.Gray6,
    onSecondary = ArcoColors.TextInverse,
    secondaryContainer = ArcoColors.Gray2,
    onSecondaryContainer = ArcoColors.Gray9,

    tertiary = ArcoColors.Pink5,
    onTertiary = ArcoColors.TextInverse,
    tertiaryContainer = ArcoColors.Pink1,
    onTertiaryContainer = ArcoColors.Pink9,

    error = ArcoColors.Red6,
    onError = ArcoColors.TextInverse,
    errorContainer = ArcoColors.Red1,
    onErrorContainer = ArcoColors.Red9,

    background = ArcoColors.BgPrimary,
    onBackground = ArcoColors.TextPrimary,

    surface = ArcoColors.BgPrimary,
    onSurface = ArcoColors.TextPrimary,
    surfaceVariant = ArcoColors.BgSecondary,
    onSurfaceVariant = ArcoColors.TextSecondary,
    surfaceContainerLowest = ArcoColors.Gray1,
    surfaceContainerLow = ArcoColors.BgPrimary,
    surfaceContainer = ArcoColors.Gray1,
    surfaceContainerHigh = ArcoColors.Gray2,
    surfaceContainerHighest = ArcoColors.Gray3,

    outline = ArcoColors.BorderDefault,
    outlineVariant = ArcoColors.Gray2,
)

/**
 * Arco Design dark color scheme.
 * Following https://arco.design/docs/spec/dark:
 *   bg-1 → #17171A  整体背景
 *   bg-2 → #232324  一级容器
 *   bg-3 → #2A2A2B  二级容器
 *   bg-4 → #313132  三级容器
 *   bg-5 → #373739  弹出层
 *   text-1 → rgba(255,255,255,0.9)  标题
 *   text-2 → rgba(255,255,255,0.7)  正文
 *   text-3 → rgba(255,255,255,0.5)  次要信息
 *   text-4 → rgba(255,255,255,0.3)  禁用/边框
 */
private val DarkColorScheme = darkColorScheme(
    primary = ArcoColors.Blue4,
    onPrimary = Color.White,
    primaryContainer = ArcoColors.Blue8,
    onPrimaryContainer = ArcoColors.Blue2,

    secondary = ArcoColors.Gray5,
    onSecondary = Color.White,
    secondaryContainer = ArcoColors.Gray8,
    onSecondaryContainer = ArcoColors.Gray2,

    tertiary = ArcoColors.Pink4,
    onTertiary = Color.White,
    tertiaryContainer = ArcoColors.Pink8,
    onTertiaryContainer = ArcoColors.Pink2,

    error = ArcoColors.Red5,
    onError = Color.White,
    errorContainer = ArcoColors.Red8,
    onErrorContainer = ArcoColors.Red2,

    background = Color(0xFF17171A),
    onBackground = Color(0xFFE6E6E6),

    surface = Color(0xFF232324),
    onSurface = Color(0xFFE6E6E6),

    surfaceVariant = Color(0xFF2A2A2B),
    onSurfaceVariant = Color(0xFFB3B3B3),

    surfaceContainerLowest = Color(0xFF17171A),
    surfaceContainerLow = Color(0xFF232324),
    surfaceContainer = Color(0xFF2A2A2B),
    surfaceContainerHigh = Color(0xFF313132),
    surfaceContainerHighest = Color(0xFF373739),

    outline = Color(0xFF4D4D4D),
    outlineVariant = Color(0xFF282828),
)

/**
 * Arco Design theme for MengPaw.
 * Apply at the root of the composable tree.
 *
 * Reads agent-customized colors from Agents/theme.md (set via self.theme CLI).
 * Falls back to hardcoded ArcoColors defaults if no custom theme is found.
 */
@Composable
fun ArcoTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    // Load agent-customized theme colors from disk (cached on first read).
    val customTheme = remember {
        try {
            val themeFile = java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, "theme.md")
            if (themeFile.exists()) parseThemeFile(themeFile.readText()) else null
        } catch (_: Exception) { null }
    }

    val baseScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val colorScheme = if (customTheme != null && !darkTheme) {
        baseScheme.copy(
            primary = customTheme.primary,
            onPrimary = customTheme.onPrimary,
            primaryContainer = customTheme.primaryContainer,
            onPrimaryContainer = customTheme.onPrimaryContainer,
            surface = customTheme.surface,
            onSurface = customTheme.textPrimary,
            background = customTheme.surface,
            surfaceVariant = customTheme.surfaceVariant,
            onSurfaceVariant = customTheme.textSecondary,
            surfaceContainerLowest = customTheme.surface,
            surfaceContainerLow = customTheme.surface,
            surfaceContainer = customTheme.container,
            surfaceContainerHigh = customTheme.containerHigh,
            surfaceContainerHighest = customTheme.containerHigh,
            outline = customTheme.border,
            outlineVariant = customTheme.surfaceVariant,
        )
    } else baseScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

/**
 * Parsed custom theme from theme.md — light mode only.
 * When darkTheme is active, custom theme is ignored and DarkColorScheme is used as-is.
 */
private data class CustomTheme(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val surface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val surfaceVariant: Color,
    val container: Color,
    val containerHigh: Color,
    val border: Color,
)

/** Read hex from markdown table. Format: `| primary | \`#0E4397\` | ...` */
private fun readThemeHex(content: String, key: String, default: Color): Color {
    val m = Regex("""$key.*?#([0-9A-Fa-f]{6})""").find(content)
    return m?.groupValues?.get(1)?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: default
}

/**
 * Parse theme.md into CustomTheme (light-mode only).
 *
 * The Agent can set 3 values:
 *   primary      — brand color
 *   surface      — page background
 *   containerLight — card/container background
 *
 * All other colors (text, borders, containers) are derived automatically
 * from these 3 values. Dark mode colors are ignored — dark mode always
 * uses the default DarkColorScheme.
 */
private fun parseThemeFile(content: String): CustomTheme? {
    val p = readThemeHex(content, "primary", LightColorScheme.primary)
    val s = readThemeHex(content, "surface", LightColorScheme.surface)
    val c = readThemeHex(content, "containerLight", LightColorScheme.surfaceContainer)
    // If all == defaults → no custom theme set
    if (p == LightColorScheme.primary && s == LightColorScheme.surface && c == LightColorScheme.surfaceContainer) return null
    // Derive all other colors from the 3 base values
    val surfaceDark = s.copy(red = s.red * 0.97f, green = s.green * 0.97f, blue = s.blue * 0.97f)
    val borderColor = s.copy(red = s.red * 0.85f, green = s.green * 0.85f, blue = s.blue * 0.85f)
    val textSec = s.copy(
        red = s.red * 0.35f + 0.4f,
        green = s.green * 0.35f + 0.4f,
        blue = s.blue * 0.35f + 0.4f
    )
    return CustomTheme(
        primary = p,
        onPrimary = Color.White,
        primaryContainer = p.copy(alpha = 0.12f),
        onPrimaryContainer = p,
        surface = s,
        textPrimary = Color(0xFF1D2129),
        textSecondary = textSec,
        surfaceVariant = surfaceDark,
        container = c,
        containerHigh = c.copy(red = c.red * 0.95f, green = c.green * 0.95f, blue = c.blue * 0.95f),
        border = borderColor,
    )
}

/**
 * Theme-aware color references — always read current MaterialTheme.colorScheme
 * instead of hardcoded ArcoColors. Use these in @Composable functions.
 */
object ThemeColors {
    val surface: Color @Composable get() = MaterialTheme.colorScheme.surface
    val onSurface: Color @Composable get() = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val primary: Color @Composable get() = MaterialTheme.colorScheme.primary
    val primaryContainer: Color @Composable get() = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer: Color @Composable get() = MaterialTheme.colorScheme.onPrimaryContainer
    val surfaceContainer: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainer
    val surfaceContainerHigh: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh
    val surfaceContainerHighest: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest
    val surfaceVariant: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
    val outline: Color @Composable get() = MaterialTheme.colorScheme.outline
    val error: Color @Composable get() = MaterialTheme.colorScheme.error
    val errorContainer: Color @Composable get() = MaterialTheme.colorScheme.errorContainer

    // Semantic aliases
    val bgPrimary: Color @Composable get() = surface
    val bgSecondary: Color @Composable get() = surfaceVariant
    val bgCard: Color @Composable get() = surfaceContainer
    val bgCardHigh: Color @Composable get() = surfaceContainerHigh
    val textPrimary: Color @Composable get() = onSurface
    val textSecondary: Color @Composable get() = onSurfaceVariant
    val border: Color @Composable get() = outline

    // Brand (always from primary — theme-aware)
    val brand: Color @Composable get() = primary
    val brandContainer: Color @Composable get() = primaryContainer
}
