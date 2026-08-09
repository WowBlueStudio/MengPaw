// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.shell.ui.screens.model.ExecutionMode
import com.mengpaw.shell.ui.screens.model.PendingTask

@Composable
fun ExpandItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick, shape = RoundedCornerShape(ArcoRadius.lg),
            color = ThemeColors.bgCardHigh,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(28.dp), tint = ThemeColors.textSecondary)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
    }
}

/** 执行模式按钮 — 带激活态高亮。 */
@Composable
fun ModeItem(mode: ExecutionMode, isActive: Boolean, onClick: () -> Unit) {
    val icon = when (mode) {
        ExecutionMode.SWARM -> Icons.Outlined.Whatshot
        ExecutionMode.FLEET -> Icons.Outlined.Groups
        ExecutionMode.GOAL -> Icons.Outlined.FlagCircle
        ExecutionMode.PLAN -> Icons.Outlined.Checklist
        ExecutionMode.RESEARCH -> Icons.Outlined.TravelExplore
        ExecutionMode.TRANSLATE -> Icons.Outlined.Translate
        ExecutionMode.SILENT -> Icons.Outlined.NotificationsOff
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(ArcoRadius.lg),
            color = if (isActive) ThemeColors.brand.copy(alpha = 0.15f) else ThemeColors.bgCardHigh,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, mode.label, Modifier.size(28.dp),
                    tint = if (isActive) ThemeColors.brand else ThemeColors.textSecondary)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(mode.prefix, style = MaterialTheme.typography.labelSmall,
            color = if (isActive) ThemeColors.brand else ThemeColors.textSecondary)
    }
}

// ── Pending tasks bar — floating overlay at bottom of message area ──
@Composable
fun PendingTasksBar(
    tasks: List<PendingTask>,
    onRemove: (Int) -> Unit,
    onClearAll: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(ArcoRadius.md),
        color = ThemeColors.brandContainer.copy(alpha = 0.95f),
        shadowElevation = 4.dp
    ) {
        Row(
            Modifier.padding(horizontal = ArcoSpacing.sm, vertical = ArcoSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.PendingActions, null, Modifier.size(16.dp), tint = ThemeColors.brand)
            Spacer(Modifier.width(6.dp))
            Text("待办 (${tasks.size})", style = MaterialTheme.typography.labelSmall, color = ThemeColors.brand)
            Spacer(Modifier.width(8.dp))
            Text(
                tasks.first().text.take(25) + if (tasks.first().text.length > 25) "…" else "",
                style = MaterialTheme.typography.bodySmall,
                color = ThemeColors.textSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (tasks.size > 1) {
                Text(" +${tasks.size - 1}", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onClearAll, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Outlined.Delete, "清空待办", Modifier.size(16.dp), tint = ThemeColors.textSecondary)
            }
        }
    }
}
