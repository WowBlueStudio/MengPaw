// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mengpaw.browser.data.BrowserPrefs
import com.mengpaw.browser.data.DetectedImage
import com.mengpaw.browser.data.HistoryStore
import com.mengpaw.browser.data.TabState
import com.mengpaw.browser.util.smartNavigate
import com.mengpaw.browser.web.createWebView
import com.mengpaw.browser.ui.BrowserTopBar
import com.mengpaw.browser.ui.DesktopTabBar
import com.mengpaw.browser.ui.NewTabPage
import com.mengpaw.browser.ui.theme.BrowserThemeConfig
import com.mengpaw.design.theme.ThemeColors
import kotlinx.coroutines.launch

/**
 * 浏览器主 UI（自 BrowserActivity 拆出 — 400 行文件拆分批次 2）。
 * 状态 + 逻辑 + Scaffold 骨架; 对话框渲染委托 [BrowserAppDialogs], 内容区委托 [BrowserContentArea]。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material.ExperimentalMaterialApi::class)
@Composable
fun BrowserApp(initialUrl: String? = null, initialMdContent: String? = null) {
    val ctx = LocalContext.current
    val prefs = remember { BrowserPrefs(ctx) }
    val isWide = LocalConfiguration.current.screenWidthDp >= 600
    val maxTabs = 5
    // Scroll-aware toolbar animation
    var scrollOffset by remember { mutableStateOf(0) }
    val showToolbar = isWide || scrollOffset < 200

    // Restore tabs from previous session, or start fresh
    var tabs by remember {
        val savedUrls = prefs.savedTabUrls
        val savedActive = prefs.savedActiveTabId
        if (initialUrl == null && savedUrls.isNotEmpty()) {
            mutableStateOf(savedUrls.mapIndexed { i, url ->
                TabState(id = i, url = url)
            })
        } else {
            mutableStateOf(listOf(TabState(id = 0, url = initialUrl ?: "")))
        }
    }
    var activeTabId by remember {
        val savedUrls = prefs.savedTabUrls
        val savedActive = prefs.savedActiveTabId
        if (initialUrl == null && savedUrls.isNotEmpty() && savedActive in savedUrls.indices) {
            mutableStateOf(savedActive)
        } else {
            mutableStateOf(0)
        }
    }
    var isColdStart by remember { mutableStateOf(initialUrl == null && prefs.savedTabUrls.isEmpty()) }

    // Persist tab session on every change
    LaunchedEffect(tabs.map { it.url }, activeTabId) {
        prefs.savedTabUrls = tabs.filter { it.url.isNotBlank() }.map { it.url }
        prefs.savedActiveTabId = activeTabId
    }
    var showUrlBar by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(showToolbar) }
    var searchQuery by remember { mutableStateOf("") }
    var showImages by remember { mutableStateOf(false) }
    var images by remember { mutableStateOf<List<DetectedImage>>(emptyList()) }
    var showTabs by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showPasswords by remember { mutableStateOf(false) }
    var showTranslate by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var showReader by remember { mutableStateOf(false) }
    var showMdViewer by remember { mutableStateOf(false) }
    var mdContent by remember { mutableStateOf("") }
    var showBookmarks by remember { mutableStateOf(false) }
    var historyEnabled by remember { mutableStateOf(prefs.historyEnabled) }

    // Auto-open Markdown viewer if launched with .md file
    LaunchedEffect(initialMdContent) {
        if (!initialMdContent.isNullOrBlank()) {
            mdContent = initialMdContent
            showMdViewer = true
        }
    }
    val historyStore = remember { HistoryStore(ctx) }
    var searchEngine by remember { mutableStateOf(prefs.defaultEngine()) }
    var adBlockEnabled by remember { mutableStateOf(prefs.adBlockEnabled) }
    var darkMode by remember { mutableStateOf(prefs.darkMode) }
    var quickClickEnabled by remember { mutableStateOf(prefs.quickClickEnabled) }
    var autoInjectBridge by remember { mutableStateOf(prefs.autoInjectBridge) }
    var screenshotMaxH by remember { mutableStateOf(prefs.screenshotMaxHeight) }
    var screenshotQuality by remember { mutableStateOf(prefs.screenshotQuality) }
    var showAgentSettings by remember { mutableStateOf(false) }
    val webViewMap = remember { mutableMapOf<Int, WebView>() }
    // Sync WebView map to Activity for system back-key navigation
    // P1 fix: 同步浏览器状态桥 — 内置 browser.* 命令 (BuiltinBrowserPlugin) 经它操作真实 tab 状态
    SideEffect {
        val activity = ctx as? BrowserActivity ?: return@SideEffect
        activity.webViewMapRef = webViewMap
        activity.browserState = BrowserActivity.createStateBridge(
            tabs = { tabs }, setTabs = { tabs = it },
            activeTabId = { activeTabId }, setActiveTabId = { activeTabId = it },
            setIsColdStart = { isColdStart = it },
            webViewMap = webViewMap, maxTabs = maxTabs
        )
    }

    val activeTab = tabs.find { it.id == activeTabId } ?: tabs.first()

    // Inject dark mode CSS after page loads (URL change + not loading + darkMode on)
    LaunchedEffect(activeTab.url, activeTab.isLoading, darkMode) {
        if (darkMode && !activeTab.isLoading && activeTab.url.isNotBlank()) {
            kotlinx.coroutines.delay(300)
            webViewMap[activeTabId]?.evaluateJavascript(DARK_MODE_CSS, null)
        }
    }

    // System back key: WebView history → close tab → return to Shell
    // P1 fix: 安全销毁 (先脱离视图树再 destroy) — Activity 的 destroyWebViewSafe 是
    // private, BrowserApp (类外 top-level) 不可访问, 此处内联同等逻辑
    val destroyWv: (android.webkit.WebView?) -> Unit = { w ->
        if (w != null) {
            try { (w.parent as? android.view.ViewGroup)?.removeView(w) } catch (_: Exception) {}
            try { w.stopLoading(); w.destroy() } catch (_: Exception) {}
        }
    }
    val handleBack: () -> Unit = {
        val wv = webViewMap[activeTabId]
        if (wv?.canGoBack() == true) { wv.goBack() }
        else {
            val remaining = tabs.filter { it.id != activeTabId }
            if (remaining.isNotEmpty()) {
                destroyWv(wv); webViewMap.remove(activeTabId)
                tabs = remaining; activeTabId = remaining.first().id; isColdStart = false
            } else if (!isColdStart) {
                webViewMap.values.forEach { destroyWv(it) }; webViewMap.clear()
                tabs = listOf(TabState(id = 0)); activeTabId = 0; isColdStart = true
            } else {
                try { ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage("com.mengpaw.shell")); (ctx as? BrowserActivity)?.finish() }
                catch (_: Exception) { (ctx as? BrowserActivity)?.finish() }
            }
        }
    }
    val navigate = { input: String ->
        val final = smartNavigate(input, searchEngine)
        if (final.isNotBlank()) {
            tabs = tabs.map { if (it.id == activeTabId) it.copy(url = final) else it }
            showUrlBar = false; isColdStart = false
            if (historyEnabled) historyStore.record(final, final.take(60))
            webViewMap[activeTabId]?.loadUrl(final)
        }
    }

    /**
     * P2 fix: 统一开新 tab 入口 — maxTabs=5 上限真正生效。
     * 此前 DesktopTabBar/BrowserTabDialog 只在 UI 上隐藏 "+" 按钮, TopBar 菜单 "新标签页"
     * 与 Agent 桥 (browser.mcp.invoke tab/new) 可无限开 tab。达上限时提示, 不静默创建。
     */
    val openNewTab: () -> Unit = {
        if (tabs.size >= maxTabs) {
            Toast.makeText(ctx, "标签页已达上限 ($maxTabs)，请先关闭部分标签页", Toast.LENGTH_SHORT).show()
        } else {
            val newId = (tabs.maxOfOrNull { it.id } ?: 0) + 1
            tabs = tabs + TabState(id = newId)
            activeTabId = newId
            isColdStart = true
        }
    }

    val updateTab = { id: Int, update: (TabState) -> TabState ->
        tabs = tabs.map { if (it.id == id) update(it) else it }
    }

    DisposableEffect(Unit) {
        val activity = ctx as? BrowserActivity
        activity?.onSystemBack = handleBack
        activity?.onOpenUrl = { url -> if (url != null) navigate(url) }
        activity?.onOpenMd = { title, url, md ->
            mdContent = md
            showMdViewer = true
        }
        onDispose {
            activity?.onSystemBack = null
            activity?.onOpenUrl = null
            activity?.onOpenMd = null
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (!isColdStart) {
                BrowserTopBar(
                    visible = showToolbar || showControls,
                    showUrlBar = showUrlBar,
                    onShowUrlBarChange = { showUrlBar = it },
                    isWide = isWide,
                    activeTab = activeTab,
                    activeTabId = activeTabId,
                    tabs = tabs,
                    adBlockEnabled = adBlockEnabled,
                    isBookmarked = prefs.isBookmarked(activeTab.url),
                    webViewMap = webViewMap,
                    homeUrl = prefs.homeUrl,
                    onNavigate = { navigate(it) },
                    onBack = handleBack,
                    onShowTabs = { showTabs = !showTabs },
                    onShowBookmarks = { showBookmarks = true },
                    onRefresh = { webViewMap[activeTabId]?.reload() },
                    onGoForward = { webViewMap[activeTabId]?.goForward() },
                    onGoBack = { webViewMap[activeTabId]?.goBack() },
                    onNewTab = openNewTab,
                    onCloseTab = {
                        tabs = tabs.filter { it.id != activeTabId }
                        // P1 fix: 先脱离视图树再 destroy (attached destroy 风险)
                        webViewMap.remove(activeTabId)?.let { wv ->
                            try { (wv.parent as? android.view.ViewGroup)?.removeView(wv) } catch (_: Exception) {}
                            try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
                        }
                        activeTabId = tabs.first().id
                    },
                    onShowTranslate = { showTranslate = true },
                    onShowFind = { showFind = true },
                    onShowReader = { showReader = true },
                    onAdBlockToggle = {
                        adBlockEnabled = !adBlockEnabled
                        prefs.adBlockEnabled = adBlockEnabled
                        webViewMap[activeTabId]?.reload()
                    },
                    onShowHistory = { showHistory = true },
                    onShowPasswords = { showPasswords = true },
                    onShare = { url ->
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, url)
                        }
                        ctx.startActivity(Intent.createChooser(intent, "分享到"))
                    },
                    onSendToAgent = { url -> (ctx as? BrowserActivity)?.sendToAgent(url, activeTab.title, extract = false) },
                    onExtractToAgent = { url, title -> (ctx as? BrowserActivity)?.sendToAgent(url, title, extract = true) },
                    onShowSettings = { showSettings = true },
                    onShowAgentSettings = { showAgentSettings = true }
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ── Desktop tab bar ──
            if (isWide && !isColdStart) {
                DesktopTabBar(
                    tabs = tabs,
                    activeTabId = activeTabId,
                    maxTabs = maxTabs,
                    webViewMap = webViewMap,
                    prefs = prefs,
                    onTabSelected = { id ->
                        activeTabId = id
                        isColdStart = tabs.find { it.id == id }?.url.isNullOrBlank() ?: true
                    },
                    onTabClose = { id ->
                        // P1 fix: 先脱离视图树再 destroy (attached destroy 风险)
                        webViewMap.remove(id)?.let { wv ->
                            try { (wv.parent as? android.view.ViewGroup)?.removeView(wv) } catch (_: Exception) {}
                            try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
                        }
                        tabs = tabs.filter { it.id != id }
                        if (activeTabId == id) activeTabId = tabs.first().id
                    },
                    onNewTab = openNewTab
                )
            }
            // ── Loader (material-colored progress bar) ──
            AnimatedVisibility(visible = activeTab.isLoading && !isColdStart, enter = fadeIn(), exit = fadeOut()) {
                LinearProgressIndicator(
                    { activeTab.progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = ThemeColors.brand,
                    trackColor = ThemeColors.brand.copy(alpha = 0.12f)
                )
            }
            // ── Content (NewTabPage / 多标签 WebView — 实现见 BrowserContentArea) ──
            BrowserContentArea(
                isColdStart = isColdStart,
                tabs = tabs,
                activeTab = activeTab,
                activeTabId = activeTabId,
                webViewMap = webViewMap,
                isWide = isWide,
                adBlockEnabled = adBlockEnabled,
                autoInjectBridge = autoInjectBridge,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                searchEngine = searchEngine,
                onSearchEngineCycle = {
                    val engines = prefs.enabledEngines()
                    if (engines.isNotEmpty()) {
                        val idx = engines.indexOfFirst { it.key == searchEngine.key }
                        searchEngine = engines.getOrElse((idx + 1) % engines.size) { engines.first() }
                        prefs.setDefaultEngine(searchEngine)
                    }
                },
                prefs = prefs,
                updateTab = updateTab,
                onImagesDetected = { imgs -> images = imgs; showImages = true },
                onScroll = { dy -> scrollOffset = (scrollOffset + dy).coerceIn(0, 500) },
                onNavigate = { navigate(it) },
                onShowBookmarks = { showBookmarks = true },
                onShowMarkdown = { text -> mdContent = text; showMdViewer = true }
            )
            // ── 全屏对话框层 (Settings/历史/密码/翻译/图片/查找/阅读/标签页/书签/Markdown) ──
            BrowserAppDialogs(
                prefs = prefs,
                webViewMap = webViewMap,
                activeTab = activeTab,
                activeTabId = activeTabId,
                tabs = tabs,
                isWide = isWide,
                isColdStart = isColdStart,
                maxTabs = maxTabs,
                historyStore = historyStore,
                historyEnabled = historyEnabled,
                images = images,
                // Settings dialog
                showSettings = showSettings, onDismissSettings = { showSettings = false },
                adBlockEnabled = adBlockEnabled, onAdBlockToggled = { adBlockEnabled = it; prefs.adBlockEnabled = it },
                darkMode = darkMode, onDarkModeToggled = { darkMode = it; prefs.darkMode = it; webViewMap[activeTabId]?.reload() },
                searchEngine = searchEngine, onDefaultEngineChanged = { searchEngine = it },
                webViewVersion = remember {
                    try { WebView.getCurrentWebViewPackage()?.versionName ?: "" } catch (_: Exception) { "" }
                },
                onOpenCoolApk = {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.coolapk.com/apk/com.google.android.webview"))
                    try { ctx.startActivity(intent) } catch (_: Exception) { }
                },
                onOpenApkCombo = {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://apkcombo.com/zh/android-system-webview/com.google.android.webview/"))
                    try { ctx.startActivity(intent) } catch (_: Exception) { }
                },
                // Agent Collaboration Settings
                showAgentSettings = showAgentSettings, onDismissAgentSettings = { showAgentSettings = false },
                quickClickEnabled = quickClickEnabled, onQuickClickToggled = { quickClickEnabled = it; prefs.quickClickEnabled = it },
                autoInjectBridge = autoInjectBridge, onAutoInjectToggled = { autoInjectBridge = it; prefs.autoInjectBridge = it },
                screenshotMaxH = screenshotMaxH, onScreenshotMaxHChanged = { screenshotMaxH = it; prefs.screenshotMaxHeight = it },
                screenshotQuality = screenshotQuality, onScreenshotQualityChanged = { screenshotQuality = it; prefs.screenshotQuality = it },
                // History dialog
                showHistory = showHistory, onDismissHistory = { showHistory = false },
                onHistoryEnabledToggle = { historyEnabled = it; prefs.historyEnabled = it },
                // Password dialog
                showPasswords = showPasswords, onDismissPasswords = { showPasswords = false },
                // Translate dialog
                showTranslate = showTranslate, onDismissTranslate = { showTranslate = false },
                // Image picker
                showImages = showImages, onDismissImages = { showImages = false },
                // Find / Reader
                showFind = showFind, onDismissFind = { showFind = false },
                showReader = showReader, onDismissReader = { showReader = false },
                // Tab dialog (phone)
                showTabs = showTabs, onDismissTabs = { showTabs = false },
                onTabSelected = { id, cold -> activeTabId = id; isColdStart = cold; showTabs = false },
                onTabClose = { id ->
                    // P1 fix: 先脱离视图树再 destroy (attached destroy 风险)
                    webViewMap.remove(id)?.let { wv ->
                        try { (wv.parent as? android.view.ViewGroup)?.removeView(wv) } catch (_: Exception) {}
                        try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
                    }
                    tabs = tabs.filter { it.id != id }
                    if (tabs.isEmpty()) { tabs = listOf(TabState(id = 0)); activeTabId = 0; isColdStart = true; showTabs = false }
                    else if (activeTabId == id) activeTabId = tabs.first().id
                },
                onNewTab = openNewTab,
                // Bookmarks / Markdown viewer
                showBookmarks = showBookmarks, onDismissBookmarks = { showBookmarks = false },
                onNavigate = { navigate(it) },
                showMdViewer = showMdViewer, mdContent = mdContent,
                onDismissMdViewer = { showMdViewer = false; mdContent = "" }
            )
        }
    }
}