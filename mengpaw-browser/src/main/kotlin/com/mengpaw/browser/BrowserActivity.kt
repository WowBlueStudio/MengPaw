// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

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
import androidx.compose.ui.input.pointer.pointerInput
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
import com.mengpaw.browser.service.GoogleTranslate
import com.mengpaw.browser.util.downloadImage
import com.mengpaw.browser.util.isAdRequest
import com.mengpaw.browser.util.smartNavigate
import com.mengpaw.browser.web.createWebView
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
import com.mengpaw.browser.ui.BrowserTopBar
import com.mengpaw.browser.ui.BrowserTranslateDialog
import com.mengpaw.browser.ui.DesktopTabBar
import com.mengpaw.browser.ui.NewTabPage
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
        // 设备内 MCP 桥: 启动本地 HTTP server (127.0.0.1:9880), Shell 进程经它调 MCP 工具
        // (废弃旧反射静态字段绑定 — 插件类在 Shell 进程, 浏览器进程赋值互不可见)
        com.mengpaw.browser.mcp.McpHttpServer.start { tool, args -> runMcpTool(tool, args) }
        // P0 fix: 桥认证 — 生成 32 字节随机 token, 经签名级 ContentProvider 写入 Shell 进程。
        // 第三方 app 无签名权限无法读写; 无 token 时 McpHttpServer 对 /mcp 一律 401 (fail-closed)。
        try {
            val bytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(bytes)
            val token = bytes.joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) } // Locale.ROOT: 阿拉伯语设备 %02x 畸形 (P2)
            com.mengpaw.browser.mcp.McpHttpServer.setAuthToken(token)
            val values = android.content.ContentValues().apply { put("token", token) }
            contentResolver.update(
                android.net.Uri.parse("content://com.mengpaw.bridge.token"),
                values, null, null
            )
        } catch (e: Exception) {
            android.util.Log.w("MengPaw", "MCP bridge token 注入失败 (Shell 未运行?): ${e.message}")
        }
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

    /**
     * MCP 工具入口 (HTTP server 线程调用)。
     *
     * P1 fix: 双路径分流 —
     * - **内置 browser.* 命令** (BuiltinBrowserPlugin, 44 条): 后台线程直接执行。命令内部
     *   经 webView.post 自行回主线程, 原 runOnUiThread 方案会让主线程阻塞在 evalJs 的
     *   latch.await 上, post 的 runnable 永远排不上 → 每次调用 2s 超时 (隐形失效)。
     * - **原生 6 工具** (navigate/screenshot/click/type/extract/eval): 保持主线程执行
     *   (browser_screenshot 的 View.draw 必须主线程)。
     */
    private fun runMcpTool(toolName: String, args: Map<String, String>): String {
        if (builtinBrowserPlugin.commands.containsKey(toolName)) {
            return executeBuiltinTool(toolName, args)
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return executeMcpTool(toolName, args)
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        var result = """{"ok":false,"error":"timeout"}"""
        runOnUiThread {
            try {
                result = executeMcpTool(toolName, args)
            } catch (e: Exception) {
                result = """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
            } finally {
                latch.countDown()
            }
        }
        latch.await(25, java.util.concurrent.TimeUnit.SECONDS)
        return result
    }

    /**
     * P1 fix: 内置 browser.* 命令执行器 (BuiltinBrowserPlugin 此前零实例化 — 44 条命令
     * 全部不可达)。命令键直接作 MCP 工具名 (Agent 经 browser.mcp.invoke <命令> 调用)。
     */
    private fun executeBuiltinTool(toolName: String, args: Map<String, String>): String {
        val handler = builtinBrowserPlugin.commands[toolName]
            ?: return """{"ok":false,"error":"Unknown tool: $toolName"}"""
        val positional = mcpArgsToPositional(args)
        val ctx = com.mengpaw.kernel.cli.ExecutionContext(sessionId = "mcp", userId = "mcp")
        return try {
            // 命令为 suspend — 在 HTTP server 线程上 runBlocking 执行;
            // 命令内部经 webView.post 自行回主线程, 无主线程死锁
            val result = kotlinx.coroutines.runBlocking { handler(positional, ctx) }
            if (result.success) result.output else result.error ?: "命令执行失败"
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

    /**
     * P1 fix: MCP 参数 map → 内置命令位置参数列表。
     * 按常见键序映射 (url/selector/text/x/y/...); 无命中键时按值序兜底 (单参数命令)。
     */
    private fun mcpArgsToPositional(args: Map<String, String>): List<String> {
        val ordered = POS_ARG_KEYS.mapNotNull { args[it] }
        return if (ordered.isNotEmpty()) ordered else args.values.toList()
    }

    /** MCP tool executor — 直接操作 WebView (必须在主线程调用)。 */
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

    /**
     * 发送 URL (或网页提炼请求) 给 Shell 的 MengPaw Agent。
     * extract=true 时加 mode=extract + title, Shell 会直接触发 Agent 提炼并回传。
     * internal: 顶层 BrowserApp 回调经 (ctx as? BrowserActivity) 调用。
     */
    internal fun sendToAgent(url: String, title: String, extract: Boolean) {
        val intent = Intent("com.mengpaw.action.OPEN_URL").apply {
            setClassName("com.mengpaw.shell", "com.mengpaw.shell.MainActivity")
            putExtra("url", url)
            if (extract) {
                putExtra("mode", "extract")
                putExtra("title", title.ifBlank { url })
            }
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "MengPaw 未安装", Toast.LENGTH_SHORT).show()
        }
    }

    /** 读取 OPEN_MD 的 md 内容 (extra 或 FileProvider URI)。 */
    private fun readMdUri(uriString: String?): String? {
        if (uriString.isNullOrBlank()) return null
        return try {
            val resolver = contentResolver
            val input = resolver.openInputStream(android.net.Uri.parse(uriString)) ?: return null
            input.bufferedReader().use { it.readText().take(500_000) }
        } catch (_: Exception) { null }
    }


    /** 处理浏览器 APK 收到的外部 Intent (OPEN_URL 重复打开 / OPEN_MD 提炼回传)。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent.action) {
            "com.mengpaw.action.OPEN_URL" -> onOpenUrl?.invoke(extractUrl(intent))
            "com.mengpaw.action.OPEN_MD" -> {
                val md = intent.getStringExtra("md") ?: readMdUri(intent.getStringExtra("mdUri")) ?: return
                onOpenMd?.invoke(intent.getStringExtra("title") ?: "", intent.getStringExtra("url") ?: "", md)
            }
            android.content.Intent.ACTION_VIEW -> {
                val md = checkMdFile(intent)
                if (md != null) onOpenMd?.invoke("", "", md)
            }
        }
    }

    /** Check if the intent carries a Markdown file and return its content, or null. */
    private fun checkMdFile(intent: Intent?): String? {
        if (intent?.action != android.content.Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        // file:// — 直接读文件; content:// — 经 ContentResolver (FileProvider 共享 / SAF 选择)
        return when (uri.scheme) {
            "file" -> {
                val path = uri.path ?: return null
                if (!path.endsWith(".md", ignoreCase = true) && intent.type != "text/markdown") return null
                try {
                    val file = java.io.File(path)
                    if (file.exists() && file.canRead()) file.readText().take(500_000) else null
                } catch (_: Exception) { null }
            }
            "content" -> if (intent.type != "text/plain" || uri.toString().endsWith(".md", ignoreCase = true)) readMdUri(uri.toString()) else null
            else -> null
        }
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
    /** OPEN_URL 热路径回调 (重复打开时由 onNewIntent 触发)。 */
    internal var onOpenUrl: ((String?) -> Unit)? = null
    /** OPEN_MD 提炼回传回调: (title, url, md) → 弹 Markdown 预览。 */
    internal var onOpenMd: ((String, String, String) -> Unit)? = null

    // ── P1 fix: BuiltinBrowserPlugin 接线 (此前零实例化, 44 条 browser.* 命令不可达) ──

    /** BrowserApp (Compose) 暴露的标签页状态桥 — 命令经它操作真实 UI 状态。 */
    interface BrowserStateBridge {
        fun activeWebView(): WebView?
        fun currentTabs(): List<TabState>
        fun currentActiveTabId(): Int
        fun switchTab(id: Int)
        fun openTab(id: Int, url: String)
        fun closeTab(id: Int)
    }

    /** 由 BrowserApp 的 SideEffect 每次重组赋值 (Compose state 可变)。 */
    @Volatile
    internal var browserState: BrowserStateBridge? = null

    /** 内置浏览器命令插件 — 经 9880 桥暴露给 Agent (browser.mcp.invoke <命令>)。 */
    private val builtinBrowserPlugin: com.mengpaw.browser.plugin.BuiltinBrowserPlugin by lazy {
        com.mengpaw.browser.plugin.BuiltinBrowserPlugin(
            webViewProvider = { browserState?.activeWebView() },
            tabInfoProvider = {
                val bs = browserState ?: return@BuiltinBrowserPlugin emptyList()
                bs.currentTabs().map { t ->
                    com.mengpaw.browser.plugin.BrowserTab(
                        id = t.id, url = t.url, title = t.title,
                        isLoading = t.isLoading, isActive = t.id == bs.currentActiveTabId()
                    )
                }
            },
            tabSwitcher = { id -> browserState?.switchTab(id) },
            tabOpener = { id, url -> browserState?.openTab(id, url) },
            tabCloser = { id -> browserState?.closeTab(id) }
        )
    }

    companion object {
        /** MCP map 参数 → 位置参数映射键序 (多参数命令按此顺序取)。 */
        private val POS_ARG_KEYS = listOf(
            "url", "selector", "text", "x", "y", "script", "value", "name",
            "width", "height", "css", "n", "maxHeight", "type", "op", "key",
            "domain", "target", "timeoutMs", "attribute"
        )
    }

    /** 安全销毁 WebView: 先脱离视图树再 destroy (P1 fix — attached 时 destroy 有崩溃风险)。 */
    private fun destroyWebViewSafe(wv: android.webkit.WebView?) {
        if (wv == null) return
        try {
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
        } catch (_: Exception) {}
        try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        // 设备内 MCP 桥停止
        com.mengpaw.browser.mcp.McpHttpServer.stop()
        // CRITICAL: Destroy all WebViews to free native renderer memory
        webViewMapRef.values.forEach { destroyWebViewSafe(it) }
        webViewMapRef.clear()
        try { android.webkit.CookieManager.getInstance().flush() } catch (_: Exception) { }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                // Pause all non-visible WebView rendering
                webViewMapRef.values.forEach { wv ->
                    try { wv.onPause() } catch (_: Exception) {}
                }
            }
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                // P1 fix: 不再 destroy — 所有 WebView 仍在 Compose 树 (AndroidView) 渲染中,
                // destroy 后 UI 继续引用已销毁实例必崩, 且旧实现 destroy 后残留 map 引用。
                // 改暂停全部 + 清缓存 (渲染内存的主要来源)。
                webViewMapRef.values.forEach { wv ->
                    try { wv.onPause() } catch (_: Exception) {}
                }
                try { webViewMapRef.values.firstOrNull()?.clearCache(true) } catch (_: Exception) {}
            }
        }
    }
}

/** 拉取站内 .md URL 内容 — top-level 使 BrowserApp (类外) 可访问。
 *  HttpURLConnection 一次性请求, 500K 截断对齐 readMdUri; 失败返回 null。 */
private fun fetchUrlTextTop(url: String): String? {
    return try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            // P2 fix: UA 版本号不再硬编码 "0.7" — 随 BuildConfig.VERSION_NAME 自动同步
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 MengPawBrowser/${BuildConfig.VERSION_NAME}")
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return null
            val charset = (conn.contentType ?: "")
                .substringAfter("charset=", "").trim().ifBlank { "UTF-8" }
            val text = conn.inputStream.bufferedReader(java.nio.charset.Charset.forName(charset))
                .use { it.readText() }
            text.take(500_000)
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) { null }
}

// ── Main Browser App ──────────────────────────────────────────────

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
        activity.browserState = object : BrowserActivity.BrowserStateBridge {
            override fun activeWebView(): WebView? = webViewMap[activeTabId]
            override fun currentTabs(): List<TabState> = tabs
            override fun currentActiveTabId(): Int = activeTabId
            override fun switchTab(id: Int) { if (tabs.any { it.id == id }) activeTabId = id }
            override fun openTab(id: Int, url: String) {
                if (tabs.any { it.id == id }) {
                    tabs = tabs.map { if (it.id == id) it.copy(url = url) else it }
                    activeTabId = id
                    webViewMap[id]?.loadUrl(url)
                } else if (tabs.size < maxTabs) {
                    // P2 fix: Agent 路径 (browser.mcp.invoke tab/new) 同样受 maxTabs 上限约束
                    val newId = (tabs.maxOfOrNull { it.id } ?: -1) + 1
                    tabs = tabs + TabState(id = newId, url = url)
                    activeTabId = newId
                    isColdStart = false
                }
            }
            override fun closeTab(id: Int) {
                if (tabs.size <= 1) return  // 保持至少一个标签
                val wv = webViewMap.remove(id)
                if (wv != null) {
                    // 先脱离视图树再 destroy (P1 fix — attached destroy 风险)
                    try { (wv.parent as? android.view.ViewGroup)?.removeView(wv) } catch (_: Exception) {}
                    try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
                }
                tabs = tabs.filter { it.id != id }
                activeTabId = tabs.firstOrNull()?.id ?: 0
            }
        }
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
                NewTabPage(
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
                    isWide = isWide,
                    prefs = prefs,
                    onNavigate = { navigate(it) },
                    onShowBookmarks = { showBookmarks = true }
                )
            } else {
                // WebView with pull-to-refresh
                val pullState = rememberPullRefreshState(
                    refreshing = activeTab.isLoading,
                    onRefresh = { webViewMap[activeTabId]?.reload() }
                )
                // Pre-render: keep all WebViews alive, visibility-toggle instead of destroy
                Box(Modifier.weight(1f).pullRefresh(pullState)) {
                    // 站内 .md URL → 拉取 → 预览 (与 OPEN_MD 通道共用 mdContent/showMdViewer 状态)
                    val mdFetchScope = rememberCoroutineScope()
                    val appCtx = LocalContext.current
                    fun fetchMarkdownUrl(url: String) {
                        mdFetchScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val text = runCatching { fetchUrlTextTop(url) }.getOrNull()
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (text != null) {
                                    mdContent = text
                                    showMdViewer = true
                                } else {
                                    Toast.makeText(appCtx, "无法加载 .md 文档", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    tabs.forEach { tab ->
                        val isActive = tab.id == activeTabId
                        androidx.compose.runtime.key(tab.id) {
                            AndroidView(
                                factory = { ctx ->
                                    val wv = webViewMap[tab.id]
                                    if (wv != null) wv
                                    else createWebView(
                                        ctx, tab, isWide, adBlockEnabled, autoInjectBridge, updateTab,
                                        { imgs -> images = imgs; showImages = true },
                                        onScroll = { dy -> scrollOffset = (scrollOffset + dy).coerceIn(0, 500) },
                                        onMarkdownDetected = { url -> fetchMarkdownUrl(url) }
                                    )
                                },
                                update = { wv -> webViewMap[tab.id] = wv },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(if (isActive) Modifier else Modifier.alpha(0f).height(0.dp))
                            )
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



