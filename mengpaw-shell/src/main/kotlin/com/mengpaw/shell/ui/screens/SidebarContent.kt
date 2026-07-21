// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing

/** Agent online / presence status for external frameworks. */
enum class FrameworkStatus(val label: String, val desc: String, val indicatorColor: Color) {
    ONLINE("在线", "Chat 开放 · 接受委派任务", ArcoColors.Green6),
    BUSY("忙碌", "Chat 开放 · 委派任务排队等待", ArcoColors.Orange6),
    OFFLINE("离线", "Chat 关闭 · 不响应任何外部请求", ArcoColors.Gray6)
}

/** A framework peer (ACP node) that may host multiple agents. */
data class FrameworkContact(
    val name: String,
    val address: String,
    val online: Boolean,
    val trusted: Boolean,
    val agents: List<String>
)

/**
 * Left sidebar — Agent switcher + Framework directory.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SidebarContent(
    onNavigateToPlugins: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onClose: () -> Unit,
    activeAgent: String = "MengPaw",
    onSwitchAgent: (String) -> Unit = {},
    onCreateAgent: (String) -> Unit = {}
) {
    var agentMenuTarget by remember { mutableStateOf<String?>(null) }
    var frameworkStatus by remember { mutableStateOf(FrameworkStatus.ONLINE) }
    var manualStatus by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxHeight().width(280.dp).padding(ArcoSpacing.lg)) {
        // ── Agents ──
        Text("智能体", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(ArcoSpacing.sm))

        val agents = remember { mutableStateListOf("MengPaw") }
        agents.forEach { name ->
            Box {
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(
                            onClick = { onSwitchAgent(name) },
                            onLongClick = { agentMenuTarget = name }
                        )
                        .padding(vertical = ArcoSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Avatar — loads from agent dir, falls back to initial
                    val agentAvatarFile = java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, "$name/avatar.png")
                    val agentAvatarBitmap = remember(name) {
                        if (agentAvatarFile.exists()) android.graphics.BitmapFactory.decodeFile(agentAvatarFile.absolutePath) else null
                    }
                    if (agentAvatarBitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = agentAvatarBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(36.dp).clip(CircleShape)
                        )
                    } else {
                        Surface(shape = CircleShape, modifier = Modifier.size(36.dp),
                            color = if (name == activeAgent) ThemeColors.brand else ThemeColors.bgCardHigh) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(name.take(1), color = if (name == activeAgent) Color.White else ThemeColors.textSecondary,
                                    fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(Modifier.width(ArcoSpacing.sm))
                    Text(name, fontWeight = if (name == activeAgent) FontWeight.SemiBold else FontWeight.Normal, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    if (name == activeAgent) {
                        Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ThemeColors.brand.copy(alpha = 0.15f)) {
                            Text("当前", Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = ThemeColors.brand)
                        }
                    }
                }

                // Long-press context menu
                DropdownMenu(
                    expanded = agentMenuTarget == name,
                    onDismissRequest = { agentMenuTarget = null },
                    offset = DpOffset(16.dp, 0.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text("申请智能体调度权限") },
                        onClick = { agentMenuTarget = null; /* FIX B8: Request ACP agent scheduling permission */ },
                        leadingIcon = { Icon(Icons.Outlined.AdminPanelSettings, null, Modifier.size(18.dp)) }
                    )
                }
            }
        }
        OutlinedButton(
            onClick = {
                val newName = "智能体 ${agents.size + 1}"
                agents.add(newName)
                onCreateAgent(newName)
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(ArcoRadius.md)
        ) {
            Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("新建智能体", style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(ArcoSpacing.md))

        // ── Framework Status ──
        Text("框架状态", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(ArcoSpacing.sm))
        FrameworkStatus.entries.forEach { status ->
            val selected = frameworkStatus == status
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                    .clickable { frameworkStatus = status; manualStatus = true },
                shape = RoundedCornerShape(ArcoRadius.md),
                color = if (selected) status.indicatorColor.copy(alpha = 0.1f) else Color.Transparent
            ) {
                Row(Modifier.padding(horizontal = ArcoSpacing.sm, vertical = ArcoSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically) {
                    // Status dot
                    Box(Modifier.size(10.dp).background(status.indicatorColor, CircleShape))
                    Spacer(Modifier.width(ArcoSpacing.sm))
                    Column(Modifier.weight(1f)) {
                        Text(status.label, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                            color = if (selected) status.indicatorColor else ThemeColors.textPrimary)
                        Text(status.desc, fontSize = 11.sp, color = ThemeColors.textSecondary)
                    }
                    if (selected) {
                        Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = status.indicatorColor)
                    }
                }
            }
        }
        if (manualStatus) {
            TextButton(onClick = { manualStatus = false; frameworkStatus = FrameworkStatus.ONLINE },
                modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                Text("恢复自动切换", style = MaterialTheme.typography.labelSmall, color = ThemeColors.brand)
            }
        }

        HorizontalDivider(Modifier.padding(vertical = ArcoSpacing.lg))

        // ── Framework Directory ──
        Text("框架通讯录", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(ArcoSpacing.sm))

        val frameworks = remember { mutableStateListOf<FrameworkContact>() }

        if (frameworks.isEmpty()) {
            Text("你的智能体还没有朋友", style = MaterialTheme.typography.bodySmall,
                color = ThemeColors.textSecondary, modifier = Modifier.padding(vertical = ArcoSpacing.sm))
        }

        frameworks.forEach { framework ->
            var expanded by remember { mutableStateOf(false) }

            // Framework header row
            Row(
                Modifier.fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Framework status dot (uses local status as proxy)
                val fwStatusColor = if (!framework.online) FrameworkStatus.OFFLINE.indicatorColor
                    else frameworkStatus.indicatorColor
                Box(Modifier.size(8.dp).background(fwStatusColor, CircleShape))

                Spacer(Modifier.width(ArcoSpacing.sm))

                // Expand arrow
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null, Modifier.size(16.dp), tint = ThemeColors.textSecondary
                )

                Spacer(Modifier.width(4.dp))

                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(framework.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        if (framework.trusted) {
                            Spacer(Modifier.width(4.dp))
                            Surface(
                                shape = RoundedCornerShape(ArcoRadius.sm),
                                color = ArcoColors.Green6.copy(alpha = 0.12f)
                            ) {
                                Text("已信任", Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall, color = ArcoColors.Green6, fontSize = 9.sp)
                            }
                        }
                    }
                    Text(framework.address, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
                }

                // Status badge
                if (framework.online) {
                    Surface(shape = RoundedCornerShape(ArcoRadius.sm),
                        color = frameworkStatus.indicatorColor.copy(alpha = 0.1f)) {
                        Text(frameworkStatus.label, Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall, color = frameworkStatus.indicatorColor, fontSize = 10.sp)
                    }
                } else {
                    Surface(shape = RoundedCornerShape(ArcoRadius.sm),
                        color = FrameworkStatus.OFFLINE.indicatorColor.copy(alpha = 0.1f)) {
                        Text(FrameworkStatus.OFFLINE.label, Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall, color = FrameworkStatus.OFFLINE.indicatorColor, fontSize = 10.sp)
                    }
                }
            }

            // Expandable agent list under this framework
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 28.dp, bottom = 4.dp)) {
                    framework.agents.forEach { agentName ->
                        val fullName = "${framework.name}/${agentName}"
                        Box {
                            Row(
                                Modifier.fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { onSwitchAgent(agentName) },
                                        onLongClick = { agentMenuTarget = fullName }
                                    )
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(shape = CircleShape, modifier = Modifier.size(22.dp),
                                    color = if (agentName == activeAgent) ThemeColors.brand else ThemeColors.bgCardHigh) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(agentName.take(1), color = if (agentName == activeAgent) Color.White else ThemeColors.textSecondary,
                                            fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(agentName, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textPrimary)
                                Spacer(Modifier.weight(1f))
                                if (agentName == activeAgent) {
                                    Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ThemeColors.brand.copy(alpha = 0.15f)) {
                                        Text("当前", Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = ThemeColors.brand, fontSize = 9.sp)
                                    }
                                }
                            }

                            DropdownMenu(
                                expanded = agentMenuTarget == fullName,
                                onDismissRequest = { agentMenuTarget = null },
                                offset = DpOffset(16.dp, 0.dp)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("申请智能体调度权限") },
                                    onClick = { agentMenuTarget = null; /* FIX B8: Request ACP agent scheduling permission */ },
                                    leadingIcon = { Icon(Icons.Outlined.AdminPanelSettings, null, Modifier.size(18.dp)) }
                                )
                            }
                        }
                    }
                }
            }
        }

        OutlinedButton(
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(ArcoRadius.md)
        ) {
            Icon(Icons.Outlined.PersonAdd, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("添加框架", style = MaterialTheme.typography.labelSmall)
        }

        HorizontalDivider(Modifier.padding(vertical = ArcoSpacing.lg))

        // ── Quick Nav ──
        Text("功能", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(ArcoSpacing.sm))
        SidebarNavItem(Icons.Outlined.Extension, "插件管理", onClick = onNavigateToPlugins)
        SidebarNavItem(Icons.Outlined.Settings, "设置", onClick = onNavigateToSettings)
        SidebarNavItem(Icons.Outlined.Terminal, "CLI 参考", onClick = { /* FIX B7: Open CLI.md reference in doc viewer */ })

        Spacer(Modifier.weight(1f))
        Text("MengPaw v0.2.2 · ACP 已启用", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, modifier = Modifier.padding(bottom = ArcoSpacing.sm))
    }
}

@Composable
private fun SidebarNavItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = ThemeColors.textSecondary)
        Spacer(Modifier.width(ArcoSpacing.sm))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
