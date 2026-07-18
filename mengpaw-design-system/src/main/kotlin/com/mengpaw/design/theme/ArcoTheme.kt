// SPDX-FileCopyrightText: 2026 MengPaw
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.design.theme

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

    tertiary = ArcoColors.Green6,
    onTertiary = ArcoColors.TextInverse,
    tertiaryContainer = ArcoColors.Green1,
    onTertiaryContainer = ArcoColors.Green9,

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

    tertiary = ArcoColors.Green4,
    onTertiary = ArcoColors.Gray10,
    tertiaryContainer = ArcoColors.Green8,
    onTertiaryContainer = ArcoColors.Green2,

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
 */
@Composable
fun ArcoTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
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
