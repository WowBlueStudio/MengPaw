// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser

import android.content.Intent
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.mengpaw.browser.data.BrowserPrefs
import com.mengpaw.browser.data.DetectedImage
import com.mengpaw.browser.data.HistoryStore
import com.mengpaw.browser.data.SearchEngine
import com.mengpaw.browser.data.TabState
import com.mengpaw.browser.ui.BrowserAgentSettingsDialog
import com.mengpaw.browser.ui.BrowserBookmarkDialog
import com.mengpaw.browser.ui.BrowserFindBar
import com.mengpaw.browser.ui.BrowserHistoryDialog
import com.mengpaw.browser.ui.BrowserImagePickerDialog
import com.mengpaw.browser.ui.BrowserMarkdownViewerDialog
import com.mengpaw.browser.ui.BrowserPasswordDialog
import com.mengpaw.browser.ui.BrowserReaderMode
import com.mengpaw.browser.ui.BrowserSettingsDialog
import com.mengpaw.browser.ui.BrowserTabDialog
import com.mengpaw.browser.ui.BrowserTranslateDialog

/**
 * 浏览器全屏对话框渲染层（自 BrowserActivity 拆出 — 400 行文件拆分批次 2）。
 * Settings/Agent 设置/历史/密码/翻译/图片选择/查找/阅读模式/标签页/书签/Markdown 预览。
 * 全部参数显式传入 (状态提升) — 仅渲染, 不改任何状态, 回调由调用方注入。
 */
@Composable
internal fun BrowserAppDialogs(
    prefs: BrowserPrefs,
    webViewMap: Map<Int, WebView>,
    activeTab: TabState,
    activeTabId: Int,
    tabs: List<TabState>,
    isWide: Boolean,
    isColdStart: Boolean,
    maxTabs: Int,
    historyStore: HistoryStore,
    historyEnabled: Boolean,
    images: List<DetectedImage>,
    // ── Settings dialog ──
    showSettings: Boolean, onDismissSettings: () -> Unit,
    adBlockEnabled: Boolean, onAdBlockToggled: (Boolean) -> Unit,
    darkMode: Boolean, onDarkModeToggled: (Boolean) -> Unit,
    mcpOpenMode: Boolean, onMcpOpenModeToggled: (Boolean) -> Unit,
    searchEngine: SearchEngine, onDefaultEngineChanged: (SearchEngine) -> Unit,
    webViewVersion: String, onOpenCoolApk: () -> Unit, onOpenApkCombo: () -> Unit,
    // ── Agent Collaboration Settings ──
    showAgentSettings: Boolean, onDismissAgentSettings: () -> Unit,
    quickClickEnabled: Boolean, onQuickClickToggled: (Boolean) -> Unit,
    autoInjectBridge: Boolean, onAutoInjectToggled: (Boolean) -> Unit,
    screenshotMaxH: Int, onScreenshotMaxHChanged: (Int) -> Unit,
    screenshotQuality: Int, onScreenshotQualityChanged: (Int) -> Unit,
    // ── History dialog ──
    showHistory: Boolean, onDismissHistory: () -> Unit, onHistoryEnabledToggle: (Boolean) -> Unit,
    // ── Password dialog ──
    showPasswords: Boolean, onDismissPasswords: () -> Unit,
    // ── Translate dialog ──
    showTranslate: Boolean, onDismissTranslate: () -> Unit,
    // ── Image picker ──
    showImages: Boolean, onDismissImages: () -> Unit,
    // ── Find-in-page bar / Reader mode ──
    showFind: Boolean, onDismissFind: () -> Unit,
    showReader: Boolean, onDismissReader: () -> Unit,
    // ── Tab dialog (phone) ──
    showTabs: Boolean, onDismissTabs: () -> Unit,
    onTabSelected: (Int, Boolean) -> Unit, onTabClose: (Int) -> Unit, onNewTab: () -> Unit,
    // ── Bookmarks / Markdown viewer ──
    showBookmarks: Boolean, onDismissBookmarks: () -> Unit,
    onNavigate: (String) -> Unit,
    showMdViewer: Boolean, mdContent: String, onDismissMdViewer: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // ── Settings dialog ──
    BrowserSettingsDialog(
        visible = showSettings,
        onDismiss = onDismissSettings,
        prefs = prefs,
        adBlockEnabled = adBlockEnabled,
        onAdBlockToggled = onAdBlockToggled,
        darkMode = darkMode,
        onDarkModeToggled = onDarkModeToggled,
        mcpOpenMode = mcpOpenMode,
        onMcpOpenModeToggled = onMcpOpenModeToggled,
        searchEngine = searchEngine,
        onDefaultEngineChanged = onDefaultEngineChanged,
        webViewVersion = remember {
            try { WebView.getCurrentWebViewPackage()?.versionName ?: "" } catch (_: Exception) { "" }
        },
        onOpenCoolApk = onOpenCoolApk,
        onOpenApkCombo = onOpenApkCombo
    )

    // ── Agent Collaboration Settings ──
    BrowserAgentSettingsDialog(
        visible = showAgentSettings,
        onDismiss = onDismissAgentSettings,
        prefs = prefs,
        quickClickEnabled = quickClickEnabled,
        autoInjectBridge = autoInjectBridge,
        screenshotMaxH = screenshotMaxH,
        screenshotQuality = screenshotQuality,
        onQuickClickToggled = onQuickClickToggled,
        onAutoInjectToggled = onAutoInjectToggled,
        onScreenshotMaxHChanged = onScreenshotMaxHChanged,
        onScreenshotQualityChanged = onScreenshotQualityChanged
    )

    // ── History dialog ──
    BrowserHistoryDialog(
        visible = showHistory,
        onDismiss = onDismissHistory,
        historyStore = historyStore,
        historyEnabled = historyEnabled,
        onHistoryEnabledToggle = onHistoryEnabledToggle,
        onNavigate = onNavigate
    )

    // ── Password dialog ──
    BrowserPasswordDialog(
        visible = showPasswords,
        onDismiss = onDismissPasswords,
        prefs = prefs
    )

    // ── Translate dialog ──
    BrowserTranslateDialog(
        visible = showTranslate,
        onDismiss = onDismissTranslate,
        activeTab = activeTab,
        webView = webViewMap[activeTabId]
    )

    // ── Image picker ──
    BrowserImagePickerDialog(
        visible = showImages && images.isNotEmpty(),
        onDismiss = onDismissImages,
        images = images,
        ctx = ctx
    )

    // ── Find-in-page bar ──
    BrowserFindBar(
        webView = webViewMap[activeTabId],
        visible = showFind && !isColdStart,
        onDismiss = onDismissFind
    )

    // ── Reader mode dialog ──
    BrowserReaderMode(
        webView = webViewMap[activeTabId],
        pageTitle = activeTab.title.ifBlank { activeTab.url },
        visible = showReader,
        onDismiss = onDismissReader
    )

    // ── Tab dialog (phone) ──
    if (!isWide) {
        BrowserTabDialog(
            visible = showTabs,
            onDismiss = onDismissTabs,
            tabs = tabs,
            activeTabId = activeTabId,
            webViewMap = webViewMap,
            prefs = prefs,
            onTabSelected = onTabSelected,
            onTabClose = onTabClose,
            onNewTab = onNewTab,
            maxTabs = maxTabs
        )
    }

    // ── Bookmarks ──
    BrowserBookmarkDialog(
        visible = showBookmarks,
        onDismiss = onDismissBookmarks,
        prefs = prefs,
        currentUrl = activeTab.url,
        onNavigate = onNavigate
    )

    // ── Markdown viewer ──
    BrowserMarkdownViewerDialog(
        visible = showMdViewer && mdContent.isNotBlank(),
        onDismiss = onDismissMdViewer,
        content = mdContent
    )
}
