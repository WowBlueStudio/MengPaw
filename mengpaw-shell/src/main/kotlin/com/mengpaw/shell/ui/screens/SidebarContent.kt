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
 *
 * Changes from v0.6.x:
 * - Long-press "申请智能体调度权限" removed.
 * - Tapping an agent opens an Agent Card dialog (avatar, name, workspace, intro).
 * - "+ New Agent" opens a creation dialog with name / folder / intro fields.
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
    // Extended create — passes name, workspace folder name, and intro
    onCreateAgentWithDetails: (name: String, workspaceFolder: String, intro: String) -> Unit = { name, _, _ -> onCreateAgent(name) },
    onActivateMemoryTwin: () -> Unit = {}
) {
    var frameworkStatus by remember { mutableStateOf(FrameworkStatus.ONLINE) }
    var manualStatus by remember { mutableStateOf(false) }

    // ── Card dialog states ──
    var cardAgentName by remember { mutableStateOf<String?>(null) }
    var cardFrameworkName by remember { mutableStateOf<String?>(null) }

    // ── New Agent dialog state ──
    var showNewAgentDialog by remember { mutableStateOf(false) }
    var showAddFramework by remember { mutableStateOf(false) }
    var showTwinConfirmDialog by remember { mutableStateOf(false) }

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
            Text("智能体", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { showNewAgentDialog = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Add, "新建智能体", tint = ThemeColors.brand, modifier = Modifier.size(20.dp))
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("框架通讯录", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { showAddFramework = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.PersonAdd, "添加框架", tint = ThemeColors.brand, modifier = Modifier.size(20.dp))
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
                            val json = org.json.JSONObject(file.readText())
                            contacts.add(FrameworkContact(
                                name = json.optString("name", file.nameWithoutExtension),
                                address = json.optString("address", ""),
                                online = false,
                                trusted = true,
                                agents = emptyList(),
                                remark = json.optString("remark", ""),
                                frameworkType = json.optString("frameworkType", "mengpaw")
                            ))
                        } catch (_: Exception) { /* skip corrupted files */ }
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
            Text("你的智能体还没有朋友", style = MaterialTheme.typography.bodySmall,
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
                                    Text("已信任", Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
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
                                        Text("当前", Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
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
        Text("功能", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(ArcoSpacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
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
                    Text("插件", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textPrimary)
                }
            }
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
                    Text("设置", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textPrimary)
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
        val json = try { org.json.JSONObject(pairFile.readText()) } catch (_: Exception) { null }
        if (json != null) {
            val peerName = json.optString("deviceName", json.optString("peerId", "未知").take(16))
            val peerModel = json.optString("deviceModel", "")
            val peerId = json.optString("peerId", "")
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
    // 记忆孪生激活确认 (发起方)
    // ═══════════════════════════════════════════════════════════════════
    if (showTwinConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showTwinConfirmDialog = false },
            icon = { Icon(Icons.Outlined.Hub, null, tint = ThemeColors.brand) },
            title = { Text("记忆孪生", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("⚠️ 注意你正在发起记忆孪生功能，请确认是个人设备")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "激活后，本设备的 Agent 记忆将与其他已配对的设备同步。请勿在他人的设备上激活此功能。",
                        style = MaterialTheme.typography.bodySmall,
                        color = ThemeColors.textSecondary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showTwinConfirmDialog = false
                    onActivateMemoryTwin()
                }) {
                    Text("已确认，激活", color = ThemeColors.brand)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTwinConfirmDialog = false }) {
                    Text("取消")
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

// ═══════════════════════════════════════════════════════════════════════
// Agent Card Dialog
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun AgentCardDialog(
    agentName: String,
    onDismiss: () -> Unit,
    onSwitchTo: () -> Unit
) {
    val agentDir = File(com.mengpaw.kernel.DataPaths.AGENTS, agentName)
    val workspacePath = agentDir.absolutePath

    // Read profile data from disk
    val profile = remember(agentName) { AgentProfile.load(agentName) }
    var editName by remember { mutableStateOf(profile.name.ifBlank { agentName }) }
    var editIntro by remember { mutableStateOf(profile.bio.ifBlank { profile.position.ifBlank { "" } }) }
    var isEditing by remember { mutableStateOf(false) }

    // Load avatar
    val avatarFile = File(agentDir, "avatar.png")
    val avatarBitmap = remember(agentName) {
        if (avatarFile.exists()) android.graphics.BitmapFactory.decodeFile(avatarFile.absolutePath) else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("智能体名片", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    if (isEditing) {
                        // Save edits
                        val newProfile = profile.copy(
                            name = editName,
                            bio = editIntro
                        )
                        AgentProfile.save(agentName, newProfile)
                    }
                    isEditing = !isEditing
                }) {
                    Text(if (isEditing) "保存" else "编辑", color = ThemeColors.brand, fontSize = 13.sp)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 400.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Avatar — large size 72dp
                if (avatarBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = avatarBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(72.dp).clip(CircleShape)
                    )
                } else {
                    Surface(
                        shape = CircleShape,
                        color = ThemeColors.brandContainer,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                editName.ifBlank { agentName }.take(1),
                                color = ThemeColors.brand,
                                fontWeight = FontWeight.Bold,
                                fontSize = 28.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(ArcoSpacing.md))

                // Agent Name — editable
                if (isEditing) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("智能体名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(ArcoRadius.md)
                    )
                } else {
                    Text(
                        editName.ifBlank { agentName },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        color = ThemeColors.textPrimary
                    )
                }

                Spacer(Modifier.height(ArcoSpacing.sm))

                // Workspace Path — read-only
                Row(
                    Modifier.fillMaxWidth()
                        .background(ThemeColors.bgCardHigh, RoundedCornerShape(ArcoRadius.sm))
                        .padding(ArcoSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(14.dp), tint = ThemeColors.textSecondary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        workspacePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = ThemeColors.textSecondary,
                        fontSize = 11.sp
                    )
                }

                Spacer(Modifier.height(ArcoSpacing.sm))

                // Agent Intro — editable
                Text(
                    "智能体简介",
                    style = MaterialTheme.typography.labelSmall,
                    color = ThemeColors.textSecondary,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                if (isEditing) {
                    OutlinedTextField(
                        value = editIntro,
                        onValueChange = { editIntro = it },
                        label = { Text("简介") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(ArcoRadius.md)
                    )
                } else {
                    Text(
                        editIntro.ifBlank { "暂无简介" },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (editIntro.isBlank()) ThemeColors.textSecondary.copy(alpha = 0.6f) else ThemeColors.textPrimary
                    )
                }

                Spacer(Modifier.height(ArcoSpacing.md))

                // Workspace files summary
                val mdFiles = remember(agentName) {
                    try { agentDir.listFiles()
                        ?.filter { it.extension == "md" }
                        ?.map { it.name }
                        ?.sorted()
                        ?: emptyList() } catch (_: Exception) { emptyList() }
                }
                if (mdFiles.isNotEmpty()) {
                    Text(
                        "工作区文件 (${mdFiles.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = ThemeColors.textSecondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        color = ThemeColors.bgCardHigh,
                        shape = RoundedCornerShape(ArcoRadius.sm),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp)
                    ) {
                        Column(
                            Modifier.padding(ArcoSpacing.sm).verticalScroll(rememberScrollState())
                        ) {
                            mdFiles.forEach { fname ->
                                Text(
                                    "📄 $fname",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ThemeColors.textSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // Delete button (only for non-MengPaw agents)
                if (agentName != "MengPaw") {
                    var showDeleteConfirm by remember { mutableStateOf(false) }
                    TextButton(onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text("删除智能体", fontSize = 13.sp)
                    }
                    if (showDeleteConfirm) {
                        AlertDialog(
                            onDismissRequest = { showDeleteConfirm = false },
                            title = { Text("确认删除") },
                            text = { Text("确定要删除「$agentName」吗？\n\n该操作将永久删除智能体的所有数据，包括工作区文件、记忆和会话记录。") },
                            confirmButton = {
                                Button(onClick = {
                                    // Delete agent directory
                                    try {
                                        val dir = java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, agentName)
                                        if (dir.exists()) dir.deleteRecursively()
                                    } catch (_: Exception) {}
                                    showDeleteConfirm = false
                                    onDismiss()
                                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                                    Text("删除", color = Color.White)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
                            }
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

// ═══════════════════════════════════════════════════════════════════════
// New Agent Dialog
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun NewAgentDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (NewAgentForm) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var workspaceFolder by remember { mutableStateOf("") }
    var intro by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("新建智能体", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; if (workspaceFolder.isBlank()) workspaceFolder = it },
                    label = { Text("智能体名称 *") },
                    placeholder = { Text("例如：研究助手") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(ArcoRadius.md)
                )

                OutlinedTextField(
                    value = workspaceFolder,
                    onValueChange = { workspaceFolder = it },
                    label = { Text("工作区文件夹名称") },
                    placeholder = { Text("默认与智能体名称相同") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(ArcoRadius.md),
                    supportingText = {
                        Text(
                            "将创建于: ${com.mengpaw.kernel.DataPaths.AGENTS}/${workspaceFolder.ifBlank { name }}",
                            fontSize = 10.sp,
                            color = ThemeColors.textSecondary
                        )
                    }
                )

                OutlinedTextField(
                    value = intro,
                    onValueChange = { intro = it },
                    label = { Text("智能体简介") },
                    placeholder = { Text("描述这个智能体的职责和能力...") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(ArcoRadius.md)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(NewAgentForm(
                            name = name.trim(),
                            workspaceFolder = workspaceFolder.ifBlank { name }.trim(),
                            intro = intro.trim()
                        ))
                    }
                },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.brand),
                shape = RoundedCornerShape(ArcoRadius.md)
            ) {
                Text("创建智能体", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ═══════════════════════════════════════════════════════════════════════
// Helpers
// ═══════════════════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════════════════
// Add Framework Dialog
// ═══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddFrameworkDialog(onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var frameworkType by remember { mutableStateOf("mengpaw") }
    var typeExpanded by remember { mutableStateOf(false) }
    var isDiscovering by remember { mutableStateOf(false) }
    var discovered by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    val typeOptions = listOf(
        "mengpaw" to "MengPaw (ACP)",
        "claude-code" to "Claude Code (MCP)",
        "trea-ide" to "Trea IDE (MCP)",
        "trea-work" to "Trea Work (MCP)",
        "cursor" to "Cursor (MCP)",
        "opencode" to "OpenCode (MCP)",
        "reasonix" to "Reasonix (MCP)",
        "workbuddy" to "Workbuddy (MCP)",
        "openclaw" to "OpenClaw (WS)",
        "qclaw" to "Qclaw (WS)",
        "hermes" to "Hermes (WS)",
        "codex" to "Codex (WS)",
        "qwenpaw" to "QwenPaw (REST)",
        "coze" to "Coze (REST)",
        "collab-cli" to "collab-cli (FILE)",
        "kimi-desktop" to "Kimi Desktop (?)",
        "custom" to "自定义协议"
    )
    val typeLabels = mapOf(
        "mengpaw" to "MengPaw · ACP · 端口 9876 · mDNS 自动发现 · 双向实时",
        "claude-code" to "Claude Code · MCP · JSON-RPC · 手动配置 · 单向实时",
        "trea-ide" to "Trea IDE · MCP · JSON-RPC · 手动配置 · 单向实时",
        "trea-work" to "Trea Work · MCP · JSON-RPC · 云端执行 · 单向实时",
        "cursor" to "Cursor · MCP · JSON-RPC · IDE 扩展 · 单向实时",
        "opencode" to "OpenCode · MCP · JSON-RPC · 手动配置 · 单向实时",
        "reasonix" to "Reasonix · MCP · JSON-RPC · MCP 插件 · 单向实时",
        "workbuddy" to "Workbuddy · MCP · JSON-RPC · MCP 连接器 · 单向实时",
        "openclaw" to "OpenClaw · WebSocket · 端口 18789 · 手动配置 · 单向实时",
        "qclaw" to "Qclaw · WebSocket · 端口 18789 · OpenClaw 衍生 · 单向实时",
        "hermes" to "Hermes · WebSocket · Gateway 模式 · 单向实时",
        "codex" to "Codex · Unix Socket · 本地进程 · 单向实时",
        "qwenpaw" to "QwenPaw · REST · FastAPI HTTP · 手动配置 · 单向轮询",
        "coze" to "Coze · REST · 云端 API · 单向轮询",
        "collab-cli" to "collab-cli · FILE · 文件系统共享 · UDP 广播 :9528 · 双向 · MIT 开源",
        "kimi-desktop" to "Kimi Desktop · 协议待验证 · Electron 桌面应用",
        "custom" to "自定义框架 · 手动配置协议和端口"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加框架", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(typeLabels[frameworkType] ?: "",
                    style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                Spacer(Modifier.height(ArcoSpacing.md))

                // 框架类型选择
                Text("框架类型", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
                Spacer(Modifier.height(4.dp))
                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                    OutlinedTextField(
                        value = typeOptions.first { it.first == frameworkType }.second,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                        shape = RoundedCornerShape(ArcoRadius.md)
                    )
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        typeOptions.forEach { (type, label) ->
                            DropdownMenuItem(
                                text = { Text(label, fontSize = 14.sp) },
                                onClick = {
                                    frameworkType = type
                                    typeExpanded = false
                                    // 自动设置默认端口
                                    val defaultPort = com.mengpaw.plugin.framework.FrameworkPeerStore.FRAMEWORK_TYPES[type] ?: 0
                                    if (defaultPort > 0 && address.isBlank()) {
                                        address = ":$defaultPort"
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(ArcoSpacing.sm))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("框架名称") },
                    placeholder = { Text("如: 办公室电脑") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(ArcoSpacing.sm))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("地址 (可选)") },
                    placeholder = {
                        val defaultPort = com.mengpaw.plugin.framework.FrameworkPeerStore.FRAMEWORK_TYPES[frameworkType] ?: 0
                        if (defaultPort > 0) Text("如: 192.168.1.100:$defaultPort")
                        else Text("如: localhost:8080 或 /path/to/socket")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(ArcoSpacing.sm))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        isDiscovering = true
                        val peers = com.mengpaw.plugin.framework.FrameworkPeerStore.loadAll()
                        discovered = peers.map { it.name to "${it.address}:${it.port}" }
                        isDiscovering = false
                    }) {
                        if (isDiscovering) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text("扫描局域网")
                    }
                }
                if (discovered.isNotEmpty()) {
                    Spacer(Modifier.height(ArcoSpacing.sm))
                    Text("发现的设备:", style = MaterialTheme.typography.labelSmall)
                    discovered.forEach { (n, addr) ->
                        TextButton(onClick = { name = n; address = addr }) {
                            Text("$n ($addr)", fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        val trustedDir = java.io.File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED)
                        trustedDir.mkdirs()
                        val fwFile = java.io.File(trustedDir, "$name.json")
                        val tmp = java.io.File(trustedDir, "$name.tmp.json")
                        val json = org.json.JSONObject().apply {
                            put("name", name)
                            put("address", address)
                            put("frameworkType", frameworkType)
                            put("addedAt", System.currentTimeMillis())
                        }
                        try { tmp.writeText(json.toString()); tmp.renameTo(fwFile); if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }; onDismiss() } catch (e: Exception) { ErrorCollector.report(e, "SidebarContent.saveFramework") }
                    }
                },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.brand),
                shape = RoundedCornerShape(ArcoRadius.md)
            ) { Text("添加", color = Color.White) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ═══════════════════════════════════════════════════════════════════════
// Framework Card Dialog
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun FrameworkCardDialog(
    frameworkName: String,
    onDismiss: () -> Unit
) {
    val peer = remember(frameworkName) {
        com.mengpaw.plugin.framework.FrameworkPeerStore.findByName(frameworkName)
    }
    val acpFile = java.io.File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED, "$frameworkName.json")
    val acpJson = remember(frameworkName) {
        if (acpFile.exists()) try { org.json.JSONObject(acpFile.readText()) } catch (_: Exception) { null }
        else null
    }

    // 框架类型: 从 peer 或 ACP JSON 读取
    val fwType = remember(frameworkName, peer) {
        peer?.frameworkType?.ifBlank { acpJson?.optString("frameworkType", "mengpaw") } ?: "mengpaw"
    }
    val proto = com.mengpaw.plugin.framework.FrameworkPeerStore.PROTOCOL_LABELS[fwType]
    val protoLabel = proto?.first ?: "?"
    val protoMode = proto?.second ?: ""

    // 备注名称: 优先从 FrameworkPeerStore 读取，其次从 ACP JSON 的 remark/notes 字段
    val savedRemark = remember(frameworkName, peer) {
        peer?.remark?.ifBlank { acpJson?.optString("remark", "")?.ifBlank { acpJson?.optString("notes", "") ?: "" } } ?: ""
    }
    var editRemark by remember { mutableStateOf(savedRemark) }
    var isEditing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("框架名片", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    if (isEditing) {
                        // 保存备注名称到 FrameworkPeerStore
                        if (peer != null) {
                            com.mengpaw.plugin.framework.FrameworkPeerStore.save(
                                peer.copy(remark = editRemark.trim())
                            )
                        }
                        // 同时保存到 ACP JSON 文件中
                        if (acpFile.exists()) {
                            try {
                                val updated = org.json.JSONObject(acpFile.readText())
                                updated.put("remark", editRemark.trim())
                                val tmp = java.io.File(acpFile.parentFile, "$frameworkName.tmp.json")
                                tmp.writeText(updated.toString())
                                tmp.renameTo(acpFile)
                                if (tmp.exists()) tmp.delete()
                            } catch (_: Exception) {}
                        }
                    }
                    isEditing = !isEditing
                }) {
                    Text(if (isEditing) "保存" else "编辑", color = ThemeColors.brand, fontSize = 13.sp)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 400.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(shape = RoundedCornerShape(ArcoRadius.lg),
                    color = ThemeColors.brandContainer, modifier = Modifier.size(72.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(frameworkTypeIcon(fwType), fwType, Modifier.size(36.dp), tint = ThemeColors.brand)
                    }
                }
                Spacer(Modifier.height(4.dp))
                // 协议标签 — 纯文本，无颜色
                Surface(shape = RoundedCornerShape(ArcoRadius.sm),
                    color = ThemeColors.bgCardHigh) {
                    Text("$protoLabel · $protoMode",
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = ThemeColors.textSecondary, fontSize = 10.sp)
                }
                Spacer(Modifier.height(ArcoSpacing.sm))

                // 备注名称 — 可编辑，编辑时显示输入框
                if (isEditing) {
                    Text("备注名称", style = MaterialTheme.typography.labelSmall,
                        color = ThemeColors.textSecondary, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = editRemark, onValueChange = { editRemark = it },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(frameworkName, fontSize = 14.sp) },
                        shape = RoundedCornerShape(ArcoRadius.md))
                } else {
                    val displayRemark = savedRemark.ifBlank { frameworkName }
                    Text(displayRemark, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                        color = ThemeColors.textPrimary)
                    if (savedRemark.isNotBlank()) {
                        Text(frameworkName, style = MaterialTheme.typography.labelSmall,
                            color = ThemeColors.textSecondary, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(ArcoSpacing.sm))

                if (peer != null) {
                    Row(Modifier.fillMaxWidth()
                        .background(ThemeColors.bgCardHigh, RoundedCornerShape(ArcoRadius.sm))
                        .padding(ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, null, Modifier.size(14.dp), tint = ThemeColors.textSecondary)
                        Spacer(Modifier.width(6.dp))
                        Text("版本: ${peer.version}", style = MaterialTheme.typography.labelSmall,
                            color = ThemeColors.textSecondary, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                }

                val addr = peer?.address ?: acpJson?.optString("address", "") ?: ""
                if (addr.isNotBlank()) {
                    Row(Modifier.fillMaxWidth()
                        .background(ThemeColors.bgCardHigh, RoundedCornerShape(ArcoRadius.sm))
                        .padding(ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Language, null, Modifier.size(14.dp), tint = ThemeColors.textSecondary)
                        Spacer(Modifier.width(6.dp))
                        Text("${addr}:${peer?.port ?: 9876}", style = MaterialTheme.typography.labelSmall,
                            color = ThemeColors.textSecondary, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                }

                if (peer != null) {
                    val online = peer.lastSeen > System.currentTimeMillis() - 120_000
                    Row(Modifier.fillMaxWidth()
                        .background(ThemeColors.bgCardHigh, RoundedCornerShape(ArcoRadius.sm))
                        .padding(ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(
                            if (online) ArcoColors.Green6 else ArcoColors.Gray5, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(if (online) "在线" else "离线", style = MaterialTheme.typography.labelSmall,
                            color = if (online) ArcoColors.Green6 else ThemeColors.textSecondary, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth()
                        .background(ThemeColors.bgCardHigh, RoundedCornerShape(ArcoRadius.sm))
                        .padding(ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Security, null, Modifier.size(14.dp),
                            tint = if (peer.trusted) ArcoColors.Green6 else ArcoColors.Orange6)
                        Spacer(Modifier.width(6.dp))
                        Text(if (peer.trusted) "已信任" else "未信任", style = MaterialTheme.typography.labelSmall,
                            color = if (peer.trusted) ArcoColors.Green6 else ArcoColors.Orange6, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                }

                // Agent 列表
                val agentList = peer?.agents ?: emptyList()
                if (agentList.isNotEmpty()) {
                    Text("托管智能体 (${agentList.size})", style = MaterialTheme.typography.labelSmall,
                        color = ThemeColors.textSecondary, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    agentList.forEach { agent ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = CircleShape, modifier = Modifier.size(20.dp),
                                color = ThemeColors.bgCardHigh) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(agent.take(1), fontSize = 9.sp, color = ThemeColors.textSecondary)
                                }
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(agent, style = MaterialTheme.typography.bodySmall,
                                color = ThemeColors.textPrimary, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 孪生配对按钮: 本地已激活 + 对方是 MengPaw → 可发起配对
                val twinReady = com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.isActivated
                if (twinReady && fwType == "mengpaw") {
                    TextButton(onClick = {
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val ctx = com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.appContext ?: return@launch
                                val transport = com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.acpTransport ?: return@launch
                                val deviceId = try { com.mengpaw.kernel.acp.AcpCrypto.myFingerprint() } catch (_: Exception) { "device-${System.currentTimeMillis()}" }
                                val myFingerprint = deviceId
                                val collector = com.mengpaw.plugin.memorytwin.TwinCapabilityCollector(ctx, deviceId, android.os.Build.MODEL ?: "")
                                val card = collector.collect(null, emptyList())
                                // Use the new pairing engine — sends CAPABILITY_ANNOUNCE with nonce
                                val result = com.mengpaw.plugin.memorytwin.TwinPairingEngine.initiatePairing(
                                    peerId = frameworkName,
                                    myDeviceId = deviceId,
                                    myFingerprint = myFingerprint,
                                    capabilityCard = card.toJson(),
                                    transport = transport
                                )
                                android.util.Log.i("MengPawTwin", "配对发起: session=${result.sessionId}")
                            } catch (e: Exception) {
                                android.util.Log.e("MengPawTwin", "配对失败: ${e.message}", e)
                            }
                        }
                        onDismiss()
                    }) {
                        Text("发起孪生配对", color = ThemeColors.brand, fontSize = 13.sp)
                    }
                }
                if (peer != null && !peer.trusted) {
                    TextButton(onClick = {
                        com.mengpaw.plugin.framework.FrameworkPeerStore.save(peer.copy(trusted = true))
                        onDismiss()
                    }) { Text("信任此框架", color = ThemeColors.brand, fontSize = 13.sp) }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 根据框架类型返回对应图标。 */
@Composable
private fun frameworkTypeIcon(frameworkType: String): androidx.compose.ui.graphics.vector.ImageVector = when (frameworkType) {
    "claude-code", "trea-ide", "trea-work", "cursor", "opencode",
    "reasonix", "workbuddy" -> Icons.Outlined.Terminal  // MCP
    "openclaw", "qclaw", "hermes", "codex" -> Icons.Outlined.Dns  // WebSocket
    "qwenpaw", "coze" -> Icons.Outlined.Language  // REST
    "collab-cli" -> Icons.Outlined.Folder  // File
    "kimi-desktop" -> Icons.Outlined.DesktopWindows  // 未知
    "custom" -> Icons.Outlined.MoreHoriz
    else -> Icons.Outlined.Hub  // mengpaw
}
