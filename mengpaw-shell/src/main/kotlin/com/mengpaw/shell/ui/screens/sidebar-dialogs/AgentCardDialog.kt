// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.kernel.agent.AgentProfile
import com.mengpaw.shell.ui.localization.AppStrings
import java.io.File

@Composable
fun AgentCardDialog(
    strings: AppStrings,
    agentName: String,
    onDismiss: () -> Unit
) {
    val agentDir = File(com.mengpaw.kernel.DataPaths.AGENTS, agentName)
    val workspacePath = agentDir.absolutePath
    // v0.35.1: 显示短路径 — 去掉 BASE 前缀 (如 "Agent文档/MengPaw"), 非 BASE 下保留原样
    val shortWorkspace = workspacePath.removePrefix(com.mengpaw.kernel.DataPaths.BASE + "/")

    val profile = remember(agentName) { AgentProfile.load(agentName) }
    var editName by remember { mutableStateOf(profile.name.ifBlank { agentName }) }
    var editIntro by remember { mutableStateOf(profile.bio.ifBlank { profile.position.ifBlank { "" } }) }
    var isEditing by remember { mutableStateOf(false) }

    val avatarFile = File(agentDir, "avatar.png")
    val avatarBitmap = remember(agentName) {
        if (avatarFile.exists()) decodeSampled(avatarFile.absolutePath, maxDim = 256) else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // v0.35.1: 去掉"智能体名片"标题文字 — 右上角仅编辑/保存图形按钮
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = {
                    if (isEditing) {
                        val newProfile = profile.copy(name = editName, bio = editIntro)
                        AgentProfile.save(agentName, newProfile)
                    }
                    isEditing = !isEditing
                }) {
                    Icon(if (isEditing) Icons.Outlined.Check else Icons.Outlined.Edit,
                        contentDescription = if (isEditing) strings.cardSave else strings.cardEdit,
                        tint = ThemeColors.brand)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(max = 440.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── 头像 ──
                if (avatarBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = avatarBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(84.dp).clip(CircleShape)
                    )
                } else {
                    Surface(shape = CircleShape, color = ThemeColors.brandContainer, modifier = Modifier.size(84.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(editName.ifBlank { agentName }.take(1), color = ThemeColors.brand,
                                fontWeight = FontWeight.Bold, fontSize = 32.sp)
                        }
                    }
                }
                Spacer(Modifier.height(ArcoSpacing.lg))

                // ── 名称 ──
                if (isEditing) {
                    OutlinedTextField(value = editName, onValueChange = { editName = it },
                        label = { Text(strings.agentCardNameLabel) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.titleMedium,
                        shape = RoundedCornerShape(ArcoRadius.md))
                } else {
                    Text(editName.ifBlank { agentName }, fontWeight = FontWeight.SemiBold, fontSize = 20.sp,
                        color = ThemeColors.textPrimary)
                }
                Spacer(Modifier.height(ArcoSpacing.md))

                // ── 简介 ──
                if (isEditing) {
                    OutlinedTextField(value = editIntro, onValueChange = { editIntro = it },
                        label = { Text(strings.agentCardIntroField) }, minLines = 2, maxLines = 4,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(ArcoRadius.md))
                } else {
                    Text(editIntro.ifBlank { strings.agentCardNoIntro },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (editIntro.isBlank()) ThemeColors.textSecondary.copy(alpha = 0.6f)
                            else ThemeColors.textPrimary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Spacer(Modifier.height(ArcoSpacing.lg))

                HorizontalDivider(color = ThemeColors.border)
                Spacer(Modifier.height(ArcoSpacing.sm))

                // ── 工作目录 ──
                Row(Modifier.fillMaxWidth().background(ThemeColors.bgCardHigh, RoundedCornerShape(ArcoRadius.sm))
                    .padding(ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(14.dp), tint = ThemeColors.textSecondary)
                    Spacer(Modifier.width(6.dp))
                    Text(shortWorkspace, style = MaterialTheme.typography.labelSmall,
                        color = ThemeColors.textSecondary, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            // v0.35.1: 去掉"切换到此智能体"与"关闭"; 底部仅保留删除 (主 Agent 不可删)
            if (agentName != com.mengpaw.shell.ui.screens.DEFAULT_AGENT_NAME) {
                var showDeleteConfirm by remember { mutableStateOf(false) }
                TextButton(onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(strings.agentCardDelete, fontSize = 13.sp)
                }
                if (showDeleteConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirm = false },
                        title = { Text(strings.agentCardDeleteTitle) },
                        text = { Text(String.format(strings.agentCardDeleteBody, agentName)) },
                        confirmButton = {
                            TextButton(onClick = {
                                val agentDir = File(com.mengpaw.kernel.DataPaths.AGENTS, agentName)
                                if (agentDir.exists()) agentDir.deleteRecursively()
                                showDeleteConfirm = false; onDismiss()
                            }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(strings.delete, fontWeight = FontWeight.Bold) }
                        },
                        dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text(strings.cancel) } }
                    )
                }
            }
        }
    )
}
