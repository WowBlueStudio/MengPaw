// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.MotionEvent
import android.net.http.SslError
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import android.view.KeyEvent
import com.mengpaw.core.AndroidLogger
import com.mengpaw.core.DataPathsInitializer
import com.mengpaw.kernel.KernelLog
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mengpaw.browser.data.BrowserPrefs
import com.mengpaw.browser.data.DetectedImage
import com.mengpaw.browser.data.HistoryStore
import com.mengpaw.browser.data.SearchEngine
import com.mengpaw.browser.data.TabState
import com.mengpaw.browser.plugin.BrowserElement
import com.mengpaw.browser.service.GoogleTranslate
import com.mengpaw.browser.util.downloadImage
import com.mengpaw.browser.util.isAdRequest
import com.mengpaw.browser.util.smartNavigate
import com.mengpaw.browser.web.createWebView
import com.mengpaw.browser.plugin.BrowserPluginRegistry
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
import com.mengpaw.browser.ui.components.SearchEngineLogo
import com.mengpaw.browser.ui.components.TabChip
import com.mengpaw.browser.ui.theme.BrowserThemeConfig
import com.mengpaw.design.components.MarkdownText
import com.mengpaw.design.components.parseMarkdown
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.mengpaw.design.theme.ArcoTheme
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ── Activity ──────────────────────────────────────────────────────

class BrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DataPathsInitializer.initialize(this)
        KernelLog.setLogger(AndroidLogger())
        // Bind shared PluginManager to BrowserPluginRegistry for active-state filtering
        com.mengpaw.browser.plugin.BrowserPluginRegistry.pluginManager =
            com.mengpaw.kernel.plugin.PluginManager.globalInstance
        // Bind WebView + tool executor to BrowserMcpPlugin (via reflection, plugin is optional)
        try {
            val clazz = Class.forName("com.mengpaw.plugin.browsermcp.BrowserMcpPlugin")
            val wvField = clazz.getDeclaredField("webViewProvider")
            wvField.isAccessible = true
            wvField.set(null, { -> webViewMapRef.values.firstOrNull() } as kotlin.jvm.functions.Function0<*>)
            // Tool executor: delegates to instance method
            val self = this
            val executor: (String, Map<String, String>) -> String = { toolName, args ->
                self.executeMcpTool(toolName, args)
            }
            val exField = clazz.getDeclaredField("toolExecutor")
            exField.isAccessible = true
            exField.set(null, executor)
        } catch (_: Exception) { /* plugin not installed, skip */ }
        // Bind Quick Click toggle and screenshot settings to BuiltinBrowserPlugin
        val prefs = BrowserPrefs(this)
        com.mengpaw.browser.plugin.BuiltinBrowserPlugin.quickClickEnabled = { prefs.quickClickEnabled }
        com.mengpaw.browser.plugin.BuiltinBrowserPlugin.screenshotMaxHeight = { prefs.screenshotMaxHeight }
        enableEdgeToEdge()
        // Read theme from first Agent's theme.md (or default)
        val themeConfig = BrowserThemeConfig.load(this)
        val isDark = (resources.configuration.uiMode
            and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        // Check for .md file intent
        val mdFileContent = checkMdFile(intent)
        setContent {
            ArcoTheme(darkTheme = isDark) {
                BrowserApp(initialUrl = extractUrl(intent), initialMdContent = mdFileContent)
            }
        }
    }

    /** MCP tool executor called by BrowserMcpPlugin via toolExecutor delegate. */
    private fun executeMcpTool(toolName: String, args: Map<String, String>): String {
        val wv = webViewMapRef.values.firstOrNull()
            ?: return """{"ok":false,"error":"WebView not available"}"""
        val bridge = com.mengpaw.browser.bridge.BrowserBridge(wv)
        return try {
            when (toolName) {
                "browser_navigate" -> {
                    val url = args["url"] ?: return """{"ok":false,"error":"Missing 'url'"}"""
                    wv.loadUrl(url)
                    // Wait for page to finish loading (max 10s)
                    var waited = 0
                    while (wv.progress < 100 && waited < 100) {
                        Thread.sleep(100); waited++
                    }
                    """{"ok":true}"""
                }
                "browser_screenshot" -> bridge.screenshot()
                "browser_click" -> {
                    val sel = args["selector"] ?: return """{"ok":false,"error":"Missing 'selector'"}"""
                    bridge.click(sel)
                }
                "browser_type" -> bridge.type(args["selector"] ?: "", args["text"] ?: "")
                "browser_extract" -> bridge.content()
                "browser_eval" -> {
                    val script = args["script"] ?: return """{"ok":false,"error":"Missing 'script'"}"""
                    bridge.eval(script)
                }
                else -> """{"ok":false,"error":"Unknown tool: $toolName"}"""
            }
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

    /** Check if the intent carries a Markdown file and return its content, or null. */
    private fun checkMdFile(intent: Intent?): String? {
        if (intent?.action != android.content.Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        // Only handle file:// URIs with .md extension
        if (uri.scheme != "file") return null
        val path = uri.path ?: return null
        if (!path.endsWith(".md", ignoreCase = true) && intent.type != "text/markdown") return null
        return try {
            val file = java.io.File(path)
            if (file.exists() && file.canRead()) file.readText().take(500_000) else null
        } catch (_: Exception) { null }
    }

    private fun extractUrl(intent: Intent?): String? {
        val raw = when {
            intent?.action == "com.mengpaw.action.OPEN_URL" -> intent.getStringExtra("url")
            intent?.dataString != null -> intent.dataString
            else -> null
        }
        // SECURITY: Only allow http/https schemes — block javascript:, file:, content:, etc.
        return if (raw != null && (raw.startsWith("http://") || raw.startsWith("https://"))) raw else null
    }

    /** Back key: delegate to Compose callback which handles tab closing logic. */
    override fun onBackPressed() {
        onSystemBack?.invoke() ?: super.onBackPressed()
    }

    /** Mutable reference to Compose's webViewMap, synced via SideEffect. */
    internal var webViewMapRef: MutableMap<Int, WebView> = mutableMapOf()
    /** System back key callback set by Compose. */
    internal var onSystemBack: (() -> Unit)? = null

    override fun onDestroy() {
        super.onDestroy()
        // CRITICAL: Destroy all WebViews to free native renderer memory
        webViewMapRef.values.forEach { wv ->
            try { wv.stopLoading(); wv.destroy() } catch (_: Exception) { }
        }
        webViewMapRef.clear()
        try { android.webkit.CookieManager.getInstance().flush() } catch (_: Exception) { }
    }
}

// ── Main Browser App ──────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material.ExperimentalMaterialApi::class)
@Composable
fun BrowserApp(initialUrl: String? = null, initialMdContent: String? = null) {
    val ctx = LocalContext.current
    val prefs = remember { BrowserPrefs(ctx) }
    val isWide = LocalConfiguration.current.screenWidthDp >= 600
    val maxTabs = 4

    // Scroll-aware toolbar animation
    var scrollOffset by remember { mutableStateOf(0) }
    val showToolbar = isWide || scrollOffset < 200

    var tabs by remember { mutableStateOf(listOf(TabState(id = 0, url = initialUrl ?: ""))) }
    var activeTabId by remember { mutableStateOf(0) }
    var isColdStart by remember { mutableStateOf(initialUrl == null) }
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
    SideEffect { (ctx as? BrowserActivity)?.webViewMapRef = webViewMap }

    val activeTab = tabs.find { it.id == activeTabId } ?: tabs.first()

    // Inject dark mode CSS after page loads (URL change + not loading + darkMode on)
    LaunchedEffect(activeTab.url, activeTab.isLoading, darkMode) {
        if (darkMode && !activeTab.isLoading && activeTab.url.isNotBlank()) {
            kotlinx.coroutines.delay(300)
            webViewMap[activeTabId]?.evaluateJavascript(DARK_MODE_CSS, null)
        }
    }

    // System back key: WebView history → close tab → return to Shell
    val handleBack: () -> Unit = {
        val wv = webViewMap[activeTabId]
        if (wv?.canGoBack() == true) { wv.goBack() }
        else {
            val remaining = tabs.filter { it.id != activeTabId }
            if (remaining.isNotEmpty()) {
                wv?.destroy(); webViewMap.remove(activeTabId)
                tabs = remaining; activeTabId = remaining.first().id; isColdStart = false
            } else if (!isColdStart) {
                webViewMap.values.forEach { it.destroy() }; webViewMap.clear()
                tabs = listOf(TabState(id = 0)); activeTabId = 0; isColdStart = true
            } else {
                try { ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage("com.mengpaw.shell")); (ctx as? BrowserActivity)?.finish() }
                catch (_: Exception) { (ctx as? BrowserActivity)?.finish() }
            }
        }
    }
    DisposableEffect(Unit) {
        (ctx as? BrowserActivity)?.onSystemBack = handleBack
        onDispose { (ctx as? BrowserActivity)?.onSystemBack = null }
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

    val updateTab = { id: Int, update: (TabState) -> TabState ->
        tabs = tabs.map { if (it.id == id) update(it) else it }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (!isColdStart) {
                AnimatedVisibility(
                    visible = showToolbar || showControls,
                    enter = fadeIn() + slideInVertically(animationSpec = tween(200)),
                    exit = fadeOut() + slideOutVertically(animationSpec = tween(200))
                ) {
                    TopAppBar(
                        title = {
                            if (showUrlBar || isWide) {
                                var editUrl by remember(activeTabId) { mutableStateOf(activeTab.url) }
                                OutlinedTextField(
                                    value = editUrl, onValueChange = { editUrl = it },
                                    modifier = Modifier.fillMaxWidth().height(48.dp), singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                                    shape = RoundedCornerShape(ArcoRadius.round),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = ThemeColors.brand, unfocusedBorderColor = ThemeColors.border
                                    ),
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                                        imeAction = androidx.compose.ui.text.input.ImeAction.Search
                                    ),
                                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                        onSearch = { navigate(editUrl) }
                                    ),
                                    trailingIcon = {
                                        FilledIconButton(onClick = { navigate(editUrl) },
                                            modifier = Modifier.size(32.dp).offset(x = 1.dp), shape = CircleShape,
                                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = ThemeColors.brand)
                                        ) { Icon(Icons.Default.ArrowForward, "→", tint = Color.White, modifier = Modifier.size(16.dp)) }
                                    }
                                )
                            } else {
                                Row(Modifier.fillMaxWidth().clickable { showUrlBar = true },
                                    verticalAlignment = Alignment.CenterVertically) {
                                    if (activeTab.title.isNotBlank()) Column(Modifier.weight(1f)) {
                                        Text(activeTab.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(activeTab.url.take(60), style = MaterialTheme.typography.labelSmall,
                                            color = ThemeColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        },
                        navigationIcon = {
                            Row {
                                if (isWide) {
                                    // Visible nav buttons for keyboard+mouse on tablet
                                    IconButton(onClick = { navigate("https://www.baidu.com") }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Default.Home, "主页", tint = ThemeColors.brand)
                                    }
                                    IconButton(onClick = handleBack, modifier = Modifier.size(36.dp)) {
                                        @Suppress("DEPRECATION")
                                        Icon(Icons.Default.ArrowBack, "后退", tint = if (activeTab.canGoBack) ThemeColors.brand else ThemeColors.brand.copy(alpha = 0.3f))
                                    }
                                    IconButton(onClick = { webViewMap[activeTabId]?.goForward() }, enabled = activeTab.canGoForward, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Default.ArrowForward, "前进", tint = if (activeTab.canGoForward) ThemeColors.brand else ThemeColors.brand.copy(alpha = 0.3f))
                                    }
                                    IconButton(onClick = { webViewMap[activeTabId]?.reload() }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Default.Refresh, "刷新", tint = ThemeColors.brand)
                                    }
                                    Spacer(Modifier.width(4.dp))
                                }
                                // Tab count badge (phone only, hidden when 1 tab)
                                if (!isWide) {
                                    IconButton(onClick = { showTabs = !showTabs }, modifier = Modifier.size(40.dp)) {
                                        if (tabs.size > 1) {
                                            BadgedBox(badge = { Badge(containerColor = ThemeColors.textSecondary) { Text("${tabs.size}") } }) {
                                                Icon(Icons.Default.List, "标签页", tint = ThemeColors.brand)
                                            }
                                        } else {
                                            Icon(Icons.Default.List, "标签页", tint = ThemeColors.brand)
                                        }
                                    }
                                }
                            }
                        },
                        actions = {
                            // Bookmark star
                            val isBm = prefs.isBookmarked(activeTab.url)
                            IconButton(onClick = { showBookmarks = true }, modifier = Modifier.size(36.dp)) {
                                @Suppress("DEPRECATION")
                                Icon(Icons.Default.Star, "收藏夹", tint = if (isBm) ThemeColors.brand else ThemeColors.textSecondary)
                            }
                            // Menu button with dropdown
                            var menuExpanded by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.MoreVert, "菜单", tint = ThemeColors.brand)
                                }
                                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                    DropdownMenuItem(text = { Text("刷新") }, leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                        onClick = { webViewMap[activeTabId]?.reload(); menuExpanded = false })
                                    DropdownMenuItem(text = { Text("新标签页") }, leadingIcon = { Icon(Icons.Default.Add, null) },
                                        onClick = {
                                            val newId = (tabs.maxOfOrNull { it.id } ?: 0) + 1
                                            tabs = tabs + TabState(id = newId); activeTabId = newId; isColdStart = true
                                            menuExpanded = false
                                        })
                                    DropdownMenuItem(text = { Text("翻译页面") }, leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                        enabled = !isColdStart && activeTab.title.isNotBlank(),
                                        onClick = { showTranslate = true; menuExpanded = false })
                                    @Suppress("DEPRECATION")
                                    DropdownMenuItem(text = { Text("页面查找") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                                        enabled = !isColdStart,
                                        onClick = { showFind = true; menuExpanded = false })
                                    @Suppress("DEPRECATION")
                                    DropdownMenuItem(text = { Text("阅读模式") }, leadingIcon = { Icon(Icons.Default.Star, null) },
                                        enabled = !isColdStart,
                                        onClick = { showReader = true; menuExpanded = false })
                                    DropdownMenuItem(
                                        text = { Text(if (adBlockEnabled) "广告拦截: 开" else "广告拦截: 关") },
                                        leadingIcon = { Icon(if (adBlockEnabled) Icons.Default.Star else Icons.Default.Close, null) },
                                        onClick = { adBlockEnabled = !adBlockEnabled; prefs.adBlockEnabled = adBlockEnabled; webViewMap[activeTabId]?.reload(); menuExpanded = false })
                                    DropdownMenuItem(text = { Text("后退") }, leadingIcon = { Icon(Icons.Default.ArrowBack, null) },
                                        enabled = activeTab.canGoBack,
                                        onClick = { webViewMap[activeTabId]?.goBack(); menuExpanded = false })
                                    DropdownMenuItem(text = { Text("前进") }, leadingIcon = { Icon(Icons.Default.ArrowForward, null) },
                                        enabled = activeTab.canGoForward,
                                        onClick = { webViewMap[activeTabId]?.goForward(); menuExpanded = false })
                                    HorizontalDivider()
                                    DropdownMenuItem(text = { Text("历史记录") }, leadingIcon = { Icon(Icons.Default.Star, null) },
                                        onClick = { showHistory = true; menuExpanded = false })
                                    DropdownMenuItem(text = { Text("密码管理") }, leadingIcon = { Icon(Icons.Default.Lock, null) },
                                        onClick = { showPasswords = true; menuExpanded = false })
                                    DropdownMenuItem(text = { Text("分享链接") }, leadingIcon = { Icon(Icons.Default.Share, null) },
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, activeTab.url) }
                                            ctx.startActivity(Intent.createChooser(intent, "分享到"))
                                            menuExpanded = false
                                        })
                                    if (ctx.packageManager.getLaunchIntentForPackage("com.mengpaw.shell") != null) {
                                        DropdownMenuItem(text = { Text("发送给 MengPaw Agent") }, leadingIcon = { Icon(Icons.Default.Send, null) },
                                            onClick = {
                                                val intent = Intent("com.mengpaw.action.OPEN_URL").apply {
                                                    setClassName("com.mengpaw.shell", "com.mengpaw.shell.MainActivity")
                                                    putExtra("url", activeTab.url)
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                                        Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                                }
                                                try { ctx.startActivity(intent) } catch (_: Exception) { Toast.makeText(ctx, "MengPaw 未安装", Toast.LENGTH_SHORT).show() }
                                                menuExpanded = false
                                            })
                                    }
                                    DropdownMenuItem(text = { Text("设置") }, leadingIcon = { Icon(Icons.Default.Settings, null) },
                                        onClick = { showSettings = true; menuExpanded = false })
                                    DropdownMenuItem(text = { Text("智能体协同") }, leadingIcon = { Icon(Icons.Default.SmartToy, null) },
                                        onClick = { showAgentSettings = true; menuExpanded = false })
                                    // Plugin-contributed menu items
                                    val pluginItems = remember { BrowserPluginRegistry.activeMenuItems() }
                                    if (pluginItems.isNotEmpty()) {
                                        HorizontalDivider()
                                        pluginItems.forEach { item ->
                                            DropdownMenuItem(
                                                text = { Text(item.label) },
                                                leadingIcon = { Icon(Icons.Default.Star, null) },
                                                onClick = {
                                                    // FIX B21: Execute plugin command via the current tab's WebView
                                                    item.command?.let { cmd ->
                                                        webViewMap[activeTabId]?.evaluateJavascript(cmd, null)
                                                    }
                                                    menuExpanded = false
                                                }
                                            )
                                        }
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(text = { Text("关闭标签 Close Tab") }, leadingIcon = { Icon(Icons.Default.Close, null) },
                                        enabled = tabs.size > 1,
                                        onClick = {
                                            tabs = tabs.filter { it.id != activeTabId }
            webViewMap.remove(activeTabId)?.destroy()
            activeTabId = tabs.first().id
            menuExpanded = false
                                        })
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // ── Desktop tab bar ──
            if (isWide && !isColdStart) {
                Surface(tonalElevation = 1.dp, color = ThemeColors.bgCardHigh) {
                    Column {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 6.dp, end = 6.dp, top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        var tabMenuTabId by remember { mutableStateOf<Int?>(null) }
                        tabs.forEach { tab ->
                            TabChip(
                                label = tab.title.ifBlank { "新标签页" },
                                selected = tab.id == activeTabId,
                                isLoading = tab.isLoading,
                                onClick = { activeTabId = tab.id; isColdStart = tab.url.isBlank() },
                                onClose = if (tabs.size > 1) {{
                                    webViewMap.remove(tab.id)?.destroy()
                                    tabs = tabs.filter { it.id != tab.id }
                                    if (activeTabId == tab.id) activeTabId = tabs.first().id
                                }} else null,
                                onMenu = { tabMenuTabId = tab.id }
                            )
                            // Per-tab dropdown menu (no emoji)
                            DropdownMenu(expanded = tabMenuTabId == tab.id, onDismissRequest = { tabMenuTabId = null }) {
                                DropdownMenuItem(text = { Text("静音标签") }, onClick = { tabMenuTabId = null })
                                DropdownMenuItem(text = { Text("推送给智能体") }, onClick = {
                                    val intent = Intent("com.mengpaw.action.OPEN_URL").apply {
                                        setClassName("com.mengpaw.shell", "com.mengpaw.shell.MainActivity")
                                        putExtra("url", tab.url)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    }
                                    try { ctx.startActivity(intent) } catch (_: Exception) { }
                                    tabMenuTabId = null
                                })
                                DropdownMenuItem(text = { Text("强制刷新") }, onClick = { webViewMap[tab.id]?.reload(); tabMenuTabId = null })
                                DropdownMenuItem(text = { Text("添加收藏") }, onClick = { prefs.addBookmark(tab.url); tabMenuTabId = null })
                            }
                        }
                        if (tabs.size < maxTabs) {
                            IconButton(onClick = {
                                val newId = (tabs.maxOfOrNull { it.id } ?: 0) + 1
                                tabs = tabs + TabState(id = newId); activeTabId = newId; isColdStart = true
                            }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Add, "新标签", tint = ThemeColors.textSecondary, modifier = Modifier.size(18.dp)) }
                        }
                    }
                    // Seam line below tabs — same color as active tab, bridges to webpage
                    val seamColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF1A1A1A) else Color.White
                    Box(Modifier.fillMaxWidth().height(2.dp).background(seamColor))
                    }
                }
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

            // ── Settings dialog ──
            // ── Settings dialog ──
            BrowserSettingsDialog(
                visible = showSettings,
                onDismiss = { showSettings = false },
                prefs = prefs,
                adBlockEnabled = adBlockEnabled,
                onAdBlockToggled = { adBlockEnabled = it; prefs.adBlockEnabled = it },
                darkMode = darkMode,
                onDarkModeToggled = { darkMode = it; prefs.darkMode = it; webViewMap[activeTabId]?.reload() },
                searchEngine = searchEngine,
                onDefaultEngineChanged = { searchEngine = it },
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
                }
            )

            // ── Agent Collaboration Settings ──
            BrowserAgentSettingsDialog(
                visible = showAgentSettings,
                onDismiss = { showAgentSettings = false },
                prefs = prefs,
                quickClickEnabled = quickClickEnabled,
                autoInjectBridge = autoInjectBridge,
                screenshotMaxH = screenshotMaxH,
                screenshotQuality = screenshotQuality,
                onQuickClickToggled = { quickClickEnabled = it; prefs.quickClickEnabled = it },
                onAutoInjectToggled = { autoInjectBridge = it; prefs.autoInjectBridge = it },
                onScreenshotMaxHChanged = { screenshotMaxH = it; prefs.screenshotMaxHeight = it },
                onScreenshotQualityChanged = { screenshotQuality = it; prefs.screenshotQuality = it }
            )

            // ── History dialog ──
            BrowserHistoryDialog(
                visible = showHistory,
                onDismiss = { showHistory = false },
                historyStore = historyStore,
                historyEnabled = historyEnabled,
                onHistoryEnabledToggle = { historyEnabled = it; prefs.historyEnabled = it },
                onNavigate = { navigate(it) }
            )

            // ── Password dialog ──
            BrowserPasswordDialog(
                visible = showPasswords,
                onDismiss = { showPasswords = false },
                prefs = prefs
            )

            // ── Translate dialog ──
            BrowserTranslateDialog(
                visible = showTranslate,
                onDismiss = { showTranslate = false },
                activeTab = activeTab,
                webView = webViewMap[activeTabId]
            )

            // ── Image picker ──
            BrowserImagePickerDialog(
                visible = showImages && images.isNotEmpty(),
                onDismiss = { showImages = false },
                images = images,
                ctx = ctx
            )

            // ── Content ──
            if (isColdStart) {
                // ── Branded new tab page ──
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top spacer
                    Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "MengPaw 浏览器",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = ThemeColors.textPrimary
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "安全的 Agent 控制浏览器",
                                style = MaterialTheme.typography.bodySmall,
                                color = ThemeColors.textSecondary
                            )
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                    // Search / URL input bar
                    Surface(
                        modifier = Modifier.fillMaxWidth(if (isWide) 0.55f else 0.88f),
                        shape = RoundedCornerShape(ArcoRadius.round),
                        shadowElevation = 2.dp,
                        color = ThemeColors.surface
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                                if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_TAB
                                    && event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                    val engines = prefs.enabledEngines()
                                    if (engines.isNotEmpty()) {
                                        val idx = engines.indexOfFirst { it.key == searchEngine.key }
                                        searchEngine = engines.getOrElse((idx + 1) % engines.size) { engines.first() }
                                        prefs.setDefaultEngine(searchEngine)
                                    }
                                    true
                                } else false
                            },
                            placeholder = { Text("搜索关键词或输入网址...") },
                            leadingIcon = {
                                Box(Modifier.pointerInput(Unit) { detectTapGestures {
                                    val engines = prefs.enabledEngines()
                                    if (engines.isNotEmpty()) {
                                        val idx = engines.indexOfFirst { it.key == searchEngine.key }
                                        searchEngine = engines.getOrElse((idx + 1) % engines.size) { engines.first() }
                                        prefs.setDefaultEngine(searchEngine)
                                    }
                                }}) { Box(Modifier.offset(x = 2.dp)) { SearchEngineLogo(searchEngine, size = 28) } }
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty())
                                    FilledIconButton(onClick = { navigate(searchQuery) }, modifier = Modifier.size(36.dp).offset(x = (-2).dp), shape = CircleShape,
                                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = ThemeColors.brand)
                                    ) { Icon(Icons.Default.ArrowForward, "→", tint = Color.White, modifier = Modifier.size(18.dp)) }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(ArcoRadius.round),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Search
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onSearch = { navigate(searchQuery) }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ThemeColors.brand,
                                unfocusedBorderColor = ThemeColors.brand.copy(alpha = 0.2f)
                            )
                        )
                    }
                    // Dynamic bookmark bar
                    val bmList = prefs.bookmarks
                    if (bmList.isNotEmpty()) {
                        Spacer(Modifier.height(24.dp))
                        BoxWithConstraints(
                            modifier = Modifier.fillMaxWidth(if (isWide) 0.55f else 0.88f)
                        ) {
                            val itemWidth = 72.dp
                            val maxItems = (maxWidth / itemWidth).toInt().coerceAtLeast(1).coerceAtMost(6)
                            val showOverflow = bmList.size > maxItems
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                bmList.take(if (showOverflow) maxItems - 1 else maxItems).forEach { url ->
                                    val domain = url.substringAfter("://").substringBefore("/").take(10)
                                    Surface(
                                        modifier = Modifier.weight(1f).clickable { navigate(url) },
                                        shape = RoundedCornerShape(10.dp),
                                        color = ThemeColors.bgCardHigh
                                    ) {
                                        Text(
                                            domain,
                                            modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            fontSize = 11.sp,
                                            color = ThemeColors.textSecondary,
                                            maxLines = 1
                                        )
                                    }
                                }
                                if (showOverflow) {
                                    Surface(
                                        modifier = Modifier.weight(1f).clickable { showBookmarks = true },
                                        shape = RoundedCornerShape(10.dp),
                                        color = ThemeColors.brand.copy(alpha = 0.1f)
                                    ) {
                                        Text(
                                            "…",
                                            modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            fontSize = 14.sp,
                                            color = ThemeColors.brand,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // Bottom balance spacer
                    Box(Modifier.weight(1f))
                }
            } else {
                // WebView with pull-to-refresh
                val pullState = rememberPullRefreshState(
                    refreshing = activeTab.isLoading,
                    onRefresh = { webViewMap[activeTabId]?.reload() }
                )
                Box(Modifier.weight(1f).pullRefresh(pullState)) {
                    // FIX U47+U48: key() ensures each tab gets its own WebView, and old ones are disposed
                    androidx.compose.runtime.key(activeTabId) {
                        var wvRef by remember { mutableStateOf<WebView?>(null) }
                        AndroidView(
                            factory = { createWebView(it, activeTab, isWide, adBlockEnabled, autoInjectBridge, updateTab, { imgs -> images = imgs; showImages = true }) { dy -> scrollOffset = (scrollOffset + dy).coerceIn(0, 500) } },
                            update = { wv -> wvRef = wv; webViewMap[activeTabId] = wv },
                            modifier = Modifier.fillMaxSize()
                        )
                        // FIX U48: Clean up WebView when tab leaves composition
                        DisposableEffect(activeTabId) {
                            onDispose {
                                wvRef?.let { wv ->
                                    try { wv.stopLoading(); wv.destroy() } catch (_: Exception) { }
                                }
                                webViewMap.remove(activeTabId)
                            }
                        }
                    }
                    PullRefreshIndicator(activeTab.isLoading, pullState, Modifier.align(Alignment.TopCenter))
                }
            }

            // ── Find-in-page bar ──
            BrowserFindBar(
                webView = webViewMap[activeTabId],
                visible = showFind && !isColdStart,
                onDismiss = { showFind = false }
            )

            // ── Reader mode dialog ──
            BrowserReaderMode(
                webView = webViewMap[activeTabId],
                pageTitle = activeTab.title.ifBlank { activeTab.url },
                visible = showReader,
                onDismiss = { showReader = false }
            )

            // ── Tab dialog (phone) ──
            if (!isWide) {
                BrowserTabDialog(
                    visible = showTabs,
                    onDismiss = { showTabs = false },
                    tabs = tabs,
                    activeTabId = activeTabId,
                    webViewMap = webViewMap,
                    prefs = prefs,
                    onTabSelected = { id, cold -> activeTabId = id; isColdStart = cold; showTabs = false },
                    onTabClose = { id ->
                        webViewMap.remove(id)?.destroy()
                        tabs = tabs.filter { it.id != id }
                        if (tabs.isEmpty()) { tabs = listOf(TabState(id = 0)); activeTabId = 0; isColdStart = true; showTabs = false }
                        else if (activeTabId == id) activeTabId = tabs.first().id
                    },
                    onNewTab = {
                        val newId = (tabs.maxOfOrNull { it.id } ?: 0) + 1
                        tabs = tabs + TabState(id = newId); activeTabId = newId; isColdStart = true
                    },
                    maxTabs = maxTabs
                )
            }

            // ── Bookmarks ──
            BrowserBookmarkDialog(
                visible = showBookmarks,
                onDismiss = { showBookmarks = false },
                prefs = prefs,
                currentUrl = activeTab.url,
                onNavigate = { navigate(it) }
            )

            // ── Markdown viewer ──
            BrowserMarkdownViewerDialog(
                visible = showMdViewer && mdContent.isNotBlank(),
                onDismiss = { showMdViewer = false; mdContent = "" },
                content = mdContent
            )
        }
    }
}



