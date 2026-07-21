// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    val agents: List<String>
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

    // ── Agent Card dialog state ──
    var cardAgentName by remember { mutableStateOf<String?>(null) }

    // ── New Agent dialog state ──
    var showNewAgentDialog by remember { mutableStateOf(false) }

    // Discover agents from disk
    val agentsDir = remember { File(com.mengpaw.kernel.DataPaths.AGENTS) }
    val discoveredAgents = remember {
        val dirs = agentsDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: listOf("MengPaw")
        if (dirs.isEmpty()) listOf("MengPaw") else dirs
    }

    Column(Modifier.fillMaxHeight().width(280.dp).padding(ArcoSpacing.lg)) {
        // ── Agents ──
        Text("智能体", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(ArcoSpacing.sm))

        discoveredAgents.forEach { name ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable { cardAgentName = name }
                    .padding(vertical = ArcoSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar — loads from agent dir, falls back to initial
                val agentAvatarFile = File(com.mengpaw.kernel.DataPaths.AGENTS, "$name/avatar.png")
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

                // Agent name + workspace path
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = if (name == activeAgent) FontWeight.SemiBold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${com.mengpaw.kernel.DataPaths.AGENTS}/$name",
                        style = MaterialTheme.typography.labelSmall,
                        color = ThemeColors.textSecondary,
                        maxLines = 1,
                        fontSize = 10.sp
                    )
                }

                if (name == activeAgent) {
                    Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ThemeColors.brand.copy(alpha = 0.15f)) {
                        Text("当前", Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall, color = ThemeColors.brand)
                    }
                }
            }
        }

        // ── "+ New Agent" button ──
        OutlinedButton(
            onClick = { showNewAgentDialog = true },
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

            Row(
                Modifier.fillMaxWidth()
                    .clickable { expanded = !expanded }
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
                    Text(framework.address, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
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
                modifier = Modifier.verticalScroll(rememberScrollState()),
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
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(ArcoSpacing.sm)) {
                            mdFiles.take(8).forEach { fname ->
                                Text(
                                    "📄 $fname",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ThemeColors.textSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            if (mdFiles.size > 8) {
                                Text(
                                    "... 还有 ${mdFiles.size - 8} 个文件",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ThemeColors.textSecondary.copy(alpha = 0.5f),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSwitchTo,
                colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.brand),
                shape = RoundedCornerShape(ArcoRadius.md)
            ) {
                Text("切换到此智能体", color = Color.White)
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

@Composable
private fun SidebarNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = ArcoSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = ThemeColors.textSecondary)
        Spacer(Modifier.width(ArcoSpacing.sm))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
