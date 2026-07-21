// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing

/**
 * Right sidebar — hierarchical session history like QQ contacts.
 *
 * Layout:
 *   [Header]
 *   ── 智能体 ── (local agents)
 *     ├ Agent name  [N] [+]  ← collapsible section header
 *     │  ├ Session 1
 *     │  └ Session 2
 *     └ Agent name  [N] [+]
 *   ── 框架通讯录 ── (remote frameworks)
 *     ├ Framework name  ← expandable
 *     │  └ Agent name  [N] [+]  ← expandable
 *     │     ├ Session 1
 *     │     └ Session 2
 *     └ Framework name (no sessions)
 */
@Composable
fun HistorySidebar(
    localGroups: List<AgentViewModel.AgentSessionGroup>,
    frameworkNames: List<String>,
    frameworkGroups: List<Pair<String, List<AgentViewModel.AgentSessionGroup>>>,
    hideCompacted: Boolean,
    onToggleHideCompacted: () -> Unit,
    onSelectSession: (AgentViewModel.SessionRecord) -> Unit,
    onDeleteSession: (String) -> Unit,
    onCompactSession: (String) -> Unit,
    onRepairSession: (String) -> Unit,
    onNewSessionFor: (agentName: String, framework: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(ThemeColors.bgPrimary.copy(alpha = 0.97f))
            .padding(top = ArcoSpacing.md)
    ) {
        // ── Header ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("历史会话", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = ThemeColors.textPrimary)
            IconButton(onClick = onToggleHideCompacted, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (hideCompacted) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    if (hideCompacted) "显示已压缩" else "隐藏已压缩",
                    tint = if (hideCompacted) ThemeColors.textSecondary else ThemeColors.brand,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        HorizontalDivider(color = ThemeColors.border, thickness = 0.5.dp)

        if (localGroups.isEmpty() && frameworkGroups.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(ArcoSpacing.lg), contentAlignment = Alignment.Center) {
                Text("暂无历史会话\n新会话自动保存", color = ThemeColors.textSecondary, fontSize = 14.sp)
            }
        }

        LazyColumn(
            Modifier.fillMaxSize().clipToBounds(),
            contentPadding = PaddingValues(vertical = ArcoSpacing.sm)
        ) {
            // ── Section: 智能体 (local agents) ──
            if (localGroups.isNotEmpty()) {
                item(key = "sec_local") {
                    SectionHeader("智能体")
                }
                items(localGroups, key = { "local_${it.agentName}" }) { group ->
                    AgentGroupItem(
                        agentName = group.agentName,
                        framework = null,
                        sessions = group.sessions,
                        onSelectSession = onSelectSession,
                        onDeleteSession = onDeleteSession,
                        onCompactSession = onCompactSession,
                        onRepairSession = onRepairSession,
                        onNewSession = { onNewSessionFor(group.agentName, null) }
                    )
                }
            }

            // ── Section: 框架通讯录 (remote frameworks) ──
            val allFrameworkNames = (frameworkNames + frameworkGroups.map { it.first }).distinct()
            if (allFrameworkNames.isNotEmpty()) {
                item(key = "sec_fw") {
                    SectionHeader("框架通讯录")
                }
                allFrameworkNames.forEach { fwName ->
                    val groups = frameworkGroups.find { it.first == fwName }?.second ?: emptyList()
                    item(key = "fw_$fwName") {
                        FrameworkGroupItem(
                            frameworkName = fwName,
                            agentGroups = groups,
                            onSelectSession = onSelectSession,
                            onDeleteSession = onDeleteSession,
                            onCompactSession = onCompactSession,
                            onRepairSession = onRepairSession,
                            onNewSession = onNewSessionFor
                        )
                    }
                }
            }
        }
    }
}

// ── Building blocks ──

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.sm),
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        color = ThemeColors.textSecondary
    )
}

/**
 * Collapsible agent entry showing agent name, session count, [+], and session list.
 */
@Composable
private fun AgentGroupItem(
    agentName: String,
    framework: String?,
    sessions: List<AgentViewModel.SessionRecord>,
    onSelectSession: (AgentViewModel.SessionRecord) -> Unit,
    onDeleteSession: (String) -> Unit,
    onCompactSession: (String) -> Unit,
    onRepairSession: (String) -> Unit,
    onNewSession: () -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    var multiSelect by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }

    Column {
        // Multi-select toolbar
        if (multiSelect && selectedIds.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth()
                    .background(ArcoColors.Blue6.copy(alpha = 0.08f))
                    .padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("已选 ${selectedIds.size} 项", fontWeight = FontWeight.Medium,
                    fontSize = 13.sp, color = ArcoColors.Blue6)
                Row(horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
                    TextButton(onClick = {
                        selectedIds.forEach { onDeleteSession(it) }
                        selectedIds.clear(); multiSelect = false
                    }) {
                        Text("删除", color = ArcoColors.Red6, fontSize = 13.sp)
                    }
                    TextButton(onClick = { selectedIds.clear(); multiSelect = false }) {
                        Text("取消", fontSize = 13.sp)
                    }
                }
            }
        }

        // Agent name bar
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                null,
                Modifier.size(18.dp),
                tint = ThemeColors.textSecondary
            )
            Spacer(Modifier.width(6.dp))
            // Agent avatar initial
            Surface(
                shape = CircleShape,
                color = ThemeColors.brandContainer,
                modifier = Modifier.size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        agentName.take(1),
                        color = ThemeColors.brand,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
            Spacer(Modifier.width(ArcoSpacing.sm))
            Text(
                agentName,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = ThemeColors.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            // Session count badge
            if (sessions.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(ArcoRadius.sm),
                    color = ThemeColors.bgCardHigh
                ) {
                    Text(
                        "${sessions.size}",
                        Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        fontSize = 11.sp,
                        color = ThemeColors.textSecondary
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            // New session button [+]
            IconButton(onClick = onNewSession, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Outlined.Add,
                    "新建会话",
                    tint = ThemeColors.brand,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Session list
        AnimatedVisibility(visible = expanded) {
            Column {
                if (sessions.isEmpty()) {
                    Text(
                        "暂无会话",
                        Modifier.padding(start = 56.dp, bottom = 4.dp),
                        fontSize = 12.sp,
                        color = ThemeColors.textSecondary
                    )
                } else {
                    sessions.forEach { session ->
                        SessionItem(
                            session = session,
                            onSelect = { onSelectSession(session) },
                            onDelete = { onDeleteSession(session.id) },
                            onCompact = { onCompactSession(session.id) },
                            onRepair = { onRepairSession(session.id) },
                            multiSelectMode = multiSelect,
                            isSelected = session.id in selectedIds,
                            onToggleSelect = {
                                if (session.id in selectedIds) selectedIds.remove(session.id)
                                else selectedIds.add(session.id)
                            },
                            onLongPress = {
                                multiSelect = true
                                selectedIds.add(session.id)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Expandable framework bar with agent groups underneath.
 */
@Composable
private fun FrameworkGroupItem(
    frameworkName: String,
    agentGroups: List<AgentViewModel.AgentSessionGroup>,
    onSelectSession: (AgentViewModel.SessionRecord) -> Unit,
    onDeleteSession: (String) -> Unit,
    onCompactSession: (String) -> Unit,
    onRepairSession: (String) -> Unit,
    onNewSession: (agentName: String, framework: String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        // Framework name bar
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                null,
                Modifier.size(18.dp),
                tint = ThemeColors.textSecondary
            )
            Spacer(Modifier.width(6.dp))
            // Online indicator
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        if (agentGroups.isNotEmpty()) ThemeColors.brand else ThemeColors.bgCardHigh,
                        CircleShape
                    )
            )
            Spacer(Modifier.width(ArcoSpacing.sm))
            Text(
                frameworkName,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = ThemeColors.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (agentGroups.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(ArcoRadius.sm),
                    color = ArcoColors.Green6.copy(alpha = 0.12f)
                ) {
                    Text(
                        "在线",
                        Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        fontSize = 10.sp,
                        color = ArcoColors.Green6
                    )
                }
            }
        }

        // Nested agents under framework
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 12.dp)) {
                if (agentGroups.isEmpty()) {
                    Text(
                        "暂无智能体或会话",
                        Modifier.padding(start = 24.dp, bottom = 4.dp),
                        fontSize = 12.sp,
                        color = ThemeColors.textSecondary
                    )
                } else {
                    agentGroups.forEach { group ->
                        AgentGroupItem(
                            agentName = group.agentName,
                            framework = frameworkName,
                            sessions = group.sessions,
                            onSelectSession = onSelectSession,
                            onDeleteSession = onDeleteSession,
                            onCompactSession = onCompactSession,
                            onRepairSession = onRepairSession,
                            onNewSession = { onNewSession(group.agentName, frameworkName) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Single session row with:
 * - Swipe-left to reveal 修复/压缩/删除 actions
 * - Long-press to enter multi-select mode
 * - Tap to select session (or toggle in multi-select)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionItem(
    session: AgentViewModel.SessionRecord,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onCompact: () -> Unit,
    onRepair: () -> Unit,
    multiSelectMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    var swipeOffset by remember { mutableFloatStateOf(0f) }
    val actionWidth = 180f  // total width for 3 action buttons

    Box(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clipToBounds()) {
        // Action buttons revealed on swipe
        Row(
            Modifier.fillMaxHeight().align(Alignment.CenterEnd).width(actionWidth.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Repair
            Box(Modifier.fillMaxHeight().width(60.dp)
                .background(ArcoColors.Blue6)
                .clickable { onRepair(); swipeOffset = 0f },
                contentAlignment = Alignment.Center) {
                Text("修复", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
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

        // Foreground row
        Surface(
            Modifier.fillMaxWidth()
                .offset(x = swipeOffset.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { swipeOffset = if (swipeOffset < -90f) -actionWidth else 0f },
                        onHorizontalDrag = { _, dragAmount ->
                            swipeOffset = (swipeOffset + dragAmount).coerceIn(-actionWidth, 0f)
                        }
                    )
                }
                .combinedClickable(
                    onClick = {
                        if (multiSelectMode) onToggleSelect()
                        else onSelect()
                    },
                    onLongClick = onLongPress
                ),
            color = if (isSelected) ArcoColors.Blue6.copy(alpha = 0.08f)
                    else if (session.compacted) ThemeColors.bgCard
                    else ThemeColors.bgPrimary
        ) {
            Row(
                Modifier.padding(start = 56.dp, end = ArcoSpacing.lg, vertical = ArcoSpacing.xs),
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
        }
    }
}
