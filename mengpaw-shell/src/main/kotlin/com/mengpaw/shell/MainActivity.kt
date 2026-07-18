// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.mengpaw.design.theme.ArcoTheme
import com.mengpaw.shell.ui.localization.AppStrings
import com.mengpaw.shell.ui.localization.ChineseStrings
import com.mengpaw.shell.ui.localization.EnglishStrings
import com.mengpaw.shell.ui.screens.*

class MainActivity : ComponentActivity() {
    private val settingsViewModel = SettingsViewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        setContent {
            val settingsState by settingsViewModel.state.collectAsState()
            val strings: AppStrings = if (settingsState.useChinese) ChineseStrings else EnglishStrings
            ArcoTheme(darkTheme = settingsState.darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) { MengPawApp(strings, settingsViewModel) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MengPawApp(strings: AppStrings, settingsViewModel: SettingsViewModel) {
    var showSidebar by remember { mutableStateOf(false) }
    var showPlugins by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                SidebarContent(
                    onNavigateToPlugins = { showPlugins = true; showSidebar = false },
                    onNavigateToSettings = { showSettings = true; showSidebar = false },
                    onClose = { showSidebar = false }
                )
            }
        }
    ) {
        // ── Main Chat Screen ──
        MainScreen(
            strings = strings,
            settingsViewModel = settingsViewModel,
            onOpenSidebar = { showSidebar = true }
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
        SettingsScreen(
            onNavigateBack = { showSettings = false },
            onNavigateToPluginMarket = { showPlugins = true; showSettings = false },
            viewModel = settingsViewModel
        )
    }
}
