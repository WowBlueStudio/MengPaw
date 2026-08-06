// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.design.components.MarkdownText
import com.mengpaw.design.components.SectionHeader
import com.mengpaw.shell.ui.localization.AppStrings

/** 工作区文件面板 — 列表 + 刷新入口 (自 AgentSettingsContent 拆分). */
@Composable
internal fun AgentWorkspaceFilesPanel(
    state: SettingsState,
    workspaceItems: List<FrameworkItem>,
    onRefreshWorkspace: (() -> Unit)?,
    onDeleteWorkspaceFile: ((String) -> Unit)?,
    onResetWorkspaceFile: ((String) -> Unit)?,
    onEditWorkspaceFile: ((String) -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionHeader(state.strings.agentWorkspaceFiles)
        Spacer(Modifier.weight(1f))
        if (onRefreshWorkspace != null) {
            IconButton(onClick = onRefreshWorkspace, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.Refresh, state.strings.agentRefreshFileList, Modifier.size(18.dp), tint = ThemeColors.textSecondary)
            }
        }
    }
    if (workspaceItems.isEmpty()) {
        Text(state.strings.noWorkspaceDocs, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
    } else {
        workspaceItems.forEach { item ->
            key(item.name) {
                WorkspaceItemRow(
                    item = item,
                    onDeleteWorkspaceFile = onDeleteWorkspaceFile,
                    onResetWorkspaceFile = onResetWorkspaceFile,
                    onEditWorkspaceFile = onEditWorkspaceFile,
                    strings = state.strings
                )
            }
        }
    }
}
