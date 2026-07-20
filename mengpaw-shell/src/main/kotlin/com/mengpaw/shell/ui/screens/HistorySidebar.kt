// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing

/**
 * Swipe direction for session actions.
 */
private enum class SwipeState { NONE, DELETE, COMPACT }

/**
 * Right sidebar — session history with swipe-to-delete/compact.
 * References QwenPaw's approach: left-swipe to compact, deeper swipe to delete.
 */
@Composable
fun HistorySidebar(
    sessions: List<AgentViewModel.SessionRecord>,
    hideCompacted: Boolean,
    onToggleHideCompacted: () -> Unit,
    onSelectSession: (AgentViewModel.SessionRecord) -> Unit,
    onDeleteSession: (String) -> Unit,
    onCompactSession: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(ThemeColors.bgPrimary.copy(alpha = 0.95f))
            .padding(top = ArcoSpacing.md)
    ) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("历史会话", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = ThemeColors.textPrimary)
            Row {
                // Hide compacted toggle
                IconButton(onClick = onToggleHideCompacted, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (hideCompacted) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        if (hideCompacted) "显示已压缩" else "隐藏已压缩",
                        tint = if (hideCompacted) ThemeColors.textSecondary else ThemeColors.brand,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        HorizontalDivider(color = ThemeColors.border, thickness = 0.5.dp)

        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(ArcoSpacing.lg), contentAlignment = Alignment.Center) {
                Text("暂无历史会话\n新会话自动保存", color = ThemeColors.textSecondary, fontSize = 14.sp)
            }
        }

        // Session list with swipe gestures
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = ArcoSpacing.sm)
        ) {
            items(sessions, key = { it.id }) { session ->
                SwipeableSessionItem(
                    session = session,
                    onClick = { onSelectSession(session) },
                    onDelete = { onDeleteSession(session.id) },
                    onCompact = { onCompactSession(session.id) }
                )
            }
        }
    }
}

@Composable
private fun SwipeableSessionItem(
    session: AgentViewModel.SessionRecord,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onCompact: () -> Unit
) {
    var swipeState by remember { mutableStateOf(SwipeState.NONE) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // Background actions (revealed on swipe)
        if (swipeState != SwipeState.NONE) {
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (swipeState == SwipeState.COMPACT) {
                    Box(
                        Modifier.fillMaxHeight().width(80.dp)
                            .background(Color(0xFF165DFF))
                            .clickable { onCompact(); swipeState = SwipeState.NONE; offsetX = 0f },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("压缩", color = Color.White, fontSize = 13.sp)
                    }
                }
                Box(
                    Modifier.fillMaxHeight().width(80.dp)
                        .background(Color(0xFFF53F3F))
                        .clickable { onDelete(); swipeState = SwipeState.NONE; offsetX = 0f },
                    contentAlignment = Alignment.Center
                ) {
                    Text("删除", color = Color.White, fontSize = 13.sp)
                }
            }
        }

        // Foreground content (swipeable)
        Surface(
            Modifier
                .fillMaxWidth()
                .offset(x = (offsetX / 3).dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            swipeState = when {
                                offsetX < -200 -> SwipeState.DELETE
                                offsetX < -100 -> SwipeState.COMPACT
                                else -> SwipeState.NONE
                            }
                            if (swipeState == SwipeState.NONE) offsetX = 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            offsetX = (offsetX + dragAmount).coerceIn(-300f, 0f)
                        }
                    )
                }
                .clickable {
                    if (swipeState != SwipeState.NONE) { swipeState = SwipeState.NONE; offsetX = 0f }
                    else onClick()
                },
            color = ThemeColors.bgPrimary
        ) {
            Row(
                Modifier.padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (session.compacted) {
                            Text("📦 ", fontSize = 14.sp)
                        }
                        Text(
                            session.title,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = if (session.compacted) ThemeColors.textSecondary else ThemeColors.textPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        session.preview.ifBlank { "${session.messageCount} 条消息" },
                        fontSize = 12.sp,
                        color = ThemeColors.textSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Text(
                        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(session.timestamp)),
                        fontSize = 11.sp, color = Color(0xFF86909C)
                    )
                }
                if (session.compacted) {
                    Surface(
                        shape = RoundedCornerShape(ArcoRadius.sm),
                        color = Color(0xFFFFF7E6)
                    ) {
                        Text(
                            "已压缩",
                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 11.sp, color = Color(0xFFFF7D00)
                        )
                    }
                }
            }
        }
    }
}
