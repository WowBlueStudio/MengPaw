// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.design.components.SectionHeader

/**
 * Right sidebar — hierarchical session history like QQ contacts.
 *
 * Layout:
 *   [Header]
 *   (local agents, 无分组标题)
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
 *
 * 分组条目 (AgentGroupItem/FrameworkGroupItem) → HistorySidebarGroups.kt;
 * 会话行 (SessionItem) → HistorySidebarSessionItem.kt (2026-08-06, 批次4)。
 */
@Composable
fun HistorySidebar(
    localGroups: List<SessionPersistenceService.AgentSessionGroup>,
    frameworkNames: List<String>,
    frameworkGroups: List<Pair<String, List<SessionPersistenceService.AgentSessionGroup>>>,
    hideCompacted: Boolean,
    onToggleHideCompacted: () -> Unit,
    hideArchived: Boolean = true,
    onToggleHideArchived: () -> Unit = {},
    onSelectSession: (SessionPersistenceService.SessionRecord) -> Unit,
    onDeleteSession: (String) -> Unit,
    onCompactSession: (String) -> Unit,
    onNewSessionFor: (agentName: String, framework: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(ThemeColors.bgPrimary)
            .padding(top = ArcoSpacing.md)
    ) {
        // ── Header ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("历史会话", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = ThemeColors.textPrimary)
            Row {
                IconButton(onClick = onToggleHideArchived, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (hideArchived) Icons.Outlined.Archive else Icons.Outlined.Unarchive,
                        if (hideArchived) "显示已归档" else "隐藏已归档",
                        tint = if (hideArchived) ThemeColors.textSecondary else ThemeColors.brand,
                        modifier = Modifier.size(20.dp)
                    )
                }
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

        if (localGroups.isEmpty() && frameworkGroups.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(ArcoSpacing.lg), contentAlignment = Alignment.Center) {
                Text("暂无历史会话\n新会话自动保存", color = ThemeColors.textSecondary, fontSize = 14.sp)
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = ArcoSpacing.sm)
        ) {
            // ── Section: 智能体 (local agents) ──
            if (localGroups.isNotEmpty()) {
                items(localGroups, key = { "local_${it.agentName}" }) { group ->
                    AgentGroupItem(
                        agentName = group.agentName,
                        framework = null,
                        sessions = group.sessions,
                        onSelectSession = onSelectSession,
                        onDeleteSession = onDeleteSession,
                        onCompactSession = onCompactSession,
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
                                onNewSession = onNewSessionFor
                        )
                    }
                }
            }
        }
    }
}
