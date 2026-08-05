// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mengpaw.design.theme.ArcoTheme
import com.mengpaw.shell.PluginRegistrar
import com.mengpaw.shell.ui.localization.AppStrings
import com.mengpaw.shell.ui.localization.ChineseStrings
import com.mengpaw.shell.ui.localization.EnglishStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Compose 根 — 从 MainActivity.kt 拆出 (2026-08-04, >40KB UI 文件拆分)。
 * 主题装配 (collectAsState → strings → isDark → ArcoTheme) + 原 MengPawApp 全部 UI 树。
 * agentViewModel 在 Compose 内 viewModel() 获取 — 与 Activity 侧 viewModels<AgentViewModel>()
 * 同 ViewModelStore 同实例 (MainActivity 既有契约, 用于浏览器提炼任务触发)。
 */

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

/**
 * 用系统其他软件打开工作区 md 文档 — FileProvider 共享 + ACTION_VIEW。
 * 优先 text/markdown MIME; 无处理器时回退 text/plain; 两者皆无 → Toast 提示。
 * 选择器中出现 MP 浏览器时由浏览器自行渲染 (content:// md 支持见浏览器侧)。
 */
private fun openDocExternally(context: Context, file: java.io.File, strings: AppStrings) {
    fun launch(mime: String): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(context.packageManager) == null) return false
        context.startActivity(intent)
        return true
    }
    try {
        if (launch("text/markdown") || launch("text/plain")) return
        android.widget.Toast.makeText(context, strings.editOpenFailed, android.widget.Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "${strings.editOpenFailed} ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(settingsViewModel: SettingsViewModel) {
    val settingsState by settingsViewModel.state.collectAsState()
    val strings: AppStrings = if (settingsState.useChinese) ChineseStrings else EnglishStrings
    val ctx = LocalContext.current
    val isDark = when (settingsState.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> (ctx.resources.configuration.uiMode
            and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    ArcoTheme(darkTheme = isDark) {
        Surface(modifier = Modifier.fillMaxSize()) {
            AppRootContent(strings, settingsState, settingsViewModel, isDark)
        }
    }
}

@Composable
private fun AppRootContent(
    strings: AppStrings,
    settingsState: SettingsState,
    settingsViewModel: SettingsViewModel,
    isDark: Boolean
) {
    var showSplash by remember { mutableStateOf(true) }
    var showPlugins by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    var showAttribution by remember { mutableStateOf(false) }

    val context = LocalContext.current

    if (showSplash) {
        WowBlueSplash(onFinished = { showSplash = false })
        return
    }

    // ── 全局返回手势：逐层回退，主页再退到后台 ──
    val overlayActive = showSettings || showPlugins || showLicense || showAttribution
    BackHandler(enabled = overlayActive) {
        when {
            showLicense -> showLicense = false
            showAttribution -> showAttribution = false
            showSettings && showPlugins -> showSettings = false
            showSettings -> showSettings = false
            showPlugins -> showPlugins = false
        }
    }

    // splash 结束后：根据亮/暗主题切换状态栏图标颜色
    val view = LocalView.current
    DisposableEffect(isDark) {
        val w = (view.context as android.app.Activity).window
        WindowCompat.getInsetsController(w, view)
            .isAppearanceLightStatusBars = !isDark
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
                com.mengpaw.kernel.llm.AdaptiveLlmProvider(saved.endpoint, saved.apiKey, saved.model,
                    networkGate = com.mengpaw.shell.service.NetworkConditionMonitor),
                settingsViewModel.state.value.effectiveAgentLanguage,
                swarmRoles = settingsViewModel.state.value.swarmRoles
            )
        } else if (settingsViewModel.state.value.swarmRoles.isNotEmpty()) {
            // 只有角色路由配置（无主 key）也要同步
            agentViewModel.applyConfiguration(
                "", "", "", com.mengpaw.shell.ui.screens.model.UnconfiguredLlmProvider(),
                settingsViewModel.state.value.effectiveAgentLanguage,
                swarmRoles = settingsViewModel.state.value.swarmRoles
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

    // Sync auto-translate switch from settings (opt-in, 默认关闭 — v0.28.6)
    LaunchedEffect(settingsState.autoTranslate) {
        agentViewModel.setAutoTranslate(settingsState.autoTranslate)
    }

    // ── Apply API config when exiting Settings (lightweight, no auto-start) ──
    LaunchedEffect(showSettings) {
        if (!showSettings) {
            val s = settingsState
            if (s.apiKey.isNotBlank()) {
                agentViewModel.applyConfiguration(
                    s.apiEndpoint, s.apiKey, s.modelName,
                    com.mengpaw.kernel.llm.AdaptiveLlmProvider(s.apiEndpoint, s.apiKey, s.modelName,
                        networkGate = com.mengpaw.shell.service.NetworkConditionMonitor),
                    s.effectiveAgentLanguage,
                    swarmRoles = s.swarmRoles
                )
            } else if (s.swarmRoles.isNotEmpty()) {
                agentViewModel.applyConfiguration(
                    "", "", "", com.mengpaw.shell.ui.screens.model.UnconfiguredLlmProvider(),
                    s.effectiveAgentLanguage, swarmRoles = s.swarmRoles
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
                            com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.appContext = view.context
                            com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.agentName = name
                            android.util.Log.i("MengPawTwin", "依赖已注入")
                            val pm = com.mengpaw.kernel.plugin.PluginManager.globalInstance
                            android.util.Log.i("MengPawTwin", "PluginManager: $pm")
                            // Setup/initialization context — blocking is acceptable
                            val installResult = runBlocking { pm.install(plugin) }
                            android.util.Log.i("MengPawTwin", "install结果: ${installResult.isSuccess}")
                            installResult.fold(
                                onSuccess = {
                                    runBlocking { pm.activate(plugin.metadata.id) }.fold(
                                        onSuccess = {
                                            android.util.Log.i("MengPawTwin", "插件激活成功")
                                            (view.context as? androidx.activity.ComponentActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                                                startAcpForTwin(view.context, name)
                                            }
                                            android.widget.Toast.makeText(view.context, "记忆孪生已激活", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        onFailure = { e ->
                                            android.util.Log.e("MengPawTwin", "激活失败: ${e.message}", e)
                                            android.widget.Toast.makeText(view.context, "激活失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                },
                                onFailure = { e ->
                                    android.util.Log.e("MengPawTwin", "安装失败: ${e.message}", e)
                                    runBlocking { pm.activate(plugin.metadata.id) }.fold(
                                        onSuccess = {
                                            android.util.Log.i("MengPawTwin", "二次激活成功")
                                            (view.context as? androidx.activity.ComponentActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                                                startAcpForTwin(view.context, name)
                                            }
                                            android.widget.Toast.makeText(view.context, "记忆孪生已激活", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                        onFailure = { e2 ->
                                            android.util.Log.e("MengPawTwin", "二次激活失败: ${e2.message}", e2)
                                            android.widget.Toast.makeText(view.context, "激活失败: ${e2.message}", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("MengPawTwin", "异常: ${e.message}", e)
                            com.mengpaw.kernel.error.ErrorCollector.report(e, "activateMemoryTwin")
                            android.widget.Toast.makeText(view.context, "异常: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
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
    // 命令执行版本号: Agent 每完成一批命令 (bang/ReAct 循环) → +1 → 设置页
    // 全局工具/智能体工具/智能体技能/插件 列表实时重扫 (命令可能改文件/装插件/进化技能)
    var agentDataVersion by remember { mutableIntStateOf(0) }

    // Plugins: recomputed each time the settings screen opens (deferInit may finish later,
    // and downloaded plugins land anytime — never trust a one-shot list)
    var pluginItems by remember { mutableStateOf(emptyList<FrameworkItem>()) }
    LaunchedEffect(showSettings, agentDataVersion) {
        if (!showSettings) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val pm = com.mengpaw.kernel.plugin.PluginManager.globalInstance
            val installed = pm.listAll().map { (plugin, status) ->
                val enName = PluginRegistrar.PLUGIN_EN_NAMES[plugin.metadata.id]
                FrameworkItem(name = plugin.metadata.name, enName = enName,
                    isWowBlue = plugin.metadata.id in PluginRegistrar.WOWBLUE_PLUGIN_IDS,
                    category = if (plugin.metadata.id in PluginRegistrar.BUILTIN_PLUGIN_IDS) ItemCategory.BUILTIN else ItemCategory.OFFICIAL,
                    summary = plugin.metadata.description,
                    docMarkdown = "## ${enName?.let { "${plugin.metadata.name} ($it)" } ?: plugin.metadata.name}\n\n${plugin.metadata.description}\n\nID: ${plugin.metadata.id}\n版本: ${plugin.metadata.version}\n状态: ${status.name}\n命令数: ${plugin.commands.size}")
            }
            // Builtin plugins compiled into the APK but not yet installed — show them anyway,
            // so new bundled plugins are never invisible in the global list
            val missingBuiltins = PluginRegistrar.BUILTIN_PLUGIN_IDS
                .filter { id -> pm.get(id) == null }
                .mapNotNull { id -> PluginRegistrar.BUILTIN_PLUGIN_INFO[id]?.let { (name, desc) ->
                    val enName = PluginRegistrar.PLUGIN_EN_NAMES[id]
                    FrameworkItem(name = name, enName = enName, category = ItemCategory.BUILTIN, isWowBlue = id in PluginRegistrar.WOWBLUE_PLUGIN_IDS,
                        summary = "$desc — 内置，未安装",
                        docMarkdown = "## ${enName?.let { "$name ($it)" } ?: name}\n\n$desc\n\nID: $id\n状态: 未安装（内置插件，可在插件市场激活）")
                } }
            // Kernel namespaces are not plugins but surface as builtin capabilities
            val kernelNamespaces = listOf(
                FrameworkItem("self (内置)", ItemCategory.BUILTIN, "Agent 自我管理 — 状态/配置/统计/版本/头像/主题/通知/时间 (Agent self-management — status/config/stats/version/avatar/theme/notify/time)", ""),
    FrameworkItem("evolution (内置)", ItemCategory.BUILTIN, "Agent 进化 — 失败钩子/金字塔自问/错误四分法处置/绩效 (Agent Evolution — failure hooks/pyramid self-inquiry/error classification/performance)", "", isWowBlue = true),
                FrameworkItem("agent (内置)", ItemCategory.BUILTIN, "文档管理 — 记忆/CLI/档案/审计/梦境/存储 (Document management — memory/CLI/archive/audit/dream/storage)", ""),
                FrameworkItem("plugin (内置)", ItemCategory.BUILTIN, "插件管理 — 市场/搜索/安装/卸载/启停/升级 (Plugin management — market/search/install/uninstall/toggle/update)", ""),
                FrameworkItem("sys (内置)", ItemCategory.BUILTIN, "系统信息 — 电量/网络/CPU/存储/定位/剪贴板 (System info — battery/network/CPU/storage/location/clipboard)", ""),
            )
            val items = installed + missingBuiltins + kernelNamespaces
            withContext(Dispatchers.Main) { pluginItems = items }
        }
    }

    // ── CLI commands: built-in curated + dynamic plugin commands
    val toolItems = remember(activeAgent, agentViewModel.activeNamespaces().hashCode()) {
        val engine = agentViewModel.activeEngine()
        val selfTools = listOf(
            FrameworkItem("self.status", ItemCategory.BUILTIN, "Agent 运行状态查询 (Agent runtime status)", ""),
            FrameworkItem("self.config [key=value]", ItemCategory.BUILTIN, "查看或修改 Agent 配置 (View or modify Agent config)", ""),
            FrameworkItem("self.stats", ItemCategory.BUILTIN, "内存/CPU/线程统计信息 (Memory/CPU/thread stats)", ""),
            FrameworkItem("self.version", ItemCategory.BUILTIN, "MengPaw 版本号 (MengPaw version)", ""),
            FrameworkItem("self.time [format]", ItemCategory.BUILTIN, "当前时间 (Current time)", ""),
            FrameworkItem("self.tools [namespace]", ItemCategory.BUILTIN, "列出所有可用命令 (List all available commands)", ""),
            FrameworkItem("self.notify.message <text>", ItemCategory.BUILTIN, "Agent 推送消息到聊天 (Push a message to chat)", "", isWowBlue = true),
            FrameworkItem("self.notify.banner <text> [--level]", ItemCategory.BUILTIN, "Agent 推送通知横幅 (Push a notification banner)", "", isWowBlue = true),
            FrameworkItem("self.avatar <path>", ItemCategory.BUILTIN, "设置 Agent 头像 (Set Agent avatar)", "", isWowBlue = true),
            FrameworkItem("self.theme primary=#xxx surface=#xxx", ItemCategory.BUILTIN, "修改主题色 (Change theme colors)", "", isWowBlue = true),
            FrameworkItem("self.trigger add|list|remove|topics", ItemCategory.BUILTIN, "CRON/LIFETIME 触发器 (CRON/LIFETIME triggers)", "", isWowBlue = true),
        )
        val agentTools = listOf(
            FrameworkItem("agent.cli", ItemCategory.BUILTIN, "查阅完整 CLI.md 命令参考 (Read the full CLI.md reference)", ""),
            FrameworkItem("agent.docs", ItemCategory.BUILTIN, "列出所有 Agent 文档 (List all Agent docs)", ""),
            FrameworkItem("agent.memory [query]", ItemCategory.BUILTIN, "记忆索引/搜索 (Memory index/search)", "", isWowBlue = true),
            FrameworkItem("agent.memory.record <content>", ItemCategory.BUILTIN, "手动记录一条记忆 (Manually record a memory)", "", isWowBlue = true),
            FrameworkItem("agent.profile", ItemCategory.BUILTIN, "查看 Agent 身份档案 (View Agent profile)", ""),
            FrameworkItem("agent.soul", ItemCategory.BUILTIN, "查看 Agent 灵魂设定 (View Agent soul)", ""),
            FrameworkItem("agent.audit [N]", ItemCategory.BUILTIN, "查看最近 N 条命令审计日志 (View recent N command audit logs)", "", isWowBlue = true),
            FrameworkItem("agent.browser-tools", ItemCategory.BUILTIN, "MP浏览器插件开发能力参考 (MP Browser plugin dev reference)", "", isWowBlue = true),
            FrameworkItem("agent.dream", ItemCategory.BUILTIN, "触发梦境整理 (Trigger dream consolidation)", "", isWowBlue = true),
            FrameworkItem("agent.cleanup", ItemCategory.BUILTIN, "清理过期文件和归档记忆 (Clean expired files and archived memory)", "", isWowBlue = true),
            FrameworkItem("agent.storage", ItemCategory.BUILTIN, "工作区存储空间报告 (Workspace storage report)", "", isWowBlue = true),
            FrameworkItem("agent.boost", ItemCategory.BUILTIN, "首次引导初始化 (First-run bootstrap)", "", isWowBlue = true),
            FrameworkItem("agent.boost.delete", ItemCategory.BUILTIN, "删除引导文件 (Delete bootstrap file)", "", isWowBlue = true),
            FrameworkItem("agent.modes", ItemCategory.BUILTIN, "斜杠命令模式菜单 (Slash command mode menu)", "", isWowBlue = true),
        )
        val pluginTools = listOf(
            FrameworkItem("plugin.marketplace [--refresh]", ItemCategory.BUILTIN, "浏览插件市场 (Browse plugin market)", ""),
            FrameworkItem("plugin.search <query>", ItemCategory.BUILTIN, "搜索可用插件 (Search available plugins)", ""),
            FrameworkItem("plugin.install <id>", ItemCategory.BUILTIN, "下载+验证+安装+激活插件 (Download+verify+install+activate plugin)", ""),
            FrameworkItem("plugin.uninstall <id>", ItemCategory.BUILTIN, "卸载插件 (Uninstall plugin)", ""),
            FrameworkItem("plugin.list", ItemCategory.BUILTIN, "列出已安装插件 (List installed plugins)", ""),
            FrameworkItem("plugin.info <id>", ItemCategory.BUILTIN, "查看插件详情 (View plugin details)", ""),
            FrameworkItem("plugin.enable <id>", ItemCategory.BUILTIN, "启用插件 (Enable plugin)", ""),
            FrameworkItem("plugin.disable <id>", ItemCategory.BUILTIN, "停用插件 (Disable plugin)", ""),
            FrameworkItem("plugin.update <id>", ItemCategory.BUILTIN, "检查插件更新 (Check plugin updates)", ""),
            FrameworkItem("plugin.upgrade --all", ItemCategory.BUILTIN, "升级全部插件 (Upgrade all plugins)", ""),
        )
        val sysTools = listOf(
            FrameworkItem("sys.battery", ItemCategory.BUILTIN, "电量/充电状态/温度 (Battery/charging/temperature)", "", isWowBlue = true),
            FrameworkItem("sys.network", ItemCategory.BUILTIN, "网络类型/信号强度 (Network type/signal strength)", "", isWowBlue = true),
            FrameworkItem("sys.cpu", ItemCategory.BUILTIN, "CPU 使用率/核心数 (CPU usage/cores)", "", isWowBlue = true),
            FrameworkItem("sys.memory", ItemCategory.BUILTIN, "内存使用量 (Memory usage)", "", isWowBlue = true),
            FrameworkItem("sys.storage", ItemCategory.BUILTIN, "存储空间使用情况 (Storage usage)", "", isWowBlue = true),
            FrameworkItem("sys.display", ItemCategory.BUILTIN, "屏幕参数 (Display parameters)", "", isWowBlue = true),
            FrameworkItem("sys.sensors", ItemCategory.BUILTIN, "传感器列表 (Sensor list)", "", isWowBlue = true),
            FrameworkItem("sys.clipboard", ItemCategory.BUILTIN, "剪贴板内容 (Clipboard content)", "", isWowBlue = true),
            FrameworkItem("sys.location", ItemCategory.BUILTIN, "GPS 定位 (GPS location)", "", isWowBlue = true),
            FrameworkItem("sys.camera", ItemCategory.BUILTIN, "相机信息 (Camera info)", "", isWowBlue = true),
            FrameworkItem("sys.apps", ItemCategory.BUILTIN, "已安装应用列表 (Installed apps list)", "", isWowBlue = true),
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
    LaunchedEffect(showSettings, agentDataVersion) {
        if (!showSettings) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val skillsDir = java.io.File(com.mengpaw.kernel.DataPaths.SKILLS)
            val skillFiles = if (skillsDir.exists()) {
                skillsDir.listFiles()?.filter { it.extension == "md" }
                    ?.map { file ->
                        val content = try { file.readText() } catch (_: Exception) { "" }
                        // docMarkdown = 技能 md 全文 — 展开区以 Markdown 渲染剧本
                        FrameworkItem(file.nameWithoutExtension, ItemCategory.BUILTIN,
                            summary = extractSummary(content), docMarkdown = content)
                    }
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
    LaunchedEffect(showSettings, activeAgent, agentDataVersion) {
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
    LaunchedEffect(showSettings, activeAgent, agentDataVersion) {
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
    // 实时刷新：Agent 写工作区文档（onDocChanged 多播）→ 列表立即重扫。
    // 分屏聊天时，一边聊一边可见文档变动（写记忆/更新文档即时反映）。
    DisposableEffect(Unit) {
        val listener: (String, String?) -> Unit = { agentName, _ ->
            if (showSettings && agentName == activeAgent) workspaceVersion++
        }
        com.mengpaw.kernel.agent.AgentDocs.addDocListener(listener)
        onDispose { com.mengpaw.kernel.agent.AgentDocs.removeDocListener(listener) }
    }
    // 实时刷新：Agent 执行命令（bang "!" 或 ReAct 循环）→ 工具/技能/插件列表重扫。
    // 挂在当前 agent 的 engine 实例上（每 agent 一个 engine），切换 agent 时重挂。
    DisposableEffect(activeAgent) {
        val engine = agentViewModel.activeEngine()
        if (engine == null) {
            onDispose { }
        } else {
            val listener = { if (showSettings) agentDataVersion++ }
            engine.addCommandListener(listener)
            onDispose { engine.removeCommandListener(listener) }
        }
    }
    // 与 skill/agent-skill/agent-tool 列表一致：设置页打开时强制刷新 —
    // 缺 showSettings 键会导致列表停留在应用启动时的快照，之后 Agent 写入的
    // memory/ 记忆文件不会出现（工作区文件列表 memory 文件树不显示）
    LaunchedEffect(showSettings, activeAgent, workspaceVersion) {
        if (!showSettings) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val dir = java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, activeAgent)
            val items = buildList {
                // 顶层 Markdown 文档（agents/soul/boost 等）
                dir.listFiles { f -> f.isFile && f.extension == "md" }?.sortedBy { it.name }?.forEach { file ->
                    val content = try { file.readText() } catch (_: Exception) { "" }
                    add(FrameworkItem(name = file.name, category = ItemCategory.BUILTIN,
                        summary = extractSummary(content), docMarkdown = content))
                }
                // memory 记忆目录节点 — 三重记忆各自成行: 长期 memory.md / 中期 memory_{date}.md / 项目 project_{name}_memory.md
                val memoryDir = java.io.File(dir, "memory")
                if (memoryDir.exists()) {
                    val memoryFiles = memoryDir.listFiles { f -> f.isFile && f.extension == "md" }
                        ?.sortedBy { it.name } ?: emptyList()
                    if (memoryFiles.isNotEmpty()) {
                        val longTerm = memoryFiles.count { it.name == "memory.md" }
                        val midTerm = memoryFiles.count { it.name.startsWith("memory_") }
                        val project = memoryFiles.count { it.name.startsWith("project_") }
                        add(FrameworkItem(
                            name = strings.workspaceMemoryFolder,
                            category = ItemCategory.BUILTIN,
                            isFolder = true,
                            summary = String.format(strings.workspaceMemorySummary, longTerm, midTerm, project, memoryFiles.size),
                            children = memoryFiles.map { f ->
                                FrameworkItem(
                                    name = f.name,
                                    category = ItemCategory.BUILTIN,
                                    docMarkdown = try { f.readText() } catch (_: Exception) { "" })
                            }
                        ))
                    }
                }
                // Notes 笔记目录节点 — 记忆之外的笔记 (如其他 Agent 知识信息)。
                // 目录预建于 AgentDocs.bootstrap; 始终显示 (空目录也显示摘要), 子行仅收 .md。
                val notesDir = java.io.File(dir, "Notes")
                if (notesDir.exists()) {
                    val notesFiles = notesDir.listFiles { f -> f.isFile && f.extension == "md" }
                        ?.sortedBy { it.name } ?: emptyList()
                    add(FrameworkItem(
                        name = strings.workspaceNotesFolder,
                        category = ItemCategory.BUILTIN,
                        isFolder = true,
                        summary = String.format(strings.workspaceNotesSummary, notesFiles.size),
                        children = notesFiles.map { f ->
                            FrameworkItem(
                                name = f.name,
                                category = ItemCategory.BUILTIN,
                                docMarkdown = try { f.readText() } catch (_: Exception) { "" })
                        }
                    ))
                }
            }
            withContext(Dispatchers.Main) { workspaceItems = items }
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
                if (fileName == strings.workspaceMemoryFolder || fileName == strings.workspaceNotesFolder) return@SettingsScreen  // 目录节点只读(子文件行单独可删)
                if (fileName == "boost.md") com.mengpaw.kernel.agent.AgentDocs.deleteBoost(activeAgent)
                else java.io.File(java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, activeAgent), fileName).delete()
                workspaceVersion++
            },
            onResetWorkspaceFile = { fileName ->
                if (fileName == strings.workspaceMemoryFolder || fileName == strings.workspaceNotesFolder) return@SettingsScreen
                val lang = if (settingsState.useChinese) "zh" else "en"
                com.mengpaw.kernel.agent.AgentDocs.resetDoc(activeAgent, fileName, lang)
                workspaceVersion++
            },
            onEditWorkspaceFile = { fileName ->
                if (fileName == strings.workspaceMemoryFolder || fileName == strings.workspaceNotesFolder) return@SettingsScreen
                openDocExternally(context,
                    java.io.File(java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, activeAgent), fileName), strings)
            }
        )
    }
    if (showPlugins) {
        PluginMarketScreen(
            onNavigateBack = { showPlugins = false },
            onNavigateToDetail = {},
            strings = strings
        )
    }
    if (showLicense) {
        LicenseScreen(onBack = { showLicense = false })
    }
    if (showAttribution) {
        AttributionScreen(onBack = { showAttribution = false })
    }
}

/** 启动 ACP 服务 + 注册 TwinAcpHandler (接收配对请求) — internal: MainActivity.autoRestoreTwinIfNeeded 也调用 */
internal suspend fun startAcpForTwin(ctx: android.content.Context, agentName: String) {
    try {
        val profile = com.mengpaw.kernel.agent.AgentProfile.load(agentName)
        // SECURITY: Derive shared secret from device fingerprint for baseline auth
        val deviceFingerprint = try { com.mengpaw.kernel.acp.AcpCrypto.myFingerprint() } catch (_: Exception) { "device-${System.currentTimeMillis()}" }
        val sharedSecret = java.security.MessageDigest.getInstance("SHA-256")
            .digest("twin:$deviceFingerprint:$agentName".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val server = com.mengpaw.kernel.acp.AcpServer(profile, com.mengpaw.kernel.ports.Ports.ACP, sharedSecret)
        val transport = com.mengpaw.kernel.acp.AcpHttpTransport(server, com.mengpaw.kernel.ports.Ports.ACP)
        server.registerTransport(transport)

        // 注册 TwinAcpHandler — 处理 CAPABILITY_ANNOUNCE 等孪生消息
        val syncEngine = com.mengpaw.plugin.memorytwin.TwinSyncEngine(
            serverSupplier = { server }, transportSupplier = { transport },
            agentName = agentName, deviceId = deviceFingerprint,
            deviceName = android.os.Build.MODEL ?: "Android")
        val handler = com.mengpaw.plugin.memorytwin.TwinAcpHandler(syncEngine)
        server.registerHandler(handler)

        // ── MCP-over-ACP 桥 (协议升级: 远程 MCP 调用走配对加密通道) ──
        try {
            val pm = com.mengpaw.kernel.plugin.PluginManager.globalInstance
            val mcpServer = com.mengpaw.kernel.mcp.McpServer(pm)
            // 反射注册 browser-mcp provider (remote 插件, 未安装时跳过) — 暴露 6 个浏览器 MCP 工具
            try {
                val pluginCls = Class.forName("com.mengpaw.plugin.browsermcp.BrowserMcpPlugin")
                val provider = pluginCls.getDeclaredConstructor().newInstance()
                    as com.mengpaw.kernel.mcp.McpToolProvider
                mcpServer.registerToolProvider(provider)
            } catch (_: Exception) {}
            server.enableMcpBridge(mcpServer)
            android.util.Log.i("MengPawTwin", "MCP-over-ACP 桥已启用")
        } catch (e: Exception) {
            android.util.Log.w("MengPawTwin", "MCP 桥启用失败: ${e.message}")
        }

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
        // 注入 engine 供 twin.start 复用 (双引擎债务修复, v0.22.0)
        com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.activeEngine = syncEngine
        // 标记已激活, 下次启动自动恢复
        java.io.File(ctx.filesDir, "twin_activated").writeText(agentName)
        android.util.Log.i("MengPawTwin", "孪生服务已启动 (${frameworkPeers.size} 个节点)")
    } catch (e: Exception) {
        android.util.Log.e("MengPawTwin", "启动失败: ${e.message}", e)
    }
}
