// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.shell.ui.components.PanelOrderStore
import com.mengpaw.shell.ui.localization.AppStrings
import com.mengpaw.shell.ui.screens.model.ExecutionMode
import com.mengpaw.shell.ui.screens.model.InputTag

/**
 * 主界面展开底表 (3 段: 文件提交 / 执行模式 / 插件工具) — 从 MainScreen.kt 拆出
 * (2026-08-04, >40KB UI 文件拆分)。
 * 状态契约: sheetState / activeTags 由 MainScreen hoisted; 文件 picker 经 4 个回调
 * (launcher 留在 MainScreen, pendingUploadDir 写入点随回调闭包上抛)。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreenExpandSheet(
    show: Boolean,
    sheetState: SheetState,
    strings: AppStrings,
    panelOrder: PanelOrderStore.PanelOrder,
    activeTags: List<InputTag>,
    pluginViewModel: PluginViewModel,
    onAddTag: (InputTag) -> Unit,
    onRemoveTag: (InputTag) -> Unit,
    onPluginCommand: (String) -> Unit,
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onPickDocument: () -> Unit,
    onPickFile: () -> Unit,
    onPickCamera: () -> Unit
) {
    if (show) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
            containerColor = ThemeColors.bgPrimary) {
            Column(Modifier.padding(ArcoSpacing.lg).padding(bottom = 32.dp)) {
                // ═══ Section 1: 文件提交 ═══
                Text(strings.expandFileSection, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(ArcoSpacing.md))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ExpandItem(Icons.Outlined.Image, strings.filePickImage) {
                        onDismiss()
                        onPickImage()
                    }
                    ExpandItem(Icons.Outlined.Description, strings.filePickDocument) {
                        onDismiss()
                        onPickDocument()
                    }
                    ExpandItem(Icons.Outlined.AttachFile, strings.filePickFile) {
                        onDismiss()
                        onPickFile()
                    }
                    ExpandItem(Icons.Outlined.PhotoCamera, strings.filePickCamera) {
                        onDismiss()
                        onPickCamera()
                    }
                }
                Spacer(Modifier.height(ArcoSpacing.xl))

                // ═══ Section 2: 执行模式 ═══
                Text(strings.expandModeSection, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(ArcoSpacing.sm))
                val orderedModes = panelOrder.modes.mapNotNull { id ->
                    ExecutionMode.entries.find { it.name.lowercase() == id }
                }.ifEmpty { ExecutionMode.entries.toList() }
                // 两行布局容纳全部模式（7 个 → 4+3）— take(6) 截断曾吞掉 Swarm
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                    orderedModes.forEach { mode ->
                        val isActive = activeTags.any { it is InputTag.Mode && it.mode == mode }
                        ModeItem(mode = mode, isActive = isActive, onClick = {
                            onDismiss()
                            if (isActive) onRemoveTag(InputTag.Mode(mode))
                            else {
                                onAddTag(InputTag.Mode(mode))
                            }
                        })
                    }
                }
                Spacer(Modifier.height(ArcoSpacing.xl))

                // ═══ Section 3: 插件工具 ═══
                Text(strings.expandPluginSection, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(ArcoSpacing.sm))
                val sheetButtons = pluginViewModel.activeButtons[com.mengpaw.kernel.plugin.ButtonPlacement.BOTTOM_SHEET] ?: emptyList()
                val orderedPlugins = panelOrder.plugins.mapNotNull { btnId ->
                    sheetButtons.find { it.id == btnId }
                } + sheetButtons.filter { btn -> btn.id !in panelOrder.plugins }
                if (orderedPlugins.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        orderedPlugins.take(4).forEach { btn ->
                            ExpandItem(pluginIconForName(btn.iconName), btn.label) {
                                onDismiss(); onPluginCommand(btn.command)
                            }
                        }
                    }
                } else {
                    Text("<空>",
                        style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                }
                Spacer(Modifier.height(ArcoSpacing.lg))
            }
        }
    }
}
