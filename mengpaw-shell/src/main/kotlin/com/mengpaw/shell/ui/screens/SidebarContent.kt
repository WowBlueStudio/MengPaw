// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

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
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.kernel.agent.AgentProfile
import com.mengpaw.shell.ui.components.KanbanStatusBar
import com.mengpaw.shell.ui.components.TribeBarState
import com.mengpaw.shell.ui.components.aggregateTribeBarState
import com.mengpaw.shell.ui.localization.AppStrings
import kotlinx.coroutines.delay
import java.io.File

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
    onSwitchAgent: (String, String?) -> Unit = { _, _ -> },
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
                        onClick = { if (dirName != activeAgent) { onSwitchAgent(dirName, null); onClose() } },
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
                        Text(status.label(strings), fontWeight = FontWeight.Medium, fontSize = 14.sp,
                            color = if (selected) status.indicatorColor else ThemeColors.textPrimary)
                        Text(status.desc(strings), fontSize = 11.sp, color = ThemeColors.textSecondary)
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
                            val contactFile = sidebarAppJson.decodeFromString<FrameworkContactFile>(file.readText())
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
                            onLongClick = { cardFrameworkName = framework.name }
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

        if (showAddFramework) {
            AddFrameworkDialog(strings = strings, onDismiss = { showAddFramework = false })
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
            strings = strings,
            agentName = name,
            onDismiss = { cardAgentName = null },
            onSwitchTo = {
                onSwitchAgent(name, null)
                cardAgentName = null
            }
        )
    }

    cardFrameworkName?.let { name ->
        FrameworkCardDialog(
            strings = strings,
            frameworkName = name,
            onDismiss = { cardFrameworkName = null }
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // 记忆孪生配对对话框 (请求/验证码/5连击确认) — 已拆至 TwinPairingDialogs.kt
    // ═══════════════════════════════════════════════════════════════════
    TwinPairingDialogs(
        strings = strings,
        onActivateMemoryTwin = onActivateMemoryTwin,
        twinPairTarget = twinPairTarget,
        showTwinConfirmDialog = showTwinConfirmDialog,
        onDismissTwinConfirm = {
            showTwinConfirmDialog = false
            twinPairTarget = null
        }
    )

    // ═══════════════════════════════════════════════════════════════════
    // New Agent Dialog
    // ═══════════════════════════════════════════════════════════════════
    if (showNewAgentDialog) {
        NewAgentDialog(
            strings = strings,
            initialName = String.format(strings.newAgentDefaultName, discoveredAgents.size + 1),
            onDismiss = { showNewAgentDialog = false },
            onConfirm = { form ->
                val wsFolder = form.workspaceFolder.ifBlank { form.name }
                onCreateAgentWithDetails(form.name, wsFolder, form.intro)
                showNewAgentDialog = false
            }
        )
    }
}

