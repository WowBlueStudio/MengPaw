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
import com.mengpaw.core.AndroidLogger
import com.mengpaw.core.DataPathsInitializer
import com.mengpaw.design.theme.ArcoTheme
import com.mengpaw.kernel.KernelLog
import com.mengpaw.shell.ui.localization.AppStrings
import com.mengpaw.shell.ui.localization.ChineseStrings
import com.mengpaw.shell.ui.localization.EnglishStrings
import com.mengpaw.shell.ui.screens.*

/** Plugin IDs that are bundled with the shell APK (显示为"内置"分类). */
private val BUILTIN_PLUGIN_IDS = setOf("memory-plugin", "skill-plugin", "pad-plugin", "dev-plugin")

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
        com.mengpaw.core.namespace.SysExecutor.init(this)
        KernelLog.setLogger(AndroidLogger())
        enableEdgeToEdge()
        com.mengpaw.plugin.pad.PadPlugin.init(this)

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
        PluginViewModel.registerPluginClass("skill-plugin", "com.mengpaw.plugin.skill.SkillPlugin")
        PluginViewModel.registerPluginClass("self-plugin", "com.mengpaw.plugin.self.SelfPlugin")
        PluginViewModel.registerPluginClass("ui-plugin", "com.mengpaw.plugin.ui.UiPlugin")
        PluginViewModel.registerPluginClass("proc-plugin", "com.mengpaw.plugin.proc.ProcPlugin")
        PluginViewModel.registerPluginClass("clipboard-plugin", "com.mengpaw.plugin.clipboard.ClipboardPlugin")
        PluginViewModel.registerPluginClass("notification-plugin", "com.mengpaw.plugin.notification.NotificationPlugin")
        PluginViewModel.registerPluginClass("pad-plugin", "com.mengpaw.plugin.pad.PadPlugin")
        PluginViewModel.registerPluginClass("dev-plugin", "com.mengpaw.plugin.dev.DevPlugin")

        // Handle URL sent from Browser APK (singleTask — onNewIntent)
        handleOpenUrl(intent)

        setContent {
            val settingsState by settingsViewModel.state.collectAsState()
            val strings: AppStrings = if (settingsState.useChinese) ChineseStrings else EnglishStrings
            ArcoTheme(darkTheme = settingsState.darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) { MengPawApp(strings, settingsViewModel) }
            }
        }
    }

    /** Handle incoming OPEN_URL intent from Browser APK without creating a new task. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenUrl(intent)
    }

    private fun handleOpenUrl(intent: Intent?) {
        if (intent?.action == "com.mengpaw.action.OPEN_URL") {
            val url = intent.getStringExtra("url")
            if (url != null) {
                // Store URL so Agent can access it via browser.open or as context
                intent.putExtra("url", url)
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
    val settingsState by settingsViewModel.state.collectAsState()

    if (showSplash) {
        WowBlueSplash(onFinished = { showSplash = false })
        return
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
    LaunchedEffect(agentViewModel) {
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
                        agentViewModel.switchAgent(record.agentName)
                        close()
                    },
                    onDeleteSession = { agentViewModel.deleteSession(it) },
                    onCompactSession = { agentViewModel.compactSession(it) },
                    onRepairSession = { agentViewModel.repairSession(it) },
                    onNewSessionFor = { agentName, framework ->
                        agentViewModel.newSessionFor(agentName, framework)
                        close()
                    }
                )
            }
        )
    }

    // ── Full-screen overlays for Plugins/Settings ──
    if (showPlugins) {
        PluginMarketScreen(
            onNavigateBack = { showPlugins = false },
            onNavigateToDetail = {}
        )
    }
    if (showSettings) {
        val agentFramework = remember(activeAgent) { agentViewModel.frameworkFor(activeAgent) }
        val (agentEp, agentModel) = remember(activeAgent) { agentViewModel.agentConfig(activeAgent) }

        // ── Workspace refresh trigger ──
        var workspaceVersion by remember { mutableIntStateOf(0) }

        // ── Dynamic framework item lists ──
        // Plugins: installed plugins from PluginManager + kernel built-in namespaces
        val pluginItems = remember {
            val pm = com.mengpaw.kernel.plugin.PluginManager.globalInstance
            val installed = pm.listAll().map { (plugin, status) ->
                FrameworkItem(
                    name = plugin.metadata.name,
                    category = if (plugin.metadata.id in BUILTIN_PLUGIN_IDS) ItemCategory.BUILTIN else ItemCategory.OFFICIAL,
                    summary = plugin.metadata.description,
                    docMarkdown = "## ${plugin.metadata.name}\n\n${plugin.metadata.description}\n\nID: ${plugin.metadata.id}\n版本: ${plugin.metadata.version}\n状态: ${status.name}\n命令数: ${plugin.commands.size}"
                )
            }
            // Always show kernel built-in namespaces as "内置插件"
            val builtins = listOf(
                FrameworkItem("self (内置)", ItemCategory.BUILTIN, "Agent 自省 — 状态/配置/统计/版本/头像/主题/通知/时间", ""),
                FrameworkItem("agent (内置)", ItemCategory.BUILTIN, "文档管理 — 记忆/CLI/档案/审计/梦境/存储", ""),
                FrameworkItem("plugin (内置)", ItemCategory.BUILTIN, "插件管理 — 市场/搜索/安装/卸载/启停/升级", ""),
                FrameworkItem("sys (内置)", ItemCategory.BUILTIN, "系统信息 — 电量/网络/CPU/存储/定位/剪贴板", ""),
            )
            if (installed.isEmpty()) builtins else installed + builtins
        }
        // ── MengPaw CLI: all built-in commands by namespace + MCP tools ──
        val toolItems = remember(activeAgent) {
            val engine = agentViewModel.activeEngine()
            // Core CLI commands organized by namespace
            val selfTools = listOf(
                FrameworkItem("self.status", ItemCategory.BUILTIN, "Agent 运行状态查询", "## self.status\n\n查询 Agent 当前运行状态（Idle/Running/Finished/Error）。"),
                FrameworkItem("self.config [key=value]", ItemCategory.BUILTIN, "查看或修改 Agent 配置", ""),
                FrameworkItem("self.stats", ItemCategory.BUILTIN, "内存/CPU/线程统计信息", ""),
                FrameworkItem("self.version", ItemCategory.BUILTIN, "MengPaw 版本号", ""),
                FrameworkItem("self.time [format]", ItemCategory.BUILTIN, "当前时间 (iso/date/time/timestamp)", ""),
                FrameworkItem("self.tools [namespace]", ItemCategory.BUILTIN, "按命名空间列出所有可用命令", ""),
                FrameworkItem("self.notify.message <text>", ItemCategory.BUILTIN, "Agent 推送消息到聊天", ""),
                FrameworkItem("self.notify.banner <text> [--level]", ItemCategory.BUILTIN, "Agent 推送通知横幅 (info/success/warn/error)", ""),
                FrameworkItem("self.avatar <path>", ItemCategory.BUILTIN, "设置 Agent 头像", ""),
                FrameworkItem("self.theme primary=#xxx surface=#xxx", ItemCategory.BUILTIN, "修改主题色", ""),
                FrameworkItem("self.trigger add|list|remove|topics", ItemCategory.BUILTIN, "CRON/LIFETIME 触发器管理", ""),
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
                FrameworkItem("agent.dream", ItemCategory.BUILTIN, "触发梦境整理（记忆归档/压缩）", ""),
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
                FrameworkItem("sys.location", ItemCategory.BUILTIN, "GPS 定位 (需位置权限)", ""),
                FrameworkItem("sys.camera", ItemCategory.BUILTIN, "相机信息 (需相机权限)", ""),
                FrameworkItem("sys.apps", ItemCategory.BUILTIN, "已安装应用列表 (需应用列表权限)", ""),
            )
            // MCP tools if available
            val mcpTools = if (engine != null) try {
                com.mengpaw.kernel.mcp.McpServer(engine.getPluginManager())
                    .listTools().map { FrameworkItem(it.name, ItemCategory.OFFICIAL, it.description, "") }
            } catch (_: Exception) { emptyList() } else emptyList()
            selfTools + agentTools + pluginTools + sysTools + mcpTools
        }
        // Skills: actual skill files from disk + skill plugin commands
        val skillItems = remember {
            val skillsDir = java.io.File(com.mengpaw.kernel.DataPaths.SKILLS)
            val skillFiles = if (skillsDir.exists()) {
                skillsDir.listFiles()
                    ?.filter { it.extension == "md" }
                    ?.map { FrameworkItem(it.nameWithoutExtension, ItemCategory.BUILTIN,
                        try { it.readText().lines().firstOrNull()?.removePrefix("#")?.trim() ?: "" }
                        catch (_: Exception) { "" }, "") }
                    ?: emptyList()
            } else emptyList()

            // Add skill plugin management commands
            val pm = com.mengpaw.kernel.plugin.PluginManager.globalInstance
            val skillPlugin = pm.get("skill-plugin")
            val skillMgmt = if (skillPlugin != null && pm.status("skill-plugin") == com.mengpaw.kernel.plugin.PluginStatus.ACTIVE) {
                skillPlugin.commands.keys.map { name ->
                    FrameworkItem("⚙ skill.$name", ItemCategory.BUILTIN, "技能管理命令", "")
                }
            } else emptyList()

            if (skillFiles.isEmpty() && skillMgmt.isEmpty()) {
                listOf(FrameworkItem("skill.ls", ItemCategory.BUILTIN, "列出可用技能的索引 → Agent 按需加载具体 Skill", ""))
            } else skillFiles + skillMgmt
        }

        SettingsScreen(
            onNavigateBack = { showSettings = false },
            onNavigateToPluginMarket = { showPlugins = true; showSettings = false },
            viewModel = settingsViewModel,
            activeAgentName = activeAgent,
            agentFramework = agentFramework,
            activeAgentEndpoint = agentEp,
            activeAgentModel = agentModel,
            onAgentSelectProvider = { saved ->
                // Runtime init is handled by AgentRuntime when user exits settings
            },
            pluginItems = pluginItems,
            toolItems = toolItems,
            skillItems = skillItems,
            agentPluginItems = pluginItems,
            agentToolItems = toolItems,
            agentSkillItems = skillItems,
            workspaceItems = remember(activeAgent, workspaceVersion) {
                val dir = java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, activeAgent)
                dir.listFiles()
                    ?.filter { it.extension == "md" }
                    ?.sortedBy { it.name }
                    ?.map { file ->
                        val content = try { file.readText() } catch (_: Exception) { "" }
                        FrameworkItem(
                            name = file.name,
                            category = ItemCategory.BUILTIN,
                            summary = extractSummary(content),
                            docMarkdown = content
                        )
                    } ?: emptyList()
            },
            onRefreshWorkspace = { workspaceVersion++ }
        )
    }
}
