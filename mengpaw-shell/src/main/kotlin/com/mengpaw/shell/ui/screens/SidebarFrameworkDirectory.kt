// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.shell.ui.components.KanbanStatusBar
import com.mengpaw.shell.ui.components.TribeBarState
import com.mengpaw.shell.ui.components.aggregateTribeBarState
import com.mengpaw.shell.ui.localization.AppStrings
import kotlinx.coroutines.delay

// ── 框架通讯录区段 — 拆自 SidebarContent.kt (2026-08-06, >400 行文件拆分批次4) ──

/**
 * 框架通讯录: 每框架一行 (在线状态/类型图标/协议徽章/部落看板竖条),
 * 展开显示该框架下的 Agent 列表; MengPaw 框架图标连续点击 5 次触发记忆孪生配对。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun FrameworkDirectorySection(
    frameworks: List<FrameworkContact>,
    frameworkStatus: FrameworkStatus,
    strings: AppStrings,
    activeAgent: String,
    onSwitchAgent: (String, String?) -> Unit,
    onAddFramework: () -> Unit,
    onFrameworkLongClick: (String) -> Unit,
    onTwinActivate: (FrameworkContact) -> Unit
) {
    // ── Framework Directory ──
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(strings.sidebarFrameworkDirectory, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        IconButton(onClick = onAddFramework, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.PersonAdd, strings.sidebarFrameworkDirectory, tint = ThemeColors.brand, modifier = Modifier.size(20.dp))
        }
    }
    Spacer(Modifier.height(ArcoSpacing.sm))

    // ── 部落看板竖条状态：每 5s 轮询 Kanban 快照，按框架聚合 ──
    val tribeBarStates = remember { mutableStateMapOf<String, TribeBarState>() }
    LaunchedEffect(Unit) {
        while (true) {
            val tasks = com.mengpaw.plugin.hermes.TribeKanbanBoard().snapshotStatuses()
            frameworks.forEach { fw ->
                tribeBarStates[fw.name] = aggregateTribeBarState(fw.agents.toSet(), tasks)
            }
            delay(5000)
        }
    }

    if (frameworks.isEmpty()) {
        Text(strings.sidebarNoFriends, style = MaterialTheme.typography.bodySmall,
            color = ThemeColors.textSecondary, modifier = Modifier.padding(vertical = ArcoSpacing.sm))
    }

    frameworks.forEach { framework ->
        key(framework.name) {
            var expanded by remember { mutableStateOf(false) }

            Row(
                Modifier.fillMaxWidth()
                    .combinedClickable(
                        onClick = { expanded = !expanded },
                        onLongClick = { onFrameworkLongClick(framework.name) }
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val fwStatusColor = if (!framework.online) FrameworkStatus.OFFLINE.indicatorColor
                    else frameworkStatus.indicatorColor
                Box(Modifier.size(8.dp).background(fwStatusColor, CircleShape))
                Spacer(Modifier.width(ArcoSpacing.sm))
                // 框架类型图标 — MengPaw 连续点击5次激活记忆孪生
                val typeIcon = frameworkTypeIcon(framework.frameworkType)
                var twinTapCount by remember { mutableIntStateOf(0) }
                var twinTapLast by remember { mutableLongStateOf(0L) }
                val isMengPaw = framework.frameworkType == "mengpaw" || framework.frameworkName.contains("MengPaw", ignoreCase = true)
                Icon(
                    typeIcon, framework.frameworkType,
                    Modifier.size(if (isMengPaw) 20.dp else 14.dp)
                        .then(
                            if (isMengPaw) Modifier.pointerInput(Unit) {
                                detectTapGestures {
                                    val now = System.currentTimeMillis()
                                    if (now - twinTapLast > 3000) { twinTapCount = 0 }
                                    twinTapLast = now
                                    twinTapCount++
                                    if (twinTapCount >= 5) {
                                        twinTapCount = 0
                                        onTwinActivate(framework)
                                    }
                                }
                            } else Modifier
                        ),
                    tint = if (isMengPaw && twinTapCount > 0)
                        ThemeColors.brand.copy(alpha = 0.4f + twinTapCount * 0.12f)
                    else ThemeColors.textSecondary.copy(alpha = 0.7f)
                )
                // 点击计数提示
                if (isMengPaw && twinTapCount > 0) {
                    Spacer(Modifier.width(2.dp))
                    Text("${5 - twinTapCount}", fontSize = 8.sp,
                        color = ThemeColors.brand.copy(alpha = 0.6f))
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    val displayName = framework.remark.ifBlank { framework.name }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(displayName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        // v0.34.3: 未入册发现节点 → 行内"添加"按钮 (确认后入册)
                        if (framework.discovered) {
                            Spacer(Modifier.width(4.dp))
                            TextButton(
                                onClick = {
                                    val host = framework.address.substringBeforeLast(':').ifBlank { framework.address }
                                    val port = framework.address.substringAfterLast(':', "").toIntOrNull()
                                        ?: com.mengpaw.kernel.ports.Ports.ACP
                                    com.mengpaw.plugin.framework.FrameworkPeerStore.save(
                                        com.mengpaw.plugin.framework.FrameworkPeerStore.FrameworkPeer(
                                            fingerprint = framework.fingerprint.ifBlank {
                                                com.mengpaw.plugin.framework.FrameworkPeerStore
                                                    .computeFingerprint(framework.frameworkType, "$host:$port")
                                            },
                                            name = framework.name, version = framework.version,
                                            frameworkName = framework.frameworkName,
                                            address = host, port = port,
                                            agents = framework.agents,
                                            lastSeen = System.currentTimeMillis(),
                                            frameworkType = framework.frameworkType
                                        )
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) { Text(if (strings.isChinese) "添加" else "Add",
                                fontSize = 12.sp, color = ThemeColors.brand) }
                        }
                        if (framework.remark.isNotBlank()) {
                            Spacer(Modifier.width(4.dp))
                            Text(framework.name, style = MaterialTheme.typography.labelSmall,
                                color = ThemeColors.textSecondary, fontSize = 9.sp)
                        }
                        if (framework.trusted) {
                            Spacer(Modifier.width(4.dp))
                            Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Green6.copy(alpha = 0.12f)) {
                                Text(strings.securityTrusted, Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall, color = ArcoColors.Green6, fontSize = 9.sp)
                            }
                        }
                    }
                    // 协议标签 (ACP / MCP / REST)
                    val proto = com.mengpaw.plugin.framework.FrameworkPeerStore.PROTOCOL_LABELS[framework.frameworkType]
                    val protoLabel = proto?.first  // ACP | MCP | REST
                    val softLabel = buildString {
                        if (framework.frameworkName.isNotBlank() && framework.version.isNotBlank()) {
                            append("${framework.frameworkName} v${framework.version}")
                        } else {
                            append(framework.address)
                        }
                    }
                    Text(softLabel, style = MaterialTheme.typography.labelSmall,
                        color = ThemeColors.textSecondary, fontSize = 10.sp)
                }
                // 协议徽章 — 纯文本，无颜色区分
                val proto = com.mengpaw.plugin.framework.FrameworkPeerStore.PROTOCOL_LABELS[framework.frameworkType]
                val protoLabel = proto?.first ?: "?"
                Spacer(Modifier.width(4.dp))
                Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ThemeColors.bgCardHigh) {
                    Text(protoLabel, Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, fontSize = 9.sp)
                }
                if (framework.online) {
                    Spacer(Modifier.width(4.dp))
                    Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = frameworkStatus.indicatorColor.copy(alpha = 0.1f)) {
                        Text(frameworkStatus.label(strings), Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall, color = frameworkStatus.indicatorColor, fontSize = 9.sp)
                    }
                } else {
                    Spacer(Modifier.width(4.dp))
                    Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = FrameworkStatus.OFFLINE.indicatorColor.copy(alpha = 0.1f)) {
                        Text(FrameworkStatus.OFFLINE.label(strings), Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall, color = FrameworkStatus.OFFLINE.indicatorColor, fontSize = 9.sp)
                    }
                }
                // 部落看板竖条（绿=完成/黄=排队/黄闪烁=执行/红=错误/灰=离线）
                Spacer(Modifier.width(4.dp))
                KanbanStatusBar(if (!framework.online) TribeBarState.GRAY
                    else (tribeBarStates[framework.name] ?: TribeBarState.GREEN))
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 28.dp, bottom = 4.dp)) {
                    framework.agents.forEach { agentName ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { onSwitchAgent(agentName, framework.name) }
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
                                    Text(strings.sidebarCurrent, Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                        style = MaterialTheme.typography.labelSmall, color = ThemeColors.brand, fontSize = 9.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
