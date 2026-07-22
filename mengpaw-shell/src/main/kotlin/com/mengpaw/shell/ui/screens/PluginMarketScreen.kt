// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mengpaw.design.components.ArcoDivider
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing

/**
 * 插件管理 — 市场 + 已安装 双标签。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginMarketScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PluginViewModel = viewModel()
) {
    val plugins by viewModel.pluginItems.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val installedCount by viewModel.installedCount.collectAsState()
    val activeCount by viewModel.activeCount.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) { viewModel.refreshMarketplace() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("插件管理", fontWeight = FontWeight.SemiBold) },
            navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") } },
            actions = {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                        color = com.mengpaw.design.theme.ThemeColors.brand)
                    Spacer(Modifier.width(ArcoSpacing.sm))
                }
                IconButton(onClick = { viewModel.refreshMarketplace() }) {
                    Icon(Icons.Default.Refresh, "强制刷新")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = com.mengpaw.design.theme.ThemeColors.bgPrimary)
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Tab row
            TabRow(selectedTab, containerColor = com.mengpaw.design.theme.ThemeColors.bgPrimary) {
                Tab(selectedTab == 0, { selectedTab = 0 }) {
                    Text("市场 (${plugins.size})", modifier = Modifier.padding(ArcoSpacing.md))
                }
                Tab(selectedTab == 1, { selectedTab = 1 }) {
                    Text("已安装 ($installedCount)", modifier = Modifier.padding(ArcoSpacing.md))
                }
            }

            // Plugin list
            val displayItems = if (selectedTab == 0) plugins
            else plugins.filter { it.isInstalled }

            if (displayItems.isEmpty() && !isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Extension, null, Modifier.size(64.dp), tint = ArcoColors.Gray4)
                        Spacer(Modifier.height(ArcoSpacing.md))
                        Text(if (selectedTab == 0) "暂无可用插件" else "暂无已安装插件",
                            color = com.mengpaw.design.theme.ThemeColors.textSecondary)
                        if (selectedTab == 0) {
                            Text("点击刷新获取插件列表", style = MaterialTheme.typography.bodySmall,
                                color = ArcoColors.Gray5)
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)
                ) {
                    items(displayItems) { plugin ->
                        PluginCard(
                            item = plugin,
                            onInstall = { viewModel.installPlugin(plugin.id) },
                            onUninstall = { viewModel.uninstallPlugin(plugin.id) },
                            onToggle = {
                                if (plugin.isActive) viewModel.disablePlugin(plugin.id)
                                else viewModel.enablePlugin(plugin.id)
                            },
                            onClick = { onNavigateToDetail(plugin.id) }
                        )
                    }
                }
            }

            // Footer
            Surface(color = com.mengpaw.design.theme.ThemeColors.bgSecondary) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.sm),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Text("可用: ${plugins.size}", style = MaterialTheme.typography.labelSmall,
                        color = com.mengpaw.design.theme.ThemeColors.textSecondary)
                    Text("已安装: $installedCount", style = MaterialTheme.typography.labelSmall,
                        color = com.mengpaw.design.theme.ThemeColors.textSecondary)
                    Text("活跃: $activeCount", style = MaterialTheme.typography.labelSmall,
                        color = com.mengpaw.design.theme.ThemeColors.brand)
                }
            }
        }
    }
}

@Composable
private fun PluginCard(
    item: PluginUiItem,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    val isBuiltin = item.availability == PluginAvailability.BUILTIN

    Card(
        onClick = if (isBuiltin) ({}) else onClick,
        shape = RoundedCornerShape(ArcoRadius.lg),
        colors = CardDefaults.cardColors(containerColor = com.mengpaw.design.theme.ThemeColors.bgPrimary),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.padding(ArcoSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
            // Icon
            Surface(shape = RoundedCornerShape(ArcoRadius.md),
                color = com.mengpaw.design.theme.ThemeColors.brandContainer) {
                Icon(
                    pluginIcon(item.id), null,
                    tint = com.mengpaw.design.theme.ThemeColors.brand,
                    modifier = Modifier.size(36.dp).padding(8.dp)
                )
            }
            Spacer(Modifier.width(ArcoSpacing.md))

            // Info
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(ArcoSpacing.sm))
                    Text("v${item.version}", style = MaterialTheme.typography.labelSmall,
                        color = com.mengpaw.design.theme.ThemeColors.textSecondary)
                }
                Text(item.description, style = MaterialTheme.typography.bodySmall,
                    color = com.mengpaw.design.theme.ThemeColors.textSecondary, maxLines = 1)
                Row {
                    if (isBuiltin) {
                        Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Blue1) {
                            Text("已内置", Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall, color = ArcoColors.Blue6)
                        }
                    } else if (item.availability == PluginAvailability.EMBEDDED) {
                        Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Blue1) {
                            Text("已嵌入", Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall, color = ArcoColors.Blue6)
                        }
                    } else if (item.isInstalled) {
                        val label = if (item.isActive) "活跃" else "已禁用"
                        val bg = if (item.isActive) ArcoColors.Green1 else ArcoColors.Gray3
                        val fg = if (item.isActive) ArcoColors.Green6 else ArcoColors.Gray6
                        Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = bg) {
                            Text(label, Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall, color = fg)
                        }
                    } else if (item.availability == PluginAvailability.UNAVAILABLE) {
                        Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Gray3) {
                            Text("暂未发布", Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall, color = ArcoColors.Gray6)
                        }
                    }
                    item.permissions.take(2).forEach { perm ->
                        Spacer(Modifier.width(4.dp))
                        Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Orange1) {
                            Text(perm.take(20), Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall, color = ArcoColors.Orange6)
                        }
                    }
                }
            }

            // Action
            when (val state = item.installState) {
                is InstallState.Idle -> {
                    if (isBuiltin || item.availability == PluginAvailability.EMBEDDED) {
                        // 已内置 / 已嵌入：无操作按钮
                    } else if (item.isInstalled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = item.isActive,
                                onCheckedChange = { onToggle() },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = com.mengpaw.design.theme.ThemeColors.brand)
                            )
                            IconButton(onClick = onUninstall, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, "卸载",
                                    tint = com.mengpaw.design.theme.ThemeColors.error,
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                    } else if (item.availability == PluginAvailability.DOWNLOADABLE) {
                        IconButton(onClick = onInstall, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Download, "安装",
                                tint = com.mengpaw.design.theme.ThemeColors.brand,
                                modifier = Modifier.size(20.dp))
                        }
                    }
                }
                is InstallState.Downloading -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp,
                            color = com.mengpaw.design.theme.ThemeColors.brand)
                        Text("${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                }
                is InstallState.Verifying -> Text("校验中...", style = MaterialTheme.typography.labelSmall,
                    color = com.mengpaw.design.theme.ThemeColors.textSecondary)
                is InstallState.Installing -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LinearProgressIndicator(Modifier.width(48.dp),
                            color = com.mengpaw.design.theme.ThemeColors.brand)
                        Text(state.step.take(12), style = MaterialTheme.typography.labelSmall)
                    }
                }
                is InstallState.Done -> {
                    Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Green1) {
                        Text("已安装", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall, color = ArcoColors.Green6)
                    }
                }
                is InstallState.Failed -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("失败", style = MaterialTheme.typography.labelSmall,
                            color = com.mengpaw.design.theme.ThemeColors.error)
                        TextButton(onClick = onInstall) {
                            Text("重试", color = com.mengpaw.design.theme.ThemeColors.brand)
                        }
                    }
                }
            }
        }
    }
}

/** Icon mapping for plugin IDs. */
fun pluginIcon(id: String) = when {
    id.contains("fs") -> Icons.Default.Description
    id.contains("net") -> Icons.Default.Language
    id.contains("memory") -> Icons.Default.Star
    id.contains("skill") -> Icons.Default.Extension
    id.contains("self") -> Icons.Default.Android
    id.contains("ui") -> Icons.Default.TouchApp
    id.contains("proc") -> Icons.Default.Terminal
    id.contains("clipboard") -> Icons.Default.ContentPaste
    id.contains("notification") -> Icons.Default.Notifications
    else -> Icons.Default.Extension
}
