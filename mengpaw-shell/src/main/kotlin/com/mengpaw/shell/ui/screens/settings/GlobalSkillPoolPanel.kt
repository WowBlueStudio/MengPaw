// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.components.MarkdownText
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.shell.ui.localization.AppStrings

/**
 * 全局技能池面板 — 技能行含 来源标签(核心/插件)/@指定按钮/删除按钮(仅非预置)。
 *
 * - 来源标签: frontmatter `source:` — "core"/"plugin" = 预置技能 (框架维护, 无删除按钮);
 *   空 = 用户自建/Agent 进化/后续新注册 (可删除)。
 * - @ 指定: 写入 PinnedSkills 清单 (.pinned), PromptSystemBuilder 注入「用户指定技能」
 *   指针段 — LLM 不用 skill.ls 遍历, 直接 skill.run。技能全文不注入前缀。
 * - 删除: 非预置技能直接删文件; 若在指定清单中同步移除 (防悬空指针)。
 */
@Composable
internal fun GlobalSkillPoolPanel(
    items: List<FrameworkItem>,
    strings: AppStrings
) {
    if (items.isEmpty()) {
        Text(strings.noEntries, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        Spacer(Modifier.height(ArcoSpacing.sm))
        return
    }

    var pinnedNames by remember { mutableStateOf(com.mengpaw.kernel.skill.PinnedSkills.list().toSet()) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    // 本地可变列表 — 删除后即时移除行; 重开/重扫时由 items 重建 (文件已删, 结果一致)
    val list = remember(items) { items.toMutableStateList() }

    list.forEach { item ->
        key(item.name) {
            var expanded by remember { mutableStateOf(false) }
            val isPreset = item.source == "core" || item.source == "plugin"
            val isPinned = item.name in pinnedNames
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.bgCard,
                onClick = { expanded = !expanded }
            ) {
                Column(Modifier.padding(ArcoSpacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp),
                            tint = if (isPreset) ArcoColors.Blue6 else ArcoColors.Orange6)
                        Spacer(Modifier.width(ArcoSpacing.sm))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(item.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = ThemeColors.textPrimary)
                                if (isPreset) {
                                    Spacer(Modifier.width(6.dp))
                                    // 预置技能来源标签 — 核心/插件 (框架维护, 不可删)
                                    Surface(shape = RoundedCornerShape(ArcoRadius.sm),
                                        color = if (item.source == "core") ArcoColors.Blue6.copy(alpha = 0.1f) else ArcoColors.Orange6.copy(alpha = 0.1f)) {
                                        Text(
                                            if (item.source == "core") strings.skillSourceCore else strings.skillSourcePlugin,
                                            Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                            fontSize = 10.sp,
                                            color = if (item.source == "core") ArcoColors.Blue6 else ArcoColors.Orange6
                                        )
                                    }
                                }
                                if (isPinned) {
                                    Spacer(Modifier.width(4.dp))
                                    // 📌 已指定标记
                                    Icon(Icons.Outlined.PushPin, strings.skillPin, Modifier.size(12.dp), tint = ArcoColors.Orange6)
                                }
                            }
                            if (item.summary.isNotBlank()) Text(item.summary, fontSize = 12.sp, color = ThemeColors.textSecondary, maxLines = 1)
                        }
                        // @ 指定按钮 — 用户手动指定该技能, LLM 免遍历直接 skill.run
                        IconButton(onClick = {
                            com.mengpaw.kernel.skill.PinnedSkills.toggle(item.name)
                            pinnedNames = com.mengpaw.kernel.skill.PinnedSkills.list().toSet()
                        }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Outlined.PushPin, strings.skillPin, Modifier.size(16.dp),
                                tint = if (isPinned) ArcoColors.Orange6 else ThemeColors.textSecondary)
                        }
                        // 删除按钮 — 仅非预置技能 (用户自建/进化/新注册); 预置技能框架维护不可删
                        if (!isPreset) {
                            IconButton(onClick = { deleteTarget = item.name }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Outlined.Delete, strings.delete, Modifier.size(16.dp), tint = ThemeColors.textSecondary)
                            }
                        }
                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            null, Modifier.size(18.dp), tint = ThemeColors.textSecondary)
                    }
                    AnimatedVisibility(visible = expanded) {
                        if (item.docMarkdown.isNotBlank()) {
                            MarkdownText(content = item.docMarkdown.take(5000), modifier = Modifier.padding(top = ArcoSpacing.sm),
                                nestedScroll = true)
                        } else {
                            Text(strings.noDocs, Modifier.padding(top = ArcoSpacing.sm), fontSize = 12.sp, color = ThemeColors.textSecondary)
                        }
                    }
                }
            }
        }
    }

    // 删除确认 — 非预置技能可删; 若在指定清单中同步移除
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(strings.deleteDoc, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) },
            text = { Text(strings.deleteConfirm, fontSize = 13.sp, color = ThemeColors.textSecondary, lineHeight = 20.sp) },
            confirmButton = {
                TextButton(onClick = {
                    val f = java.io.File(com.mengpaw.kernel.DataPaths.SKILLS, "$target.md")
                    f.delete()
                    com.mengpaw.kernel.skill.PinnedSkills.remove(target)
                    list.removeAll { it.name == target }
                    deleteTarget = null
                }) { Text(strings.delete, color = ArcoColors.Red6) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(strings.cancel) } }
        )
    }
}
