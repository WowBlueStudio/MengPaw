// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.shell.ui.localization.AppStrings

/**
 * Plugin detail screen showing metadata, commands, permissions, and actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginDetailScreen(
    pluginId: String,
    onNavigateBack: () -> Unit,
    strings: AppStrings,
    modifier: Modifier = Modifier,
    viewModel: PluginViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val plugins by viewModel.pluginItems.collectAsState()
    val plugin = plugins.find { it.id == pluginId }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(plugin?.displayName ?: pluginId, fontWeight = FontWeight.SemiBold) },
            navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, strings.back) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = com.mengpaw.design.theme.ThemeColors.bgPrimary)
        )
    }) { padding ->
        if (plugin == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(strings.pluginNotFound + pluginId, color = com.mengpaw.design.theme.ThemeColors.textSecondary)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(ArcoSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(ArcoSpacing.md)
        ) {
            // ── Header card ────────────────────────────────────────
            item {
                Card(
                    shape = RoundedCornerShape(ArcoRadius.lg),
                    colors = CardDefaults.cardColors(containerColor = com.mengpaw.design.theme.ThemeColors.bgPrimary)
                ) {
                    Column(Modifier.padding(ArcoSpacing.lg)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(ArcoRadius.md), color = com.mengpaw.design.theme.ThemeColors.brandContainer) {
                                Icon(pluginIcon(plugin.id), null, tint = com.mengpaw.design.theme.ThemeColors.brand,
                                    modifier = Modifier.size(48.dp).padding(12.dp))
                            }
                            Spacer(Modifier.width(ArcoSpacing.md))
                            Column {
                                Text(plugin.displayName, fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium)
                                Text("${if (plugin.version.isNotBlank()) "v${plugin.version} · " else ""}${plugin.author}",
                                    style = MaterialTheme.typography.bodySmall, color = com.mengpaw.design.theme.ThemeColors.textSecondary)
                            }
                        }
                        Spacer(Modifier.height(ArcoSpacing.md))

                        // Status badge
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val (statusText, statusColor) = when {
                                plugin.isActive -> "✅ ${strings.activeBadge}" to ArcoColors.Green6
                                plugin.isInstalled -> "📦 ${strings.installedBadge}" to ArcoColors.Orange6
                                else -> "⬇️ ${strings.notInstalledBadge}" to ArcoColors.Gray6
                            }
                            Surface(shape = RoundedCornerShape(ArcoRadius.sm),
                                color = statusColor.copy(alpha = 0.1f)) {
                                Text(statusText, Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall, color = statusColor)
                            }
                            Spacer(Modifier.width(ArcoSpacing.sm))
                            Text("${strings.typePrefix}${plugin.type.name}", style = MaterialTheme.typography.labelSmall,
                                color = com.mengpaw.design.theme.ThemeColors.textSecondary)
                        }

                        Spacer(Modifier.height(ArcoSpacing.sm))
                        Text(plugin.description, style = MaterialTheme.typography.bodyMedium,
                            color = com.mengpaw.design.theme.ThemeColors.textPrimary)
                    }
                }
            }

            // ── Commands ────────────────────────────────────────────
            item {
                Card(
                    shape = RoundedCornerShape(ArcoRadius.lg),
                    colors = CardDefaults.cardColors(containerColor = com.mengpaw.design.theme.ThemeColors.bgPrimary)
                ) {
                    Column(Modifier.padding(ArcoSpacing.lg)) {
                        Text("${strings.commandsLabel} (${plugin.commands.size})", fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(ArcoSpacing.sm))
                        plugin.commands.forEach { cmd ->
                            Surface(
                                shape = RoundedCornerShape(ArcoRadius.sm),
                                color = com.mengpaw.design.theme.ThemeColors.bgSecondary,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                Text(cmd,
                                    modifier = Modifier.padding(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.xs),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = com.mengpaw.design.theme.ThemeColors.textPrimary)
                            }
                        }
                    }
                }
            }

            // ── Permissions ─────────────────────────────────────────
            if (plugin.permissions.isNotEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(ArcoRadius.lg),
                        colors = CardDefaults.cardColors(containerColor = com.mengpaw.design.theme.ThemeColors.bgPrimary)
                    ) {
                        Column(Modifier.padding(ArcoSpacing.lg)) {
                            Text("${strings.permissionsLabel} (${plugin.permissions.size})", fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(ArcoSpacing.sm))
                            plugin.permissions.forEach { perm ->
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 2.dp)) {
                                    Icon(Icons.Default.Shield, null, Modifier.size(14.dp), tint = ArcoColors.Orange6)
                                    Spacer(Modifier.width(ArcoSpacing.sm))
                                    Text(perm, style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace, color = com.mengpaw.design.theme.ThemeColors.textSecondary)
                                }
                            }
                        }
                    }
                }
            }

            // ── Actions ─────────────────────────────────────────────
            item {
                Spacer(Modifier.height(ArcoSpacing.md))
                if (plugin.isInstalled) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.md)) {
                        OutlinedButton(
                            onClick = {
                                if (plugin.isActive) viewModel.disablePlugin(plugin.id)
                                else viewModel.enablePlugin(plugin.id)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(ArcoRadius.md)
                        ) {
                            Icon(
                                if (plugin.isActive) Icons.Default.Pause else Icons.Default.PlayArrow,
                                null, Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(ArcoSpacing.xs))
                            Text(if (plugin.isActive) strings.disable else strings.enable)
                        }
                        Button(
                            onClick = { viewModel.uninstallPlugin(plugin.id) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(ArcoRadius.md),
                            colors = ButtonDefaults.buttonColors(containerColor = ArcoColors.Red6)
                        ) {
                            Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(ArcoSpacing.xs))
                            Text(strings.uninstall)
                        }
                    }
                } else {
                    Button(
                        onClick = { viewModel.installPlugin(plugin.id) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(ArcoRadius.md),
                        colors = ButtonDefaults.buttonColors(containerColor = com.mengpaw.design.theme.ThemeColors.brand)
                    ) {
                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(ArcoSpacing.sm))
                        Text(strings.installPlugin)
                    }
                }
            }

            // ── Install progress ────────────────────────────────────
            when (val state = plugin.installState) {
                is InstallState.Downloading -> {
                    item {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = com.mengpaw.design.theme.ThemeColors.brand)
                    }
                }
                is InstallState.Failed -> {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = ArcoColors.Red1)) {
                            Text("${strings.errorPrefix}${state.error}", Modifier.padding(ArcoSpacing.md),
                                color = com.mengpaw.design.theme.ThemeColors.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                else -> {}
            }

            item { Spacer(Modifier.height(ArcoSpacing.xxxl)) }
        }
    }
}
