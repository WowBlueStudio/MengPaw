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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.mengpaw.core.AndroidLogger
import com.mengpaw.core.DataPathsInitializer
import com.mengpaw.design.theme.ArcoTheme
import com.mengpaw.kernel.KernelLog
import com.mengpaw.shell.ui.localization.AppStrings
import com.mengpaw.shell.ui.localization.ChineseStrings
import com.mengpaw.shell.ui.localization.EnglishStrings
import com.mengpaw.shell.ui.screens.*

/** Plugin IDs that are bundled with the shell APK (显示为"内置"分类). */
private val BUILTIN_PLUGIN_IDS = setOf(
    "memory-plugin", "skill-plugin", "framework-plugin", "dev-plugin",
    "fs-plugin", "net-plugin", "self-plugin", "clipboard-plugin", "notification-plugin"
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

        DataPathsInitializer.initialize(this)
        com.mengpaw.kernel.plugin.PluginManager.initializeGlobalInstance(
            com.mengpaw.kernel.AgentEngine.CORE_VERSION)
        com.mengpaw.core.namespace.SysExecutor.init(this)
        com.mengpaw.core.namespace.SysExecutor.setActivity(this)
        com.mengpaw.core.security.IntegrityGuard.globalInstance.init(this)
        com.mengpaw.core.AgentTemplates.init(this)
        com.mengpaw.kernel.agent.AgentDocs.bootstrapper = { name -> com.mengpaw.core.AgentTemplates.bootstrapAgent(name) }

        // 自动恢复孪生服务 (已配对的设备不需要每次5连击)
        autoRestoreTwinIfNeeded()
        KernelLog.setLogger(AndroidLogger())
        com.mengpaw.shell.ui.components.TokenStatsCollector.load()
        enableEdgeToEdge()

        // ── 框架发现插件：初始化 mDNS 服务 ──
        com.mengpaw.plugin.framework.FrameworkDiscovery.instance =
            com.mengpaw.plugin.framework.FrameworkDiscovery(this).apply {
                frameworkName = "MengPaw"
                frameworkVersion = com.mengpaw.kernel.AgentEngine.CORE_VERSION
                // Agent 列表从文件系统读取
                val agentsDir = java.io.File(com.mengpaw.kernel.DataPaths.AGENTS)
                agentNames = agentsDir.listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?.map { it.name } ?: listOf("MengPaw")
            }
        com.mengpaw.plugin.framework.FrameworkDiscovery.instance?.register()
        com.mengpaw.plugin.framework.FrameworkDiscovery.instance?.startContinuousDiscovery()

        // ── Trigger engine init: load persisted triggers, set context, start loop ──
        com.mengpaw.kernel.trigger.TriggerEngine.setContext(this)
        com.mengpaw.kernel.trigger.TriggerEngine.load()
        com.mengpaw.kernel.trigger.TriggerEngine.registerSystemWake(this, 10)
        com.mengpaw.kernel.trigger.TriggerEngine.refreshCronAlarm()
        // TriggerEngine.start() deferred to MengPawApp composable —
        // onFire must be wired first to avoid silent trigger consumption

        // Start persistent foreground notification to keep process alive
        com.mengpaw.shell.service.ShellService.start(this)

        // Register zero-overhead system event receiver (no polling)
        com.mengpaw.shell.service.EventReceiver.register(this)
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

        // Auto-install bundled plugins (always available, no marketplace download needed)
        CoroutineScope(Dispatchers.IO).launch {
            val bundled = listOf(
                "framework-plugin" to "com.mengpaw.plugin.framework.FrameworkPlugin",
                "memory-plugin" to "com.mengpaw.plugin.memory.MemoryPlugin",
                "skill-plugin" to "com.mengpaw.plugin.skill.SkillPlugin",
                "dev-plugin" to "com.mengpaw.plugin.dev.DevPlugin",
                "fs-plugin" to "com.mengpaw.plugin.fs.FsPlugin",
                "net-plugin" to "com.mengpaw.plugin.net.NetPlugin",
                "self-plugin" to "com.mengpaw.plugin.self.SelfPlugin",
                "clipboard-plugin" to "com.mengpaw.plugin.clipboard.ClipboardPlugin",
                "notification-plugin" to "com.mengpaw.plugin.notification.NotificationPlugin",
                "memory-twin-plugin" to "com.mengpaw.plugin.memorytwin.MemoryTwinPlugin",
            )
            val pm = com.mengpaw.kernel.plugin.PluginManager.globalInstance
            for ((id, className) in bundled) {
                try {
                    if (pm.get(id) == null) {
                        val plugin = Class.forName(className).getDeclaredConstructor().newInstance() as com.mengpaw.kernel.plugin.Plugin
                        pm.install(plugin).fold(
                            onSuccess = { pm.activate(id) },
                            onFailure = { android.util.Log.w("MengPaw", "Bundled plugin $id: ${it.message}") }
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.w("MengPaw", "Auto-install $id failed: ${e.message}")
                }
            }
        }

        // Handle URL sent from Browser APK (singleTask — onNewIntent)
        handleOpenUrl(intent)

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

    /** 如果之前激活过孪生, 自动恢复 ACP + 同步 (无需5连击) */
    private fun autoRestoreTwinIfNeeded() {
        val marker = java.io.File(filesDir, "twin_activated")
        if (!marker.exists()) return
        val agentName = try { marker.readText().trim() } catch (_: Exception) { "MengPaw" }
        CoroutineScope(Dispatchers.IO).launch {
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
            leftSidebarContent = { close ->
                SidebarContent(
                    onNavigateToPlugins = { showPlugins = true; close() },
                    onNavigateToSettings = { showSettings = true; close() },
                    onClose = { close() },
                    activeAgent = activeAgent,
                    onSwitchAgent = { name -> agentViewModel.switchAgent(name); close() },
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
                            val installResult = pm.install(plugin)
                            android.util.Log.i("MengPawTwin", "install结果: ${installResult.isSuccess}")
                            installResult.fold(
                                onSuccess = {
                                    pm.activate(plugin.metadata.id).fold(
                                        onSuccess = {
                                            android.util.Log.i("MengPawTwin", "插件激活成功")
                                            CoroutineScope(Dispatchers.IO).launch {
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
                                            CoroutineScope(Dispatchers.IO).launch {
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

    // Plugins: computed once, stored in mutable state for reactive updates
    var pluginItems by remember { mutableStateOf(emptyList<FrameworkItem>()) }
    LaunchedEffect(Dispatchers.IO) {
        val pm = com.mengpaw.kernel.plugin.PluginManager.globalInstance
        val installed = pm.listAll().map { (plugin, status) ->
            FrameworkItem(name = plugin.metadata.name,
                category = if (plugin.metadata.id in BUILTIN_PLUGIN_IDS) ItemCategory.BUILTIN else ItemCategory.OFFICIAL,
                summary = plugin.metadata.description,
                docMarkdown = "## ${plugin.metadata.name}\n\n${plugin.metadata.description}\n\nID: ${plugin.metadata.id}\n版本: ${plugin.metadata.version}\n状态: ${status.name}\n命令数: ${plugin.commands.size}")
        }
        val builtins = listOf(
            FrameworkItem("self (内置)", ItemCategory.BUILTIN, "Agent 自省 — 状态/配置/统计/版本/头像/主题/通知/时间", ""),
            FrameworkItem("agent (内置)", ItemCategory.BUILTIN, "文档管理 — 记忆/CLI/档案/审计/梦境/存储", ""),
            FrameworkItem("plugin (内置)", ItemCategory.BUILTIN, "插件管理 — 市场/搜索/安装/卸载/启停/升级", ""),
            FrameworkItem("sys (内置)", ItemCategory.BUILTIN, "系统信息 — 电量/网络/CPU/存储/定位/剪贴板", ""),
        )
        pluginItems = if (installed.isEmpty()) builtins else installed + builtins
    }

    // ── MengPaw CLI: static built-in command reference
    val toolItems = remember(activeAgent) {
        val engine = agentViewModel.activeEngine()
        val selfTools = listOf(
            FrameworkItem("self.status", ItemCategory.BUILTIN, "Agent 运行状态查询", ""),
            FrameworkItem("self.config [key=value]", ItemCategory.BUILTIN, "查看或修改 Agent 配置", ""),
            FrameworkItem("self.stats", ItemCategory.BUILTIN, "内存/CPU/线程统计信息", ""),
            FrameworkItem("self.version", ItemCategory.BUILTIN, "MengPaw 版本号", ""),
            FrameworkItem("self.time [format]", ItemCategory.BUILTIN, "当前时间", ""),
            FrameworkItem("self.tools [namespace]", ItemCategory.BUILTIN, "列出所有可用命令", ""),
            FrameworkItem("self.notify.message <text>", ItemCategory.BUILTIN, "Agent 推送消息到聊天", ""),
            FrameworkItem("self.notify.banner <text> [--level]", ItemCategory.BUILTIN, "Agent 推送通知横幅", ""),
            FrameworkItem("self.avatar <path>", ItemCategory.BUILTIN, "设置 Agent 头像", ""),
            FrameworkItem("self.theme primary=#xxx surface=#xxx", ItemCategory.BUILTIN, "修改主题色", ""),
            FrameworkItem("self.trigger add|list|remove|topics", ItemCategory.BUILTIN, "CRON/LIFETIME 触发器", ""),
        )
        val agentTools = listOf(
            FrameworkItem("agent.cli", ItemCategory.BUILTIN, "查阅完整 CLI.md 命令参考", ""),
            FrameworkItem("agent.docs", ItemCategory.BUILTIN, "列出所有 Agent 文档", ""),
            FrameworkItem("agent.memory [query]", ItemCategory.BUILTIN, "记忆索引/搜索", ""),
            FrameworkItem("agent.memory.record <content>", ItemCategory.BUILTIN, "手动记录一条记忆", ""),
            FrameworkItem("agent.profile", ItemCategory.BUILTIN, "查看 Agent 身份档案", ""),
            FrameworkItem("agent.soul", ItemCategory.BUILTIN, "查看 Agent 灵魂设定", ""),
            FrameworkItem("agent.audit [N]", ItemCategory.BUILTIN, "查看最近 N 条命令审计日志", ""),
            FrameworkItem("agent.browser-tools", ItemCategory.BUILTIN, "MP浏览器插件开发能力参考", ""),
            FrameworkItem("agent.dream", ItemCategory.BUILTIN, "触发梦境整理", ""),
            FrameworkItem("agent.cleanup", ItemCategory.BUILTIN, "清理过期文件和归档记忆", ""),
            FrameworkItem("agent.storage", ItemCategory.BUILTIN, "工作区存储空间报告", ""),
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
            FrameworkItem("sys.battery", ItemCategory.BUILTIN, "电量/充电状态/温度", ""),
            FrameworkItem("sys.network", ItemCategory.BUILTIN, "网络类型/信号强度", ""),
            FrameworkItem("sys.cpu", ItemCategory.BUILTIN, "CPU 使用率/核心数", ""),
            FrameworkItem("sys.memory", ItemCategory.BUILTIN, "内存使用量", ""),
            FrameworkItem("sys.storage", ItemCategory.BUILTIN, "存储空间使用情况", ""),
            FrameworkItem("sys.display", ItemCategory.BUILTIN, "屏幕参数", ""),
            FrameworkItem("sys.sensors", ItemCategory.BUILTIN, "传感器列表", ""),
            FrameworkItem("sys.clipboard", ItemCategory.BUILTIN, "剪贴板内容", ""),
            FrameworkItem("sys.location", ItemCategory.BUILTIN, "GPS 定位", ""),
            FrameworkItem("sys.camera", ItemCategory.BUILTIN, "相机信息", ""),
            FrameworkItem("sys.apps", ItemCategory.BUILTIN, "已安装应用列表", ""),
        )
        val mcpTools = if (engine != null) try {
            com.mengpaw.kernel.mcp.McpServer(engine.getPluginManager())
                .listTools().map { FrameworkItem(it.name, ItemCategory.OFFICIAL, it.description, "") }
        } catch (_: Exception) { emptyList() } else emptyList()
        selfTools + agentTools + pluginTools + sysTools + mcpTools
    }

    var skillItems by remember { mutableStateOf(emptyList<FrameworkItem>()) }
    LaunchedEffect(Dispatchers.IO) {
        val skillsDir = java.io.File(com.mengpaw.kernel.DataPaths.SKILLS)
        val skillFiles = if (skillsDir.exists()) {
            skillsDir.listFiles()?.filter { it.extension == "md" }
                ?.map { FrameworkItem(it.nameWithoutExtension, ItemCategory.BUILTIN,
                    try { it.readText().lines().firstOrNull()?.removePrefix("#")?.trim() ?: "" } catch (_: Exception) { "" }, "") }
                ?: emptyList()
        } else emptyList()
        val pm = com.mengpaw.kernel.plugin.PluginManager.globalInstance
        val skillPlugin = pm.get("skill-plugin")
        val skillMgmt = if (skillPlugin != null && pm.status("skill-plugin") == com.mengpaw.kernel.plugin.PluginStatus.ACTIVE) {
            skillPlugin.commands.keys.map { name -> FrameworkItem("⚙ skill.$name", ItemCategory.BUILTIN, "技能管理命令", "") }
        } else emptyList()
        skillItems = if (skillFiles.isEmpty() && skillMgmt.isEmpty())
            listOf(FrameworkItem("skill.ls", ItemCategory.BUILTIN, "列出可用技能的索引", ""))
        else skillFiles + skillMgmt
    }

    var workspaceItems by remember { mutableStateOf(emptyList<FrameworkItem>()) }
    LaunchedEffect(activeAgent, workspaceVersion) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val dir = java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, activeAgent)
            val items = dir.listFiles()?.filter { it.extension == "md" }?.sortedBy { it.name }
                ?.map { file ->
                    val content = try { file.readText() } catch (_: Exception) { "" }
                    FrameworkItem(name = file.name, category = ItemCategory.BUILTIN, summary = extractSummary(content), docMarkdown = content)
                } ?: emptyList()
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
            agentPluginItems = pluginItems,
            agentToolItems = toolItems,
            agentSkillItems = skillItems,
            workspaceItems = workspaceItems,
            onRefreshWorkspace = { workspaceVersion++ }
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

