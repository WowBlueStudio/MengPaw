// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mengpaw.core.AndroidLogger
import com.mengpaw.core.DataPathsInitializer
import com.mengpaw.design.theme.ArcoTheme
import com.mengpaw.kernel.KernelLog
import com.mengpaw.plugin.clipboard.ClipboardPlugin
import com.mengpaw.plugin.dev.DevPlugin
import com.mengpaw.plugin.framework.FrameworkPlugin
import com.mengpaw.plugin.fs.FsPlugin
import com.mengpaw.plugin.memory.MemoryPlugin
import com.mengpaw.plugin.memorytwin.MemoryTwinPlugin
import com.mengpaw.plugin.net.NetPlugin
import com.mengpaw.plugin.notification.NotificationPlugin
import com.mengpaw.plugin.self.SelfPlugin
import com.mengpaw.plugin.skill.SkillPlugin
import com.mengpaw.shell.ui.localization.AppStrings
import com.mengpaw.shell.ui.localization.ChineseStrings
import com.mengpaw.shell.ui.localization.EnglishStrings
import com.mengpaw.shell.ui.screens.*

/**
 * Plugin IDs compiled into the shell APK (显示为"内置"分类).
 * 必须与 mengpaw-shell/build.gradle.kts 中 implementation(project(":plugin-*")) 对齐:
 * framework / memory / skill / dev / fs / net / self / clipboard / notification /
 * memory-twin / root / hermes(Tribe).
 */
private val BUILTIN_PLUGIN_IDS = setOf(
    "framework-plugin", "memory-plugin", "skill-plugin", "dev-plugin",
    "fs-plugin", "net-plugin", "self-plugin", "clipboard-plugin", "notification-plugin",
    "memory-twin-plugin", "root-plugin", "tribe-plugin", "tools-plugin"
)

/**
 * Plugins that lead similar functionality in other agent frameworks (WowBlue 原创标识).
 * 判定标准: 领先于同类框架功能的原创插件 — 记忆三轨 / 记忆孪生 / 双层技能池 /
 * mDNS 框架发现 / 插件开发工具链 / 部落协作. 基础能力(fs/net/self/clipboard/notification)
 * 与系统级能力(root)不标.
 */
private val WOWBLUE_PLUGIN_IDS = setOf(
    "memory-plugin", "memory-twin-plugin", "skill-plugin",
    "framework-plugin", "dev-plugin", "tribe-plugin", "tools-plugin"
)

/** Builtin plugin display info (名称/描述), 用于内置但未安装时在全局插件列表兜底显示. */
private val BUILTIN_PLUGIN_INFO = mapOf(
    "framework-plugin" to ("框架发现" to "局域网 MengPaw 框架发现 — mDNS 注册与扫描、指纹记录、信任管理"),
    "memory-plugin" to ("记忆系统" to "Markdown 持久化记忆系统，含 LRU 缓存和被动索引"),
    "skill-plugin" to ("技能系统" to "可复用的 Agent 剧本系统（YAML+Markdown），含默认 Skill"),
    "dev-plugin" to ("插件开发" to "插件开发工具链 — create/audit/share/examples"),
    "fs-plugin" to ("文件系统" to "文件系统操作：cat, ls, write, rm, mkdir, cp, mv, stat, grep, glob"),
    "net-plugin" to ("网络请求" to "HTTP 请求：GET/POST，支持自定义 Header 和超时"),
    "self-plugin" to ("Agent 自省" to "状态/配置/统计/版本/头像/主题等自省命令"),
    "clipboard-plugin" to ("剪贴板" to "剪贴板操作：copy, paste, clear"),
    "notification-plugin" to ("通知" to "通知发送与管理：send, list, dismiss"),
    "memory-twin-plugin" to ("记忆孪生" to "跨设备记忆孪生同步 — 哈希链账本 + ACP P2P + 心跳保活 + QoS自适应 + 手动IP发现"),
    "root-plugin" to ("Root 权限" to "Root 权限管理 — su 命令执行/应用管理/文件系统/系统修改/备份恢复/审计日志"),
    "tribe-plugin" to ("部落协作 (Tribe)" to "多 Agent 部落协作：LAN 自动组队、Kanban 委派、LLM 路由、任务模板、Fleet 并行、广播讨论、ACP 实时、心跳"),
    "tools-plugin" to ("Agent 命令集" to "Agent 命令集注册 — 导入外部 CLI 命令集(gh/飞书等)，摘要注入系统提示词快速调用")
)

/**
 * Extract a human-readable summary from a markdown file.
 * Skips YAML frontmatter (lines between --- delimiters) and returns
 * the first heading or meaningful line.
 */
private fun extractSummary(markdown: String): String {
    val lines = markdown.lines()
    var inFrontmatter = false
    var frontmatterCount = 0
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed == "---") {
            frontmatterCount++
            if (frontmatterCount == 1) { inFrontmatter = true; continue }
            if (frontmatterCount >= 2) { inFrontmatter = false; continue }
        }
        if (inFrontmatter) continue
        if (trimmed.startsWith("#")) return trimmed.removePrefix("#").trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("_") && !trimmed.startsWith(">"))
            return trimmed.take(60)
    }
    return ""
}

class MainActivity : ComponentActivity() {
    private val settingsViewModel by viewModels<SettingsViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Global crash logger ──
        // Writes to both internal (for ADB on debug builds) and public Downloads
        // (for release builds, where /data/data is not ADB-readable on Android 10+)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            val entry = "\n=== $ts ===\nThread: ${thread.name}\n" +
                "Exception: ${throwable.javaClass.name}: ${throwable.message}\n" +
                throwable.stackTraceToString() + "\n"
            try {
                // Internal storage (ADB accessible on debug builds)
                val internal = java.io.File(filesDir, "crash.log")
                internal.parentFile?.mkdirs()
                internal.appendText(entry)
            } catch (_: Exception) {}
            try {
                // Public Downloads — accessible via file manager, no ADB needed
                val pub = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "MengPaw_crash.log")
                pub.parentFile?.mkdirs()
                pub.appendText(entry)
            } catch (_: Exception) {}
            // Pass to system default handler (crash dialog + logcat)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // ── 关键路径: 必须在 UI 渲染前完成的初始化 ──
        DataPathsInitializer.initialize(this)
        com.mengpaw.kernel.plugin.PluginManager.initializeGlobalInstance(
            com.mengpaw.kernel.AgentEngine.CORE_VERSION)
        com.mengpaw.core.namespace.SysExecutor.init(this)
        com.mengpaw.core.namespace.SysExecutor.setActivity(this)
        com.mengpaw.core.security.IntegrityGuard.globalInstance.init(this)
        com.mengpaw.core.AgentTemplates.init(this)
        com.mengpaw.kernel.agent.AgentDocs.bootstrapper = { name, lang -> com.mengpaw.core.AgentTemplates.bootstrapAgent(name, lang) }
        KernelLog.setLogger(AndroidLogger())
        enableEdgeToEdge()

        // 启动阶段：深蓝背景 → 白色状态栏图标
        val window = window
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        setContent {
            val settingsState by settingsViewModel.state.collectAsState()
            val strings: AppStrings = if (settingsState.useChinese) ChineseStrings else EnglishStrings
            val isDark = when (settingsState.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> (resources.configuration.uiMode
                    and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            ArcoTheme(darkTheme = isDark) {
                Surface(modifier = Modifier.fillMaxSize()) { MengPawApp(strings, settingsViewModel) }
            }
        }
        // 延迟初始化: 非关键路径在 UI 渲染后异步执行
        val launchIntent = intent
        lifecycleScope.launch(Dispatchers.IO) { deferInit(launchIntent) }
    }

    /** Handle incoming OPEN_URL intent from Browser APK without creating a new task. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenUrl(intent)
    }

    /** Keep the Activity reference fresh for runtime permission dialogs. */
    override fun onResume() {
        super.onResume()
        com.mengpaw.core.namespace.SysExecutor.setActivity(this)
    }

    /** Clear Activity reference to prevent leaks. */
    override fun onDestroy() {
        super.onDestroy()
        com.mengpaw.core.namespace.SysExecutor.setActivity(null)
    }

    private fun handleOpenUrl(intent: Intent?) {
        if (intent?.action == "com.mengpaw.action.OPEN_URL") {
            val url = intent.getStringExtra("url")
            if (url != null) {
                try {
                    val inbox = java.io.File(com.mengpaw.kernel.DataPaths.AGENT_INBOX)
                    inbox.mkdirs()
                    val tmp = java.io.File(inbox, "browser_url_${System.currentTimeMillis()}.txt.tmp")
                    tmp.writeText(url)
                    val dest = java.io.File(inbox, "browser_url_${System.currentTimeMillis()}.txt")
                    tmp.renameTo(dest)
                    if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * 延迟初始化 — 非关键路径, 在 UI 渲染后执行.
     * 包含: 框架发现, 触发器引擎, 前台服务, 事件接收器, 插件注册/安装, URL 处理.
     */
    private suspend fun deferInit(launchIntent: Intent) = kotlinx.coroutines.withContext(Dispatchers.Default) {
        // ── 孪生恢复 ──
        try { autoRestoreTwinIfNeeded() } catch (_: Exception) {}

        // ── Token 统计 ──
        try { com.mengpaw.shell.ui.components.TokenStatsCollector.load() } catch (_: Exception) {}

        // ── PluginViewModel 类注册 ──
        PluginViewModel.registerPluginClass("fs-plugin", "com.mengpaw.plugin.fs.FsPlugin")
        PluginViewModel.registerPluginClass("net-plugin", "com.mengpaw.plugin.net.NetPlugin")
        PluginViewModel.registerPluginClass("memory-plugin", "com.mengpaw.plugin.memory.MemoryPlugin")
        PluginViewModel.registerPluginClass("framework-plugin", "com.mengpaw.plugin.framework.FrameworkPlugin")
        PluginViewModel.registerPluginClass("skill-plugin", "com.mengpaw.plugin.skill.SkillPlugin")
        PluginViewModel.registerPluginClass("self-plugin", "com.mengpaw.plugin.self.SelfPlugin")
        PluginViewModel.registerPluginClass("clipboard-plugin", "com.mengpaw.plugin.clipboard.ClipboardPlugin")
        PluginViewModel.registerPluginClass("notification-plugin", "com.mengpaw.plugin.notification.NotificationPlugin")
        PluginViewModel.registerPluginClass("dev-plugin", "com.mengpaw.plugin.dev.DevPlugin")
        PluginViewModel.registerPluginClass("memory-twin-plugin", "com.mengpaw.plugin.memorytwin.MemoryTwinPlugin")
        PluginViewModel.registerPluginClass("root-plugin", "com.mengpaw.plugin.root.RootPlugin")
        PluginViewModel.registerPluginClass("tribe-plugin", "com.mengpaw.plugin.hermes.TribePlugin")
        PluginViewModel.registerPluginClass("tools-plugin", "com.mengpaw.plugin.agenttools.AgentToolsPlugin")

        // ── 框架发现 (mDNS) ──
        try {
            com.mengpaw.plugin.framework.FrameworkDiscovery.instance =
                com.mengpaw.plugin.framework.FrameworkDiscovery(this@MainActivity).apply {
                    frameworkName = "MengPaw"
                    frameworkVersion = com.mengpaw.kernel.AgentEngine.CORE_VERSION
                    val agentsDir = java.io.File(com.mengpaw.kernel.DataPaths.AGENTS)
                    agentNames = agentsDir.listFiles()
                        ?.filter { it.isDirectory && !it.name.startsWith(".") }
                        ?.map { it.name } ?: listOf("MengPaw")
                }
            com.mengpaw.plugin.framework.FrameworkDiscovery.instance?.register()
            com.mengpaw.plugin.framework.FrameworkDiscovery.instance?.startContinuousDiscovery()
        } catch (_: Exception) {}

        // ── 触发器引擎 ──
        try {
            com.mengpaw.kernel.trigger.TriggerEngine.setContext(this@MainActivity)
            com.mengpaw.kernel.trigger.TriggerEngine.load()
            com.mengpaw.kernel.trigger.TriggerEngine.registerSystemWake(this@MainActivity, 10)
            com.mengpaw.kernel.trigger.TriggerEngine.refreshCronAlarm()
        } catch (_: Exception) {}

        // ── 前台服务 ──
        try { com.mengpaw.shell.service.ShellService.start(this@MainActivity) } catch (_: Exception) {}

        // ── 系统事件接收器 ──
        try { com.mengpaw.shell.service.EventReceiver.register(this@MainActivity) } catch (_: Exception) {}

        // ── 捆绑插件自动安装 ──
        try {
            val pm = com.mengpaw.kernel.plugin.PluginManager.globalInstance
            val bundled: List<Pair<String, com.mengpaw.kernel.plugin.Plugin>> = listOf(
                "framework-plugin" to FrameworkPlugin(),
                "memory-plugin" to MemoryPlugin(),
                "skill-plugin" to SkillPlugin(),
                "dev-plugin" to DevPlugin(),
                "fs-plugin" to FsPlugin(),
                "net-plugin" to NetPlugin(),
                "self-plugin" to SelfPlugin(),
                "clipboard-plugin" to ClipboardPlugin(),
                "notification-plugin" to NotificationPlugin(),
                "memory-twin-plugin" to MemoryTwinPlugin(),
                "tools-plugin" to com.mengpaw.plugin.agenttools.AgentToolsPlugin(),
            )
            for ((id, plugin) in bundled) {
                try {
                    if (pm.get(id) == null) {
                        pm.install(plugin).fold(
                            onSuccess = {
                                pm.activate(id).fold(
                                    onSuccess = { android.util.Log.i("MengPaw", "Bundled plugin $id installed + activated") },
                                    onFailure = { android.util.Log.w("MengPaw", "Bundled plugin $id installed but activate failed: ${it.message}", it) }
                                )
                            },
                            onFailure = { android.util.Log.w("MengPaw", "Bundled plugin $id install failed: ${it.message}", it) }
                        )
                    } else { android.util.Log.d("MengPaw", "Bundled plugin $id already installed, skipping") }
                } catch (e: Exception) { android.util.Log.w("MengPaw", "Auto-install $id panicked: ${e.message}", e) }
            }
            android.util.Log.i("MengPaw", "Bundled auto-install done: ${pm.count()} installed, ${pm.activeCount()} active")
        } catch (_: Exception) {}

        // ── 处理外部 URL ──
        try { handleOpenUrl(launchIntent) } catch (_: Exception) {}
    }

    /** 如果之前激活过孪生, 自动恢复 ACP + 同步 (无需5连击) */
    private fun autoRestoreTwinIfNeeded() {
        val marker = java.io.File(filesDir, "twin_activated")
        if (!marker.exists()) return
        val agentName = try { marker.readText().trim() } catch (_: Exception) { "MengPaw" }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val plugin = com.mengpaw.plugin.memorytwin.MemoryTwinPlugin()
                com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.appContext = this@MainActivity
                com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.agentName = agentName
                val pm = com.mengpaw.kernel.plugin.PluginManager.globalInstance
                pm.install(plugin)
                pm.activate(plugin.metadata.id)
                startAcpForTwin(this@MainActivity, agentName)
                android.util.Log.i("MengPawTwin", "孪生服务已自动恢复")
            } catch (e: Exception) {
                android.util.Log.w("MengPawTwin", "自动恢复失败: ${e.message}")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MengPawApp(strings: AppStrings, settingsViewModel: SettingsViewModel) {
    var showSplash by remember { mutableStateOf(true) }
    var showPlugins by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    var showAttribution by remember { mutableStateOf(false) }
    val settingsState by settingsViewModel.state.collectAsState()

    if (showSplash) {
        WowBlueSplash(onFinished = { showSplash = false })
        return
    }

    // ── 全局返回手势：逐层回退，主页再退到后台 ──
    val overlayActive = showSettings || showPlugins || showLicense || showAttribution
    androidx.activity.compose.BackHandler(enabled = overlayActive) {
        when {
            showLicense -> showLicense = false
            showAttribution -> showAttribution = false
            showSettings && showPlugins -> showSettings = false
            showSettings -> showSettings = false
            showPlugins -> showPlugins = false
        }
    }

    // splash 结束后：根据亮/暗主题切换状态栏图标颜色
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val resolvedDark = when (settingsState.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> (ctx.resources.configuration.uiMode
            and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(resolvedDark) {
        val w = (view.context as android.app.Activity).window
        androidx.core.view.WindowCompat.getInsetsController(w, view)
            .isAppearanceLightStatusBars = !resolvedDark
        onDispose { }
    }

    val agentViewModel: AgentViewModel = viewModel()
    val activeAgent by agentViewModel.activeAgent.collectAsState()
    val sessionHistory by agentViewModel.sessionHistory.collectAsState()
    val hideCompacted by agentViewModel.hideCompacted.collectAsState()
    val hideArchived by agentViewModel.hideArchived.collectAsState()
    // ── Auto-restore saved API config on startup ──
    LaunchedEffect(Unit) {
        val saved = settingsViewModel.firstSavedProvider()
        if (saved != null && saved.apiKey.isNotBlank()) {
            agentViewModel.applyConfiguration(
                saved.endpoint, saved.apiKey, saved.model,
                com.mengpaw.kernel.llm.AdaptiveLlmProvider(saved.endpoint, saved.apiKey, saved.model),
                settingsViewModel.state.value.effectiveAgentLanguage
            )
        }
    }

    // ── Wire triggers once at startup ──
    LaunchedEffect(Unit) {
        com.mengpaw.shell.service.AgentRuntime.wireTriggers(agentViewModel)
        // Tribe inbox polling: refresh system prompt when new tribe tasks arrive
        agentViewModel.startTribeInboxRefresh()
    }

    // Sync loop mode from settings
    LaunchedEffect(settingsState.loopMode) { agentViewModel.loopMode = settingsState.loopMode }

    // ── Apply API config when exiting Settings (lightweight, no auto-start) ──
    LaunchedEffect(showSettings) {
        if (!showSettings) {
            val s = settingsState
            if (s.apiKey.isNotBlank()) {
                agentViewModel.applyConfiguration(
                    s.apiEndpoint, s.apiKey, s.modelName,
                    com.mengpaw.kernel.llm.AdaptiveLlmProvider(s.apiEndpoint, s.apiKey, s.modelName),
                    s.effectiveAgentLanguage
                )
            }
        }
    }
    // Grouped session data for hierarchical history sidebar
    val localGroups = remember(sessionHistory, hideCompacted) { agentViewModel.getLocalAgentGroups() }
    val frameworkGroups = remember(sessionHistory, hideCompacted) { agentViewModel.getFrameworkGroups() }
    val frameworkNames = remember { agentViewModel.knownFrameworks() }

    Box(Modifier.fillMaxSize()) {
        MainScreen(
            strings = strings,
            settingsViewModel = settingsViewModel,
            agentViewModel = agentViewModel,
            leftSidebarContent = { close, isRunning ->
                SidebarContent(
                    strings = strings,
                    isRunning = isRunning,
                    onNavigateToPlugins = { showPlugins = true; close() },
                    onNavigateToSettings = { showSettings = true; close() },
                    onClose = { close() },
                    activeAgent = activeAgent,
                    onSwitchAgent = { name, framework -> agentViewModel.switchAgent(name, framework); close() },
                    onCreateAgent = { name -> agentViewModel.createAgent(name); close() },
                    onCreateAgentWithDetails = { name, wsFolder, intro ->
                        agentViewModel.createAgentWithDetails(name, wsFolder, intro)
                        close()
                    },
                    onActivateMemoryTwin = {
                        val name = agentViewModel.activeAgent.value
                        try {
                            android.util.Log.i("MengPawTwin", "激活开始: agent=$name")
                            val plugin = com.mengpaw.plugin.memorytwin.MemoryTwinPlugin()
                            android.util.Log.i("MengPawTwin", "插件实例已创建")
                            com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.appContext = ctx
                            com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.agentName = name
                            android.util.Log.i("MengPawTwin", "依赖已注入")
                            val pm = com.mengpaw.kernel.plugin.PluginManager.globalInstance
                            android.util.Log.i("MengPawTwin", "PluginManager: $pm")
                            // Setup/initialization context — blocking is acceptable
                            val installResult = kotlinx.coroutines.runBlocking { pm.install(plugin) }
                            android.util.Log.i("MengPawTwin", "install结果: ${installResult.isSuccess}")
                            installResult.fold(
                                onSuccess = {
                                    pm.activate(plugin.metadata.id).fold(
                                        onSuccess = {
                                            android.util.Log.i("MengPawTwin", "插件激活成功")
                                            (ctx as? androidx.activity.ComponentActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                                                startAcpForTwin(ctx, name)
                                            }
                                            android.widget.Toast.makeText(ctx, "🧠 记忆孪生已激活", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        onFailure = { e ->
                                            android.util.Log.e("MengPawTwin", "激活失败: ${e.message}", e)
                                            android.widget.Toast.makeText(ctx, "激活失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                },
                                onFailure = { e ->
                                    android.util.Log.e("MengPawTwin", "安装失败: ${e.message}", e)
                                    pm.activate(plugin.metadata.id).fold(
                                        onSuccess = {
                                            android.util.Log.i("MengPawTwin", "二次激活成功")
                                            (ctx as? androidx.activity.ComponentActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                                                startAcpForTwin(ctx, name)
                                            }
                                            android.widget.Toast.makeText(ctx, "🧠 记忆孪生已激活", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        onFailure = { e2 ->
                                            android.util.Log.e("MengPawTwin", "二次激活失败: ${e2.message}", e2)
                                            android.widget.Toast.makeText(ctx, "激活失败: ${e2.message}", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("MengPawTwin", "异常: ${e.message}", e)
                            com.mengpaw.kernel.error.ErrorCollector.report(e, "activateMemoryTwin")
                            android.widget.Toast.makeText(ctx, "异常: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        close()
                    }
                )
            },
            rightSidebarContent = { close ->
                HistorySidebar(
                    localGroups = localGroups,
                    frameworkNames = frameworkNames,
                    frameworkGroups = frameworkGroups,
                    hideCompacted = hideCompacted,
                    onToggleHideCompacted = { agentViewModel.toggleHideCompacted() },
                    hideArchived = hideArchived,
                    onToggleHideArchived = { agentViewModel.toggleHideArchived() },
                    onSelectSession = { record ->
                        agentViewModel.switchToSession(record)
                        close()
                    },
                    onDeleteSession = { agentViewModel.deleteSession(it) },
                    onCompactSession = { agentViewModel.compactSession(it) },
                    onNewSessionFor = { agentName, framework ->
                        agentViewModel.newSessionFor(agentName, framework)
                        close()
                    }
                )
            }
        )
    }

    // ── Pre-computed settings data (outside if-block so it survives close/reopen) ──
    val agentFramework = remember(activeAgent) { agentViewModel.frameworkFor(activeAgent) }
    val (agentEp, agentModel) = remember(activeAgent) { agentViewModel.agentConfig(activeAgent) }
    var workspaceVersion by remember { mutableIntStateOf(0) }

    // Plugins: recomputed each time the settings screen opens (deferInit may finish later,
    // and downloaded plugins land anytime — never trust a one-shot list)
    var pluginItems by remember { mutableStateOf(emptyList<FrameworkItem>()) }
    LaunchedEffect(showSettings) {
        if (!showSettings) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val pm = com.mengpaw.kernel.plugin.PluginManager.globalInstance
            val installed = pm.listAll().map { (plugin, status) ->
                FrameworkItem(name = plugin.metadata.name, isWowBlue = plugin.metadata.id in WOWBLUE_PLUGIN_IDS,
                    category = if (plugin.metadata.id in BUILTIN_PLUGIN_IDS) ItemCategory.BUILTIN else ItemCategory.OFFICIAL,
                    summary = plugin.metadata.description,
                    docMarkdown = "## ${plugin.metadata.name}\n\n${plugin.metadata.description}\n\nID: ${plugin.metadata.id}\n版本: ${plugin.metadata.version}\n状态: ${status.name}\n命令数: ${plugin.commands.size}")
            }
            // Builtin plugins compiled into the APK but not yet installed — show them anyway,
            // so new bundled plugins are never invisible in the global list
            val missingBuiltins = BUILTIN_PLUGIN_IDS
                .filter { id -> pm.get(id) == null }
                .mapNotNull { id -> BUILTIN_PLUGIN_INFO[id]?.let { (name, desc) ->
                    FrameworkItem(name = name, category = ItemCategory.BUILTIN, isWowBlue = id in WOWBLUE_PLUGIN_IDS,
                        summary = "$desc — 内置，未安装",
                        docMarkdown = "## $name\n\n$desc\n\nID: $id\n状态: 未安装（内置插件，可在插件市场激活）")
                } }
            // Kernel namespaces are not plugins but surface as builtin capabilities
            val kernelNamespaces = listOf(
                FrameworkItem("self (内置)", ItemCategory.BUILTIN, "Agent 自省 — 状态/配置/统计/版本/头像/主题/通知/时间", ""),
                FrameworkItem("agent (内置)", ItemCategory.BUILTIN, "文档管理 — 记忆/CLI/档案/审计/梦境/存储", ""),
                FrameworkItem("plugin (内置)", ItemCategory.BUILTIN, "插件管理 — 市场/搜索/安装/卸载/启停/升级", ""),
                FrameworkItem("sys (内置)", ItemCategory.BUILTIN, "系统信息 — 电量/网络/CPU/存储/定位/剪贴板", ""),
            )
            val items = installed + missingBuiltins + kernelNamespaces
            withContext(Dispatchers.Main) { pluginItems = items }
        }
    }

    // ── CLI commands: built-in curated + dynamic plugin commands
    val toolItems = remember(activeAgent, agentViewModel.activeNamespaces().hashCode()) {
        val engine = agentViewModel.activeEngine()
        val selfTools = listOf(
            FrameworkItem("self.status", ItemCategory.BUILTIN, "Agent 运行状态查询", ""),
            FrameworkItem("self.config [key=value]", ItemCategory.BUILTIN, "查看或修改 Agent 配置", ""),
            FrameworkItem("self.stats", ItemCategory.BUILTIN, "内存/CPU/线程统计信息", ""),
            FrameworkItem("self.version", ItemCategory.BUILTIN, "MengPaw 版本号", ""),
            FrameworkItem("self.time [format]", ItemCategory.BUILTIN, "当前时间", ""),
            FrameworkItem("self.tools [namespace]", ItemCategory.BUILTIN, "列出所有可用命令", ""),
            FrameworkItem("self.notify.message <text>", ItemCategory.BUILTIN, "Agent 推送消息到聊天", "", isWowBlue = true),
            FrameworkItem("self.notify.banner <text> [--level]", ItemCategory.BUILTIN, "Agent 推送通知横幅", "", isWowBlue = true),
            FrameworkItem("self.avatar <path>", ItemCategory.BUILTIN, "设置 Agent 头像", "", isWowBlue = true),
            FrameworkItem("self.theme primary=#xxx surface=#xxx", ItemCategory.BUILTIN, "修改主题色", "", isWowBlue = true),
            FrameworkItem("self.trigger add|list|remove|topics", ItemCategory.BUILTIN, "CRON/LIFETIME 触发器", "", isWowBlue = true),
        )
        val agentTools = listOf(
            FrameworkItem("agent.cli", ItemCategory.BUILTIN, "查阅完整 CLI.md 命令参考", ""),
            FrameworkItem("agent.docs", ItemCategory.BUILTIN, "列出所有 Agent 文档", ""),
            FrameworkItem("agent.memory [query]", ItemCategory.BUILTIN, "记忆索引/搜索", "", isWowBlue = true),
            FrameworkItem("agent.memory.record <content>", ItemCategory.BUILTIN, "手动记录一条记忆", "", isWowBlue = true),
            FrameworkItem("agent.profile", ItemCategory.BUILTIN, "查看 Agent 身份档案", ""),
            FrameworkItem("agent.soul", ItemCategory.BUILTIN, "查看 Agent 灵魂设定", ""),
            FrameworkItem("agent.audit [N]", ItemCategory.BUILTIN, "查看最近 N 条命令审计日志", "", isWowBlue = true),
            FrameworkItem("agent.browser-tools", ItemCategory.BUILTIN, "MP浏览器插件开发能力参考", "", isWowBlue = true),
            FrameworkItem("agent.dream", ItemCategory.BUILTIN, "触发梦境整理", "", isWowBlue = true),
            FrameworkItem("agent.cleanup", ItemCategory.BUILTIN, "清理过期文件和归档记忆", "", isWowBlue = true),
            FrameworkItem("agent.storage", ItemCategory.BUILTIN, "工作区存储空间报告", "", isWowBlue = true),
            FrameworkItem("agent.boost", ItemCategory.BUILTIN, "首次引导初始化", "", isWowBlue = true),
            FrameworkItem("agent.boost.delete", ItemCategory.BUILTIN, "删除引导文件", "", isWowBlue = true),
        )
        val pluginTools = listOf(
            FrameworkItem("plugin.marketplace [--refresh]", ItemCategory.BUILTIN, "浏览插件市场", ""),
            FrameworkItem("plugin.search <query>", ItemCategory.BUILTIN, "搜索可用插件", ""),
            FrameworkItem("plugin.install <id>", ItemCategory.BUILTIN, "下载+验证+安装+激活插件", ""),
            FrameworkItem("plugin.uninstall <id>", ItemCategory.BUILTIN, "卸载插件", ""),
            FrameworkItem("plugin.list", ItemCategory.BUILTIN, "列出已安装插件", ""),
            FrameworkItem("plugin.info <id>", ItemCategory.BUILTIN, "查看插件详情", ""),
            FrameworkItem("plugin.enable <id>", ItemCategory.BUILTIN, "启用插件", ""),
            FrameworkItem("plugin.disable <id>", ItemCategory.BUILTIN, "停用插件", ""),
            FrameworkItem("plugin.update <id>", ItemCategory.BUILTIN, "检查插件更新", ""),
            FrameworkItem("plugin.upgrade --all", ItemCategory.BUILTIN, "升级全部插件", ""),
        )
        val sysTools = listOf(
            FrameworkItem("sys.battery", ItemCategory.BUILTIN, "电量/充电状态/温度", "", isWowBlue = true),
            FrameworkItem("sys.network", ItemCategory.BUILTIN, "网络类型/信号强度", "", isWowBlue = true),
            FrameworkItem("sys.cpu", ItemCategory.BUILTIN, "CPU 使用率/核心数", "", isWowBlue = true),
            FrameworkItem("sys.memory", ItemCategory.BUILTIN, "内存使用量", "", isWowBlue = true),
            FrameworkItem("sys.storage", ItemCategory.BUILTIN, "存储空间使用情况", "", isWowBlue = true),
            FrameworkItem("sys.display", ItemCategory.BUILTIN, "屏幕参数", "", isWowBlue = true),
            FrameworkItem("sys.sensors", ItemCategory.BUILTIN, "传感器列表", "", isWowBlue = true),
            FrameworkItem("sys.clipboard", ItemCategory.BUILTIN, "剪贴板内容", "", isWowBlue = true),
            FrameworkItem("sys.location", ItemCategory.BUILTIN, "GPS 定位", "", isWowBlue = true),
            FrameworkItem("sys.camera", ItemCategory.BUILTIN, "相机信息", "", isWowBlue = true),
            FrameworkItem("sys.apps", ItemCategory.BUILTIN, "已安装应用列表", "", isWowBlue = true),
        )
        val mcpTools = if (engine != null) try {
            com.mengpaw.kernel.mcp.McpServer(engine.getPluginManager())
                .listTools().map { FrameworkItem(it.name, ItemCategory.OFFICIAL, it.description, "") }
        } catch (_: Exception) { emptyList() } else emptyList()
        // Built-in curated lists
        selfTools + agentTools + pluginTools + sysTools + mcpTools +
        // Dynamic plugin commands from installed plugins
        try {
            val pm = engine?.getPluginManager()
            if (pm != null) {
                pm.listAll().filter { (_, status) -> status == com.mengpaw.kernel.plugin.PluginStatus.ACTIVE }
                    .flatMap { (plugin, _) ->
                        val ns = plugin.metadata.id.replace(Regex("-(plugin|ext)$"), "")
                        plugin.commands.keys.map { cmd ->
                            FrameworkItem("$ns.$cmd", ItemCategory.OFFICIAL, plugin.metadata.description, "")
                        }
                    }
            } else emptyList()
        } catch (_: Exception) { emptyList() }
    }

    // Skills: only real Skill files under /技能剧本/ (CLI commands like skill.ls are Tools,
    // not Skills — v0.19.5). Recomputed each time settings opens, because skill-plugin seeds
    // defaults asynchronously at startup and a one-shot snapshot would stay stale forever
    var skillItems by remember { mutableStateOf(emptyList<FrameworkItem>()) }
    LaunchedEffect(showSettings) {
        if (!showSettings) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val skillsDir = java.io.File(com.mengpaw.kernel.DataPaths.SKILLS)
            val skillFiles = if (skillsDir.exists()) {
                skillsDir.listFiles()?.filter { it.extension == "md" }
                    ?.map { FrameworkItem(it.nameWithoutExtension, ItemCategory.BUILTIN,
                        try { it.readText().lines().firstOrNull()?.removePrefix("#")?.trim() ?: "" } catch (_: Exception) { "" }, "") }
                    ?: emptyList()
            } else emptyList()
            // Empty dir → empty list; the section renders its own "暂无条目" empty state.
            // CLI management commands (skill.ls/skill.run/...) are Tools, not Skills.
            withContext(Dispatchers.Main) { skillItems = skillFiles }
        }
    }

    // Per-agent local skills: Agent文档/{agent}/skills/*.md — NOT the global /技能剧本/ pool.
    // Global vs exclusive separation is enforced at the UI boundary (LESSONS 99).
    var agentSkillItems by remember { mutableStateOf(emptyList<FrameworkItem>()) }
    LaunchedEffect(showSettings, activeAgent) {
        if (!showSettings) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            // 一次性迁移: DataPaths 双重路径 bug(v0.19.7 修复)前, 本地技能落在
            // Agent文档/Agent文档/{agent}/skills 错误路径 — 迁到正确路径
            try {
                val legacy = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "Agent文档/Agent文档/$activeAgent/skills")
                if (legacy.exists()) {
                    val target = java.io.File(com.mengpaw.kernel.DataPaths.agentSkillsDir(activeAgent))
                    if (target.listFiles().isNullOrEmpty()) legacy.copyRecursively(target, overwrite = false)
                    legacy.deleteRecursively()
                }
            } catch (_: Exception) {}
            val dir = java.io.File(com.mengpaw.kernel.DataPaths.agentSkillsDir(activeAgent))
            val items = if (dir.exists()) {
                dir.listFiles()?.filter { it.extension == "md" }?.sortedBy { it.name }
                    ?.map { file ->
                        val content = try { file.readText() } catch (_: Exception) { "" }
                        FrameworkItem(name = file.nameWithoutExtension, category = ItemCategory.BUILTIN,
                            summary = extractSummary(content), docMarkdown = content)
                    } ?: emptyList()
            } else emptyList()
            withContext(Dispatchers.Main) { agentSkillItems = items }
        }
    }

    // Agent 专属工具: Agent文档/{agent}/tools/*.json — 命令集注册清单（非全局共享，LESSONS 99）
    var agentToolItems by remember { mutableStateOf(emptyList<FrameworkItem>()) }
    LaunchedEffect(showSettings, activeAgent) {
        if (!showSettings) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val items = com.mengpaw.plugin.agenttools.AgentToolsStore.readAll(activeAgent).map { set ->
                FrameworkItem(
                    name = set.displayName.ifBlank { set.name },
                    category = ItemCategory.CUSTOM,
                    summary = "${set.commands.size} 条命令 · 来源: ${set.source.ifBlank { "手动粘贴" }}",
                    docMarkdown = com.mengpaw.plugin.agenttools.AgentToolsStore.toMarkdown(set))
            }
            withContext(Dispatchers.Main) { agentToolItems = items }
        }
    }

    var workspaceItems by remember { mutableStateOf(emptyList<FrameworkItem>()) }
    LaunchedEffect(activeAgent, workspaceVersion) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val dir = java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, activeAgent)
            val items = buildList {
                // 顶层 Markdown 文档（agents/soul/boost 等）
                dir.listFiles { f -> f.isFile && f.extension == "md" }?.sortedBy { it.name }?.forEach { file ->
                    val content = try { file.readText() } catch (_: Exception) { "" }
                    add(FrameworkItem(name = file.name, category = ItemCategory.BUILTIN,
                        summary = extractSummary(content), docMarkdown = content))
                }
                // memory/ 记忆目录聚合条目 — 三重记忆: 长期 memory.md / 中期 memory_{date}.md / 项目 project_{name}_memory.md
                val memoryDir = java.io.File(dir, "memory")
                if (memoryDir.exists()) {
                    val memoryFiles = memoryDir.listFiles { f -> f.isFile && f.extension == "md" }
                        ?.sortedBy { it.name } ?: emptyList()
                    if (memoryFiles.isNotEmpty()) {
                        val longTerm = memoryFiles.count { it.name == "memory.md" }
                        val midTerm = memoryFiles.count { it.name.startsWith("memory_") }
                        val project = memoryFiles.count { it.name.startsWith("project_") }
                        val doc = buildString {
                            appendLine("## memory/ — 记忆目录（三重记忆）")
                            appendLine()
                            memoryFiles.forEach { f ->
                                val content = try { f.readText() } catch (_: Exception) { "" }
                                appendLine("### ${f.name}")
                                appendLine(content.trim().take(1200).ifBlank { "（空文档）" })
                                appendLine()
                            }
                        }
                        add(FrameworkItem(
                            name = "memory/（记忆目录）",
                            category = ItemCategory.BUILTIN,
                            summary = "长期 $longTerm · 中期 $midTerm · 项目 $project · 共 ${memoryFiles.size} 个文档",
                            docMarkdown = doc))
                    }
                }
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) { workspaceItems = items }
        }
    }

    // ── Full-screen overlays — 后渲染的在上面 ──
    if (showSettings) {
        SettingsScreen(
            onNavigateBack = { showSettings = false },
            onNavigateToPluginMarket = { showPlugins = true },
            onNavigateToLicense = { showLicense = true },
            onNavigateToAttribution = { showAttribution = true },
            viewModel = settingsViewModel,
            activeAgentName = activeAgent,
            agentFramework = agentFramework,
            activeAgentEndpoint = agentEp,
            activeAgentModel = agentModel,
            onAgentSelectProvider = { },
            pluginItems = pluginItems,
            toolItems = toolItems,
            skillItems = skillItems,
            agentToolItems = agentToolItems,
            agentSkillItems = agentSkillItems,
            workspaceItems = workspaceItems,
            onRefreshWorkspace = { workspaceVersion++ },
            onDeleteWorkspaceFile = { fileName ->
                if (fileName.startsWith("memory/")) return@SettingsScreen  // 聚合条目只读
                if (fileName == "boost.md") com.mengpaw.kernel.agent.AgentDocs.deleteBoost(activeAgent)
                else java.io.File(java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, activeAgent), fileName).delete()
                workspaceVersion++
            }
        )
    }
    if (showPlugins) {
        PluginMarketScreen(
            onNavigateBack = { showPlugins = false },
            onNavigateToDetail = {}
        )
    }
    if (showLicense) {
        LicenseScreen(onBack = { showLicense = false })
    }
    if (showAttribution) {
        AttributionScreen(onBack = { showAttribution = false })
    }
}

/** 启动 ACP 服务 + 注册 TwinAcpHandler (接收配对请求) */
private suspend fun startAcpForTwin(ctx: android.content.Context, agentName: String) {
    try {
        val profile = com.mengpaw.kernel.agent.AgentProfile.load(agentName)
        // SECURITY: Derive shared secret from device fingerprint for baseline auth
        val deviceFingerprint = try { com.mengpaw.kernel.acp.AcpCrypto.myFingerprint() } catch (_: Exception) { "device-${System.currentTimeMillis()}" }
        val sharedSecret = java.security.MessageDigest.getInstance("SHA-256")
            .digest("twin:$deviceFingerprint:$agentName".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val server = com.mengpaw.kernel.acp.AcpServer(profile, 9876, sharedSecret)
        val transport = com.mengpaw.kernel.acp.AcpHttpTransport(server, 9876)
        server.registerTransport(transport)

        // 注册 TwinAcpHandler — 处理 CAPABILITY_ANNOUNCE 等孪生消息
        val syncEngine = com.mengpaw.plugin.memorytwin.TwinSyncEngine(
            serverSupplier = { server }, transportSupplier = { transport },
            agentName = agentName, deviceId = deviceFingerprint,
            deviceName = android.os.Build.MODEL ?: "Android")
        val handler = com.mengpaw.plugin.memorytwin.TwinAcpHandler(syncEngine)
        server.registerHandler(handler)
        syncEngine.startAutoSync()  // 启动自动同步 (每60秒)
        // 加载 mDNS 发现的框架节点作为同步目标
        val frameworkPeers = com.mengpaw.plugin.framework.FrameworkPeerStore.loadAll()
        syncEngine.updatePeers(frameworkPeers.map { fp ->
            com.mengpaw.plugin.memorytwin.TwinPeerInfo(
                peerId = fp.name, agentName = fp.name,
                address = fp.address.split(":").firstOrNull() ?: fp.address,
                port = fp.port)
        })
        android.util.Log.i("MengPawTwin", "已注册 + 自动同步 + ${frameworkPeers.size} 个节点")

        transport.startListener()
        com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.acpServer = server
        com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.acpTransport = transport
        com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.twinProfile = profile
        // 标记已激活, 下次启动自动恢复
        java.io.File(ctx.filesDir, "twin_activated").writeText(agentName)
        android.util.Log.i("MengPawTwin", "孪生服务已启动 (${frameworkPeers.size} 个节点)")
    } catch (e: Exception) {
        android.util.Log.e("MengPawTwin", "启动失败: ${e.message}", e)
    }
}

