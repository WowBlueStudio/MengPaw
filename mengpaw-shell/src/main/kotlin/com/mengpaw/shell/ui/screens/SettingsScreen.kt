// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.kernel.llm.CacheStrategy
import com.mengpaw.shell.ui.isWide
import com.mengpaw.shell.ui.components.TokenLineChart
import com.mengpaw.shell.ui.components.TokenStatsCollector
import com.mengpaw.shell.ui.components.formatTokenCount
import com.mengpaw.design.components.MarkdownText

/** Category tag for framework items. */
enum class ItemCategory(val label: String, val color: Color) {
    BUILTIN("内置", ArcoColors.Blue6),
    OFFICIAL("官方", ArcoColors.Green6),
    CUSTOM("自建", ArcoColors.Orange6)
}

/** A named item in a framework list (CLI command, plugin, tool, skill). */
data class FrameworkItem(
    val name: String,
    val category: ItemCategory,
    val summary: String = "",
    val docMarkdown: String = ""
)

/**
 * iPad-style two-column settings screen.
 *
 * Layout:
 *  [Sidebar] | [Content area — switches per section]
 *
 * Sections:
 *  01. Agent 设置      — LLM provider, API key, max steps, agent language
 *  02. 框架设置         — plugins, memory, triggers, PAD
 *  03. 系统设置         — appearance, language, permissions, about
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPluginMarket: () -> Unit = {},
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
    onRefreshWorkspace: (() -> Unit)? = null
) {
    val state by viewModel.state.collectAsState()
    val s = state.strings
    var selectedSection by remember { mutableIntStateOf(0) }

    // Section title based on selection
    val sectionTitle = when (selectedSection) {
        0 -> "智能体设置 - $activeAgentName"
        1 -> "框架设置"
        2 -> "系统设置"
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
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Sidebar ──
            SettingsSidebar(
                selected = selectedSection,
                onSelect = { selectedSection = it },
                expanded = isWide()
            )

            // Vertical divider
            if (isWide()) {
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = ThemeColors.border,
                    thickness = 0.5.dp
                )
            }

            // ── Content area ──
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.md)
            ) {
                when (selectedSection) {
                    0 -> AgentSettingsContent(state, viewModel, activeAgentEndpoint, activeAgentModel, onAgentSelectProvider, agentPluginItems, agentToolItems, agentSkillItems, workspaceItems, onRefreshWorkspace)
                    1 -> FrameworkSettingsContent(state, viewModel, onNavigateToPluginMarket, pluginItems, toolItems, skillItems)
                    2 -> SystemSettingsContent(state, viewModel, onNavigateToPluginMarket)
                }
                Spacer(Modifier.height(ArcoSpacing.xxxl))
            }
        }
    }
}

// ─── Sidebar ────────────────────────────────────────────────────────

@Composable
private fun SettingsSidebar(
    selected: Int,
    onSelect: (Int) -> Unit,
    expanded: Boolean
) {
    Column(
        Modifier
            .width(if (expanded) 240.dp else 68.dp)
            .fillMaxHeight()
            .background(ThemeColors.bgSecondary)
            .padding(top = ArcoSpacing.md)
    ) {
        // 01 — Agent (brand background when selected)
        SidebarItem(
            number = "01",
            title = "智能体设置",
            subtitle = null,
            selected = selected == 0,
            expanded = expanded,
            containerColor = if (selected == 0) ThemeColors.brandContainer else Color.Transparent,
            onClick = { onSelect(0) }
        )

        // 02 — Framework
        SidebarItem(
            number = "02",
            title = "框架设置",
            subtitle = null,
            selected = selected == 1,
            expanded = expanded,
            onClick = { onSelect(1) }
        )

        // 03 — System
        SidebarItem(
            number = "03",
            title = "系统设置",
            subtitle = null,
            selected = selected == 2,
            expanded = expanded,
            onClick = { onSelect(2) }
        )
    }
}

@Composable
private fun SidebarItem(
    number: String,
    title: String,
    subtitle: String?,
    selected: Boolean,
    expanded: Boolean,
    containerColor: Color = Color.Transparent,
    onClick: () -> Unit
) {
    val bgColor = if (containerColor != Color.Transparent && selected) containerColor
        else if (selected) ThemeColors.bgCardHigh
        else Color.Transparent

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (expanded) ArcoSpacing.sm else 4.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(ArcoRadius.md))
            .clickable { onClick() },
        color = bgColor,
        shape = RoundedCornerShape(ArcoRadius.md)
    ) {
        if (expanded) {
            Row(
                Modifier.padding(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    number,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (selected) ThemeColors.brand else ThemeColors.textSecondary
                )
                Spacer(Modifier.width(ArcoSpacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 14.sp,
                        color = if (selected) ThemeColors.textPrimary else ThemeColors.textSecondary
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            fontSize = 11.sp,
                            color = ThemeColors.textSecondary,
                            maxLines = 1
                        )
                    }
                }
                if (selected) {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        null,
                        Modifier.size(16.dp),
                        tint = ThemeColors.brand
                    )
                }
            }
        } else {
            // Compact: icon + number only
            Column(
                Modifier.padding(vertical = ArcoSpacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    number,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (selected) ThemeColors.brand else ThemeColors.textSecondary
                )
                Spacer(Modifier.height(2.dp))
                Icon(
                    when (number) {
                        "01" -> Icons.Outlined.SmartToy
                        "02" -> Icons.Outlined.Hub
                        else -> Icons.Outlined.Tune
                    },
                    null,
                    Modifier.size(20.dp),
                    tint = if (selected) ThemeColors.brand else ThemeColors.textSecondary
                )
            }
        }
    }
}

// ─── 01. Agent Settings Content ─────────────────────────────────────

@Composable
private fun AgentSettingsContent(
    state: SettingsState,
    viewModel: SettingsViewModel,
    activeEndpoint: String,
    activeModel: String,
    onSelectProvider: ((SavedProvider) -> Unit)?,
    agentPluginItems: List<FrameworkItem> = emptyList(),
    agentToolItems: List<FrameworkItem> = emptyList(),
    agentSkillItems: List<FrameworkItem> = emptyList(),
    workspaceItems: List<FrameworkItem> = emptyList(),
    onRefreshWorkspace: (() -> Unit)? = null
) {
    // ── 选用供应商（从框架已配置的列表中选择） ──
    SectionHeader("供应商 & 模型")
    if (state.savedProviders.isEmpty()) {
        Text("尚未配置供应商，请先在「框架设置」中添加 API Key",
            style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        Spacer(Modifier.height(ArcoSpacing.sm))
    } else {
        state.savedProviders.forEach { saved ->
            // Match by endpoint + model to identify which provider this agent uses
            val active = saved.endpoint == activeEndpoint && saved.model == activeModel
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectProvider?.invoke(saved) },
                shape = RoundedCornerShape(ArcoRadius.lg),
                color = if (active) ArcoColors.Blue1.copy(alpha = 0.4f) else ThemeColors.bgCard,
                tonalElevation = if (active) 2.dp else 0.dp
            ) {
                Row(Modifier.padding(ArcoSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.brandContainer) {
                        Icon(Icons.Outlined.Key, null, tint = ThemeColors.brand, modifier = Modifier.size(32.dp).padding(6.dp))
                    }
                    Spacer(Modifier.width(ArcoSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(saved.preset.label, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                            if (active) {
                                Spacer(Modifier.width(6.dp))
                                Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ThemeColors.brand.copy(alpha = 0.12f)) {
                                    Text("当前", Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                        style = MaterialTheme.typography.labelSmall, color = ThemeColors.brand)
                                }
                            }
                        }
                        Text(saved.model, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
                    }
                    if (active) {
                        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(20.dp), tint = ThemeColors.brand)
                    }
                }
            }
            Spacer(Modifier.height(ArcoSpacing.sm))
        }
        Text("在「框架设置」中管理供应商和 API Key", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    // ── Agent 参数 ──
    SectionHeader("Agent 参数")

    // Max Steps
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.Repeat, null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(state.strings.maxSteps, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(state.strings.maxStepsDesc, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        var stepsText by remember(state.maxSteps) { mutableStateOf(state.maxSteps.toString()) }
        OutlinedTextField(value = stepsText, onValueChange = { stepsText = it; it.toIntOrNull()?.let { n -> viewModel.updateMaxSteps(n) } },
            modifier = Modifier.width(80.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, shape = RoundedCornerShape(ArcoRadius.md))
    }

    // Shell timeout
    Spacer(Modifier.height(ArcoSpacing.sm))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.Timer, null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text("Shell 命令超时", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text("单个命令最长执行时间（秒）", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        var tText by remember(state.commandTimeoutSec) { mutableStateOf(state.commandTimeoutSec.toString()) }
        OutlinedTextField(value = tText, onValueChange = { tText = it; it.toIntOrNull()?.let { n -> viewModel.updateCommandTimeout(n) } },
            modifier = Modifier.width(80.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, shape = RoundedCornerShape(ArcoRadius.md))
    }

    // Loop 模式 — Agent 根据任务复杂度自动选择，此处仅供展示
    Spacer(Modifier.height(ArcoSpacing.lg))
    SectionHeader("Loop 模式")
    Text("Agent 会根据任务复杂度自动选择合适的执行模式，无需手动切换。",
        style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary,
        modifier = Modifier.padding(bottom = ArcoSpacing.xs))
    LoopMode.entries.forEach { mode ->
        Surface(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            shape = RoundedCornerShape(ArcoRadius.md),
            color = ThemeColors.bgCard
        ) {
            Row(Modifier.padding(ArcoSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(mode.label, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                        color = ThemeColors.textPrimary)
                    Text(mode.desc, fontSize = 12.sp, color = ThemeColors.textSecondary)
                }
            }
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    // Agent Language
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.Chat, null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(state.strings.agentLanguage, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(state.strings.agentLanguageDesc, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        OutlinedButton(onClick = { viewModel.cycleAgentLanguage() },
            shape = RoundedCornerShape(ArcoRadius.md),
            contentPadding = PaddingValues(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.sm)) {
            Text(
                text = when (state.agentLanguageMode) {
                    AgentLanguageMode.FOLLOW_UI -> state.strings.agentLanguageFollowUi
                    AgentLanguageMode.CHINESE -> state.strings.agentLanguageChinese
                    AgentLanguageMode.ENGLISH -> state.strings.agentLanguageEnglish
                }, fontWeight = FontWeight.SemiBold, color = ThemeColors.brand)
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    // ── 定时任务 & 触发器 ──
    SectionHeader("定时任务 & 触发器")
    var triggerVersion by remember { mutableStateOf(0) }
    var showAddCronDialog by remember { mutableStateOf(false) }
    var showAddLifetimeDialog by remember { mutableStateOf(false) }
    val triggers = remember(triggerVersion) { com.mengpaw.kernel.trigger.TriggerEngine.list() }

    if (triggers.isEmpty()) {
        Text("暂无触发器。添加定时任务让 Agent 在指定时间自动执行。",
            style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        Spacer(Modifier.height(ArcoSpacing.sm))
    } else {
        triggers.forEach { trigger ->
            Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.bgCard) {
                Row(Modifier.padding(ArcoSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (trigger.type == com.mengpaw.kernel.trigger.TriggerEngine.TriggerType.CRON) Icons.Outlined.Schedule
                        else Icons.Outlined.Person, null,
                        tint = if (trigger.enabled) ThemeColors.brand else ThemeColors.textSecondary,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(ArcoSpacing.sm))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(trigger.id, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(4.dp),
                                color = if (trigger.type == com.mengpaw.kernel.trigger.TriggerEngine.TriggerType.CRON)
                                    ArcoColors.Blue6.copy(alpha = 0.1f) else ArcoColors.Orange6.copy(alpha = 0.1f)) {
                                Text(
                                    if (trigger.type == com.mengpaw.kernel.trigger.TriggerEngine.TriggerType.CRON) "定时" else "真人",
                                    Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    fontSize = 9.sp, color = if (trigger.type == com.mengpaw.kernel.trigger.TriggerEngine.TriggerType.CRON)
                                        ArcoColors.Blue6 else ArcoColors.Orange6
                                )
                            }
                        }
                        Text("${trigger.config} → ${trigger.action.take(40)}",
                            style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, maxLines = 1)
                        if (trigger.lastFired > 0) {
                            Text(
                                "上次触发: ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(trigger.lastFired))}",
                                fontSize = 10.sp, color = ArcoColors.Gray6
                            )
                        }
                    }
                    Switch(checked = trigger.enabled, onCheckedChange = { newChecked ->
                        if (!newChecked) com.mengpaw.kernel.trigger.TriggerEngine.disable(trigger.id)
                        else com.mengpaw.kernel.trigger.TriggerEngine.enable(trigger.id)
                        triggerVersion++
                    }, modifier = Modifier.size(32.dp))
                    // Delete button
                    IconButton(onClick = {
                        com.mengpaw.kernel.trigger.TriggerEngine.remove(trigger.id)
                        triggerVersion++
                    }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.Close, "删除", Modifier.size(16.dp), tint = ThemeColors.textSecondary)
                    }
                }
            }
        }
    }

    // Action buttons
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
        OutlinedButton(onClick = { showAddCronDialog = true },
            modifier = Modifier.weight(1f), shape = RoundedCornerShape(ArcoRadius.md)) {
            Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("添加定时", style = MaterialTheme.typography.labelSmall)
        }
        OutlinedButton(onClick = { showAddLifetimeDialog = true },
            modifier = Modifier.weight(1f), shape = RoundedCornerShape(ArcoRadius.md)) {
            Icon(Icons.Outlined.Person, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("添加真人感", style = MaterialTheme.typography.labelSmall)
        }
    }

    // ── Add CRON Dialog ──
    if (showAddCronDialog) {
        CronTriggerDialog(
            onDismiss = { showAddCronDialog = false },
            onConfirm = { id, expr, action ->
                com.mengpaw.kernel.trigger.TriggerEngine.addCron(id, expr, action)
                triggerVersion++
                showAddCronDialog = false
            }
        )
    }

    // ── Add LIFETIME Dialog ──
    if (showAddLifetimeDialog) {
        LifetimeTriggerDialog(
            onDismiss = { showAddLifetimeDialog = false },
            onConfirm = { id, timeRange, action ->
                com.mengpaw.kernel.trigger.TriggerEngine.addLifetime(id, timeRange, action)
                triggerVersion++
                showAddLifetimeDialog = false
            }
        )
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    // ── Agent 插件（从全局池导入） ──
    FrameworkItemSection("Agent 插件", Icons.Outlined.Extension, agentPluginItems)

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    // ── MengPaw CLI（Agent 可用命令） ──
    FrameworkItemSection("MengPaw CLI", Icons.Outlined.Terminal, agentToolItems)

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    // ── Agent Skills（从全局池导入） ──
    FrameworkItemSection("Agent Skills", Icons.Outlined.AutoAwesome, agentSkillItems)

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    // ── 工作区核心文件 ──
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionHeader("工作区文件")
        Spacer(Modifier.weight(1f))
        if (onRefreshWorkspace != null) {
            IconButton(onClick = onRefreshWorkspace, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.Refresh, "刷新文件列表", Modifier.size(18.dp), tint = ThemeColors.textSecondary)
            }
        }
    }
    FrameworkItemSection("", Icons.Outlined.Description, workspaceItems)
}

// ─── 02. Framework Settings Content ─────────────────────────────────

@Composable
private fun FrameworkSettingsContent(
    state: SettingsState,
    viewModel: SettingsViewModel,
    onNavigateToPluginMarket: () -> Unit,
    pluginItems: List<FrameworkItem> = emptyList(),
    toolItems: List<FrameworkItem> = emptyList(),
    skillItems: List<FrameworkItem> = emptyList()
) {
    // ── LLM 供应商连接配置 (API Key) ──
    SectionHeader("供应商连接")

    // Saved connections summary
    if (state.savedProviders.isNotEmpty()) {
        state.savedProviders.forEach { saved ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.editProvider(saved) },
                shape = RoundedCornerShape(ArcoRadius.lg), color = ThemeColors.bgCard
            ) {
                Row(Modifier.padding(ArcoSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.brandContainer) {
                        Icon(Icons.Outlined.Key, null, tint = ThemeColors.brand, modifier = Modifier.size(32.dp).padding(6.dp))
                    }
                    Spacer(Modifier.width(ArcoSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(saved.preset.label, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                        Text("${saved.model} · ${saved.endpoint.take(35)}",
                            style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, maxLines = 1)
                    }
                    if (saved.balance.isNotBlank()) {
                        Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Green1) {
                            Text("$${saved.balance}", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall, color = ArcoColors.Green7)
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { viewModel.removeProvider(saved.preset) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, "删除", tint = ThemeColors.textSecondary, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Spacer(Modifier.height(ArcoSpacing.sm))
        }
    }

    // Provider editor — with API Key
    if (state.apiSectionExpanded) {
        SectionHeader(if (state.savedProviders.isNotEmpty()) "编辑连接" else "新增连接")
        LlmProviderPreset.entries.forEach { preset ->
            if (preset != LlmProviderPreset.CUSTOM && preset != LlmProviderPreset.SELF_HOSTED) {
                ProviderCard(preset, state.selectedProvider == preset, state.modelName, state.remoteModels,
                    { viewModel.selectProvider(preset) }, { viewModel.updateModelName(it) })
            }
        }
        ProviderCard(LlmProviderPreset.SELF_HOSTED, state.selectedProvider == LlmProviderPreset.SELF_HOSTED,
            state.modelName, state.remoteModels,
            { viewModel.selectProvider(LlmProviderPreset.SELF_HOSTED) }, { viewModel.updateModelName(it) })
        ProviderCard(LlmProviderPreset.CUSTOM, state.selectedProvider == LlmProviderPreset.CUSTOM,
            state.modelName, state.remoteModels,
            { viewModel.selectProvider(LlmProviderPreset.CUSTOM) }, { viewModel.updateModelName(it) })
        Spacer(Modifier.height(ArcoSpacing.sm))

        SettingsTextField(Icons.Outlined.Key, "API Key", state.apiKey,
            onValueChange = { viewModel.updateApiKey(it) },
            visualTransformation = if (state.showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { viewModel.toggleShowApiKey() }) {
                    Icon(if (state.showApiKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (state.showApiKey) "隐藏" else "显示")
                }
            })
        Spacer(Modifier.height(ArcoSpacing.sm))
        SettingsTextField(Icons.Outlined.Link, "API 地址", state.apiEndpoint,
            onValueChange = { viewModel.updateApiEndpoint(it) })
        Spacer(Modifier.height(ArcoSpacing.sm))
        // Model selector — dropdown from API if fetched, else text input
        if (state.remoteModelsFetched && state.remoteModels.isNotEmpty()) {
            var modelDropdownExpanded by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(vertical = ArcoSpacing.xs),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ModelTraining, null, Modifier.size(20.dp), tint = ThemeColors.textSecondary)
                Spacer(Modifier.width(ArcoSpacing.sm))
                Column(Modifier.weight(1f)) {
                    Text("模型", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
                    Box {
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { modelDropdownExpanded = true },
                            shape = RoundedCornerShape(ArcoRadius.md),
                            color = ThemeColors.bgCardHigh
                        ) {
                            Row(Modifier.padding(horizontal = ArcoSpacing.md, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(state.modelName, Modifier.weight(1f), fontSize = 14.sp,
                                    color = ArcoColors.Green6, fontWeight = FontWeight.Medium)
                                Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(20.dp),
                                    tint = ArcoColors.Green6)
                            }
                        }
                        DropdownMenu(
                            expanded = modelDropdownExpanded,
                            onDismissRequest = { modelDropdownExpanded = false }
                        ) {
                            Text("API 返回 ${state.remoteModels.size} 个模型",
                                Modifier.padding(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.xs),
                                fontSize = 10.sp, color = ThemeColors.textSecondary)
                            HorizontalDivider()
                            state.remoteModels.take(30).forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Text(model.take(40),
                                            fontWeight = if (model == state.modelName) FontWeight.Bold else FontWeight.Normal,
                                            color = if (model == state.modelName) ArcoColors.Green6 else ThemeColors.textPrimary)
                                    },
                                    onClick = { viewModel.updateModelName(model); modelDropdownExpanded = false },
                                    leadingIcon = if (model == state.modelName) {
                                        { Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = ArcoColors.Green6) }
                                    } else null
                                )
                            }
                            if (state.remoteModels.size > 30) {
                                Text("... 还有 ${state.remoteModels.size - 30} 个模型",
                                    Modifier.padding(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.xs),
                                    fontSize = 10.sp, color = ThemeColors.textSecondary)
                            }
                        }
                    }
                }
            }
        } else {
            SettingsTextField(Icons.Outlined.ModelTraining, "模型", state.modelName,
                onValueChange = { viewModel.updateModelName(it) })
        }
        Spacer(Modifier.height(ArcoSpacing.sm))

        // Cache optimization
        val cacheStrategy = CacheStrategy.forProvider(state.apiEndpoint)
        if (cacheStrategy != CacheStrategy.NONE) {
            Surface(shape = RoundedCornerShape(ArcoRadius.sm),
                color = ArcoColors.Green6.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = ArcoSpacing.md, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, null, Modifier.size(14.dp), tint = ArcoColors.Green6)
                    Spacer(Modifier.width(6.dp))
                    Text(CacheStrategy.labelFor(cacheStrategy),
                        style = MaterialTheme.typography.labelSmall, color = ArcoColors.Green6)
                    Spacer(Modifier.weight(1f))
                    Text("已优化", style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold, color = ArcoColors.Green6)
                }
            }
            Spacer(Modifier.height(ArcoSpacing.sm))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
            OutlinedButton(onClick = { viewModel.testConnection() }, modifier = Modifier.weight(1f),
                enabled = !state.isTesting && state.apiKey.isNotBlank(),
                shape = RoundedCornerShape(ArcoRadius.md)) {
                if (state.isTesting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ThemeColors.brand)
                else Icon(Icons.Outlined.Wifi, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("测试", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(onClick = { viewModel.saveApiKey() }, modifier = Modifier.weight(1f),
                enabled = state.apiKey.isNotBlank(), shape = RoundedCornerShape(ArcoRadius.md)) {
                Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("保存", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(onClick = { viewModel.toggleApiSection() }, modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(ArcoRadius.md)) {
                Text("收起", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (state.isTesting) {
            Spacer(Modifier.height(4.dp))
            Text("正在测试连接...", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
        }
        Spacer(Modifier.height(ArcoSpacing.lg))
    } else {
        OutlinedButton(onClick = { viewModel.toggleApiSection() },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(ArcoRadius.md)) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("新增供应商连接")
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    // Plugin management
    NavigationLink(Icons.Outlined.Extension, "插件管理", "浏览、安装、管理 Agent 插件") { onNavigateToPluginMarket() }
    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    // Memory backend
    SectionHeader("记忆管理")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("记忆管理后端", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text(if (state.memoryBackend == "memory-plugin") "内置 · Markdown 文件" else state.memoryBackend,
                style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Gray3) {
            Text("需安装插件", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, color = ArcoColors.Gray6)
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    // Context strategy
    SectionHeader("上下文策略")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("上下文策略", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text(if (state.contextStrategy == "default") "内置 · Reasonix 四级折叠" else state.contextStrategy,
                style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Gray3) {
            Text("需安装插件", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, color = ArcoColors.Gray6)
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    // PAD floating window
    SectionHeader("界面增强")
    val context = androidx.compose.ui.platform.LocalContext.current
    var dotEnabled by remember { mutableStateOf(com.mengpaw.plugin.pad.PadPlugin.isVisible()) }
    Row(Modifier.fillMaxWidth().padding(vertical = ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.AdsClick, null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text("PAD 悬浮窗", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text("呼吸灯小圆点显示 Agent 工作状态", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        Switch(checked = dotEnabled, onCheckedChange = { checked ->
            dotEnabled = checked
            if (checked) com.mengpaw.plugin.pad.PadPlugin.show() else com.mengpaw.plugin.pad.PadPlugin.hide()
        })
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    // ── 安全规则 ──
    SecurityRulesSection()

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    // ── 全局插件 ──
    FrameworkItemSection("全局插件", Icons.Outlined.Extension, pluginItems)

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    // ── MengPaw CLI ──
    FrameworkItemSection("MengPaw CLI", Icons.Outlined.Terminal, toolItems)

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    // ── 全局 Skills ──
    FrameworkItemSection("全局 Skills", Icons.Outlined.AutoAwesome, skillItems)
}

// ─── 03. System Settings Content ────────────────────────────────────

@Composable
private fun SystemSettingsContent(
    state: SettingsState,
    viewModel: SettingsViewModel,
    onNavigateToPluginMarket: () -> Unit
) {
    // Appearance
    SectionHeader(state.strings.appearance)
    SettingsSwitch(Icons.Outlined.DarkMode, state.strings.darkTheme, state.strings.darkThemeDesc,
        checked = state.darkTheme, onCheckedChange = { viewModel.toggleDarkTheme() })
    Spacer(Modifier.height(ArcoSpacing.lg))

    // UI Language
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.Translate, null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(state.strings.language, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(state.strings.languageDesc, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        OutlinedButton(onClick = { viewModel.toggleLanguage() },
            shape = RoundedCornerShape(ArcoRadius.md),
            contentPadding = PaddingValues(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.sm)) {
            Text(if (state.useChinese) state.strings.languageEn else state.strings.languageZh,
                fontWeight = FontWeight.SemiBold, color = ThemeColors.brand)
        }
    }

    // Timezone
    Spacer(Modifier.height(ArcoSpacing.lg))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.Language, null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text("时区", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text(state.timezone, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        TextButton(onClick = { viewModel.updateTimezone(if (state.timezone == "Asia/Shanghai") java.util.TimeZone.getDefault().id else "Asia/Shanghai") }) {
            Text(if (state.timezone == "Asia/Shanghai") "自动" else "上海")
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    // Battery optimization
    SectionHeader("后台运行")
    var powerSaverEnabled by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("后台省电模式", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text("降低后台轮询频率和动画帧率，延长续航", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        Switch(checked = powerSaverEnabled, onCheckedChange = { powerSaverEnabled = it })
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    OutlinedButton(onClick = {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = android.net.Uri.parse("package:com.mengpaw.shell")
            ctx.startActivity(intent)
        } catch (_: Exception) {}
    }, modifier = Modifier.fillMaxWidth().padding(top = ArcoSpacing.sm), shape = RoundedCornerShape(ArcoRadius.md)) {
        Text("忽略电池优化（推荐开启）", style = MaterialTheme.typography.labelSmall)
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    // ── Token 用量统计 ──
    SectionHeader("Token 用量统计")
    var statRange by remember { mutableIntStateOf(0) }  // 0=daily, 1=weekly, 2=monthly

    Row(Modifier.fillMaxWidth().padding(bottom = ArcoSpacing.sm), horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
        listOf("每日", "每周", "每月").forEachIndexed { i, label ->
            Surface(
                modifier = Modifier.clickable { statRange = i },
                shape = RoundedCornerShape(ArcoRadius.sm),
                color = if (statRange == i) ThemeColors.brand else ThemeColors.bgCard
            ) {
                Text(label, Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    fontSize = 12.sp, fontWeight = if (statRange == i) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (statRange == i) Color.White else ThemeColors.textSecondary)
            }
        }
    }

    val collector = TokenStatsCollector
    val models = remember { collector.allModels() }
    val chartData = remember(statRange) {
        when (statRange) {
            0 -> collector.dailyRecords().map { it.date.substring(5) to it }
            1 -> collector.weeklyRecords().map { it.weekLabel to it }
            2 -> collector.monthlyRecords().map { it.weekLabel to it }
            else -> emptyList()
        }
    }

    // Chart
    if (chartData.isNotEmpty()) {
        @Suppress("UNCHECKED_CAST")
        val modelSeries = models.map { model ->
            model to chartData.map { (label, record) ->
                val tokens = when (statRange) {
                    0 -> (record as TokenStatsCollector.DayRecord).modelTokens[model] ?: 0L
                    else -> (record as TokenStatsCollector.WeeklySummary).modelTokens[model] ?: 0L
                }
                label to tokens
            }
        }
        val cacheSeries = chartData.map { (label, record) ->
            val cache = when (statRange) {
                0 -> (record as TokenStatsCollector.DayRecord).cacheHitTokens
                else -> (record as TokenStatsCollector.WeeklySummary).cacheHitTokens
            }
            label to cache
        }
        TokenLineChart(series = modelSeries, cacheSeries = cacheSeries)
    } else {
        Text("暂无 Token 用量数据。开始使用后自动记录。",
            style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary,
            modifier = Modifier.padding(vertical = ArcoSpacing.lg))
    }

    // Summary cards
    val totalTokens = collector.dailyRecords().sumOf { it.totalTokens }
    val cacheSaved = collector.totalCacheSaved()
    if (totalTokens > 0) {
        Spacer(Modifier.height(ArcoSpacing.md))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
            StatCard("总用量", formatTokenCount(totalTokens), Icons.Outlined.BarChart, ArcoColors.Blue1, ArcoColors.Blue6)
            StatCard("缓存节省", formatTokenCount(cacheSaved), Icons.Outlined.Cached, ArcoColors.Green1, ArcoColors.Green6)
            StatCard("预估节省", "\$" + "%.2f".format(collector.estimatedSavingsUsd()),
                Icons.Outlined.AttachMoney, ArcoColors.Orange1, ArcoColors.Orange6)
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    // About
    SectionHeader(state.strings.about)
    InfoRow(state.strings.version, "0.7.2")
    InfoRow(state.strings.core, "mengpaw-kernel + mengpaw-core")
    InfoRow(state.strings.design, "Arco Design · Compose Material 3")

    Spacer(Modifier.height(ArcoSpacing.md))

    // Legal & contact
    SectionHeader("法律与联系")
    Row(Modifier.fillMaxWidth().clickable { /* open license page */ }.padding(vertical = ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Description, null, Modifier.size(20.dp), tint = ThemeColors.textSecondary)
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text("许可证", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text("AGPL-3.0", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = ArcoColors.Gray5)
    }
    Row(Modifier.fillMaxWidth().clickable { /* open open-source attributions */ }.padding(vertical = ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.MenuBook, null, Modifier.size(20.dp), tint = ThemeColors.textSecondary)
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text("开源声明", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text("第三方库与许可", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = ArcoColors.Gray5)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Email, null, Modifier.size(20.dp), tint = ThemeColors.textSecondary)
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text("联系我们", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text("support@wowblue.cn", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Info, null, Modifier.size(20.dp), tint = ThemeColors.textSecondary)
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text("版权声明", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text("© 2026 深圳哇蓝文化科技有限公司", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    // Reset
    SectionHeader("数据管理")
    OutlinedButton(
        onClick = { viewModel.resetToDefaults() },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ArcoRadius.lg),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = ArcoColors.Red6)
    ) {
        Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(ArcoSpacing.sm))
        Text(state.strings.resetDefaults)
    }
}

// ─── Security Rules Section ──────────────────────────────────────────

@Composable
private fun SecurityRulesSection() {
    SectionHeader("安全规则")

    // ── 1. 框架信任列表 ──
    var showTrusted by remember { mutableStateOf(false) }
    val trustedPeers = remember { com.mengpaw.kernel.security.PromptFirewall.listTrusted() }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .clickable { showTrusted = !showTrusted },
        shape = RoundedCornerShape(ArcoRadius.md),
        color = ThemeColors.bgCard
    ) {
        Column(Modifier.padding(ArcoSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.VerifiedUser, null, Modifier.size(20.dp), tint = ArcoColors.Blue6)
                Spacer(Modifier.width(ArcoSpacing.sm))
                Column(Modifier.weight(1f)) {
                    Text("框架信任列表", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text("已信任 ${trustedPeers.size} 个框架设备", fontSize = 12.sp, color = ThemeColors.textSecondary)
                }
                Icon(
                    if (showTrusted) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null, Modifier.size(18.dp), tint = ThemeColors.textSecondary
                )
            }
            AnimatedVisibility(visible = showTrusted) {
                Column(Modifier.padding(top = ArcoSpacing.sm)) {
                    if (trustedPeers.isEmpty()) {
                        Text("暂无受信任的框架设备。通过 ACP 配对添加。",
                            fontSize = 12.sp, color = ThemeColors.textSecondary)
                    } else {
                        trustedPeers.forEach { peerId ->
                            val fingerprint = remember(peerId) {
                                try {
                                    java.io.File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED, "$peerId.trusted")
                                        .readText().take(16)
                                } catch (_: Exception) { "—" }
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.Devices, null, Modifier.size(16.dp), tint = ArcoColors.Green6)
                                Spacer(Modifier.width(ArcoSpacing.sm))
                                Column(Modifier.weight(1f)) {
                                    Text(peerId, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("指纹: $fingerprint", fontSize = 11.sp, color = ThemeColors.textSecondary)
                                }
                                Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Green1) {
                                    Text("已信任", Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                        fontSize = 10.sp, color = ArcoColors.Green6)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 2. 内核完整性防护 ──
    var kernelIntegrity by remember { mutableStateOf(com.mengpaw.kernel.security.SecurityPolicy.globalEnabled) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = RoundedCornerShape(ArcoRadius.md),
        color = ThemeColors.bgCard
    ) {
        Row(Modifier.padding(ArcoSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Security, null, Modifier.size(20.dp), tint = if (kernelIntegrity) ArcoColors.Green6 else ArcoColors.Gray5)
            Spacer(Modifier.width(ArcoSpacing.sm))
            Column(Modifier.weight(1f)) {
                Text("内核完整性防护", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    if (kernelIntegrity) "已启用 — 阻止 Agent 修改内核文件" else "已禁用",
                    fontSize = 12.sp, color = ThemeColors.textSecondary
                )
            }
            Switch(checked = kernelIntegrity, onCheckedChange = {
                kernelIntegrity = it
                com.mengpaw.kernel.security.SecurityPolicy.globalEnabled = it
            }, modifier = Modifier.size(32.dp),
                colors = SwitchDefaults.colors(checkedTrackColor = ArcoColors.Green6))
        }
    }

    // ── 3. 插件完整性防护 ──
    var pluginIntegrity by remember { mutableStateOf(com.mengpaw.kernel.plugin.PluginManager.globalInstance.integrityCheckEnabled) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = RoundedCornerShape(ArcoRadius.md),
        color = ThemeColors.bgCard
    ) {
        Row(Modifier.padding(ArcoSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Shield, null, Modifier.size(20.dp), tint = if (pluginIntegrity) ArcoColors.Green6 else ArcoColors.Gray5)
            Spacer(Modifier.width(ArcoSpacing.sm))
            Column(Modifier.weight(1f)) {
                Text("插件完整性防护", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    if (pluginIntegrity) "已启用 — 验证插件签名和哈希" else "已禁用",
                    fontSize = 12.sp, color = ThemeColors.textSecondary
                )
            }
            Switch(checked = pluginIntegrity, onCheckedChange = {
                pluginIntegrity = it
                com.mengpaw.kernel.plugin.PluginManager.globalInstance.integrityCheckEnabled = it
            }, modifier = Modifier.size(32.dp),
                colors = SwitchDefaults.colors(checkedTrackColor = ArcoColors.Green6))
        }
    }

    // ── 4. 文件完整性防护 ──
    var fileIntegrity by remember { mutableStateOf(com.mengpaw.core.security.IntegrityGuard.globalEnabled) }
    var showProtectedPaths by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .clickable { showProtectedPaths = !showProtectedPaths },
        shape = RoundedCornerShape(ArcoRadius.md),
        color = ThemeColors.bgCard
    ) {
        Column(Modifier.padding(ArcoSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Folder, null, Modifier.size(20.dp), tint = if (fileIntegrity) ArcoColors.Green6 else ArcoColors.Gray5)
                Spacer(Modifier.width(ArcoSpacing.sm))
                Column(Modifier.weight(1f)) {
                    Text("文件完整性防护", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text(
                        if (fileIntegrity) "已启用 — 保护核心目录不被修改" else "已禁用",
                        fontSize = 12.sp, color = ThemeColors.textSecondary
                    )
                }
                Switch(checked = fileIntegrity, onCheckedChange = {
                    fileIntegrity = it
                    com.mengpaw.core.security.IntegrityGuard.globalEnabled = it
                }, modifier = Modifier.size(32.dp),
                    colors = SwitchDefaults.colors(checkedTrackColor = ArcoColors.Green6))
            }
            AnimatedVisibility(visible = showProtectedPaths) {
                Column(Modifier.padding(top = ArcoSpacing.sm)) {
                    Text("受保护的目录：", fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        color = ThemeColors.textSecondary)
                    Spacer(Modifier.height(4.dp))
                    val paths = listOf(
                        "内核目录" to "/data/data/com.mengpaw/core",
                        "Agent 文档" to com.mengpaw.kernel.DataPaths.AGENTS,
                        "插件缓存" to com.mengpaw.kernel.DataPaths.PLUGIN_CACHE,
                        "密钥存储" to "/data/data/com.mengpaw/shared_prefs/mengpaw_vault"
                    )
                    paths.forEach { (label, path) ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Icon(Icons.Outlined.Lock, null, Modifier.size(12.dp), tint = ArcoColors.Gray5)
                            Spacer(Modifier.width(6.dp))
                            Text("$label  ", fontSize = 11.sp, color = ThemeColors.textSecondary)
                            Text(path, fontSize = 11.sp, color = ArcoColors.Gray5)
                        }
                    }
                }
            }
        }
    }
}

// ─── Reusable Helpers ────────────────────────────────────────────────

@Composable
private fun StatCard(
    title: String, value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    bgColor: Color, fgColor: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = bgColor
    ) {
        Column(Modifier.padding(ArcoSpacing.md)) {
            Icon(icon, null, Modifier.size(20.dp), tint = fgColor)
            Spacer(Modifier.height(6.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = fgColor)
            Text(title, fontSize = 11.sp, color = fgColor.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun FrameworkItemSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    items: List<FrameworkItem>
) {
    if (title.isNotBlank()) SectionHeader(title)
    if (items.isEmpty()) {
        Text("暂无条目", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        Spacer(Modifier.height(ArcoSpacing.sm))
        return
    }

    // Group: builtin → official → custom
    val order = listOf(ItemCategory.BUILTIN, ItemCategory.OFFICIAL, ItemCategory.CUSTOM)
    val grouped = items.groupBy { it.category }

    order.forEach { cat ->
        val group = grouped[cat] ?: return@forEach
        group.forEach { item ->
            var expanded by remember { mutableStateOf(false) }
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                shape = RoundedCornerShape(ArcoRadius.md),
                color = ThemeColors.bgCard,
                onClick = { expanded = !expanded }
            ) {
                Column(Modifier.padding(ArcoSpacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, Modifier.size(16.dp), tint = cat.color)
                        Spacer(Modifier.width(ArcoSpacing.sm))
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = ThemeColors.textPrimary)
                            if (item.summary.isNotBlank()) {
                                Text(item.summary, fontSize = 12.sp, color = ThemeColors.textSecondary, maxLines = 1)
                            }
                        }
                        Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = cat.color.copy(alpha = 0.1f)) {
                            Text(cat.label, Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                fontSize = 10.sp, color = cat.color)
                        }
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            null, Modifier.size(18.dp), tint = ThemeColors.textSecondary
                        )
                    }
                    // Expanded markdown doc
                    AnimatedVisibility(visible = expanded) {
                        if (item.docMarkdown.isNotBlank()) {
                            MarkdownText(
                                content = item.docMarkdown,
                                modifier = Modifier.padding(top = ArcoSpacing.sm)
                            )
                        } else {
                            Text("暂无文档", Modifier.padding(top = ArcoSpacing.sm),
                                fontSize = 12.sp, color = ThemeColors.textSecondary)
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(ArcoSpacing.sm))
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        color = ThemeColors.brand, modifier = Modifier.padding(bottom = ArcoSpacing.sm))
}

@Composable
private fun SettingsSwitch(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String, subtitle: String, checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = ArcoSpacing.sm)) {
        Icon(icon, contentDescription = null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = ArcoColors.Blue6))
    }
}

@Composable
private fun SettingsTextField(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String, value: String,
    onValueChange: (String) -> Unit,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(ArcoSpacing.md))
        OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            shape = RoundedCornerShape(ArcoRadius.md),
            visualTransformation = visualTransformation, trailingIcon = trailingIcon,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ArcoColors.Blue6,
                unfocusedBorderColor = ThemeColors.border,
                focusedLabelColor = ArcoColors.Blue6))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = ArcoSpacing.xs), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ThemeColors.textSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NavigationLink(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(ArcoRadius.lg),
        color = ArcoColors.Gray1,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(ArcoSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = ThemeColors.brand, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(ArcoSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = ThemeColors.brand)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = ArcoColors.Gray5)
        }
    }
}

@Composable
private fun ProviderCard(
    preset: LlmProviderPreset,
    isSelected: Boolean,
    selectedModel: String,
    remoteModels: List<String> = emptyList(),
    onSelect: () -> Unit,
    onSelectModel: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(isSelected) }
    LaunchedEffect(isSelected) {
        if (isSelected) expanded = true else expanded = false
    }

    val strategy = CacheStrategy.forProvider(preset.endpoint)
    val optimized = strategy != CacheStrategy.NONE

    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(ArcoRadius.md),
        color = if (isSelected) ArcoColors.Blue1.copy(alpha = 0.3f) else ThemeColors.bgCard,
        tonalElevation = if (isSelected) 2.dp else 0.dp
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(); expanded = !expanded }
                    .padding(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null, Modifier.size(18.dp), tint = ThemeColors.textSecondary)
                Spacer(Modifier.width(ArcoSpacing.sm))
                Text(preset.label, Modifier.weight(1f),
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 14.sp, color = if (isSelected) ArcoColors.Blue6 else ThemeColors.textPrimary)
                if (optimized) {
                    Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Green6.copy(alpha = 0.1f)) {
                        Text("已优化", Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            fontSize = 10.sp, color = ArcoColors.Green6)
                    }
                }
                if (isSelected) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Outlined.CheckCircle, null, Modifier.size(16.dp), tint = ArcoColors.Blue6)
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = ArcoSpacing.lg, end = ArcoSpacing.md, bottom = ArcoSpacing.sm)) {
                    Text(preset.endpoint.take(60), fontSize = 11.sp, color = ThemeColors.textSecondary,
                        modifier = Modifier.padding(bottom = 6.dp))
                    preset.models.forEach { model ->
                        Row(Modifier.fillMaxWidth().clickable { onSelectModel(model.name) }
                            .padding(vertical = 4.dp, horizontal = ArcoSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedModel == model.name, onClick = { onSelectModel(model.name) },
                                modifier = Modifier.size(18.dp),
                                colors = RadioButtonDefaults.colors(selectedColor = ThemeColors.brand))
                            Spacer(Modifier.width(8.dp))
                            Text(model.name, Modifier.weight(1f), fontSize = 13.sp)
                            if (model.type == "多模态") Text("🖼", fontSize = 12.sp)
                        }
                    }
                    if (remoteModels.isNotEmpty()) {
                        Text("API 返回", fontSize = 10.sp, color = ArcoColors.Green6, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                        remoteModels.take(10).filter { it !in preset.models.map { m -> m.name } }.forEach { model ->
                            Row(Modifier.fillMaxWidth().clickable { onSelectModel(model) }
                                .padding(vertical = 4.dp, horizontal = ArcoSpacing.sm),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selectedModel == model, onClick = { onSelectModel(model) },
                                    modifier = Modifier.size(18.dp),
                                    colors = RadioButtonDefaults.colors(selectedColor = ThemeColors.brand))
                                Spacer(Modifier.width(8.dp))
                                Text(model, Modifier.weight(1f), fontSize = 13.sp, color = ArcoColors.Green6)
                            }
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(2.dp))
}

// ═══════════════════════════════════════════════════════════════════════
// CRON Trigger Dialog — add a scheduled CRON task
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun CronTriggerDialog(
    onDismiss: () -> Unit,
    onConfirm: (id: String, cronExpr: String, action: String) -> Unit
) {
    var selectedPreset by remember { mutableStateOf(0) }
    var customCron by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("") }

    // Preset CRON templates for common use cases
    data class CronPreset(val label: String, val cron: String, val hint: String)
    val presets = listOf(
        CronPreset("每天早上 9:00", "0 9 * * *", "生成昨日摘要并推送"),
        CronPreset("每天中午 12:00", "0 12 * * *", "检查今日待办事项"),
        CronPreset("每天晚上 20:00", "0 20 * * *", "总结今日工作进展"),
        CronPreset("每小时整点", "0 * * * *", "检查系统状态"),
        CronPreset("自定义 (输入Cron表达式)", "", "")
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加定时任务", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)
            ) {
                Text("选择时间", style = MaterialTheme.typography.labelMedium, color = ThemeColors.textSecondary)
                presets.forEachIndexed { i, preset ->
                    Surface(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { selectedPreset = i; if (preset.cron.isNotBlank()) customCron = preset.cron },
                        shape = RoundedCornerShape(ArcoRadius.sm),
                        color = if (selectedPreset == i) ThemeColors.brand.copy(alpha = 0.08f)
                            else ThemeColors.bgCardHigh
                    ) {
                        Row(
                            Modifier.padding(ArcoSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedPreset == i,
                                onClick = { selectedPreset = i; if (preset.cron.isNotBlank()) customCron = preset.cron },
                                modifier = Modifier.size(20.dp),
                                colors = RadioButtonDefaults.colors(selectedColor = ThemeColors.brand)
                            )
                            Spacer(Modifier.width(ArcoSpacing.sm))
                            Column {
                                Text(preset.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                if (preset.cron.isNotBlank()) {
                                    Text("CRON: ${preset.cron}", fontSize = 10.sp, color = ThemeColors.textSecondary,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }

                // Custom CRON input (shown when "自定义" is selected or for manual edit)
                if (selectedPreset == presets.size - 1 || selectedPreset >= 0) {
                    OutlinedTextField(
                        value = customCron,
                        onValueChange = { customCron = it; selectedPreset = presets.size - 1 },
                        label = { Text("Cron 表达式") },
                        placeholder = { Text("分 时 日 月 周，如: 0 9 * * *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(ArcoRadius.md),
                        supportingText = {
                            Text(
                                "模糊窗口 ±${com.mengpaw.kernel.trigger.TriggerEngine.cronFuzzyWindowMinutes} 分钟，无需精确到秒",
                                fontSize = 10.sp, color = ThemeColors.textSecondary
                            )
                        }
                    )
                }

                OutlinedTextField(
                    value = action,
                    onValueChange = { action = it },
                    label = { Text("执行动作") },
                    placeholder = {
                        Text(presets.getOrNull(selectedPreset)?.hint ?: "描述 Agent 需要执行的任务...")
                    },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(ArcoRadius.md)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val expr = customCron.ifBlank { presets[selectedPreset].cron }
                    val act = action.ifBlank { presets[selectedPreset].hint }
                    if (expr.isNotBlank() && act.isNotBlank()) {
                        onConfirm("cron-${(1000..9999).random()}", expr, act)
                    }
                },
                enabled = customCron.isNotBlank() && action.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.brand),
                shape = RoundedCornerShape(ArcoRadius.md)
            ) { Text("添加", color = Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ═══════════════════════════════════════════════════════════════════════
// LIFETIME Trigger Dialog — add a human-like random trigger
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun LifetimeTriggerDialog(
    onDismiss: () -> Unit,
    onConfirm: (id: String, timeRange: String, action: String) -> Unit
) {
    var startHour by remember { mutableStateOf("10") }
    var startMin by remember { mutableStateOf("00") }
    var endHour by remember { mutableStateOf("20") }
    var endMin by remember { mutableStateOf("00") }
    var action by remember { mutableStateOf("") }
    var selectedTopic by remember { mutableStateOf(-1) }
    val topics = remember { com.mengpaw.kernel.trigger.TriggerEngine.LIFETIME_TOPICS }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加真人感触发器", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)
            ) {
                Text("在这个时间段内随机触发一次", style = MaterialTheme.typography.labelMedium, color = ThemeColors.textSecondary)

                // Time range picker
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)
                ) {
                    OutlinedTextField(
                        value = startHour, onValueChange = { if (it.length <= 2) startHour = it },
                        label = { Text("开始时") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(ArcoRadius.md),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Text(":", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = startMin, onValueChange = { if (it.length <= 2) startMin = it },
                        label = { Text("分") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(ArcoRadius.md),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Text("—", fontSize = 18.sp, color = ThemeColors.textSecondary)
                    OutlinedTextField(
                        value = endHour, onValueChange = { if (it.length <= 2) endHour = it },
                        label = { Text("结束时") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(ArcoRadius.md),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Text(":", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = endMin, onValueChange = { if (it.length <= 2) endMin = it },
                        label = { Text("分") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(ArcoRadius.md),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                // Topic presets
                Text("话题预设", style = MaterialTheme.typography.labelMedium, color = ThemeColors.textSecondary)
                topics.forEachIndexed { i, topic ->
                    Surface(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { selectedTopic = i; action = topic },
                        shape = RoundedCornerShape(ArcoRadius.sm),
                        color = if (selectedTopic == i) ThemeColors.brand.copy(alpha = 0.08f)
                            else ThemeColors.bgCardHigh
                    ) {
                        Row(
                            Modifier.padding(ArcoSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedTopic == i,
                                onClick = { selectedTopic = i; action = topic },
                                modifier = Modifier.size(20.dp),
                                colors = RadioButtonDefaults.colors(selectedColor = ThemeColors.brand)
                            )
                            Spacer(Modifier.width(ArcoSpacing.sm))
                            Text(topic, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }

                // Custom action
                OutlinedTextField(
                    value = action,
                    onValueChange = { action = it; selectedTopic = -1 },
                    label = { Text("自定义动作") },
                    minLines = 2, maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(ArcoRadius.md)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val timeRange = "${startHour}:${startMin}-${endHour}:${endMin}"
                    if (action.isNotBlank()) {
                        onConfirm("chat-${(1000..9999).random()}", timeRange, action)
                    }
                },
                enabled = action.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.brand),
                shape = RoundedCornerShape(ArcoRadius.md)
            ) { Text("添加", color = Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
