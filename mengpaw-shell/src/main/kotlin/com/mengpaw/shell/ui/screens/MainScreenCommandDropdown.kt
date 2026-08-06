// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing

// ── @mention / !bang 命令补全内联下拉 — 拆自 MainScreenInputBar.kt (2026-08-06, 批次4) ──
// 不走 Popup，不干扰输入法; 候选计算 (remember 防 bangCommands 副作用) 与点击应用分离,
// 点击后文本替换/关下拉/加标签/聚焦由调用方 (MainScreenInputBar) 完成。

@Composable
internal fun CommandCompletionDropdown(
    showBangDropdown: Boolean,
    showMentionDropdown: Boolean,
    bangQuery: String,
    mentionQuery: String,
    inputFocus: FocusRequester,
    viewModel: AgentViewModel,
    onApplyBang: (cmdName: String) -> Unit,
    onApplyMention: (name: String) -> Unit
) {
    if (!showMentionDropdown && !showBangDropdown) return

    val bangCandidates = if (showBangDropdown)
        // P2 修复: key 只依赖 bangQuery — bangCommands() 有 buildPipeline 副作用,
        // 放 key 里每次重组都执行（主线程全量重建）
        remember(bangQuery) {
            val all = viewModel.bangCommands()
            val q = bangQuery.trim()
            if (q.isEmpty()) all
            else all.filter {
                it.name.startsWith(q, ignoreCase = true) || it.name.contains(q, ignoreCase = true)
            }
        }
    else emptyList()
    val mentionAgents = if (!showBangDropdown)
        remember(mentionQuery, viewModel.agentNamesForMention().size) {
            viewModel.agentNamesForMention().filter { (name, _) ->
                mentionQuery.isBlank() || name.contains(mentionQuery, ignoreCase = true)
            }
        }
    else emptyList()
    if (bangCandidates.isEmpty() && mentionAgents.isEmpty()) return

    Surface(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = ArcoSpacing.lg),
        shape = RoundedCornerShape(ArcoRadius.md),
        shadowElevation = 6.dp,
        color = ThemeColors.bgPrimary
    ) {
        Column(Modifier.padding(vertical = ArcoSpacing.xs)) {
            if (showBangDropdown) {
                bangCandidates.take(6).forEach { cmd ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onApplyBang(cmd.name) }
                            .padding(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("!${cmd.name}", fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ThemeColors.brand)
                        if (cmd.description.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(cmd.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = ThemeColors.textSecondary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                mentionAgents.take(6).forEach { (name, framework) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onApplyMention(name) }
                            .padding(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = CircleShape, color = ThemeColors.brandContainer,
                            modifier = Modifier.size(24.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(name.take(1), color = ThemeColors.brand,
                                    fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.width(ArcoSpacing.sm))
                        Text("@$name", fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium)
                        if (framework != null) {
                            Spacer(Modifier.width(4.dp))
                            Text("· $framework",
                                style = MaterialTheme.typography.labelSmall,
                                color = ThemeColors.textSecondary)
                        }
                    }
                }
            }
        }
    }
}
