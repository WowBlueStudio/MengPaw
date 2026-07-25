// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.browser.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Centralized icon references for MP Browser.
 * All icons use Material Icons — no custom drawables needed at runtime.
 * Organized by semantic category for easy discovery.
 */
object BrowserIcons {
    // Navigation
    val Back = Icons.Default.ArrowBack
    val Forward = Icons.Default.ArrowForward
    val Refresh = Icons.Default.Refresh
    val Home = Icons.Default.Home
    val Close = Icons.Default.Close

    // Tab management
    val Tabs = Icons.Default.Tab
    val NewTab = Icons.Default.Add
    val CloseTab = Icons.Default.Close

    // Actions
    val Search = Icons.Default.Search
    val Menu = Icons.Default.MoreVert
    val Share = Icons.Default.Share
    val Send = Icons.Default.Send

    // Find & Read
    val FindPage = Icons.Default.FindInPage
    val ReaderMode = Icons.Default.ChromeReaderMode
    val Translate = Icons.Default.Translate

    // Security & Settings
    val Shield = Icons.Default.Shield
    val Lock = Icons.Default.Lock
    val Settings = Icons.Default.Settings
    val History = Icons.Default.History
    val Password = Icons.Default.Password

    // Media
    val Image = Icons.Default.Image
    val Download = Icons.Default.Download

    // Status indicators
    val Loading = Icons.Default.HourglassEmpty
    val Check = Icons.Default.Check
    val Error = Icons.Default.Error
    val Warning = Icons.Default.Warning
}

/**
 * Semantic icons organized as extension properties for common browser toolbar actions.
 */
object BrowserToolbarIcons {
    val navigateBack: ImageVector get() = Icons.Default.ArrowBack
    val navigateForward: ImageVector get() = Icons.Default.ArrowForward
    val refresh: ImageVector get() = Icons.Default.Refresh
    val home: ImageVector get() = Icons.Default.Home
    val tabs: ImageVector get() = Icons.Default.Tab
    val newTab: ImageVector get() = Icons.Default.Add
    val menu: ImageVector get() = Icons.Default.MoreVert
    val find: ImageVector get() = Icons.Default.FindInPage
    val reader: ImageVector get() = Icons.Default.ChromeReaderMode
    val translate: ImageVector get() = Icons.Default.Translate
    val history: ImageVector get() = Icons.Default.History
    val download: ImageVector get() = Icons.Default.Download
    val settings: ImageVector get() = Icons.Default.Settings
    val share: ImageVector get() = Icons.Default.Share
}
