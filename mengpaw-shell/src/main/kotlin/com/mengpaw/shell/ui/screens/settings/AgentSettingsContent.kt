// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mengpaw.design.components.SectionHeader
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.shell.ui.localization.AppStrings

/**
 * Agent 设置页 — 按面板拆分为同包子 composable:
 * AgentProviderModelPanel / AgentParamsPanel / AgentTriggersPanel /
 * AgentToolsPanel / AgentSkillsPanel / AgentWorkspaceFilesPanel。
 */
@Composable
fun AgentSettingsContent(
    state: SettingsState,
    viewModel: SettingsViewModel,
    activeEndpoint: String,
    activeModel: String,
    onSelectProvider: ((SavedProvider) -> Unit)?,
    agentToolItems: List<FrameworkItem> = emptyList(),     // Agent 专属工具
    agentSkillItems: List<FrameworkItem> = emptyList(),    // Agent 本地 Skills
    workspaceItems: List<FrameworkItem> = emptyList(),
    onRefreshWorkspace: (() -> Unit)? = null,
    onDeleteWorkspaceFile: ((String) -> Unit)? = null,     // 按文件名删除工作区文档（如 boost.md）
    onResetWorkspaceFile: ((String) -> Unit)? = null,      // 预置文档重置为 APK 内置版
    onOpenWorkspaceFile: ((String) -> Unit)? = null,       // 用其他软件打开工作区文档 (预览)
    activeAgentName: String = "MengPaw"                    // 当前主 Agent — 引导进度按它读取
) {
    // v0.34.3: 引导进度面板已移除 (用户拍板) — 设置页直接从 Provider/模型面板开始
    AgentProviderModelPanel(state, viewModel, activeEndpoint, activeModel, onSelectProvider)

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    AgentParamsPanel(state, viewModel)

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    AgentPermissionPanel(activeAgentName)

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    AgentTriggersPanel(state, viewModel)

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    AgentToolsPanel(state, agentToolItems, activeAgentName)

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    AgentSkillsPanel(state, agentSkillItems, activeAgentName)

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    AgentWorkspaceFilesPanel(
        state = state,
        workspaceItems = workspaceItems,
        onRefreshWorkspace = onRefreshWorkspace,
        onDeleteWorkspaceFile = onDeleteWorkspaceFile,
        onResetWorkspaceFile = onResetWorkspaceFile,
        onOpenWorkspaceFile = onOpenWorkspaceFile,
    )
}
