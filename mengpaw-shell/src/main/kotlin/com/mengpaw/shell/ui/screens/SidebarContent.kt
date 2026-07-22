// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

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
    val frameworkName: String = ""
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
    onCreateAgentWithDetails: (name: String, workspaceFolder: String, intro: String) -> Unit = { name, _, _ -> onCreateAgent(name) }
) {
    var frameworkStatus by remember { mutableStateOf(FrameworkStatus.ONLINE) }
    var manualStatus by remember { mutableStateOf(false) }

    // ── Card dialog states ──
    var cardAgentName by remember { mutableStateOf<String?>(null) }
    var cardFrameworkName by remember { mutableStateOf<String?>(null) }

    // ── New Agent dialog state ──
    var showNewAgentDialog by remember { mutableStateOf(false) }
    var showAddFramework by remember { mutableStateOf(false) }

    // Discover agents from disk — no remember() so list stays fresh when agents are created/deleted
    val agentsDir = File(com.mengpaw.kernel.DataPaths.AGENTS)
    // Exclude system dirs (inbox, team, acp, incubator) from agent list
    val systemDirs = setOf("inbox", "team", "acp", "incubator", "agent-001")
    val discoveredAgents = agentsDir.listFiles()
        ?.filter { it.isDirectory && it.name !in systemDirs && !it.name.startsWith(".") }
        ?.map { it.name }?.sorted()
        ?.ifEmpty { listOf("MengPaw") } ?: listOf("MengPaw")

    Column(Modifier.fillMaxHeight().width(280.dp).padding(ArcoSpacing.lg).verticalScroll(rememberScrollState())) {
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
                                agents = emptyList()
                            ))
                        } catch (_: Exception) { /* skip corrupted files */ }
                    }
            }
            // 合并 mDNS 发现的框架
            val discovered = com.mengpaw.plugin.framework.FrameworkPeerStore.loadAll()
            discovered.forEach { peer ->
                val existing = contacts.indexOfFirst { it.name == peer.name }
                if (existing >= 0) {
                    // 更新在线状态和 Agent 列表
                    val old = contacts[existing]
                    contacts[existing] = old.copy(
                        online = peer.lastSeen > System.currentTimeMillis() - 120_000,
                        address = "${peer.address}:${peer.port}",
                        agents = peer.agents,
                        version = peer.version,
                        frameworkName = peer.frameworkName
                    )
                } else {
                    contacts.add(FrameworkContact(
                        name = peer.name,
                        address = "${peer.address}:${peer.port}",
                        online = peer.lastSeen > System.currentTimeMillis() - 120_000,
                        trusted = peer.trusted,
                        agents = peer.agents,
                        version = peer.version,
                        frameworkName = peer.frameworkName
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
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(framework.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            if (framework.trusted) {
                                Spacer(Modifier.width(4.dp))
                                Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Green6.copy(alpha = 0.12f)) {
                                    Text("已信任", Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                        style = MaterialTheme.typography.labelSmall, color = ArcoColors.Green6, fontSize = 9.sp)
                                }
                            }
                        }
                        val softLabel = if (framework.frameworkName.isNotBlank() && framework.version.isNotBlank())
                            "${framework.frameworkName} v${framework.version}"
                        else framework.address
                        Text(softLabel, style = MaterialTheme.typography.labelSmall,
                            color = ThemeColors.textSecondary, fontSize = 10.sp)
                    }
                    if (framework.online) {
                        Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = frameworkStatus.indicatorColor.copy(alpha = 0.1f)) {
                            Text(frameworkStatus.label, Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall, color = frameworkStatus.indicatorColor, fontSize = 10.sp)
                        }
                    } else {
                        Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = FrameworkStatus.OFFLINE.indicatorColor.copy(alpha = 0.1f)) {
                            Text(FrameworkStatus.OFFLINE.label, Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall, color = FrameworkStatus.OFFLINE.indicatorColor, fontSize = 10.sp)
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
                    agentDir.listFiles()
                        ?.filter { it.extension == "md" }
                        ?.map { it.name }
                        ?.sorted()
                        ?: emptyList()
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

@Composable
private fun AddFrameworkDialog(onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var isDiscovering by remember { mutableStateOf(false) }
    var discovered by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加框架", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("通过 ACP 协议连接到其他 MengPaw 设备或兼容框架。",
                    style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                Spacer(Modifier.height(ArcoSpacing.md))
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
                    placeholder = { Text("如: 192.168.1.100:9876") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(ArcoSpacing.sm))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        isDiscovering = true
                        // 使用框架协议插件扫描局域网
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
                        // Save framework to ACP trusted list
                        val trustedDir = java.io.File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED)
                        trustedDir.mkdirs()
                        val fwFile = java.io.File(trustedDir, "$name.json")
                        // Atomic write — tmp then rename, avoids corruption on crash
                        val tmp = java.io.File(trustedDir, "$name.tmp.json")
                        tmp.writeText("""{"name":"$name","address":"$address","addedAt":${System.currentTimeMillis()}}""")
                        tmp.renameTo(fwFile)
                        if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }
                        onDismiss()
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

    var editNote by remember { mutableStateOf(acpJson?.optString("notes", "") ?: "") }
    var isEditing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("框架名片", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    if (isEditing && acpFile.exists()) {
                        try {
                            val updated = org.json.JSONObject(acpFile.readText())
                            updated.put("notes", editNote)
                            val tmp = java.io.File(acpFile.parentFile, "$frameworkName.tmp.json")
                            tmp.writeText(updated.toString())
                            tmp.renameTo(acpFile)
                            if (tmp.exists()) tmp.delete()
                        } catch (_: Exception) {}
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
                        Icon(Icons.Outlined.Hub, null, Modifier.size(36.dp), tint = ThemeColors.brand)
                    }
                }
                Spacer(Modifier.height(ArcoSpacing.md))
                Text(frameworkName, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                    color = ThemeColors.textPrimary)
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
                    Spacer(Modifier.height(ArcoSpacing.md))
                }

                Text("框架备注", style = MaterialTheme.typography.labelSmall,
                    color = ThemeColors.textSecondary, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                if (isEditing) {
                    OutlinedTextField(value = editNote, onValueChange = { editNote = it },
                        minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(ArcoRadius.md))
                } else {
                    Text(editNote.ifBlank { "暂无备注" }, style = MaterialTheme.typography.bodySmall,
                        color = if (editNote.isBlank()) ThemeColors.textSecondary.copy(alpha = 0.6f)
                        else ThemeColors.textPrimary)
                }
            }
        },
        confirmButton = {
            if (peer != null && !peer.trusted) {
                TextButton(onClick = {
                    com.mengpaw.plugin.framework.FrameworkPeerStore.save(peer.copy(trusted = true))
                    onDismiss()
                }) { Text("信任此框架", color = ThemeColors.brand, fontSize = 13.sp) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
