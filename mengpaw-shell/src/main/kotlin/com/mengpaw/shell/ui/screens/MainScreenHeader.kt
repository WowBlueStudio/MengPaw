// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.components.ArcoDivider
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.shell.ui.localization.AppStrings
import java.io.File

/**
 * 主界面头栏 — 从 MainScreen.kt 拆出 (2026-08-04, >40KB UI 文件拆分)。
 * 纯展示 + 回调上抛: 侧栏开关 / 新会话 / 任务模式开关 / 插件命令写入均经回调。
 */
@Composable
fun MainScreenHeader(
    strings: AppStrings,
    displayAgentName: String,
    agentFramework: String?,
    sessionLabel: String,
    missionActiveState: Boolean,
    pluginViewModel: PluginViewModel,
    onPluginCommand: (String) -> Unit,
    onToggleLeftSidebar: () -> Unit,
    onToggleRightSidebar: () -> Unit,
    onToggleMissionOverlay: () -> Unit,
    onNewSession: () -> Unit
) {
    Surface(
        shadowElevation = 2.dp,
        color = ThemeColors.bgPrimary.copy(alpha = 0.92f)
    ) {
        Row(
            Modifier.fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Agent avatar — 44dp circle, 点击打开左侧栏
            val avatarFile = File(com.mengpaw.kernel.DataPaths.AGENTS, "$displayAgentName/avatar.png")
            val avatarBitmap = remember(displayAgentName) { if (avatarFile.exists()) BitmapFactory.decodeFile(avatarFile.absolutePath) else null }
            Box(modifier = Modifier.size(44.dp).clip(CircleShape)
                .pointerInput(Unit) { detectTapGestures { onToggleLeftSidebar() } }) {
                if (avatarBitmap != null) {
                    Image(bitmap = avatarBitmap.asImageBitmap(), null, Modifier.fillMaxSize())
                } else {
                    Surface(shape = CircleShape, color = ThemeColors.brandContainer, modifier = Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(displayAgentName.take(1), color = ThemeColors.brand, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.width(ArcoSpacing.sm))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(displayAgentName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    if (agentFramework != null) {
                        Spacer(Modifier.width(4.dp))
                        Text("@$agentFramework",
                            style = MaterialTheme.typography.labelSmall,
                            color = ThemeColors.textSecondary,
                            maxLines = 1)
                    }
                }
                Text(sessionLabel,
                    style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, maxLines = 1)
            }
            Box(modifier = Modifier.size(44.dp).pointerInput(Unit) { detectTapGestures { onNewSession() } },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Add, strings.newSession, tint = ThemeColors.textSecondary)
            }
            // Mission monitor toggle (visible when mission is active)
            if (missionActiveState) {
                Box(modifier = Modifier.size(44.dp).pointerInput(Unit) { detectTapGestures { onToggleMissionOverlay() } },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Monitor, "Mission", tint = ThemeColors.brand)
                }
            }
            // FIX: Dynamic plugin buttons from HEADER_BAR placement
            val headerButtons = remember(pluginViewModel.activeButtons) { pluginViewModel.activeButtons[com.mengpaw.kernel.plugin.ButtonPlacement.HEADER_BAR] ?: emptyList() }
            if (headerButtons.isNotEmpty()) {
                headerButtons.take(2).forEach { btn ->
                    Box(modifier = Modifier.size(44.dp).pointerInput(btn.command) {
                            detectTapGestures { if (btn.command.isNotBlank()) onPluginCommand(btn.command) }
                        },
                        contentAlignment = Alignment.Center) {
                        Icon(pluginIconForName(btn.iconName), btn.label, tint = ThemeColors.brand)
                    }
                }
            }
            Box(modifier = Modifier.size(44.dp).pointerInput(Unit) { detectTapGestures { onToggleRightSidebar() } },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.History, strings.history, tint = ThemeColors.textSecondary)
            }
        }
    }
    ArcoDivider()
}
