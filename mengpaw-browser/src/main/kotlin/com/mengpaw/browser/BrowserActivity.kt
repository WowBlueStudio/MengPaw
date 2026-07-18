// SPDX-FileCopyrightText: 2026 MengPaw
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.MotionEvent
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mengpaw.browser.plugin.BrowserElement
import com.mengpaw.browser.plugin.BrowserPluginRegistry
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

// ── Types ──────────────────────────────────────────────────────────

data class TabState(
    val id: Int, val url: String = "", val title: String = "",
    val isLoading: Boolean = false, val progress: Int = 0,
    val canGoBack: Boolean = false, val canGoForward: Boolean = false
)

data class DetectedImage(
    val src: String, val alt: String, val width: Int = 0, val height: Int = 0, val z: Int = 0,
    val mediaType: String = "image" // "image" or "video"
)

enum class SearchEngine(val label: String, val url: String, val key: String) {
    GOOGLE("Google", "https://www.google.com/search?q=", "google"),
    BING("Bing", "https://www.bing.com/search?q=", "bing"),
    BAIDU("百度", "https://www.baidu.com/s?wd=", "baidu"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=", "duckduckgo");

    companion object {
        fun fromKey(key: String) = entries.find { it.key == key } ?: BING
    }
}

/** Persistent browser settings backed by SharedPreferences. */
class BrowserPrefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("mp_browser", Context.MODE_PRIVATE)

    var adBlockEnabled: Boolean
        get() = p.getBoolean("adblock", true)
        set(v) = p.edit().putBoolean("adblock", v).apply()

    /** Ordered list of enabled engine keys (comma-separated). */
    var engineKeys: List<String>
        get() = p.getString("engines", "bing,google,baidu,duckduckgo")!!.split(",").filter { it.isNotBlank() }
        set(v) = p.edit().putString("engines", v.joinToString(",")).apply()

    /** Last-used engine key. */
    var lastEngineKey: String
        get() = p.getString("last_engine", "bing")!!
        set(v) = p.edit().putString("last_engine", v).apply()

    /** Get the ordered list of enabled SearchEngine instances. */
    fun enabledEngines(): List<SearchEngine> = engineKeys.mapNotNull { SearchEngine.fromKey(it) }

    /** Current default engine (last used). */
    fun defaultEngine(): SearchEngine = SearchEngine.fromKey(lastEngineKey)

    /** Set a new default and persist. */
    fun setDefaultEngine(engine: SearchEngine) { lastEngineKey = engine.key }

    var historyEnabled: Boolean
        get() = p.getBoolean("history_enabled", true)
        set(v) = p.edit().putBoolean("history_enabled", v).apply()

    var savePasswords: Boolean
        get() = p.getBoolean("save_passwords", true)
        set(v) = p.edit().putBoolean("save_passwords", v).apply()
}

/** Simple history store with 30-day retention and in-memory cache. */
class HistoryStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("mp_history", Context.MODE_PRIVATE)
    private val cutoffTime: Long get() = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
    private var cached: List<Entry>? = null
    private var cacheTimestamp = 0L

    data class Entry(val url: String, val title: String, val time: Long) {
        val daysLeft: Int get() = maxOf(0, ((30L * 24 * 60 * 60 * 1000 - (System.currentTimeMillis() - time)) / (24 * 60 * 60 * 1000)).toInt())
        val countdown: String get() = "${daysLeft}d"
    }

    fun record(url: String, title: String) {
        val entries = all().toMutableList()
        entries.add(0, Entry(url.take(500), title.take(100), System.currentTimeMillis()))
        val pruned = entries.filter { it.time > cutoffTime }.take(MAX_ENTRIES)
        p.edit().putString("entries", pruned.joinToString("|") { encode(it) }).apply()
        cached = pruned; cacheTimestamp = System.currentTimeMillis()
    }

    fun all(): List<Entry> {
        val now = System.currentTimeMillis()
        if (cached != null && now - cacheTimestamp < CACHE_TTL_MS) return cached!!
        val raw = p.getString("entries", "") ?: ""
        val entries = if (raw.isBlank()) emptyList()
        else raw.split("|").mapNotNull { decode(it) }.filter { it.time > cutoffTime }
        cached = entries; cacheTimestamp = now
        return entries
    }

    fun clear() { p.edit().remove("entries").apply(); cached = emptyList() }

    companion object {
        private const val MAX_ENTRIES = 500
        private const val CACHE_TTL_MS = 5000L
    }

    private fun encode(e: Entry): String {
        val obj = org.json.JSONObject()
        obj.put("u", e.url)
        obj.put("t", e.title)
        obj.put("ts", e.time)
        return obj.toString()
    }
    private fun decode(s: String): Entry? = try {
        val obj = org.json.JSONObject(s)
        Entry(obj.getString("u"), obj.optString("t", ""), obj.getLong("ts"))
    } catch (e: Exception) {
        android.util.Log.w("HistoryStore", "Failed to decode history entry", e)
        null
    }
}

// ── Ad Block List ──────────────────────────────────────────────────

private val AD_DOMAINS = listOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
    "adservice.google.com", "adservice.google.nl", "pagead2.googlesyndication.com",
    "amazon-adsystem.com", "criteo.com", "criteo.net", "adsrvr.org", "adnxs.com",
    "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com",
    "smartadserver.com", "outbrain.com", "taboola.com", "moatads.com",
    "advertising.com", "serving-sys.com", "adsafeprotected.com", "yieldmo.com",
    "scorecardresearch.com", "quantserve.com", "bluekai.com", "exelator.com",
    "demdex.net", "ads.linkedin.com", "ads.twitter.com", "ads.yahoo.com",
    "analytics.google.com", "googletagmanager.com", "facebook.com/tr",
    "bat.bing.com", "clarity.ms", "hotjar.com", "mouseflow.com"
)

private val AD_PATTERNS = listOf(
    Regex("[/.](?:ad|ads|advert|banner|popup|popunder|sponsor)[s]?[/.]", RegexOption.IGNORE_CASE),
    Regex("[/.](?:tracker|tracking|pixel|beacon|analytics|stat)[s]?[/.]", RegexOption.IGNORE_CASE),
    Regex("[?&](?:utm_|ref=|sponsored|adid|gclid|fbclid)", RegexOption.IGNORE_CASE)
)

private fun isAdRequest(url: String): Boolean {
    val host = try { java.net.URI(url).host ?: "" } catch (_: Exception) { "" }
    return AD_DOMAINS.any { host.contains(it, ignoreCase = true) } ||
           AD_PATTERNS.any { it.containsMatchIn(url) }
}

/**
 * Free Google Translate client using the public web endpoint.
 * No API key required — uses the same backend as translate.google.com.
 * For higher-volume/critical use, switch to the official Cloud Translation API.
 */
object GoogleTranslate {
    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"

    /** Common target language codes. */
    val LANGUAGES = mapOf(
        "中文(简)" to "zh-CN", "中文(繁)" to "zh-TW", "English" to "en",
        "日本語" to "ja", "한국어" to "ko", "Français" to "fr",
        "Deutsch" to "de", "Español" to "es", "Português" to "pt",
        "Italiano" to "it", "Русский" to "ru", "العربية" to "ar",
        "हिन्दी" to "hi", "ไทย" to "th", "Tiếng Việt" to "vi",
        "Bahasa Indonesia" to "id", "Türkçe" to "tr"
    )

    /** Translate text using the free public endpoint. */
    suspend fun translate(text: String, targetLang: String, sourceLang: String = "auto"): String {
        return withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = "$ENDPOINT?client=gtx&sl=$sourceLang&tl=$targetLang&dt=t&q=$encoded"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val raw = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            parseResult(raw)
        }
    }

    /** Parse Google's JSON response: [[["translated","orig",...]],null,"en"] */
    private fun parseResult(json: String): String {
        return try {
            // Remove the outer array wrapper, extract first string from each sentence
            val sb = StringBuilder()
            // Simple parser: find all ["translated","original",...] patterns
            val sentenceRegex = Regex("""\[\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"""")
            sentenceRegex.findAll(json).forEach { match ->
                val translated = match.groupValues[1]
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\\\", "\\")
                sb.append(translated)
            }
            sb.toString().ifBlank { "(translation empty)" }
        } catch (e: Exception) {
            "(translation failed: ${e.message})"
        }
    }
}

/** Smart URL detection: returns search URL for keywords, original URL with https for domains. */
private fun smartNavigate(input: String, engine: SearchEngine): String {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return ""
    // Already a full URL
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    // Contains a dot and no spaces → treat as domain
    if (trimmed.contains(".") && !trimmed.contains(" ")) return "https://$trimmed"
    // Fallback: search engine
    return engine.url + java.net.URLEncoder.encode(trimmed, "UTF-8")
}

// ── Activity ──────────────────────────────────────────────────────

class BrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Read theme from first Agent's theme.md (or default)
        val themeConfig = BrowserThemeConfig.load(this)
        setContent {
            ArcoTheme(darkTheme = false /* follows system */) {
                BrowserApp(initialUrl = extractUrl(intent))
            }
        }
    }

    private fun extractUrl(intent: Intent?): String? = when {
        intent?.action == "com.mengpaw.action.OPEN_URL" -> intent.getStringExtra("url")
        intent?.dataString != null -> intent.dataString
        else -> null
    }
}

// ── Main Browser App ──────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material.ExperimentalMaterialApi::class)
@Composable
fun BrowserApp(initialUrl: String? = null) {
    val ctx = LocalContext.current
    val prefs = remember { BrowserPrefs(ctx) }
    val isWide = LocalConfiguration.current.screenWidthDp >= 600
    val maxTabs = 4

    var tabs by remember { mutableStateOf(listOf(TabState(id = 0, url = initialUrl ?: ""))) }
    var activeTabId by remember { mutableStateOf(0) }
    var isColdStart by remember { mutableStateOf(initialUrl == null) }
    var showUrlBar by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var showImages by remember { mutableStateOf(false) }
    var images by remember { mutableStateOf<List<DetectedImage>>(emptyList()) }
    var showTabs by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showPasswords by remember { mutableStateOf(false) }
    var showTranslate by remember { mutableStateOf(false) }
    var historyEnabled by remember { mutableStateOf(prefs.historyEnabled) }
    val historyStore = remember { HistoryStore(ctx) }
    var searchEngine by remember { mutableStateOf(prefs.defaultEngine()) }
    var adBlockEnabled by remember { mutableStateOf(prefs.adBlockEnabled) }
    val webViewMap = remember { mutableMapOf<Int, WebView>() }

    val activeTab = tabs.find { it.id == activeTabId } ?: tabs.first()

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
                AnimatedVisibility(visible = showControls || isWide, enter = slideInVertically(), exit = slideOutVertically()) {
                    TopAppBar(
                        title = {
                            if (showUrlBar || isWide) {
                                var editUrl by remember(activeTabId) { mutableStateOf(activeTab.url) }
                                OutlinedTextField(
                                    value = editUrl, onValueChange = { editUrl = it },
                                    modifier = Modifier.fillMaxWidth().height(40.dp), singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                    shape = RoundedCornerShape(ArcoRadius.round),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = ThemeColors.brand, unfocusedBorderColor = ThemeColors.border
                                    ),
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri
                                    ),
                                    trailingIcon = {
                                        FilledIconButton(onClick = { navigate(editUrl) },
                                            modifier = Modifier.size(32.dp), shape = CircleShape,
                                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = ThemeColors.brand)
                                        ) { Icon(Icons.Default.Send, "前往", tint = Color.White, modifier = Modifier.size(14.dp)) }
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
                                        Icon(Icons.Default.Home, "主页", tint = ThemeColors.textPrimary)
                                    }
                                    IconButton(onClick = { webViewMap[activeTabId]?.goBack() }, enabled = activeTab.canGoBack, modifier = Modifier.size(36.dp)) {
                                        @Suppress("DEPRECATION")
                                        Icon(Icons.Default.ArrowBack, "后退", tint = if (activeTab.canGoBack) ThemeColors.textPrimary else ThemeColors.bgCardHigh)
                                    }
                                    IconButton(onClick = { webViewMap[activeTabId]?.goForward() }, enabled = activeTab.canGoForward, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Default.ArrowForward, "前进", tint = if (activeTab.canGoForward) ThemeColors.textPrimary else ThemeColors.bgCardHigh)
                                    }
                                    IconButton(onClick = { webViewMap[activeTabId]?.reload() }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Default.Refresh, "刷新", tint = ThemeColors.textPrimary)
                                    }
                                    Spacer(Modifier.width(4.dp))
                                }
                                // Tab count badge
                                IconButton(onClick = { showTabs = !showTabs }, modifier = Modifier.size(40.dp)) {
                                    BadgedBox(badge = { Badge { Text("${tabs.size}") } }) {
                                        @Suppress("DEPRECATION")
                                        Icon(Icons.Default.Favorite, "标签页 (${tabs.size})", tint = ThemeColors.brand)
                                    }
                                }
                            }
                        },
                        actions = {
                            if (showUrlBar) {
                                IconButton(onClick = { showUrlBar = false }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.Close, "收起地址栏")
                                }
                            }
                            // Menu button with dropdown
                            var menuExpanded by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.MoreVert, "菜单", tint = ThemeColors.textPrimary)
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
                                    DropdownMenuItem(
                                        text = { Text(if (adBlockEnabled) "广告拦截: 开" else "广告拦截: 关") },
                                        leadingIcon = { Icon(if (adBlockEnabled) Icons.Default.Star else Icons.Default.Close, null) },
                                        onClick = { adBlockEnabled = !adBlockEnabled; menuExpanded = false })
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
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                try { ctx.startActivity(intent) } catch (_: Exception) { Toast.makeText(ctx, "MengPaw 未安装", Toast.LENGTH_SHORT).show() }
                                                menuExpanded = false
                                            })
                                    }
                                    DropdownMenuItem(text = { Text("设置") }, leadingIcon = { Icon(Icons.Default.Settings, null) },
                                        onClick = { showSettings = true; menuExpanded = false })
                                    // Plugin-contributed menu items
                                    val pluginItems = remember { BrowserPluginRegistry.menuItems() }
                                    if (pluginItems.isNotEmpty()) {
                                        HorizontalDivider()
                                        pluginItems.forEach { item ->
                                            DropdownMenuItem(
                                                text = { Text(item.label) },
                                                leadingIcon = { Icon(Icons.Default.Star, null) },
                                                onClick = {
                                                    // Execute plugin CLI command via the tab's WebView
                                                    // The plugin's command handler will process it
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
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                }} else null
                            )
                        }
                        if (tabs.size < maxTabs) {
                            IconButton(onClick = {
                                val newId = (tabs.maxOfOrNull { it.id } ?: 0) + 1
                                tabs = tabs + TabState(id = newId); activeTabId = newId; isColdStart = true
                            }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Add, "新标签", tint = ThemeColors.textSecondary, modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
            }

            // ── Loader ──
            if (activeTab.isLoading && !isColdStart) LinearProgressIndicator(
                { activeTab.progress / 100f }, Modifier.fillMaxWidth().height(2.dp),
                color = ThemeColors.brand, trackColor = ThemeColors.bgCardHigh
            )

            // ── Settings dialog ──
            if (showSettings) {
                val engines = prefs.enabledEngines()
                val engineKeys = remember { mutableStateListOf(*prefs.engineKeys.toTypedArray()) }
                AlertDialog(
                    onDismissRequest = { showSettings = false },
                    title = { Text("设置") },
                    text = {
                        LazyColumn {
                            item { Text("搜索引擎 (勾选+拖动排序，首次为默认)", style = MaterialTheme.typography.labelMedium, color = ThemeColors.textSecondary) }
                            item { Spacer(Modifier.height(8.dp)) }
                            items(engineKeys.size) { idx ->
                                val key = engineKeys[idx]
                                val eng = SearchEngine.fromKey(key)
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = true, onCheckedChange = {
                                        if (engineKeys.size > 1) engineKeys.removeAt(idx)
                                    })
                                    SearchEngineLogo(eng, size = 22, dimmed = false)
                                    Spacer(Modifier.width(8.dp))
                                    Text(eng.label, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { if (idx > 0) { val t = engineKeys[idx]; engineKeys[idx] = engineKeys[idx-1]; engineKeys[idx-1] = t } },
                                        enabled = idx > 0, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.ArrowBack, "上移", Modifier.size(16.dp)) }
                                    IconButton(onClick = { if (idx < engineKeys.size - 1) { val t = engineKeys[idx]; engineKeys[idx] = engineKeys[idx+1]; engineKeys[idx+1] = t } },
                                        enabled = idx < engineKeys.size - 1, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.ArrowForward, "下移", Modifier.size(16.dp)) }
                                }
                            }
                            // Add disabled engines
                            val disabled = SearchEngine.entries.filter { it.key !in engineKeys }
                            if (disabled.isNotEmpty()) {
                                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                                item { Text("已关闭的引擎", style = MaterialTheme.typography.labelMedium, color = ThemeColors.textSecondary) }
                                items(disabled.size) { i ->
                                    val eng = disabled[i]
                                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = false, onCheckedChange = { engineKeys.add(eng.key) })
                                        SearchEngineLogo(eng, size = 22, dimmed = true)
                                        Spacer(Modifier.width(8.dp))
                                        Text(eng.label, modifier = Modifier.weight(1f), color = ThemeColors.textSecondary)
                                    }
                                }
                            }
                            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                            item {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("广告拦截")
                                    Switch(checked = adBlockEnabled, onCheckedChange = { adBlockEnabled = it; prefs.adBlockEnabled = it })
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            prefs.engineKeys = engineKeys.toList()
                            if (engineKeys.isNotEmpty()) {
                                searchEngine = SearchEngine.fromKey(engineKeys.first())
                                prefs.setDefaultEngine(searchEngine)
                            }
                            showSettings = false
                        }) { Text("保存") }
                    },
                    dismissButton = { TextButton(onClick = { showSettings = false }) { Text("取消") } }
                )
            }

            // ── History dialog ──
            if (showHistory) {
                val entries = remember { historyStore.all() }
                AlertDialog(
                    onDismissRequest = { showHistory = false },
                    title = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("历史记录 History")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("记录", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
                                Switch(checked = historyEnabled, onCheckedChange = { historyEnabled = it; prefs.historyEnabled = it })
                            }
                        }
                    },
                    text = {
                        LazyColumn {
                            if (entries.isEmpty()) {
                                item { Text("暂无历史记录", color = ThemeColors.textSecondary, modifier = Modifier.padding(16.dp)) }
                            }
                            items(entries.take(50)) { entry ->
                                Surface(Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { navigate(entry.url); showHistory = false },
                                    shape = RoundedCornerShape(6.dp), color = ThemeColors.bgCardHigh) {
                                    Column(Modifier.padding(8.dp)) {
                                        Text(entry.title.ifBlank { entry.url.take(50) }, maxLines = 1, fontSize = 13.sp)
                                        Row { Text(entry.url.take(60), style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, maxLines = 1, modifier = Modifier.weight(1f)); Text(entry.countdown, style = MaterialTheme.typography.labelSmall, color = if (entry.daysLeft < 3) ThemeColors.error else ThemeColors.textSecondary) }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showHistory = false }) { Text("关闭") } },
                    dismissButton = {
                        TextButton(onClick = { historyStore.clear(); showHistory = false }, colors = ButtonDefaults.textButtonColors(contentColor = ThemeColors.error)) {
                            Text("清空全部")
                        }
                    }
                )
            }

            // ── Password dialog ──
            if (showPasswords) {
                val pwdDb = remember { android.webkit.WebViewDatabase.getInstance(ctx) }
                AlertDialog(
                    onDismissRequest = { showPasswords = false },
                    title = { Text("密码管理 Passwords") },
                    text = {
                        Column {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("保存密码")
                                Switch(checked = prefs.savePasswords, onCheckedChange = { prefs.savePasswords = it })
                            }
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Text("已保存的密码会在登录时自动填充。", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                            Text("长按页面中的登录表单可以选择保存凭据。", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = {
                                pwdDb.clearUsernamePassword()
                                Toast.makeText(ctx, "已清除所有密码", Toast.LENGTH_SHORT).show()
                            }, modifier = Modifier.fillMaxWidth()) { Text("清除所有密码") }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showPasswords = false }) { Text("关闭") } }
                )
            }

            // ── Translate dialog ──
            if (showTranslate) {
                val targetLang = remember { mutableStateOf("zh-CN") }
                val translating = remember { mutableStateOf(false) }
                val result = remember { mutableStateOf("") }
                val sysLang = java.util.Locale.getDefault().language.let {
                    when (it) { "zh" -> "zh-CN"; "en" -> "en"; "ja" -> "ja"; "ko" -> "ko"; else -> "zh-CN" }
                }
                LaunchedEffect(showTranslate) { targetLang.value = sysLang }
                AlertDialog(
                    onDismissRequest = { showTranslate = false; result.value = "" },
                    title = { Text("翻译页面") },
                    text = {
                        Column {
                            Text(activeTab.title.ifBlank { activeTab.url.take(60) }, fontWeight = FontWeight.Medium, maxLines = 1)
                            Spacer(Modifier.height(12.dp))
                            // Language picker
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("翻译为:", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                var expanded by remember { mutableStateOf(false) }
                                Box {
                                    OutlinedButton(onClick = { expanded = true }) {
                                        Text(GoogleTranslate.LANGUAGES.entries.find { it.value == targetLang.value }?.key ?: targetLang.value, fontSize = 12.sp)
                                    }
                                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                        GoogleTranslate.LANGUAGES.forEach { (name, code) ->
                                            DropdownMenuItem(text = { Text(name) },
                                                onClick = { targetLang.value = code; expanded = false })
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            // Translate button
                            Button(
                                onClick = {
                                    translating.value = true
                                    CoroutineScope(Dispatchers.IO).launch {
                                        try {
                                            val pageText = activeTab.title
                                            val translated = GoogleTranslate.translate(pageText, targetLang.value)
                                            result.value = translated
                                        } catch (e: Exception) {
                                            result.value = "翻译失败: ${e.message}"
                                        }
                                        translating.value = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !translating.value
                            ) {
                                if (translating.value) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(if (translating.value) "翻译中..." else "翻译")
                            }
                            if (result.value.isNotBlank()) {
                                Spacer(Modifier.height(12.dp))
                                Surface(color = ThemeColors.bgCardHigh, shape = RoundedCornerShape(8.dp)) {
                                    Text(result.value, modifier = Modifier.padding(12.dp), fontSize = 14.sp)
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showTranslate = false; result.value = "" }) { Text("关闭") } }
                )
            }

            // ── Image picker ──
            if (showImages && images.isNotEmpty()) AlertDialog(
                onDismissRequest = { showImages = false },
                title = { Text("检测到 ${images.size} 个图片 (顶层→底层)") },
                text = {
                    LazyColumn {
                        items(images.size) { idx ->
                            val img = images[idx]
                            Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                downloadImage(ctx, img.src)
                                Toast.makeText(ctx, "已保存: ${img.src.substringAfterLast('/').take(30)}", Toast.LENGTH_SHORT).show()
                                showImages = false
                            }, shape = RoundedCornerShape(8.dp), color = ThemeColors.bgCardHigh) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("#${idx + 1}", fontWeight = FontWeight.Bold, color = ThemeColors.brand, modifier = Modifier.width(28.dp), fontSize = 12.sp)
                                    Column(Modifier.weight(1f)) {
                                        Text(img.alt.ifBlank { img.src.substringAfterLast('/').take(40) }, maxLines = 1, fontSize = 13.sp)
                                        Text(img.src.take(50), style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, maxLines = 1)
                                    }
                                    Icon(Icons.Default.Add, "保存", tint = ThemeColors.brand, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showImages = false }) { Text("关闭") } }
            )

            // ── Content ──
            if (isColdStart) {
                Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("MengPaw 浏览器", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(24.dp))
                    OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(), placeholder = { Text("搜索关键词或输入网址...") },
                        leadingIcon = {
                            // Engine icon — tap to cycle through search engines
                            IconButton(onClick = {
                                val engines = prefs.enabledEngines()
                                if (engines.isNotEmpty()) {
                                    val idx = engines.indexOfFirst { it.key == searchEngine.key }
                                    searchEngine = engines.getOrElse((idx + 1) % engines.size) { engines.first() }
                                    prefs.setDefaultEngine(searchEngine)
                                }
                            }) {
                                SearchEngineLogo(searchEngine, size = 28)
                            }
                        },
                        trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { navigate(searchQuery) }) { Icon(Icons.Default.Send, "搜索", tint = ThemeColors.brand) } },
                        singleLine = true, shape = RoundedCornerShape(ArcoRadius.round),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ThemeColors.brand, unfocusedBorderColor = ThemeColors.border))
                }
            } else {
                // WebView with pull-to-refresh
                val pullState = rememberPullRefreshState(
                    refreshing = activeTab.isLoading,
                    onRefresh = { webViewMap[activeTabId]?.reload() }
                )
                Box(Modifier.weight(1f).pullRefresh(pullState)) {
                    AndroidView(
                        factory = { createWebView(it, activeTab, isWide, adBlockEnabled, updateTab, { imgs -> images = imgs; showImages = true }) },
                        update = { wv -> webViewMap[activeTabId] = wv },
                        modifier = Modifier.fillMaxSize()
                    )
                    PullRefreshIndicator(activeTab.isLoading, pullState, Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }
}

// ── WebView Factory ──────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun createWebView(
    ctx: android.content.Context, tab: TabState, isWide: Boolean, adBlock: Boolean,
    updateTab: (Int, (TabState) -> TabState) -> Unit,
    onMediaDetected: (List<DetectedImage>) -> Unit
): WebView = WebView(ctx).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    CookieManager.getInstance().setAcceptCookie(true)

    var lastScrollY = 0
    setOnScrollChangeListener { _, _, scrollY, _, _ ->
        if (!isWide) {
            val delta = scrollY - lastScrollY
            if (delta > 50) { /* hide handled by Compose */ }
            else if (delta < -20) { /* show handled by Compose */ }
            lastScrollY = scrollY
        }
    }

    var touchX = 0f; var touchY = 0f
    setOnTouchListener { _, event ->
        if (event.action == MotionEvent.ACTION_DOWN) { touchX = event.x; touchY = event.y }
        false
    }

    // ── Long press: detect images + videos at touch point ──
    setOnLongClickListener {
        val js = """
            (function(){
                var els=document.elementsFromPoint($touchX,$touchY);
                var r=[];
                for(var i=0;i<els.length;i++){
                    var el=els[i];
                    var src=el.src||el.getAttribute('src')||el.style.backgroundImage||el.getAttribute('poster')||'';
                    var tag=el.tagName||'';
                    var mt=tag==='VIDEO'?'video':'image';
                    if(tag==='IMG'||tag==='VIDEO'||tag==='SOURCE'||src){
                        src=src.replace(/url\(["']?/,'').replace(/["']?\)/,'');
                        if(src&&src!=='none'&&!src.startsWith('data:')){
                            r.push(JSON.stringify({
                                src:src,alt:el.alt||'',tag:tag,
                                width:el.naturalWidth||el.videoWidth||el.width||0,
                                height:el.naturalHeight||el.videoHeight||el.height||0,
                                z:i,mediaType:mt
                            }));
                        }
                    }
                }
                return '['+r.join(',')+']';
            })();
        """.trimIndent()
        evaluateJavascript(js) { json ->
            try {
                val arr = org.json.JSONArray(json)
                val list = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                    DetectedImage(it.getString("src"), it.optString("alt"), it.optInt("width"), it.optInt("height"), it.optInt("z"), it.optString("mediaType", "image"))
                }
                if (list.isNotEmpty()) {
                    onMediaDetected(list)
                    // Also dispatch to plugins
                    list.firstOrNull()?.let { img ->
                        BrowserPluginRegistry.onLongPress(
                            BrowserElement(type = img.mediaType.uppercase(), url = img.src, alt = img.alt, width = img.width, height = img.height)
                        )
                    }
                }
            } catch (_: Exception) {}
        }
        true
    }

    webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            url?.let { u -> updateTab(tab.id) { it.copy(url = u, isLoading = true) }; BrowserPluginRegistry.onPageStarted(u) }
        }
        override fun onPageFinished(view: WebView?, url: String?) {
            updateTab(tab.id) { it.copy(isLoading = false, title = view?.title ?: "", canGoBack = view?.canGoBack() ?: false, canGoForward = view?.canGoForward() ?: false) }
            url?.let { u ->
                view?.title?.let { t -> BrowserPluginRegistry.onPageFinished(u, t) }
                // Inject plugin scripts
                BrowserPluginRegistry.injectScripts(u)?.let { js -> evaluateJavascript(js, null) }
                BrowserPluginRegistry.injectStyles(u)?.let { css ->
                    evaluateJavascript("(function(){var s=document.createElement('style');s.textContent='$css';document.head.appendChild(s);})()", null)
                }
                // ComfyUI theme following: inject MengPaw theme colors
                if (u.contains(":8188") || u.contains("comfyui", ignoreCase = true) || u.contains("comfy", ignoreCase = true)) {
                    val theme = BrowserThemeConfig.load(ctx)
                    val primary = "#" + java.lang.Long.toHexString(theme.primary).takeLast(6).uppercase()
                    evaluateJavascript("""
(function(){
var s=document.createElement('style');
s.textContent=`
:root{--comfy-primary:${primary};--comfy-bg:${if (theme.surface == 0xFFFFFFFFL) "#FFFFFF" else "#1A1A2E"}}
.comfy-menu, .comfy-topbar, .comfy-btn-primary{background:var(--comfy-primary)!important}
.comfy-multiline-input, .comfy-modal-content{background:var(--comfy-bg)!important}
.comfy-node{background:${primary}11!important;border-color:${primary}44!important}
.comfy-btn-primary:hover{filter:brightness(1.1)}
`;
document.head.appendChild(s);
})();
""".trimIndent(), null)
                }
            }
        }
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            request?.let { BrowserPluginRegistry.shouldIntercept(it)?.let { return it } }
            if (adBlock && request?.url != null && isAdRequest(request.url.toString())) {
                return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
            }
            return super.shouldInterceptRequest(view, request)
        }
    }
    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, p: Int) { updateTab(tab.id) { it.copy(progress = p) } }
        override fun onReceivedTitle(view: WebView?, t: String?) { updateTab(tab.id) { it.copy(title = t ?: "") } }
    }

    if (tab.url.isNotBlank()) loadUrl(tab.url)
}

// ── Components ────────────────────────────────────────────────────

@Composable
private fun TabChip(label: String, selected: Boolean, isLoading: Boolean, onClick: () -> Unit, onClose: (() -> Unit)?) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) ThemeColors.brandContainer else Color.Transparent,
        tonalElevation = if (selected) 1.dp else 0.dp
    ) {
        Row(Modifier.padding(start = 10.dp, end = if (onClose != null) 2.dp else 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (isLoading) { CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = ThemeColors.brand); Spacer(Modifier.width(6.dp)) }
            Text(label.take(20), fontSize = 12.sp, maxLines = 1)
            if (onClose != null) {
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onClose, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Default.Close, "关闭标签", modifier = Modifier.size(12.dp), tint = ThemeColors.textSecondary)
                }
            }
        }
    }
}

// ── Search Engine Logo Bitmaps ────────────────────────────────────

/** Generate a high-res bitmap for each search engine's recognizable logo. */
private fun generateLogoBitmap(engine: SearchEngine, sizePx: Int = 128): android.graphics.Bitmap {
    val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val s = sizePx.toFloat()
    val pad = s * 0.08f
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    when (engine) {
        SearchEngine.GOOGLE -> {
            // Multi-color 'G' icon — 4 colored arcs + white center
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = s * 0.18f; paint.strokeCap = android.graphics.Paint.Cap.ROUND
            val rect = android.graphics.RectF(pad, pad, s - pad, s - pad)
            // Blue (top-left)
            paint.color = 0xFF4285F4.toInt(); c.drawArc(rect, 45f, 170f, false, paint)
            // Red (right)
            paint.color = 0xFFEA4335.toInt(); c.drawArc(rect, -10f, 85f, false, paint)
            // Yellow (bottom)
            paint.color = 0xFFFBBC05.toInt(); c.drawArc(rect, 235f, 120f, false, paint)
            // Green (bottom-left)
            paint.color = 0xFF34A853.toInt(); c.drawArc(rect, 150f, 110f, false, paint)
            // White center
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt(); c.drawCircle(s/2, s/2, s * 0.18f, paint)
        }
        SearchEngine.BING -> {
            // Teal rounded square with white 'b'
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = 0xFF00809D.toInt()
            c.drawRoundRect(pad, pad, s - pad, s - pad, s * 0.2f, s * 0.2f, paint)
            // White 'b' — vertical bar
            paint.color = 0xFFFFFFFF.toInt()
            c.drawRect(s * 0.28f, pad * 3, s * 0.42f, s - pad * 3, paint)
            // White bowl (ring)
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = s * 0.13f; paint.strokeCap = android.graphics.Paint.Cap.ROUND
            c.drawArc(android.graphics.RectF(s*0.35f, s*0.2f, s*0.75f, s*0.7f), -30f, 240f, false, paint)
        }
        SearchEngine.BAIDU -> {
            // Blue paw — circle + pad + toes
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = 0xFF2932E1.toInt()
            c.drawCircle(s/2, s/2, s/2 - pad, paint)
            paint.color = 0xFFFFFFFF.toInt()
            // Main pad
            c.drawCircle(s/2, s * 0.62f, s * 0.16f, paint)
            // 4 toes
            for (a in listOf(-0.30f, -0.12f, 0.12f, 0.30f)) {
                c.drawCircle(s/2 + s * a, s * 0.32f, s * 0.09f, paint)
            }
        }
        SearchEngine.DUCKDUCKGO -> {
            // Orange duck head
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = 0xFFDE5833.toInt()
            c.drawCircle(s/2, s/2, s/2 - pad, paint)
            // White eye
            paint.color = 0xFFFFFFFF.toInt()
            c.drawCircle(s * 0.62f, s * 0.36f, s * 0.13f, paint)
            // Black pupil
            paint.color = 0xFF222222.toInt()
            c.drawCircle(s * 0.64f, s * 0.36f, s * 0.06f, paint)
            // Orange beak (triangle)
            paint.color = 0xFFFFA500.toInt()
            val path = android.graphics.Path()
            path.moveTo(s * 0.74f, s * 0.40f)
            path.lineTo(s * 0.92f, s * 0.50f)
            path.lineTo(s * 0.74f, s * 0.60f)
            path.close()
            c.drawPath(path, paint)
        }
    }
    return bmp
}

@Composable
private fun SearchEngineLogo(engine: SearchEngine, size: Int = 32, dimmed: Boolean = false) {
    val bitmap = remember(engine) { generateLogoBitmap(engine, 128) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = engine.label,
        modifier = Modifier.size(size.dp).then(if (dimmed) Modifier.alpha(0.4f) else Modifier.alpha(1f))
    )
}

/** Theme config loaded from first Agent's theme.md. Falls back to default blue. */
object BrowserThemeConfig {
    data class Config(val primary: Long, val surface: Long)

    fun load(ctx: android.content.Context? = null): Config {
        try {
            val agentsDir = java.io.File(com.mengpaw.core.DataPaths.AGENTS)
            val dirs = agentsDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
            for (dir in dirs) {
                val themeFile = java.io.File(dir, "theme.md")
                if (themeFile.exists()) {
                    val text = themeFile.readText()
                    val primary = Regex("primary.*?#([0-9A-Fa-f]{6})").find(text)?.groupValues?.get(1)?.toLongOrNull(16)?.let { 0xFF000000 or it } ?: 0xFF165DFF
                    val surface = Regex("surface.*?#([0-9A-Fa-f]{6})").find(text)?.groupValues?.get(1)?.toLongOrNull(16)?.let { 0xFF000000 or it } ?: 0xFFFFFFFF
                    return Config(primary, surface)
                }
            }
        } catch (_: Exception) {}
        return Config(0xFF165DFF, 0xFFFFFFFF)
    }
}

// ── Download ──────────────────────────────────────────────────────

private fun downloadImage(ctx: android.content.Context, url: String) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val conn = withContext(Dispatchers.IO) {
                URL(url).openConnection() as HttpURLConnection
            }
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 Chrome/120.0.0.0")
            conn.setRequestProperty("Referer", url)
            conn.connectTimeout = 15000; conn.readTimeout = 15000
            val bmp = withContext(Dispatchers.IO) {
                android.graphics.BitmapFactory.decodeStream(conn.inputStream)
            }
            conn.disconnect()
            if (bmp != null) {
                val name = url.substringAfterLast('/').substringBefore('?').take(100)
                    .ifBlank { "img_${System.currentTimeMillis()}" }
                val dir = File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "MengPaw")
                dir.mkdirs()
                val file = File(dir, name)
                withContext(Dispatchers.IO) {
                    file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
                }
                android.media.MediaScannerConnection.scanFile(
                    ctx, arrayOf(file.absolutePath), null, null
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("BrowserActivity", "Image download failed", e)
        }
    }
}
