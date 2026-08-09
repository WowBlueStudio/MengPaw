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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
        // v0.35.1: 去掉"框架名片"标题文字 — 右上角仅编辑/保存图形按钮
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(max = 440.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                // ── 类型图标 ──
                Surface(shape = RoundedCornerShape(ArcoRadius.lg), color = ThemeColors.brandContainer, modifier = Modifier.size(64.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(frameworkTypeIcon(fwType), fwType, Modifier.size(30.dp), tint = ThemeColors.brand)
                    }
                }
                Spacer(Modifier.height(ArcoSpacing.lg))

                // ── 1. 框架名称 (软件名) ──
                Text(peer?.frameworkName?.ifBlank { "MengPaw" } ?: "MengPaw",
                    fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = ThemeColors.textPrimary)
                Spacer(Modifier.height(4.dp))

                // ── 2. 框架备注名 (可编辑) ──
                if (isEditing) {
                    Text(strings.frameworkCardRemarkLabel, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = editRemark, onValueChange = { editRemark = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), placeholder = { Text(frameworkName, fontSize = 14.sp) },
                        shape = RoundedCornerShape(ArcoRadius.md))
                } else {
                    val displayRemark = savedRemark.ifBlank { frameworkName }
                    Text(displayRemark, fontSize = 13.sp, color = ThemeColors.textSecondary)
                }
                Spacer(Modifier.height(ArcoSpacing.lg))

                // ── 3+4. 系统环境 + 名称-版本号 (合一信息卡片) ──
                peer?.let { p ->
                    val platform = p.platform.ifBlank { "" }
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(ArcoRadius.sm), color = ThemeColors.bgCardHigh) {
                        Column(Modifier.padding(ArcoSpacing.sm)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Devices, null, Modifier.size(14.dp), tint = ThemeColors.textSecondary)
                                Spacer(Modifier.width(6.dp))
                                Text(if (platform.isNullOrBlank()) strings.frameworkUnknown else platform,
                                    style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Info, null, Modifier.size(14.dp), tint = ThemeColors.textSecondary)
                                Spacer(Modifier.width(6.dp))
                                Text("${p.frameworkName.ifBlank { "MengPaw" }} v${p.version}",
                                    style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(ArcoSpacing.sm))

                    // ── 5. 智能体列表 (胶囊 chips) ──
                    if (p.agents.isNotEmpty()) {
                        Text(String.format(strings.frameworkCardHostedAgents, p.agents.size), style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        androidx.compose.foundation.layout.FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            p.agents.forEach { agent ->
                                Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ThemeColors.bgCardHigh) {
                                    Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Surface(shape = CircleShape, modifier = Modifier.size(16.dp), color = ThemeColors.brandContainer) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(agent.take(1), fontSize = 8.sp, color = ThemeColors.brand)
                                            }
                                        }
                                        Spacer(Modifier.width(4.dp))
                                        Text(agent, style = MaterialTheme.typography.labelSmall,
                                            color = ThemeColors.textPrimary, fontSize = 11.sp)
                                    }
                                }
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
