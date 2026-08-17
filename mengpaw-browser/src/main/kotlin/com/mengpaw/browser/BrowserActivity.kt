// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser

import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mengpaw.browser.data.BrowserPrefs
import com.mengpaw.browser.data.TabState
import com.mengpaw.browser.util.BrowserStorage
import com.mengpaw.browser.ui.theme.BrowserThemeConfig
import com.mengpaw.core.AndroidLogger
import com.mengpaw.core.DataPathsInitializer
import com.mengpaw.kernel.KernelLog
import com.mengpaw.design.theme.ArcoTheme

/**
 * 浏览器独立 APK 入口。
 *
 * v0.32.x (400 行文件拆分批次 2): MCP 工具执行拆至 [BrowserMcpTools.kt],
 * 主 UI (BrowserApp) 拆至 [BrowserApp.kt] + 对话框层 [BrowserAppDialogs.kt]。
 */
class BrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DataPathsInitializer.initialize(this)
        KernelLog.setLogger(AndroidLogger())
        // 设备内 MCP 桥: 启动本地 HTTP server (127.0.0.1:9880), Shell 进程经它调 MCP 工具
        // (废弃旧反射静态字段绑定 — 插件类在 Shell 进程, 浏览器进程赋值互不可见)
        com.mengpaw.browser.mcp.McpHttpServer.start { tool, args ->
            runMcpTool(
                builtinPlugin = builtinBrowserPlugin,
                webViewProvider = { webViewMapRef.values.firstOrNull() },
                onMainThread = { block -> runOnUiThread(block) },
                toolName = tool, args = args
            )
        }
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
        // 开放模式 (Playwright 式): 用户显式开启后 /mcp 免 token, 本机任意进程可控制;
        // 默认关闭, 保持签名级安全模型 (开关见设置 → 开放 MCP 控制)。
        val prefs = BrowserPrefs(this)
        com.mengpaw.browser.mcp.McpHttpServer.setOpenMode(prefs.mcpOpenMode)
        // Bind Quick Click toggle and screenshot settings to BuiltinBrowserPlugin
        com.mengpaw.browser.plugin.BuiltinBrowserPlugin.quickClickEnabled = { prefs.quickClickEnabled }
        com.mengpaw.browser.plugin.BuiltinBrowserPlugin.screenshotMaxHeight = { prefs.screenshotMaxHeight }
        // am 桥共享实例（Phase 2）— RunCommandService 经 signature 权限调用同一命令引擎
        com.mengpaw.browser.plugin.BuiltinBrowserPlugin.shared = builtinBrowserPlugin
        // 半自动武器方案决策 #2: 首次打开弹窗引导授予「所有文件访问」(截图落公共目录)
        ensureStoragePermission()
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

    /** 决策 #2: 首次打开引导授予 MANAGE_EXTERNAL_STORAGE（API 30+ 跳系统设置页，非运行时弹窗）。 */
    private fun ensureStoragePermission() {
        if (BrowserStorage.hasStorageAccess()) return
        val prefs = getSharedPreferences("mp_browser", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("storage_permission_prompted", false)) return
        prefs.edit().putBoolean("storage_permission_prompted", true).apply()
        android.app.AlertDialog.Builder(this)
            .setTitle("需要存储权限")
            .setMessage(
                "MP 浏览器需要「所有文件访问」权限，用于把截图保存到公共目录\n" +
                    "(MengPaw/截图存档)，Agent 才能读取并分析。\n\n" +
                    "点击「去授权」后在系统设置中允许访问所有文件。"
            )
            .setPositiveButton("去授权") { _, _ ->
                try {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {
                    try {
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (_: Exception) {
                        // 设备不支持跳转 — 放弃引导，命令层会持续提示（决策 #6）
                    }
                }
            }
            .setNegativeButton("暂不", null)
            .show()
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

    // ── P1 fix: BuiltinBrowserPlugin 接线 (此前零实例化, browser.* 命令不可达);
    //    v0.8.0 半自动武器: page.* + browser.* 45 条, 经 9880 桥 + am 桥暴露 ──

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

    companion object {
        /** 标签页状态桥工厂 — BrowserApp (Compose) 注入 state 读写闭包。 */
        internal fun createStateBridge(
            tabs: () -> List<TabState>,
            setTabs: (List<TabState>) -> Unit,
            activeTabId: () -> Int,
            setActiveTabId: (Int) -> Unit,
            setIsColdStart: (Boolean) -> Unit,
            webViewMap: MutableMap<Int, WebView>,
            maxTabs: Int
        ): BrowserStateBridge = object : BrowserStateBridge {
            override fun activeWebView(): WebView? = webViewMap[activeTabId()]
            override fun currentTabs(): List<TabState> = tabs()
            override fun currentActiveTabId(): Int = activeTabId()
            override fun switchTab(id: Int) { if (tabs().any { it.id == id }) setActiveTabId(id) }
            override fun openTab(id: Int, url: String) {
                if (tabs().any { it.id == id }) {
                    setTabs(tabs().map { if (it.id == id) it.copy(url = url) else it })
                    setActiveTabId(id)
                    webViewMap[id]?.loadUrl(url)
                } else if (tabs().size < maxTabs) {
                    // P2 fix: Agent 路径 (browser.mcp.invoke tab/new) 同样受 maxTabs 上限约束
                    val newId = (tabs().maxOfOrNull { it.id } ?: -1) + 1
                    setTabs(tabs() + TabState(id = newId, url = url))
                    setActiveTabId(newId)
                    setIsColdStart(false)
                }
            }
            override fun closeTab(id: Int) {
                if (tabs().size <= 1) return  // 保持至少一个标签
                val wv = webViewMap.remove(id)
                if (wv != null) {
                    // 先脱离视图树再 destroy (P1 fix — attached destroy 风险)
                    try { (wv.parent as? android.view.ViewGroup)?.removeView(wv) } catch (_: Exception) {}
                    try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
                }
                setTabs(tabs().filter { it.id != id })
                setActiveTabId(tabs().firstOrNull()?.id ?: 0)
            }
        }
    }

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

    /** 安全销毁 WebView: 先脱离视图树再 destroy (P1 fix — attached 时 destroy 有崩溃风险)。 */
    private fun destroyWebViewSafe(wv: WebView?) {
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
internal fun fetchUrlTextTop(url: String): String? {
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
