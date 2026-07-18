package com.mengpaw.shell.ui

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)

/**
 * Compute a Material 3 WindowSizeClass from the current screen dimensions.
 * Adapts to configuration changes (rotation, folding, multi-window).
 */
@Composable
fun currentWindowSizeClass(): WindowSizeClass {
    val config = LocalConfiguration.current
    val widthDp = config.screenWidthDp.dp
    val heightDp = config.screenHeightDp.dp
    return WindowSizeClass.calculateFromSize(DpSize(widthDp, heightDp))
}

/** Convenience: is the window compact (phone portrait)? */
@Composable
fun isCompact(): Boolean =
    currentWindowSizeClass().widthSizeClass == WindowWidthSizeClass.Compact

/** Convenience: is the window medium (phone landscape / small tablet)? */
@Composable
fun isMedium(): Boolean =
    currentWindowSizeClass().widthSizeClass == WindowWidthSizeClass.Medium

/** Convenience: is the window expanded (tablet / desktop)? */
@Composable
fun isExpanded(): Boolean =
    currentWindowSizeClass().widthSizeClass == WindowWidthSizeClass.Expanded

/** Convenience: is there room for a dual-pane layout? */
@Composable
fun isWide(): Boolean =
    currentWindowSizeClass().widthSizeClass >= WindowWidthSizeClass.Medium

/** Max content width for readable text on large screens. */
const val MAX_CONTENT_WIDTH = 720
