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
 */
private val DarkColorScheme = darkColorScheme(
    primary = ArcoColors.Blue4,
    onPrimary = ArcoColors.Gray10,
    primaryContainer = ArcoColors.Blue8,
    onPrimaryContainer = ArcoColors.Blue2,

    secondary = ArcoColors.Gray5,
    onSecondary = ArcoColors.Gray10,
    secondaryContainer = ArcoColors.Gray8,
    onSecondaryContainer = ArcoColors.Gray2,

    tertiary = ArcoColors.Pink4,
    onTertiary = ArcoColors.Gray10,
    tertiaryContainer = ArcoColors.Pink8,
    onTertiaryContainer = ArcoColors.Pink2,

    error = ArcoColors.Red4,
    onError = ArcoColors.Gray10,
    errorContainer = ArcoColors.Red8,
    onErrorContainer = ArcoColors.Red2,

    background = ArcoColors.Gray10,
    onBackground = Color(0xFFE5E6EB),

    surface = ArcoColors.Gray9,
    onSurface = Color(0xFFE5E6EB),
    surfaceVariant = ArcoColors.Gray8,
    onSurfaceVariant = ArcoColors.Gray4,
    surfaceContainerLowest = ArcoColors.Gray10,
    surfaceContainerLow = ArcoColors.Gray9,
    surfaceContainer = ArcoColors.Gray8,
    surfaceContainerHigh = ArcoColors.Gray7,
    surfaceContainerHighest = ArcoColors.Gray6,

    outline = ArcoColors.Gray7,
    outlineVariant = ArcoColors.Gray8,
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
    val colorScheme = if (customTheme != null) {
        baseScheme.copy(
            primary = customTheme.primary,
            onPrimary = customTheme.onPrimary,
            primaryContainer = customTheme.primaryContainer,
            onPrimaryContainer = customTheme.onPrimaryContainer,
            surface = customTheme.surface,
            background = customTheme.surface,
            surfaceVariant = customTheme.surfaceVariant,
            surfaceContainer = customTheme.container,
            surfaceContainerHigh = customTheme.container,
        )
    } else baseScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

/** Parsed custom theme from theme.md. Null fields → use defaults. */
private data class CustomTheme(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val container: Color,
)

/** Read hex from markdown table. Format: `| primary | \`#0E4397\` | ...` */
private fun readThemeHex(content: String, key: String, default: Color): Color {
    val m = Regex("""$key.*?#([0-9A-Fa-f]{6})""").find(content)
    return m?.groupValues?.get(1)?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: default
}

private fun parseThemeFile(content: String): CustomTheme? {
    val p = readThemeHex(content, "primary", LightColorScheme.primary)
    val s = readThemeHex(content, "surface", LightColorScheme.surface)
    val c = readThemeHex(content, "containerLight", LightColorScheme.surfaceContainer)
    // If primary == default and surface == default → no custom theme set
    if (p == LightColorScheme.primary && s == LightColorScheme.surface) return null
    return CustomTheme(
        primary = p,
        onPrimary = Color.White,
        primaryContainer = p.copy(alpha = 0.12f),
        onPrimaryContainer = p,
        surface = s,
        surfaceVariant = s.copy(red = s.red * 0.94f, green = s.green * 0.94f, blue = s.blue * 0.94f),
        container = c,
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
