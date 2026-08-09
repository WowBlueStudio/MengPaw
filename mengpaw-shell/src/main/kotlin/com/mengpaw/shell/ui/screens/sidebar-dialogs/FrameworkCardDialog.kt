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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.kernel.KernelLog
import com.mengpaw.shell.ui.localization.AppStrings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private val appJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

@Serializable
data class AcpContactFile(
    val name: String = "",
    val address: String = "",
    val frameworkType: String = "mengpaw",
    val addedAt: Long = 0L,
    val remark: String = "",
    val notes: String = ""
)

@Composable
fun FrameworkCardDialog(
    strings: AppStrings,
    frameworkName: String,
    onDismiss: () -> Unit
) {
    // v0.35.1: 可变 peer — 保存备注/切换信任后 UI 实时刷新
    var peer by remember(frameworkName) {
        mutableStateOf(com.mengpaw.plugin.framework.FrameworkPeerStore.findByName(frameworkName))
    }
    val acpFile = File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED, "$frameworkName.json")
    val acpJson = remember(frameworkName) { if (acpFile.exists()) try { appJson.decodeFromString<AcpContactFile>(acpFile.readText()) } catch (_: Exception) { null } else null }

    val fwType = remember(frameworkName, peer) { peer?.frameworkType?.ifBlank { acpJson?.frameworkType ?: "mengpaw" } ?: "mengpaw" }

    val savedRemark = remember(frameworkName, peer) { peer?.remark?.ifBlank { acpJson?.remark?.ifBlank { acpJson?.notes?.ifBlank { "" } } } ?: "" }
    var editRemark by remember { mutableStateOf(savedRemark) }
    var isEditing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(strings.frameworkCardTitle, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                // v0.35.1: 图形按钮 (编辑 ↔ 保存)
                IconButton(onClick = {
                    if (isEditing) {
                        peer?.let { p ->
                            val updated = p.copy(remark = editRemark.trim())
                            com.mengpaw.plugin.framework.FrameworkPeerStore.save(updated)
                            peer = updated
                        }
                        if (acpFile.exists()) {
                            try {
                                val current = appJson.decodeFromString<AcpContactFile>(acpFile.readText())
                                val updated = current.copy(remark = editRemark.trim())
                                val tmp = File(acpFile.parentFile, "$frameworkName.tmp.json"); tmp.writeText(appJson.encodeToString(AcpContactFile.serializer(), updated)); if (acpFile.exists()) acpFile.delete(); tmp.renameTo(acpFile); if (tmp.exists()) try { tmp.delete() } catch (_: Exception) {}; isEditing = false
                            } catch (_: Exception) { KernelLog.w("FrameworkDialog", "update remark json failed") }
                        }
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
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 400.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // 类型图标
                Surface(shape = RoundedCornerShape(ArcoRadius.lg), color = ThemeColors.brandContainer, modifier = Modifier.size(72.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(frameworkTypeIcon(fwType), fwType, Modifier.size(36.dp), tint = ThemeColors.brand)
                    }
                }
                Spacer(Modifier.height(ArcoSpacing.sm))

                // 1. 框架名称 (软件名)
                Text(peer?.frameworkName?.ifBlank { "MengPaw" } ?: "MengPaw",
                    fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = ThemeColors.textPrimary)
                Spacer(Modifier.height(ArcoSpacing.sm))

                // 2. 框架备注名 (可编辑)
                if (isEditing) {
                    Text(strings.frameworkCardRemarkLabel, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = editRemark, onValueChange = { editRemark = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), placeholder = { Text(frameworkName, fontSize = 14.sp) },
                        shape = RoundedCornerShape(ArcoRadius.md))
                } else {
                    val displayRemark = savedRemark.ifBlank { frameworkName }
                    Text(displayRemark, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = ThemeColors.textPrimary)
                }
                Spacer(Modifier.height(ArcoSpacing.sm))

                // 3. 框架所在系统环境
                peer?.let { p ->
                    val platform = p.platform.ifBlank { "" }
                    Row(Modifier.fillMaxWidth().background(ThemeColors.bgCardHigh, RoundedCornerShape(ArcoRadius.sm)).padding(ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Devices, null, Modifier.size(14.dp), tint = ThemeColors.textSecondary)
                        Spacer(Modifier.width(6.dp))
                        Text(if (platform.isNullOrBlank()) "未知" else platform,
                            style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    // 4. 框架名称-版本号
                    Row(Modifier.fillMaxWidth().background(ThemeColors.bgCardHigh, RoundedCornerShape(ArcoRadius.sm)).padding(ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, null, Modifier.size(14.dp), tint = ThemeColors.textSecondary)
                        Spacer(Modifier.width(6.dp))
                        Text("${p.frameworkName.ifBlank { "MengPaw" }} v${p.version}",
                            style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(4.dp))

                    // 5. 智能体列表
                    if (p.agents.isNotEmpty()) {
                        Text(String.format(strings.frameworkCardHostedAgents, p.agents.size), style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        p.agents.forEach { agent ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = CircleShape, modifier = Modifier.size(20.dp), color = ThemeColors.bgCardHigh) {
                                    Box(contentAlignment = Alignment.Center) { Text(agent.take(1), fontSize = 9.sp, color = ThemeColors.textSecondary) }
                                }
                                Spacer(Modifier.width(6.dp)); Text(agent, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textPrimary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val fp = peer?.fingerprint ?: com.mengpaw.plugin.framework.FrameworkPeerStore.findByName(frameworkName)?.fingerprint
                    if (fp != null) com.mengpaw.plugin.framework.FrameworkPeerStore.remove(fp)
                    if (acpFile.exists()) try { acpFile.delete() } catch (_: Exception) { KernelLog.w("FrameworkDialog", "delete acpFile failed") }
                    val twinTrusted = File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED, "${frameworkName}.trusted")
                    if (twinTrusted.exists()) try { twinTrusted.delete() } catch (_: Exception) { KernelLog.w("FrameworkDialog", "delete twinTrusted failed") }
                    val twinKey = File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED, "${frameworkName}.key")
                    if (twinKey.exists()) try { twinKey.delete() } catch (_: Exception) { KernelLog.w("FrameworkDialog", "delete twinKey failed") }
                    onDismiss()
                }) { Text(strings.frameworkCardDelete, color = ArcoColors.Red6, fontSize = 13.sp) }

                // v0.35.1: 信任框架 / 解除信任 按钮 (按当前状态切换)
                peer?.let { p ->
                    if (p.trusted) {
                        TextButton(onClick = {
                            val cur = peer ?: return@TextButton
                            val peerId = cur.fingerprint.ifBlank { frameworkName }
                            try { com.mengpaw.kernel.security.PromptFirewall.untrust(peerId) } catch (_: Exception) {}
                            val twinTrusted = File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED, "${frameworkName}.trusted")
                            if (twinTrusted.exists()) try { twinTrusted.delete() } catch (_: Exception) {}
                            val twinKey = File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED, "${frameworkName}.key")
                            if (twinKey.exists()) try { twinKey.delete() } catch (_: Exception) {}
                            val twinKeyFp = File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED, "${peerId}.key")
                            if (twinKeyFp.exists()) try { twinKeyFp.delete() } catch (_: Exception) {}
                            val updated = cur.copy(trusted = false)
                            com.mengpaw.plugin.framework.FrameworkPeerStore.save(updated)
                            peer = updated
                        }) { Text(strings.frameworkCardUntrust, color = ArcoColors.Orange6, fontSize = 13.sp) }
                    } else {
                        TextButton(onClick = {
                            val cur = peer ?: return@TextButton
                            val updated = cur.copy(trusted = true)
                            com.mengpaw.plugin.framework.FrameworkPeerStore.save(updated)
                            peer = updated
                        }) { Text(strings.frameworkCardTrust, color = ThemeColors.brand, fontSize = 13.sp) }
                    }
                }
            }
        }
    )
}
