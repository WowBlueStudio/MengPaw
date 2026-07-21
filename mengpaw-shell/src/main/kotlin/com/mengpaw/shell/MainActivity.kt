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

class MainActivity : ComponentActivity() {
    private val settingsViewModel by viewModels<SettingsViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DataPathsInitializer.initialize(this)
        KernelLog.setLogger(AndroidLogger())
        enableEdgeToEdge()
        com.mengpaw.plugin.pad.PadPlugin.init(this)

        // Register system-level wake alarms — survive Doze
        com.mengpaw.kernel.trigger.TriggerEngine.registerSystemWake(this, 10)
        com.mengpaw.kernel.trigger.TriggerEngine.registerCronAlarm(this)

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
    // Sync loop mode from settings to agent view model
    LaunchedEffect(settingsState.loopMode) { agentViewModel.loopMode = settingsState.loopMode }
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
                    onCreateAgent = { name -> agentViewModel.createAgent(name); close() }
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
        // Plugins: real data from PluginManager
        val pluginItems = remember {
            com.mengpaw.kernel.plugin.PluginManager.globalInstance.listAll().map { (plugin, status) ->
                FrameworkItem(
                    name = plugin.metadata.id,
                    category = if (plugin.metadata.id in BUILTIN_PLUGIN_IDS) ItemCategory.BUILTIN else ItemCategory.OFFICIAL,
                    summary = plugin.metadata.description,
                    docMarkdown = "## ${plugin.metadata.name}\n\n${plugin.metadata.description}\n\n版本: ${plugin.metadata.version}\n状态: ${status.name}"
                )
            }.ifEmpty {
                // Fallback: show built-in info when no plugins installed
                listOf(
                    FrameworkItem("fs-plugin", ItemCategory.BUILTIN, "文件系统插件", "## fs-plugin\n\n允许 Agent 读写 Android 文件系统。"),
                    FrameworkItem("memory-plugin", ItemCategory.BUILTIN, "记忆管理插件", "## memory-plugin\n\n基于 Markdown 文件的长期记忆后端。")
                )
            }
        }
        // CLI namespaces: real data from engine
        val cliItems = remember(activeAgent) {
            val ns = agentViewModel.activeNamespaces()
            ns.map { name ->
                FrameworkItem(name, ItemCategory.BUILTIN, "$name.* 命令组", "")
            }
        }
        // Tools from MCP server (wired via engine)
        val toolItems = remember(activeAgent) {
            val engine = agentViewModel.activeEngine() ?: return@remember emptyList<FrameworkItem>()
            try {
                val mcp = com.mengpaw.kernel.mcp.McpServer(engine.getPluginManager())
                mcp.listTools().map { tool ->
                    FrameworkItem(tool.name, ItemCategory.BUILTIN, tool.description, "")
                }
            } catch (_: Exception) { emptyList() }
        }
        // Skills: from skill plugin if installed
        val skillItems = remember {
            val pm = com.mengpaw.kernel.plugin.PluginManager.globalInstance
            val skillPlugin = pm.get("skill-plugin")
            if (skillPlugin != null && pm.status("skill-plugin") == com.mengpaw.kernel.plugin.PluginStatus.ACTIVE) {
                skillPlugin.commands.keys.map { name ->
                    FrameworkItem("skill.$name", ItemCategory.BUILTIN, "", "")
                }
            } else {
                listOf(
                    FrameworkItem("skill.ls", ItemCategory.BUILTIN, "列出可用技能 (需安装 skill-plugin)", ""),
                    FrameworkItem("skill.run", ItemCategory.BUILTIN, "运行指定技能 (需安装 skill-plugin)", "")
                )
            }
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
                agentViewModel.configureLlm(
                    endpoint = saved.endpoint,
                    apiKey = saved.apiKey,
                    model = saved.model,
                    agentName = activeAgent,
                    agentLang = settingsViewModel.state.value.effectiveAgentLanguage
                )
            },
            cliItems = cliItems,
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
                            summary = content.lines().firstOrNull()?.removePrefix("#")?.trim() ?: "",
                            docMarkdown = content
                        )
                    } ?: emptyList()
            },
            onRefreshWorkspace = { workspaceVersion++ }
        )
    }
}
