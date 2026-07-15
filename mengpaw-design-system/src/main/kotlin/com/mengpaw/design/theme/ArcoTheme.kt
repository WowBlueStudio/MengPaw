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
