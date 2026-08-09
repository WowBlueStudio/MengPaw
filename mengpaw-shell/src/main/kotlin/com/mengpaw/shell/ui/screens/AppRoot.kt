// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mengpaw.design.theme.ArcoTheme
import com.mengpaw.shell.ui.localization.AppStrings
import com.mengpaw.shell.ui.localization.ChineseStrings
import com.mengpaw.shell.ui.localization.EnglishStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compose 根 — 从 MainActivity.kt 拆出 (2026-08-04, >40KB UI 文件拆分)。
 * 主题装配 (collectAsState → strings → isDark → ArcoTheme) + 原 MengPawApp 全部 UI 树。
 * agentViewModel 在 Compose 内 viewModel() 获取 — 与 Activity 侧 viewModels<AgentViewModel>()
 * 同 ViewModelStore 同实例 (MainActivity 既有契约, 用于浏览器提炼任务触发)。
 *
 * 批次4 (2026-08-06) 再拆: md 文档助手 → AppRootMdDocs.kt; 记忆孪生激活 → AppRootTwin.kt;
 * 设置页数据预计算 → AppRootSettingsItems.kt。
 */

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

    // v0.35.1: 输出目录授权引导 — Android 11+ 未授权 (公共 /MengPaw/ 不可写) 时启动即弹;
    // 授权返回后 onResume refreshOutput 切公共目录, 轮询检测到已满足条件自动关闭
    var showOutputPrompt by remember { mutableStateOf(needsOutputPermission()) }
    LaunchedEffect(Unit) {
        while (showOutputPrompt && needsOutputPermission()) {
            kotlinx.coroutines.delay(1000)
        }
        showOutputPrompt = false
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
                            // P2 修复: 原 runBlocking 在主线程执行插件安装/激活 (点击即卡 UI) —
                            // 改 IO 协程; 主线程只保留兜底 Toast。安装完成前的依赖方 (插件列表/命令集)
                            // 均为延迟加载, 未就绪时静默缺失, 无启动时序依赖。
                            val activity = view.context as? androidx.activity.ComponentActivity
                            val job = activity?.lifecycleScope?.launch(Dispatchers.IO) {
                                try {
                                    installAndActivateTwin(view.context, name)
                                } catch (e: Exception) {
                                    android.util.Log.e("MengPawTwin", "异常: ${e.message}", e)
                                    com.mengpaw.kernel.error.ErrorCollector.report(e, "activateMemoryTwin")
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(view.context, "异常: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            if (job == null) {
                                android.widget.Toast.makeText(view.context, "记忆孪生激活失败: 未找到宿主 Activity", android.widget.Toast.LENGTH_SHORT).show()
                            }
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
                // v0.34.3 /plan UI: 右侧边栏 = 历史会话 (上) + 计划模式列表 (底部)
                // v0.35.1 修复: 外层固定 300dp 宽 — fillMaxSize 在持久侧栏 wrap 容器
                // 内撑开异常宽度, 导致 300dp 历史会话内容被推到左侧
                Column(Modifier.width(300.dp).fillMaxHeight()) {
                    Box(Modifier.weight(1f)) {
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
                    com.mengpaw.shell.ui.components.PlanListSection()
                }
            }
        )

        // ── 输出目录授权引导 (全屏最上层) ──
        if (showOutputPrompt) {
            OutputPermissionPrompt(onDismiss = { showOutputPrompt = false })
        }
    }

    // ── Pre-computed settings data (设置页六类列表 → AppRootSettingsItems.kt) ──
    val settingsItems = rememberAppRootSettingsItems(showSettings, activeAgent, agentViewModel, strings)
    val agentFramework = remember(activeAgent) { agentViewModel.frameworkFor(activeAgent) }
    val (agentEp, agentModel) = remember(activeAgent) { agentViewModel.agentConfig(activeAgent) }

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
            pluginItems = settingsItems.pluginItems,
            toolItems = settingsItems.toolItems,
            skillItems = settingsItems.skillItems,
            agentToolItems = settingsItems.agentToolItems,
            agentSkillItems = settingsItems.agentSkillItems,
            workspaceItems = settingsItems.workspaceItems,
            onRefreshWorkspace = settingsItems.refreshWorkspace,
            onDeleteWorkspaceFile = { fileName ->
                if (fileName == strings.workspaceMemoryFolder || fileName == strings.workspaceNotesFolder
                    || fileName == strings.workspaceEvolutionFolder) return@SettingsScreen  // 目录节点只读(子文件行单独可删)
                if (fileName == "boost.md") com.mengpaw.kernel.agent.AgentDocs.deleteBoost(activeAgent)
                else java.io.File(java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, activeAgent), fileName).delete()
                settingsItems.refreshWorkspace()
            },
            onResetWorkspaceFile = { fileName ->
                if (fileName == strings.workspaceMemoryFolder || fileName == strings.workspaceNotesFolder
                    || fileName == strings.workspaceEvolutionFolder) return@SettingsScreen
                val lang = if (settingsState.useChinese) "zh" else "en"
                com.mengpaw.kernel.agent.AgentDocs.resetDoc(activeAgent, fileName, lang)
                settingsItems.refreshWorkspace()
            },
            onOpenWorkspaceFile = { fileName ->
                if (fileName == strings.workspaceMemoryFolder || fileName == strings.workspaceNotesFolder
                    || fileName == strings.workspaceEvolutionFolder) return@SettingsScreen
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
