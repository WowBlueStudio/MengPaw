// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.shell.ui.isWide

/** Category tag for framework items. */
enum class ItemCategory(val label: String, val enLabel: String, val color: Color) {
    BUILTIN("内置", "Built-in", ArcoColors.Blue6),
    OFFICIAL("官方", "Official", ArcoColors.Green6),
    CUSTOM("自建", "Custom", ArcoColors.Orange6)
}

/** A named item in a framework list (CLI command, plugin, tool, skill). */
data class FrameworkItem(
    val name: String,
    val category: ItemCategory,
    val summary: String = "",
    val docMarkdown: String = "",
    val isWowBlue: Boolean = false,
    /** 插件英文名 — 显示为「中文名 (English)」; null 时只显示 name。 */
    val enName: String? = null,
    /** 子条目 — 非空时本条目为目录节点(点击展开子列表),空时为文档行(点击展开 docMarkdown)。 */
    val children: List<FrameworkItem> = emptyList(),
    /** 显式目录标记 — children 为空的目录(如空 Notes)也按目录节点渲染。 */
    val isFolder: Boolean = false,
    /** 技能来源标记 — "core"=框架核心 / "plugin"=插件附带 (预置,不可删); ""=用户技能(可删)。 */
    val source: String = ""
) {
    /** UI 显示名 — 插件统一「中文名 (English)」中英对照格式。 */
    val displayName: String get() = enName?.let { "$name ($it)" } ?: name
}

/**
 * iPad-style two-column settings screen.
 *
 * Layout:
 *   [Sidebar] | [Content area — switches per section]
 *
 * Sections:
 *   01. Agent 设置      — LLM provider, API key, max steps, agent language
 *   02. 框架设置         — plugins, memory, triggers
 *   03. 系统设置         — appearance, language, permissions, about
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPluginMarket: () -> Unit = {},
    onNavigateToLicense: () -> Unit = {},
    onNavigateToAttribution: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(),
    activeAgentName: String = "MengPaw",
    agentFramework: String? = null,
    activeAgentEndpoint: String = "",
    activeAgentModel: String = "",
    onAgentSelectProvider: ((SavedProvider) -> Unit)? = null,
    pluginItems: List<FrameworkItem> = emptyList(),
    toolItems: List<FrameworkItem> = emptyList(),
    skillItems: List<FrameworkItem> = emptyList(),
    agentPluginItems: List<FrameworkItem> = emptyList(),
    agentToolItems: List<FrameworkItem> = emptyList(),
    agentSkillItems: List<FrameworkItem> = emptyList(),
    workspaceItems: List<FrameworkItem> = emptyList(),
    onRefreshWorkspace: (() -> Unit)? = null,
    onDeleteWorkspaceFile: ((String) -> Unit)? = null,
    onResetWorkspaceFile: ((String) -> Unit)? = null,
    onOpenWorkspaceFile: ((String) -> Unit)? = null
) {
    val state by viewModel.state.collectAsState()
    val s = state.strings
    var selectedSection by remember { mutableIntStateOf(0) }

    val sectionTitle = when (selectedSection) {
        0 -> "${s.sidebarSettingsAgent} - $activeAgentName"
        1 -> s.sidebarSettingsFramework
        2 -> s.sidebarSettingsSystem
        else -> s.settings
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Settings, null, tint = ThemeColors.brand, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(ArcoSpacing.sm))
                        Text(sectionTitle, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = s.back)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ThemeColors.bgPrimary)
            )
        }
    ) { padding ->
        Row(
            Modifier.fillMaxSize().padding(padding)
        ) {
            SettingsSidebar(
                strings = s,
                selected = selectedSection,
                onSelect = { selectedSection = it },
                expanded = isWide()
            )

            if (isWide()) {
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = ThemeColors.border,
                    thickness = 0.5.dp
                )
            }

            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.md)
            ) {
                when (selectedSection) {
                    0 -> AgentSettingsContent(state, viewModel, activeAgentEndpoint, activeAgentModel,
                        onAgentSelectProvider, agentToolItems, agentSkillItems, workspaceItems, onRefreshWorkspace, onDeleteWorkspaceFile, onResetWorkspaceFile, onOpenWorkspaceFile,
                        activeAgentName = activeAgentName)
                    1 -> FrameworkSettingsContent(state, viewModel, onNavigateToPluginMarket, pluginItems, toolItems, skillItems)
                    2 -> SystemSettingsContent(onNavigateToLicense, onNavigateToAttribution, state, viewModel, onNavigateToPluginMarket)
                }
                Spacer(Modifier.height(ArcoSpacing.xxxl))
            }
        }
    }
}
