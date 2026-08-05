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
    onDismiss: () -> Unit,
    onSwitchTo: () -> Unit
) {
    val agentDir = File(com.mengpaw.kernel.DataPaths.AGENTS, agentName)
    val workspacePath = agentDir.absolutePath

    val profile = remember(agentName) { AgentProfile.load(agentName) }
    var editName by remember { mutableStateOf(profile.name.ifBlank { agentName }) }
    var editIntro by remember { mutableStateOf(profile.bio.ifBlank { profile.position.ifBlank { "" } }) }
    var isEditing by remember { mutableStateOf(false) }

    val avatarFile = File(agentDir, "avatar.png")
    val avatarBitmap = remember(agentName) {
        if (avatarFile.exists()) android.graphics.BitmapFactory.decodeFile(avatarFile.absolutePath) else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(strings.agentCardTitle, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    if (isEditing) {
                        val newProfile = profile.copy(name = editName, bio = editIntro)
                        AgentProfile.save(agentName, newProfile)
                    }
                    isEditing = !isEditing
                }) {
                    Text(if (isEditing) strings.cardSave else strings.cardEdit, color = ThemeColors.brand, fontSize = 13.sp)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 400.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (avatarBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = avatarBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(72.dp).clip(CircleShape)
                    )
                } else {
                    Surface(shape = CircleShape, color = ThemeColors.brandContainer, modifier = Modifier.size(72.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(editName.ifBlank { agentName }.take(1), color = ThemeColors.brand,
                                fontWeight = FontWeight.Bold, fontSize = 28.sp)
                        }
                    }
                }
                Spacer(Modifier.height(ArcoSpacing.md))

                if (isEditing) {
                    OutlinedTextField(value = editName, onValueChange = { editName = it },
                        label = { Text(strings.agentCardNameLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(ArcoRadius.md))
                } else {
                    Text(editName.ifBlank { agentName }, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                        color = ThemeColors.textPrimary)
                }
                Spacer(Modifier.height(ArcoSpacing.sm))

                Row(Modifier.fillMaxWidth().background(ThemeColors.bgCardHigh, RoundedCornerShape(ArcoRadius.sm))
                    .padding(ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(14.dp), tint = ThemeColors.textSecondary)
                    Spacer(Modifier.width(6.dp))
                    Text(workspacePath, style = MaterialTheme.typography.labelSmall,
                        color = ThemeColors.textSecondary, fontSize = 11.sp)
                }
                Spacer(Modifier.height(ArcoSpacing.sm))

                Text(strings.agentCardIntroLabel, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                if (isEditing) {
                    OutlinedTextField(value = editIntro, onValueChange = { editIntro = it },
                        label = { Text(strings.agentCardIntroField) }, minLines = 2, maxLines = 4,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(ArcoRadius.md))
                } else {
                    Text(editIntro.ifBlank { strings.agentCardNoIntro }, style = MaterialTheme.typography.bodySmall,
                        color = if (editIntro.isBlank()) ThemeColors.textSecondary.copy(alpha = 0.6f) else ThemeColors.textPrimary)
                }
                Spacer(Modifier.height(ArcoSpacing.md))

                val mdFiles = remember(agentName) {
                    try { agentDir.listFiles()?.filter { it.extension == "md" }?.map { it.name }?.sorted() ?: emptyList() } catch (_: Exception) { emptyList() }
                }
                if (mdFiles.isNotEmpty()) {
                    Text(String.format(strings.agentCardWorkspaceFiles, mdFiles.size), style = MaterialTheme.typography.labelSmall,
                        color = ThemeColors.textSecondary, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Surface(color = ThemeColors.bgCardHigh, shape = RoundedCornerShape(ArcoRadius.sm),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp)) {
                        Column(Modifier.padding(ArcoSpacing.sm).verticalScroll(rememberScrollState())) {
                            mdFiles.forEach { fname ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                                    Icon(Icons.Outlined.Description, null, Modifier.size(12.dp), tint = ThemeColors.textSecondary)
                                    Spacer(Modifier.width(4.dp))
                                    Text(fname, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (agentName != "MengPaw") {
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
                Spacer(Modifier.weight(1f))
                Button(onClick = onSwitchTo, colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.brand),
                    shape = RoundedCornerShape(ArcoRadius.md)) { Text(strings.agentCardSwitch, color = Color.White, fontSize = 13.sp) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.close) } }
    )
}
