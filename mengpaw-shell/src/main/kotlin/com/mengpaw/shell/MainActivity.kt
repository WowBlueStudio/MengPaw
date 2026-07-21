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

    if (showSplash) {
        WowBlueSplash(onFinished = { showSplash = false })
        return
    }

    val agentViewModel: AgentViewModel = viewModel()
    val activeAgent by agentViewModel.activeAgent.collectAsState()
    val sessionHistory by agentViewModel.sessionHistory.collectAsState()
    val hideCompacted by agentViewModel.hideCompacted.collectAsState()
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

        // ── Framework item lists ──
        val cliItems = remember {
            listOf(
                FrameworkItem("self.trigger", ItemCategory.BUILTIN, "管理 Agent 定时触发器", "## self.trigger\n\n创建、启用、禁用 Agent 的定时触发器（Cron / Lifetime）。\n\n```\nself.trigger add cron \"0 9 * * *\" \"早安播报\"\nself.trigger list\n```"),
                FrameworkItem("self.log", ItemCategory.BUILTIN, "查看 Agent 运行日志", "## self.log\n\n读取 Agent 的执行日志和错误收集器内容。"),
                FrameworkItem("plugin.install", ItemCategory.BUILTIN, "安装插件", "## plugin.install\n\n从插件市场或本地文件安装插件。\n\n```\nplugin.install <plugin-id>\nplugin.install fs-plugin\n```"),
                FrameworkItem("plugin.list", ItemCategory.BUILTIN, "列出已安装插件", "## plugin.list\n\n显示所有已安装插件及其状态（active / installed / error）。"),
                FrameworkItem("agent.new", ItemCategory.BUILTIN, "创建新智能体", "## agent.new\n\n创建一个新的 Agent 会话，可指定框架隶属。\n\n```\nagent.new <name> [framework]\n```")
            )
        }
        val pluginItems = remember {
            listOf(
                FrameworkItem("pad-plugin", ItemCategory.BUILTIN, "PAD 悬浮窗插件", "## pad-plugin\n\n提供呼吸灯小圆点显示 Agent 工作状态。支持 SYSTEM_ALERT_WINDOW 权限。"),
                FrameworkItem("fs-plugin", ItemCategory.BUILTIN, "文件系统插件", "## fs-plugin\n\n允许 Agent 读写 Android 文件系统。\n\n权限：READ/WRITE_EXTERNAL_STORAGE"),
                FrameworkItem("memory-plugin", ItemCategory.BUILTIN, "记忆管理插件", "## memory-plugin\n\n基于 Markdown 文件的长期记忆后端。支持 Agent 自主创建、更新、检索记忆文档。")
            )
        }
        val toolItems = remember {
            listOf(
                FrameworkItem("sys.shell", ItemCategory.BUILTIN, "Shell 命令执行器", "## sys.shell\n\n在 Android 设备上执行 Shell 命令并返回结果。\n\n超时限制：60s（可在 Agent 设置中调整）"),
                FrameworkItem("sys.screenshot", ItemCategory.BUILTIN, "屏幕截图", "## sys.screenshot\n\n捕获当前屏幕内容并返回给 Agent 分析。")
            )
        }
        val skillItems = remember {
            listOf(
                FrameworkItem("translate", ItemCategory.BUILTIN, "中英互译中间件", "## translate\n\n自动检测中英文输入并在发送给 LLM 前翻译。对于英文优化模型可节省约 40% token 消耗。"),
                FrameworkItem("memory-middleware", ItemCategory.BUILTIN, "记忆注入中间件", "## memory-middleware\n\n在每次 LLM 请求前注入 Agent 的长期记忆文档作为 System Prompt 前缀。")
            )
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
            workspaceItems = remember(activeAgent) {
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
            }
        )
    }
}
