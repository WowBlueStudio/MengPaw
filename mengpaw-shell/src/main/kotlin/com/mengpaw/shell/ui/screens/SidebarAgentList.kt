// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.kernel.agent.AgentProfile
import com.mengpaw.shell.ui.localization.AppStrings
import java.io.File

// ── 侧栏 Agent 列表 + 快捷导航 — 拆自 SidebarContent.kt (2026-08-06, 批次4) ──

/** Agent 列表: 头像 (磁盘加载, 兜底首字母) + 显示名 + 工作区路径 + 当前徽章。 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun AgentListSection(
    discoveredAgents: List<String>,
    activeAgent: String,
    strings: AppStrings,
    onSwitchAgent: (String, String?) -> Unit,
    onClose: () -> Unit,
    onAgentLongClick: (String) -> Unit,
    onAddAgent: () -> Unit
) {
    // ── Agents ──
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(strings.sidebarAgents, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        IconButton(onClick = onAddAgent, modifier = Modifier.size(32.dp)) {
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
                    onLongClick = { onAgentLongClick(dirName) }
                )
                .padding(vertical = ArcoSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar — loads from agent dir, falls back to initial (P2: decodeSampled 有界解码防大头像 OOM)
            val agentAvatarFile = File(com.mengpaw.kernel.DataPaths.AGENTS, "$dirName/avatar.png")
            val agentAvatarBitmap = remember(dirName) {
                if (agentAvatarFile.exists()) decodeSampled(agentAvatarFile.absolutePath, maxDim = 256) else null
            }
            if (agentAvatarBitmap != null) {
                Image(
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
}

/** 快捷导航: 插件市场 / 设置 入口 (按压缩放动效)。 */
@Composable
internal fun SidebarQuickNav(
    strings: AppStrings,
    onNavigateToPlugins: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
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
}
