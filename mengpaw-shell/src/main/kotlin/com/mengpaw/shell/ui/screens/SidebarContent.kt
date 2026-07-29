// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.error.ErrorCollector
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.components.MarkdownText
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.kernel.agent.AgentProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

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
    val agents: List<String>,
    val version: String = "",
    val frameworkName: String = "",
    val remark: String = "",
    val frameworkType: String = "mengpaw"
)

/** Data class for new agent creation form. */
data class NewAgentForm(
    val name: String = "",
    val workspaceFolder: String = "",
    val intro: String = ""
)

/**
 * Left sidebar — Agent switcher + Framework directory.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun SidebarContent(
    onNavigateToPlugins: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onClose: () -> Unit,
    activeAgent: String = "MengPaw",
    onSwitchAgent: (String) -> Unit = {},
    onCreateAgent: (String) -> Unit = {},
    onCreateAgentWithDetails: (name: String, workspaceFolder: String, intro: String) -> Unit = { name, _, _ -> onCreateAgent(name) },
    onActivateMemoryTwin: () -> Unit = {}
) {
    var frameworkStatus by remember { mutableStateOf(FrameworkStatus.ONLINE) }
    var manualStatus by remember { mutableStateOf(false) }

    var cardAgentName by remember { mutableStateOf<String?>(null) }
    var cardFrameworkName by remember { mutableStateOf<String?>(null) }

    var showNewAgentDialog by remember { mutableStateOf(false) }
    var showAddFramework by remember { mutableStateOf(false) }
    var showTwinConfirmDialog by remember { mutableStateOf(false) }
    var twinPairTarget by remember { mutableStateOf<Any?>(null) }

    val agentsDir = File(com.mengpaw.kernel.DataPaths.AGENTS)
    val systemDirs = setOf("inbox", "team", "acp", "incubator", "agent-001")
    val discoveredAgents = try { agentsDir.listFiles()
        ?.filter { it.isDirectory && it.name !in systemDirs && !it.name.startsWith(".") }
        ?.map { it.name }?.sorted()
        ?.ifEmpty { listOf("MengPaw") } ?: listOf("MengPaw") } catch (_: Exception) { listOf("MengPaw") }

    Column(Modifier.fillMaxHeight().width(280.dp).background(ThemeColors.bgPrimary).padding(ArcoSpacing.lg).verticalScroll(rememberScrollState())) {
        // ── Agents ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("智能体", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { showNewAgentDialog = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Add, "新建智能体", tint = ThemeColors.brand, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(ArcoSpacing.sm))

        discoveredAgents.forEach { dirName ->
            val profile = remember(dirName) { AgentProfile.load(dirName) }
            val displayName = profile.name.ifBlank { dirName }

            Row(
                Modifier.fillMaxWidth().combinedClickable(
                    onClick = { if (dirName != activeAgent) { onSwitchAgent(dirName); onClose() } },
                    onLongClick = { cardAgentName = dirName }
                ).padding(vertical = ArcoSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val agentAvatarFile = File(com.mengpaw.kernel.DataPaths.AGENTS, "$dirName/avatar.png")
                val agentAvatarBitmap = remember(dirName) {
                    if (agentAvatarFile.exists()) android.graphics.BitmapFactory.decodeFile(agentAvatarFile.absolutePath) else null
                }
                if (agentAvatarBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = agentAvatarBitmap.asImageBitmap(), contentDescription = null,
                        modifier = Modifier.size(36.dp).clip(CircleShape))
                } else {
                    Surface(shape = CircleShape, modifier = Modifier.size(36.dp),
                        color = if (dirName == activeAgent) ThemeColors.brand else ThemeColors.bgCardHigh) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(displayName.take(1), color = if (dirName == activeAgent) Color.White else ThemeColors.textSecondary,
                                fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.width(ArcoSpacing.sm))

                Column(Modifier.weight(1f)) {
                    Text(displayName, fontWeight = if (dirName == activeAgent) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Agent文档/$dirName", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary,
                        maxLines = 1, fontSize = 10.sp)
                }

                if (dirName == activeAgent) {
                    Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ThemeColors.brand.copy(alpha = 0.15f)) {
                        Text("当前", Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall, color = ThemeColors.brand)
                    }
                }
            }
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
                    Box(Modifier.size(8.dp).background(status.indicatorColor, CircleShape))
                    Spacer(Modifier.width(ArcoSpacing.sm))
                    Column(Modifier.weight(1f)) {
                        Text(status.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(status.desc, fontSize = 9.sp, color = ThemeColors.textSecondary, maxLines = 1)
                    }
                    if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(14.dp), tint = status.indicatorColor)
                }
            }
        }

        Spacer(Modifier.height(ArcoSpacing.md))

        // ── Toggle Button ──
        Surface(
            onClick = {
                val new = if (manualStatus) {
                    manualStatus = false
                    FrameworkStatus.ONLINE
                } else {
                    val next = FrameworkStatus.entries[(frameworkStatus.ordinal + 1) % FrameworkStatus.entries.size]
                    manualStatus = true
                    next
                }
                frameworkStatus = new
            },
            shape = RoundedCornerShape(ArcoRadius.md),
            color = ThemeColors.bgCardHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(ArcoSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(18.dp), tint = ThemeColors.brand)
                Spacer(Modifier.width(ArcoSpacing.sm))
                Text("循环切换状态", fontSize = 13.sp, color = ThemeColors.brand)
            }
        }
        Spacer(Modifier.height(ArcoSpacing.md))

        // ── Framework Directory ──
        Text("框架目录", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(ArcoSpacing.sm))

        var showAddFwButton by remember { mutableStateOf(true) }
        val fpDir = File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED)
        val frameworkFiles = remember(fpDir) {
            fpDir.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() } ?: emptyList()
        }
        // Also load from FrameworkPeerStore for active peers
        val activePeers = remember {
            com.mengpaw.plugin.framework.FrameworkPeerStore.loadAll()
        }

        if (activePeers.isEmpty() && frameworkFiles.isEmpty()) {
            Text("尚未添加框架", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        } else {
            val allFrameworks = (activePeers.map { it.name } + frameworkFiles.map { it.nameWithoutExtension }).distinct()
            allFrameworks.forEach { fwName ->
                val peer = activePeers.find { it.name == fwName }
                val online = peer?.lastSeen?.let { it > System.currentTimeMillis() - 120_000 } ?: false
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        .combinedClickable(
                            onClick = { cardFrameworkName = fwName },
                            onLongClick = {
                                twinPairTarget = peer
                                showTwinConfirmDialog = true
                            }
                        ),
                    shape = RoundedCornerShape(ArcoRadius.md),
                    color = if (online) ThemeColors.brandContainer.copy(alpha = 0.3f) else ThemeColors.bgCard
                ) {
                    Row(Modifier.padding(horizontal = ArcoSpacing.sm, vertical = ArcoSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (online) Icons.Outlined.Hub else Icons.Outlined.Dns, null,
                            Modifier.size(20.dp), tint = if (online) ArcoColors.Green6 else ArcoColors.Gray5)
                        Spacer(Modifier.width(ArcoSpacing.sm))
                        Column(Modifier.weight(1f)) {
                            Text(fwName, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            if (peer != null) {
                                val agentCount = peer.agents.size
                                Text("${peer.address} · ${agentCount}智能体", fontSize = 10.sp,
                                    color = ThemeColors.textSecondary, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(ArcoSpacing.sm))

        // Add framework button
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
            OutlinedButton(onClick = { showAddFramework = true }, modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(ArcoRadius.md)) {
                Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加框架", style = MaterialTheme.typography.labelSmall)
            }
        }

        // ── Pair Twin Confirmation Dialog ──
        if (showTwinConfirmDialog && twinPairTarget != null) {
            AlertDialog(
                onDismissRequest = { showTwinConfirmDialog = false; twinPairTarget = null },
                title = { Text("配对记忆孪生", fontWeight = FontWeight.Bold) },
                text = {
                    val targetName = twinPairTarget?.let { (it as? com.mengpaw.plugin.framework.FrameworkPeerStore.FrameworkPeer)?.name ?: it.toString() } ?: ""
                    Text("是否与「$targetName」建立记忆孪生关系？\n\n" +
                        "建立后：\n" +
                        "• 双方共享长期记忆\n" +
                        "• Agent 可在两设备间无缝切换上下文\n" +
                        "• 支持跨设备任务委派")
                },
                confirmButton = {
                    Button(onClick = {
                        onActivateMemoryTwin()
                        showTwinConfirmDialog = false
                        twinPairTarget = null
                    }, colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.brand),
                        shape = RoundedCornerShape(ArcoRadius.md)) {
                        Text("确认配对", color = Color.White)
                    }
                },
                dismissButton = { TextButton(onClick = { showTwinConfirmDialog = false; twinPairTarget = null }) { Text("取消") } }
            )
        }

        Spacer(Modifier.height(ArcoSpacing.md))
        HorizontalDivider(color = ThemeColors.border)

        // ── Navigation ──
        Spacer(Modifier.height(ArcoSpacing.md))
        Surface(onClick = { onNavigateToPlugins(); onClose() }, shape = RoundedCornerShape(ArcoRadius.md),
            color = ThemeColors.bgCardHigh, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(ArcoSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Extension, null, Modifier.size(18.dp), tint = ThemeColors.brand)
                Spacer(Modifier.width(ArcoSpacing.sm))
                Text("插件管理", fontSize = 13.sp, color = ThemeColors.brand)
            }
        }
        Spacer(Modifier.height(4.dp))
        Surface(onClick = { onNavigateToSettings(); onClose() }, shape = RoundedCornerShape(ArcoRadius.md),
            color = ThemeColors.bgCardHigh, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(ArcoSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Settings, null, Modifier.size(18.dp), tint = ThemeColors.brand)
                Spacer(Modifier.width(ArcoSpacing.sm))
                Text("设置", fontSize = 13.sp, color = ThemeColors.brand)
            }
        }
    }

    // ── Dialogs ──

    if (cardAgentName != null) {
        AgentCardDialog(
            agentName = cardAgentName!!,
            onDismiss = { cardAgentName = null },
            onSwitchTo = { onSwitchAgent(cardAgentName!!); cardAgentName = null; onClose() }
        )
    }

    if (showNewAgentDialog) {
        NewAgentDialog(
            initialName = "",
            onDismiss = { showNewAgentDialog = false },
            onConfirm = { form -> onCreateAgentWithDetails(form.name, form.workspaceFolder, form.intro); showNewAgentDialog = false }
        )
    }

    if (showAddFramework) {
        AddFrameworkDialog(onDismiss = { showAddFramework = false })
    }

    if (cardFrameworkName != null) {
        FrameworkCardDialog(
            frameworkName = cardFrameworkName!!,
            onDismiss = { cardFrameworkName = null }
        )
    }
}

/** Returns the Material icon for a given framework type string. */
@Composable
fun frameworkTypeIcon(type: String): androidx.compose.ui.graphics.vector.ImageVector = when {
    type == "mengpaw" -> Icons.Outlined.Hub
    type == "claude-code" -> Icons.Outlined.SmartToy
    type == "openclaw" || type == "qclaw" -> Icons.Outlined.ShutterSpeed
    type == "hermes" -> Icons.Outlined.Rocket
    type == "openode" -> Icons.Outlined.Code
    type == "collab-cli" -> Icons.Outlined.Groups
    type.startsWith("trea") -> Icons.Outlined.WorkspacePremium
    type == "cursor" -> Icons.Outlined.TouchApp
    type == "reasonix" -> Icons.Outlined.Psychology
    type == "qwenpaw" -> Icons.Outlined.AutoAwesome
    type == "coze" -> Icons.Outlined.Bolt
    type == "kimi-desktop" -> Icons.Outlined.DesktopWindows
    type == "custom" -> Icons.Outlined.Settings
    else -> Icons.Outlined.Hub
}
