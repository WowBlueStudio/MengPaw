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
import com.mengpaw.shell.ui.localization.AppStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val appJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

@Serializable
data class FrameworkContactFile(
    val name: String = "",
    val address: String = "",
    val remark: String = "",
    val frameworkType: String = "mengpaw"
)

@Serializable
data class TwinPairFile(
    val deviceName: String = "",
    val deviceModel: String = "",
    val peerId: String = ""
)

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
 *
 * Changes from v0.6.x:
 * - Long-press "申请智能体调度权限" removed.
 * - Tapping an agent opens an Agent Card dialog (avatar, name, workspace, intro).
 * - "+ New Agent" opens a creation dialog with name / folder / intro fields.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun SidebarContent(
    strings: AppStrings,
    onNavigateToPlugins: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onClose: () -> Unit,
    activeAgent: String = "MengPaw",
    onSwitchAgent: (String) -> Unit = {},
    onCreateAgent: (String) -> Unit = {},
    // Extended create — passes name, workspace folder name, and intro
    onCreateAgentWithDetails: (name: String, workspaceFolder: String, intro: String) -> Unit = { name, _, _ -> onCreateAgent(name) },
    onActivateMemoryTwin: () -> Unit = {},
    isRunning: Boolean = false
) {
    var frameworkStatus by remember { mutableStateOf(FrameworkStatus.ONLINE) }
    var wasAutoBusy by remember { mutableStateOf(false) }

    // Auto-switch: running → BUSY, done → back to ONLINE
    LaunchedEffect(isRunning) {
        if (isRunning && frameworkStatus == FrameworkStatus.ONLINE) {
            frameworkStatus = FrameworkStatus.BUSY
            wasAutoBusy = true
        } else if (!isRunning && wasAutoBusy) {
            frameworkStatus = FrameworkStatus.ONLINE
            wasAutoBusy = false
        }
    }

    // ── Card dialog states ──
    var cardAgentName by remember { mutableStateOf<String?>(null) }
    var cardFrameworkName by remember { mutableStateOf<String?>(null) }

    // ── New Agent dialog state ──
    var showNewAgentDialog by remember { mutableStateOf(false) }
    var showAddFramework by remember { mutableStateOf(false) }
    var showTwinConfirmDialog by remember { mutableStateOf(false) }
    var twinPairTarget by remember { mutableStateOf<FrameworkContact?>(null) }
    val scope = rememberCoroutineScope()

    // Discover agents from disk — no remember() so list stays fresh when agents are created/deleted
    val agentsDir = File(com.mengpaw.kernel.DataPaths.AGENTS)
    // Exclude system dirs (inbox, team, acp, incubator) from agent list
    val systemDirs = setOf("inbox", "team", "acp", "incubator", "agent-001")
    val discoveredAgents = try { agentsDir.listFiles()
        ?.filter { it.isDirectory && it.name !in systemDirs && !it.name.startsWith(".") }
        ?.map { it.name }?.sorted()
        ?.ifEmpty { listOf("MengPaw") } ?: listOf("MengPaw") } catch (_: Exception) { listOf("MengPaw") }

    Column(Modifier.fillMaxHeight().width(280.dp).background(ThemeColors.bgPrimary).padding(ArcoSpacing.lg).verticalScroll(rememberScrollState())) {
        // ── Agents ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.sidebarAgents, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { showNewAgentDialog = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Add, strings.sidebarAgents, tint = ThemeColors.brand, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(ArcoSpacing.sm))

        discoveredAgents.forEach { dirName ->
            // Load display name from profile, fall back to directory name
            val profile = remember(dirName) { AgentProfile.load(dirName) }
            val displayName = profile.name.ifBlank { dirName }

            Row(
                Modifier.fillMaxWidth()
                    .combinedClickable(
                        onClick = { if (dirName != activeAgent) { onSwitchAgent(dirName); onClose() } },
                        onLongClick = { cardAgentName = dirName }
                    )
                    .padding(vertical = ArcoSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar — loads from agent dir, falls back to initial
                val agentAvatarFile = File(com.mengpaw.kernel.DataPaths.AGENTS, "$dirName/avatar.png")
                val agentAvatarBitmap = remember(dirName) {
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
                        color = if (dirName == activeAgent) ThemeColors.brand else ThemeColors.bgCardHigh) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(displayName.take(1), color = if (dirName == activeAgent) Color.White else ThemeColors.textSecondary,
                                fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.width(ArcoSpacing.sm))

                // Display name + workspace folder path
                Column(Modifier.weight(1f)) {
                    Text(displayName, fontWeight = if (dirName == activeAgent) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Agent文档/$dirName",
                        style = MaterialTheme.typography.labelSmall,
                        color = ThemeColors.textSecondary,
                        maxLines = 1,
                        fontSize = 10.sp
                    )
                }

                if (dirName == activeAgent) {
                    Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ThemeColors.brand.copy(alpha = 0.15f)) {
                        Text(strings.sidebarCurrent, Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall, color = ThemeColors.brand)
                    }
                }
            }
        }

        Spacer(Modifier.height(ArcoSpacing.md))

        // ── Framework Status ──
        Text(strings.sidebarFrameworkStatus, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(ArcoSpacing.sm))
        FrameworkStatus.entries.forEach { status ->
            val selected = frameworkStatus == status
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                    .clickable { frameworkStatus = status; wasAutoBusy = false },
                shape = RoundedCornerShape(ArcoRadius.md),
                color = if (selected) status.indicatorColor.copy(alpha = 0.1f) else Color.Transparent
            ) {
                Row(Modifier.padding(horizontal = ArcoSpacing.sm, vertical = ArcoSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically) {
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
        HorizontalDivider(Modifier.padding(vertical = ArcoSpacing.lg))

        // ── Framework Directory ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.sidebarFrameworkDirectory, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { showAddFramework = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.PersonAdd, strings.sidebarFrameworkDirectory, tint = ThemeColors.brand, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(ArcoSpacing.sm))

        // Load saved framework contacts from ACP_TRUSTED + discovered peers
        val frameworks = remember {
            val contacts = mutableListOf<FrameworkContact>()
            val trustedDir = java.io.File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED)
            if (trustedDir.exists()) {
                trustedDir.listFiles()
                    ?.filter { it.extension == "json" && !it.name.endsWith(".tmp.json") }
                    ?.forEach { file ->
                        try {
                            val contactFile = appJson.decodeFromString<FrameworkContactFile>(file.readText())
                            contacts.add(FrameworkContact(
                                name = contactFile.name.ifBlank { file.nameWithoutExtension },
                                address = contactFile.address,
                                online = false,
                                trusted = true,
                                agents = emptyList(),
                                remark = contactFile.remark,
                                frameworkType = contactFile.frameworkType
                            ))
                        } catch (e: Exception) { com.mengpaw.kernel.KernelLog.w("SidebarContent", "load framework: ${e.message}") }
                    }
            }
            // 合并 mDNS 发现的框架
            val discovered = com.mengpaw.plugin.framework.FrameworkPeerStore.loadAll()
            discovered.forEach { peer ->
                val existing = contacts.indexOfFirst { it.name == peer.name }
                if (existing >= 0) {
                    // 更新在线状态和 Agent 列表, 保留已有的 remark 和 frameworkType
                    val old = contacts[existing]
                    contacts[existing] = old.copy(
                        online = peer.lastSeen > System.currentTimeMillis() - 120_000,
                        address = "${peer.address}:${peer.port}",
                        agents = peer.agents,
                        version = peer.version,
                        frameworkName = peer.frameworkName,
                        remark = peer.remark.ifBlank { old.remark },
                        frameworkType = peer.frameworkType.let { if (it != "mengpaw") it else old.frameworkType }
                    )
                } else {
                    contacts.add(FrameworkContact(
                        name = peer.name,
                        address = "${peer.address}:${peer.port}",
                        online = peer.lastSeen > System.currentTimeMillis() - 120_000,
                        trusted = peer.trusted,
                        agents = peer.agents,
                        version = peer.version,
                        frameworkName = peer.frameworkName,
                        remark = peer.remark,
                        frameworkType = peer.frameworkType
                    ))
                }
            }
            mutableStateListOf<FrameworkContact>().also { it.addAll(contacts) }
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
                            onLongClick = { cardFrameworkName = framework.name }
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val fwStatusColor = if (!framework.online) FrameworkStatus.OFFLINE.indicatorColor
                        else frameworkStatus.indicatorColor
                    Box(Modifier.size(8.dp).background(fwStatusColor, CircleShape))
                    Spacer(Modifier.width(ArcoSpacing.sm))
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        null, Modifier.size(16.dp), tint = ThemeColors.textSecondary
                    )
                    Spacer(Modifier.width(2.dp))
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
                                            twinPairTarget = framework
                                            showTwinConfirmDialog = true
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
                            Text(frameworkStatus.label, Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall, color = frameworkStatus.indicatorColor, fontSize = 9.sp)
                        }
                    } else {
                        Spacer(Modifier.width(4.dp))
                        Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = FrameworkStatus.OFFLINE.indicatorColor.copy(alpha = 0.1f)) {
                            Text(FrameworkStatus.OFFLINE.label, Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall, color = FrameworkStatus.OFFLINE.indicatorColor, fontSize = 9.sp)
                        }
                    }
                }

                AnimatedVisibility(visible = expanded) {
                    Column(Modifier.padding(start = 28.dp, bottom = 4.dp)) {
                        framework.agents.forEach { agentName ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { onSwitchAgent(agentName) }
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

        if (showAddFramework) {
            AddFrameworkDialog(onDismiss = { showAddFramework = false })
        }

        HorizontalDivider(Modifier.padding(vertical = ArcoSpacing.lg))

        // ── Quick Nav ──
        Text(strings.sidebarFeatures, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(ArcoSpacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val pluginsInteraction = remember { MutableInteractionSource() }
            val pluginsPressed = pluginsInteraction.collectIsPressedAsState()
            val pluginsScale by animateFloatAsState(if (pluginsPressed.value) 0.94f else 1f, tween(120))

            val settingsInteraction = remember { MutableInteractionSource() }
            val settingsPressed = settingsInteraction.collectIsPressedAsState()
            val settingsScale by animateFloatAsState(if (settingsPressed.value) 0.94f else 1f, tween(120))

            Surface(
                onClick = onNavigateToPlugins,
                shape = RoundedCornerShape(ArcoRadius.md),
                color = if (pluginsPressed.value) ThemeColors.brand.copy(alpha = 0.12f) else ThemeColors.bgCardHigh,
                modifier = Modifier.weight(1f).scale(pluginsScale),
                interactionSource = pluginsInteraction
            ) {
                Row(Modifier.padding(horizontal = ArcoSpacing.md, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Extension, null, Modifier.size(18.dp), tint = ThemeColors.brand)
                    Spacer(Modifier.width(6.dp))
                    Text(strings.sidebarPlugins, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textPrimary)
                }
            }
            Spacer(Modifier.width(ArcoSpacing.sm))
            Surface(
                onClick = onNavigateToSettings,
                shape = RoundedCornerShape(ArcoRadius.md),
                color = if (settingsPressed.value) ThemeColors.brand.copy(alpha = 0.12f) else ThemeColors.bgCardHigh,
                modifier = Modifier.weight(1f).scale(settingsScale),
                interactionSource = settingsInteraction
            ) {
                Row(Modifier.padding(horizontal = ArcoSpacing.md, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Settings, null, Modifier.size(18.dp), tint = ThemeColors.brand)
                    Spacer(Modifier.width(6.dp))
                    Text(strings.sidebarSettings, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textPrimary)
                }
            }
        }

        // Bottom safe area for nav bar
        Spacer(Modifier.height(ArcoSpacing.lg))
    }

    // ═══════════════════════════════════════════════════════════════════
    // Agent Card Dialog — replaces old long-press "申请智能体调度权限"
    // ═══════════════════════════════════════════════════════════════════
    cardAgentName?.let { name ->
        AgentCardDialog(
            agentName = name,
            onDismiss = { cardAgentName = null },
            onSwitchTo = {
                onSwitchAgent(name)
                cardAgentName = null
            }
        )
    }

    cardFrameworkName?.let { name ->
        FrameworkCardDialog(
            frameworkName = name,
            onDismiss = { cardFrameworkName = null }
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // 记忆孪生配对请求 (接收方) — 检查 inbox 中的 twin_pair_*.json
    // ═══════════════════════════════════════════════════════════════════
    // 轮询 inbox 中的孪生配对请求 (文件写入不会自动触发 Compose 重组)
    var twinPairFiles by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            val inbox = java.io.File(com.mengpaw.kernel.DataPaths.AGENT_INBOX)
            val files = if (inbox.exists())
                inbox.listFiles()?.filter { it.name.startsWith("twin_pair_") && it.name.endsWith(".json") }?.toList() ?: emptyList()
            else emptyList()
            twinPairFiles = files
            kotlinx.coroutines.delay(2000) // 每2秒检查一次
        }
    }
    twinPairFiles.firstOrNull()?.let { pairFile ->
        val twinData = try { appJson.decodeFromString<TwinPairFile>(pairFile.readText()) } catch (_: Exception) { null }
        if (twinData != null) {
            val peerName = twinData.deviceName.ifBlank { twinData.peerId.take(16).ifBlank { "未知" } }
            val peerModel = twinData.deviceModel
            val peerId = twinData.peerId
            AlertDialog(
                onDismissRequest = { pairFile.delete() },
                icon = { Icon(Icons.Outlined.Warning, null, tint = ArcoColors.Orange6) },
                title = { Text("记忆孪生配对请求", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text("⚠️ 请确认是个人设备请求，请勿与他人设备记忆孪生")
                        Spacer(Modifier.height(12.dp))
                        Text("请求设备: $peerName", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        if (peerModel.isNotBlank()) {
                            Text("型号: $peerModel", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("同意后，双方 Agent 的记忆将开始同步。", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        // Check if pairing engine already has a session (new protocol)
                        val pairingSession = com.mengpaw.plugin.memorytwin.TwinPairingEngine.getSessionForPeer(peerId)
                        if (pairingSession != null && pairingSession.phase == com.mengpaw.plugin.memorytwin.TwinPairingEngine.PairingPhase.AWAITING_CONFIRM) {
                            // New protocol: pairing engine will handle through verification code dialog
                            android.util.Log.i("MengPawTwin", "使用新配对协议, 等待验证码确认")
                        } else {
                            // Legacy: write trust directly (will be upgraded to new protocol on next sync)
                            val trustedDir = java.io.File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED)
                            trustedDir.mkdirs()
                            java.io.File(trustedDir, "$peerId.trusted").writeText(
                                """{"deviceId":"$peerId","deviceName":"$peerName","pairedAt":${System.currentTimeMillis()}}"""
                            )
                        }
                        pairFile.delete()
                    }) {
                        Text("同意", color = ThemeColors.brand)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pairFile.delete() }) {
                        Text("不同意")
                    }
                }
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 记忆孪生 6位验证码弹窗 (双方) — 观察 TwinPairingEngine StateFlow
    // ═══════════════════════════════════════════════════════════════════
    val twinPairingState by com.mengpaw.plugin.memorytwin.TwinPairingEngine.pairingUiState.collectAsState()
    var showTwinVerifyDialog by remember { mutableStateOf(false) }
    var verifySessionId by remember { mutableStateOf("") }
    var verifyPeerId by remember { mutableStateOf("") }
    var verifyCode by remember { mutableStateOf("") }

    // 当配对引擎状态变为 AWAITING_CONFIRM 时自动弹出验证码对话框
    LaunchedEffect(twinPairingState) {
        if (twinPairingState.phase == com.mengpaw.plugin.memorytwin.TwinPairingEngine.PairingPhase.AWAITING_CONFIRM &&
            twinPairingState.verificationCode.isNotBlank() && !showTwinVerifyDialog) {
            verifySessionId = twinPairingState.sessionId
            verifyPeerId = twinPairingState.peerId
            verifyCode = twinPairingState.verificationCode
            showTwinVerifyDialog = true
        }
        if (twinPairingState.phase == com.mengpaw.plugin.memorytwin.TwinPairingEngine.PairingPhase.ESTABLISHED) {
            showTwinVerifyDialog = false
            com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.appContext?.let { ctx ->
                android.widget.Toast.makeText(ctx, "🧠 记忆孪生配对成功！", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showTwinVerifyDialog) {
        AlertDialog(
            onDismissRequest = {
                showTwinVerifyDialog = false
                com.mengpaw.plugin.memorytwin.TwinPairingEngine.cancelPairing(verifySessionId)
            },
            icon = { Icon(Icons.Outlined.Security, null, tint = ThemeColors.brand) },
            title = { Text("验证配对码", fontWeight = FontWeight.Bold) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "请确认两台设备显示相同的 6 位验证码",
                        style = MaterialTheme.typography.bodySmall,
                        color = ThemeColors.textSecondary
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        verifyCode,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = ThemeColors.brand,
                        letterSpacing = 8.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "配对设备: ${verifyPeerId.take(16)}...",
                        style = MaterialTheme.typography.bodySmall,
                        color = ThemeColors.textSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "⚠️ 如验证码不一致，说明存在中间人攻击，请立即取消",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArcoColors.Red6,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showTwinVerifyDialog = false
                    com.mengpaw.plugin.memorytwin.TwinPairingEngine.confirmPairing(verifySessionId)
                }) {
                    Text("一致，确认配对", color = ThemeColors.brand)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showTwinVerifyDialog = false
                    com.mengpaw.plugin.memorytwin.TwinPairingEngine.cancelPairing(verifySessionId)
                }) {
                    Text("取消", color = ArcoColors.Red6)
                }
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // 记忆孪生配对确认 (发起方) — 5连击触发
    // ═══════════════════════════════════════════════════════════════════
    if (showTwinConfirmDialog) {
        val target = twinPairTarget
        AlertDialog(
            onDismissRequest = { showTwinConfirmDialog = false; twinPairTarget = null },
            icon = { Icon(Icons.Outlined.Hub, null, tint = ThemeColors.brand) },
            title = { Text("记忆孪生配对", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    if (target != null) {
                        Text("即将与以下设备建立记忆孪生配对：")
                        Spacer(Modifier.height(4.dp))
                        Surface(shape = RoundedCornerShape(ArcoRadius.sm),
                            color = ThemeColors.bgCardHigh, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                target.remark.ifBlank { target.name },
                                Modifier.padding(8.dp),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text("⚠️ 配对后本设备的 Agent 记忆将与对方同步。请确认是个人设备，勿与他人设备配对。",
                        style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                }
            },
            confirmButton = {
                val twinAlreadyActive = com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.isActivated
                TextButton(onClick = {
                    showTwinConfirmDialog = false
                    val peer = twinPairTarget
                    twinPairTarget = null
                    // 未激活则先激活孪生服务
                    if (!twinAlreadyActive) {
                        onActivateMemoryTwin()
                    }
                    // 发起配对 (已激活的直接配对, 未激活的等 ACP 就绪后配对)
                    if (peer != null) {
                        scope.launch(Dispatchers.IO) {
                            if (!twinAlreadyActive) {
                                // 轮询等待 ACP 就绪 (最多 5 秒)
                                val ready = com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.awaitAcpReady(5000L)
                                if (!ready) {
                                    android.util.Log.w("MengPawTwin", "ACP 未就绪, 放弃配对")
                                    return@launch
                                }
                            }
                            try {
                                val transport = com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.acpTransport
                                if (transport != null) {
                                    val deviceId = try { com.mengpaw.kernel.acp.AcpCrypto.myFingerprint() } catch (_: Exception) { "device-${System.currentTimeMillis()}" }
                                    val ctx = com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.appContext ?: return@launch
                                    val collector = com.mengpaw.plugin.memorytwin.TwinCapabilityCollector(ctx, deviceId, android.os.Build.MODEL ?: "")
                                    val card = collector.collect(null, emptyList())
                                    val result = com.mengpaw.plugin.memorytwin.TwinPairingEngine.initiatePairing(
                                        peerId = peer.name,
                                        myDeviceId = deviceId,
                                        myFingerprint = deviceId,
                                        capabilityCard = card.toJson(),
                                        transport = transport
                                    )
                                    android.util.Log.i("MengPawTwin", "5连击配对发起: session=${result.sessionId} peer=${peer.name}")
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("MengPawTwin", "5连击配对失败: ${e.message}", e)
                            }
                        }
                    }
                }) {
                    Text("确认配对", color = ThemeColors.brand)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTwinConfirmDialog = false; twinPairTarget = null }) {
                    Text(strings.cancel)
                }
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // New Agent Dialog
    // ═══════════════════════════════════════════════════════════════════
    if (showNewAgentDialog) {
        NewAgentDialog(
            initialName = "智能体 ${discoveredAgents.size + 1}",
            onDismiss = { showNewAgentDialog = false },
            onConfirm = { form ->
                val wsFolder = form.workspaceFolder.ifBlank { form.name }
                onCreateAgentWithDetails(form.name, wsFolder, form.intro)
                showNewAgentDialog = false
            }
        )
    }
}

/** 根据框架类型返回对应图标。 */
@Composable
fun frameworkTypeIcon(frameworkType: String): androidx.compose.ui.graphics.vector.ImageVector = when (frameworkType) {
    "claude-code", "trea-ide", "trea-work", "cursor", "opencode",
    "reasonix", "workbuddy" -> Icons.Outlined.Terminal  // MCP
    "openclaw", "qclaw", "hermes", "codex" -> Icons.Outlined.Dns  // WebSocket
    "qwenpaw", "coze" -> Icons.Outlined.Language  // REST
    "collab-cli" -> Icons.Outlined.Folder  // File
    "kimi-desktop" -> Icons.Outlined.DesktopWindows  // 未知
    "custom" -> Icons.Outlined.MoreHoriz
    else -> Icons.Outlined.Hub  // mengpaw
}
