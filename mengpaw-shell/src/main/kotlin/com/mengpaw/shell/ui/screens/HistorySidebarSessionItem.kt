// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing

/**
 * Single session row with:
 * - Swipe-left to reveal 修复/压缩/删除 actions
 * - Long-press to enter multi-select mode
 * - Tap to select session (or toggle in multi-select)
 * 拆自 HistorySidebar.kt (2026-08-06, >400 行文件拆分批次4)。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionItem(
    session: SessionPersistenceService.SessionRecord,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onCompact: () -> Unit,
    multiSelectMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    val actionWidth = 120f  // 2 buttons: compact + delete
    // Reset swipe when multi-select mode changes
    LaunchedEffect(multiSelectMode) { swipeOffset = 0f }

    Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clipToBounds()) {
        // Action buttons revealed on swipe
        Row(
            Modifier.fillMaxHeight().align(Alignment.CenterEnd).width(actionWidth.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Compact (only if not already compacted)
            if (!session.compacted) {
                Box(Modifier.fillMaxHeight().width(60.dp)
                    .background(ArcoColors.Orange6)
                    .clickable { onCompact(); swipeOffset = 0f },
                    contentAlignment = Alignment.Center) {
                    Text("压缩", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            // Delete
            Box(Modifier.fillMaxHeight().width(60.dp)
                .background(ArcoColors.Red6)
                .clickable { onDelete(); swipeOffset = 0f },
                contentAlignment = Alignment.Center) {
                Text("删除", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }

        // Foreground row — inner Box background moves with swipe; Column must be transparent
        Column {
            HorizontalDivider(color = ThemeColors.border, thickness = 0.5.dp)
            Box(
                Modifier.fillMaxWidth()
                    .offset(x = swipeOffset.dp)
                    .background(ThemeColors.bgPrimary)
                    .pointerInput(multiSelectMode) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (!multiSelectMode) {
                                    swipeOffset = if (swipeOffset < -90f) -actionWidth else 0f
                                }
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                if (!multiSelectMode) {
                                    swipeOffset = (swipeOffset + dragAmount).coerceIn(-actionWidth, 0f)
                                }
                            }
                        )
                    }
                    .combinedClickable(
                        onClick = {
                            if (multiSelectMode) onToggleSelect()
                            else onSelect()
                        },
                        onLongClick = onLongPress
                    )
            ) {
            Row(
                Modifier.padding(start = 56.dp, end = ArcoSpacing.lg).padding(vertical = ArcoSpacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Multi-select checkbox or session icon
                if (multiSelectMode) {
                    Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() },
                        modifier = Modifier.size(20.dp),
                        colors = CheckboxDefaults.colors(checkedColor = ArcoColors.Blue6))
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(
                        if (session.compacted) Icons.Outlined.Inventory2 else Icons.Outlined.ChatBubbleOutline,
                        null, Modifier.size(16.dp),
                        tint = if (session.compacted) ArcoColors.Orange6 else ThemeColors.textSecondary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(session.title, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                        color = if (session.compacted) ThemeColors.textSecondary else ThemeColors.textPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(session.timestamp)),
                        fontSize = 11.sp, color = ArcoColors.Gray6)
                }
                // Swipe hint (shows when swipeOffset == 0)
                if (swipeOffset == 0f && !multiSelectMode) {
                    Text("← 左滑", fontSize = 9.sp, color = ArcoColors.Gray4)
                }
            }
            } // Box
        } // Column
    }
}
