// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import java.io.File

// ── Building blocks — 拆自 HistorySidebar.kt (2026-08-06, >400 行文件拆分批次4) ──


/**
 * Collapsible agent entry showing agent name, session count, [+], and session list.
 */
@Composable
internal fun AgentGroupItem(
    agentName: String,
    framework: String?,
    sessions: List<SessionPersistenceService.SessionRecord>,
    onSelectSession: (SessionPersistenceService.SessionRecord) -> Unit,
    onDeleteSession: (String) -> Unit,
    onCompactSession: (String) -> Unit,
    onNewSession: () -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    var multiSelect by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }

    Column {
        // Multi-select toolbar
        if (multiSelect && selectedIds.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth()
                    .background(ArcoColors.Blue6.copy(alpha = 0.08f))
                    .padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("已选 ${selectedIds.size} 项", fontWeight = FontWeight.Medium,
                    fontSize = 13.sp, color = ThemeColors.accentText)
                Row(horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
                    TextButton(onClick = {
                        selectedIds.forEach { onDeleteSession(it) }
                        selectedIds.clear(); multiSelect = false
                    }) {
                        Text("删除", color = ArcoColors.Red6, fontSize = 13.sp)
                    }
                    TextButton(onClick = { selectedIds.clear(); multiSelect = false }) {
                        Text("取消", fontSize = 13.sp)
                    }
                }
            }
        }

        // Agent name bar
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                null,
                Modifier.size(18.dp),
                tint = ThemeColors.textSecondary
            )
            Spacer(Modifier.width(6.dp))
            // Agent avatar — loads from disk, falls back to initial
            val agentAvatarFile = File(com.mengpaw.kernel.DataPaths.AGENTS, "$agentName/avatar.png")
            val agentAvatarBitmap = remember(agentName) {
                if (agentAvatarFile.exists()) decodeSampled(agentAvatarFile.absolutePath, maxDim = 256) else null
            }
            if (agentAvatarBitmap != null) {
                Image(
                    bitmap = agentAvatarBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp).clip(CircleShape)
                )
            } else {
                Surface(
                    shape = CircleShape,
                    color = ThemeColors.brandContainer,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            agentName.take(1),
                            color = ThemeColors.brand,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
            Spacer(Modifier.width(ArcoSpacing.sm))
            Text(
                agentName,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = ThemeColors.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            // Session count — 纯文字数字, 无底色 (2026-08-18 UI 调整)
            if (sessions.isNotEmpty()) {
                Text(
                    "${sessions.size}",
                    fontSize = 11.sp,
                    color = ThemeColors.textSecondary
                )
                Spacer(Modifier.width(4.dp))
            }
            // New session button [+]
            IconButton(onClick = onNewSession, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Outlined.Add,
                    "新建会话",
                    tint = ThemeColors.brand,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Session list
        AnimatedVisibility(visible = expanded) {
            Column {
                if (sessions.isEmpty()) {
                    Text(
                        "暂无会话",
                        Modifier.padding(start = 56.dp, bottom = 4.dp),
                        fontSize = 12.sp,
                        color = ThemeColors.textSecondary
                    )
                } else {
                    // Confirmation dialog state
                    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
                    var pendingCompactId by remember { mutableStateOf<String?>(null) }

                    sessions.forEach { session ->
                        SessionItem(
                            session = session,
                            onSelect = { onSelectSession(session) },
                            onDelete = { pendingDeleteId = session.id },
                            onCompact = { pendingCompactId = session.id },
                            multiSelectMode = multiSelect,
                            isSelected = session.id in selectedIds,
                            onToggleSelect = {
                                if (session.id in selectedIds) selectedIds.remove(session.id)
                                else selectedIds.add(session.id)
                            },
                            onLongPress = {
                                multiSelect = true
                                selectedIds.add(session.id)
                            }
                        )
                    }

                    // Delete confirmation dialog
                    pendingDeleteId?.let { id ->
                        AlertDialog(
                            onDismissRequest = { pendingDeleteId = null },
                            title = { Text("确认删除") },
                            text = { Text("删除后将无法恢复此会话。确定要删除吗？") },
                            confirmButton = {
                                TextButton(onClick = { onDeleteSession(id); pendingDeleteId = null }) {
                                    Text("删除", color = ArcoColors.Red6)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { pendingDeleteId = null }) { Text("取消") }
                            }
                        )
                    }

                    // Compact confirmation dialog
                    pendingCompactId?.let { id ->
                        AlertDialog(
                            onDismissRequest = { pendingCompactId = null },
                            title = { Text("确认压缩") },
                            text = { Text("压缩后将保留摘要，原始消息不可恢复。确定要压缩吗？") },
                            confirmButton = {
                                TextButton(onClick = { onCompactSession(id); pendingCompactId = null }) {
                                    Text("压缩", color = ArcoColors.Orange6)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { pendingCompactId = null }) { Text("取消") }
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Expandable framework bar with agent groups underneath.
 */
@Composable
internal fun FrameworkGroupItem(
    frameworkName: String,
    agentGroups: List<SessionPersistenceService.AgentSessionGroup>,
    onSelectSession: (SessionPersistenceService.SessionRecord) -> Unit,
    onDeleteSession: (String) -> Unit,
    onCompactSession: (String) -> Unit,
    onNewSession: (agentName: String, framework: String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        // Framework name bar
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Online indicator
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        if (agentGroups.isNotEmpty()) ThemeColors.brand else ThemeColors.bgCardHigh,
                        CircleShape
                    )
            )
            Spacer(Modifier.width(ArcoSpacing.sm))
            Text(
                frameworkName,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = ThemeColors.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (agentGroups.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(ArcoRadius.sm),
                    color = ArcoColors.Green6.copy(alpha = 0.12f)
                ) {
                    Text(
                        "在线",
                        Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        fontSize = 10.sp,
                        color = ArcoColors.Green6
                    )
                }
            }
        }

        // Nested agents under framework
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 12.dp)) {
                if (agentGroups.isEmpty()) {
                    Text(
                        "暂无智能体或会话",
                        Modifier.padding(start = 24.dp, bottom = 4.dp),
                        fontSize = 12.sp,
                        color = ThemeColors.textSecondary
                    )
                } else {
                    agentGroups.forEach { group ->
                        AgentGroupItem(
                            agentName = group.agentName,
                            framework = frameworkName,
                            sessions = group.sessions,
                            onSelectSession = onSelectSession,
                            onDeleteSession = onDeleteSession,
                            onCompactSession = onCompactSession,
                                onNewSession = { onNewSession(group.agentName, frameworkName) }
                        )
                    }
                }
            }
        }
    }
}
