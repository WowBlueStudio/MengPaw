// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mengpaw.design.theme.ArcoTheme
import com.mengpaw.shell.ui.localization.AppStrings
import com.mengpaw.shell.ui.localization.ChineseStrings
import com.mengpaw.shell.ui.localization.EnglishStrings
import com.mengpaw.shell.ui.screens.*

class MainActivity : ComponentActivity() {
    private val settingsViewModel = SettingsViewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.mengpaw.core.DataPaths.initialize(this)
        enableEdgeToEdge()
        com.mengpaw.plugin.pad.PadPlugin.init(this)

        // Register system-level wake alarms — survive Doze
        com.mengpaw.core.trigger.TriggerEngine.registerSystemWake(this, 10)
        com.mengpaw.core.trigger.TriggerEngine.registerCronAlarm(this)

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
    var showPlugins by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val leftDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val rightDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val agentViewModel: AgentViewModel = viewModel()
    val activeAgent by agentViewModel.activeAgent.collectAsState()
    val sessionHistory by agentViewModel.sessionHistory.collectAsState()
    val hideCompacted by agentViewModel.hideCompacted.collectAsState()
    val isTablet = com.mengpaw.shell.ui.isWide()

    // Tablet: permanent left sidebar drawer open, right sidebar as second drawer
    LaunchedEffect(isTablet) {
        if (isTablet) leftDrawerState.open() else leftDrawerState.close()
    }

    // Left sidebar drawer
    ModalNavigationDrawer(
        drawerState = leftDrawerState,
        gesturesEnabled = !isTablet, // tablet: permanent, phone: swipe to open
        drawerContent = {
            ModalDrawerSheet {
                SidebarContent(
                    onNavigateToPlugins = { showPlugins = true; scope.launch { leftDrawerState.close() } },
                    onNavigateToSettings = { showSettings = true; scope.launch { leftDrawerState.close() } },
                    onClose = { scope.launch { leftDrawerState.close() } },
                    activeAgent = activeAgent,
                    onSwitchAgent = { name -> agentViewModel.switchAgent(name); scope.launch { leftDrawerState.close() } },
                    onCreateAgent = { name ->
                        agentViewModel.createAgent(name)
                        scope.launch { leftDrawerState.close() }
                    }
                )
            }
        }
    ) {
        // Right sidebar drawer (history)
        ModalNavigationDrawer(
            drawerState = rightDrawerState,
            gesturesEnabled = true,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = com.mengpaw.design.theme.ThemeColors.bgPrimary,
                    drawerShape = androidx.compose.foundation.shape.RoundedCornerShape(
                        topStart = 12.dp, bottomStart = 12.dp
                    )
                ) {
                    HistorySidebar(
                        sessions = sessionHistory,
                        hideCompacted = hideCompacted,
                        onToggleHideCompacted = { agentViewModel.toggleHideCompacted() },
                        onSelectSession = { /* TODO: restore session */ },
                        onDeleteSession = { agentViewModel.deleteSession(it) },
                        onCompactSession = { agentViewModel.compactSession(it) }
                    )
                }
            }
        ) {
            // ── Main Chat Screen ──
            MainScreen(
                strings = strings,
                settingsViewModel = settingsViewModel,
                onOpenSidebar = { scope.launch { leftDrawerState.open() } },
                onOpenHistory = { scope.launch { rightDrawerState.open() } },
                agentViewModel = agentViewModel
            )
        }
    }

    // ── Full-screen overlays for Plugins/Settings ──
    if (showPlugins) {
        PluginMarketScreen(
            onNavigateBack = { showPlugins = false },
            onNavigateToDetail = {}
        )
    }
    if (showSettings) {
        SettingsScreen(
            onNavigateBack = { showSettings = false },
            onNavigateToPluginMarket = { showPlugins = true; showSettings = false },
            viewModel = settingsViewModel
        )
    }
}
