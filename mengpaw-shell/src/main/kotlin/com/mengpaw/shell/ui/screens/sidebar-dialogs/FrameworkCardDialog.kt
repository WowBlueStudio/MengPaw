// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

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
import java.io.File

@Composable
fun FrameworkCardDialog(
    frameworkName: String,
    onDismiss: () -> Unit
) {
    val peer = remember(frameworkName) { com.mengpaw.plugin.framework.FrameworkPeerStore.findByName(frameworkName) }
    val acpFile = File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED, "$frameworkName.json")
    val acpJson = remember(frameworkName) { if (acpFile.exists()) try { org.json.JSONObject(acpFile.readText()) } catch (_: Exception) { null } else null }

    val fwType = remember(frameworkName, peer) { peer?.frameworkType?.ifBlank { acpJson?.optString("frameworkType", "mengpaw") } ?: "mengpaw" }
    val proto = com.mengpaw.plugin.framework.FrameworkPeerStore.PROTOCOL_LABELS[fwType]
    val protoLabel = proto?.first ?: "?"
    val protoMode = proto?.second ?: ""

    val savedRemark = remember(frameworkName, peer) { peer?.remark?.ifBlank { acpJson?.optString("remark", "")?.ifBlank { acpJson?.optString("notes", "") ?: "" } } ?: "" }
    var editRemark by remember { mutableStateOf(savedRemark) }
    var isEditing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("框架名片", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    if (isEditing) {
                        if (peer != null) com.mengpaw.plugin.framework.FrameworkPeerStore.save(peer.copy(remark = editRemark.trim()))
                        if (acpFile.exists()) {
                            try {
                                val updated = org.json.JSONObject(acpFile.readText()); updated.put("remark", editRemark.trim())
                                val tmp = File(acpFile.parentFile, "$frameworkName.tmp.json"); tmp.writeText(updated.toString()); tmp.renameTo(acpFile); if (tmp.exists()) tmp.delete()
                            } catch (_: Exception) {}
                        }
                    }
                    isEditing = !isEditing
                }) { Text(if (isEditing) "保存" else "编辑", color = ThemeColors.brand, fontSize = 13.sp) }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 400.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(shape = RoundedCornerShape(ArcoRadius.lg), color = ThemeColors.brandContainer, modifier = Modifier.size(72.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(frameworkTypeIcon(fwType), fwType, Modifier.size(36.dp), tint = ThemeColors.brand)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ThemeColors.bgCardHigh) {
                    Text("$protoLabel · $protoMode", Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, fontSize = 10.sp)
                }
                Spacer(Modifier.height(ArcoSpacing.sm))

                if (isEditing) {
                    Text("备注名称", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = editRemark, onValueChange = { editRemark = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), placeholder = { Text(frameworkName, fontSize = 14.sp) },
                        shape = RoundedCornerShape(ArcoRadius.md))
                } else {
                    val displayRemark = savedRemark.ifBlank { frameworkName }
                    Text(displayRemark, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = ThemeColors.textPrimary)
                    if (savedRemark.isNotBlank()) Text(frameworkName, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, fontSize = 11.sp)
                }
                Spacer(Modifier.height(ArcoSpacing.sm))

                if (peer != null) {
                    Row(Modifier.fillMaxWidth().background(ThemeColors.bgCardHigh, RoundedCornerShape(ArcoRadius.sm)).padding(ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, null, Modifier.size(14.dp), tint = ThemeColors.textSecondary)
                        Spacer(Modifier.width(6.dp)); Text("版本: ${peer.version}", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                }

                val addr = peer?.address ?: acpJson?.optString("address", "") ?: ""
                if (addr.isNotBlank()) {
                    Row(Modifier.fillMaxWidth().background(ThemeColors.bgCardHigh, RoundedCornerShape(ArcoRadius.sm)).padding(ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Language, null, Modifier.size(14.dp), tint = ThemeColors.textSecondary)
                        Spacer(Modifier.width(6.dp)); Text("${addr}:${peer?.port ?: 9876}", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                }

                if (peer != null) {
                    val online = peer.lastSeen > System.currentTimeMillis() - 120_000
                    Row(Modifier.fillMaxWidth().background(ThemeColors.bgCardHigh, RoundedCornerShape(ArcoRadius.sm)).padding(ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(if (online) ArcoColors.Green6 else ArcoColors.Gray5, CircleShape))
                        Spacer(Modifier.width(6.dp)); Text(if (online) "在线" else "离线", style = MaterialTheme.typography.labelSmall, color = if (online) ArcoColors.Green6 else ThemeColors.textSecondary, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth().background(ThemeColors.bgCardHigh, RoundedCornerShape(ArcoRadius.sm)).padding(ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Security, null, Modifier.size(14.dp), tint = if (peer.trusted) ArcoColors.Green6 else ArcoColors.Orange6)
                        Spacer(Modifier.width(6.dp)); Text(if (peer.trusted) "已信任" else "未信任", style = MaterialTheme.typography.labelSmall, color = if (peer.trusted) ArcoColors.Green6 else ArcoColors.Orange6, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                }

                val agentList = peer?.agents ?: emptyList()
                if (agentList.isNotEmpty()) {
                    Text("托管智能体 (${agentList.size})", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    agentList.forEach { agent ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = CircleShape, modifier = Modifier.size(20.dp), color = ThemeColors.bgCardHigh) {
                                Box(contentAlignment = Alignment.Center) { Text(agent.take(1), fontSize = 9.sp, color = ThemeColors.textSecondary) }
                            }
                            Spacer(Modifier.width(6.dp)); Text(agent, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textPrimary, fontSize = 12.sp)
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
                    if (acpFile.exists()) try { acpFile.delete() } catch (_: Exception) {}
                    val twinTrusted = File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED, "${frameworkName}.trusted")
                    if (twinTrusted.exists()) try { twinTrusted.delete() } catch (_: Exception) {}
                    val twinKey = File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED, "${frameworkName}.key")
                    if (twinKey.exists()) try { twinKey.delete() } catch (_: Exception) {}
                    onDismiss()
                }) { Text("删除框架", color = ArcoColors.Red6, fontSize = 13.sp) }

                if (peer != null && !peer.trusted) {
                    TextButton(onClick = { com.mengpaw.plugin.framework.FrameworkPeerStore.save(peer.copy(trusted = true)); onDismiss() }) { Text("信任此框架", color = ThemeColors.brand, fontSize = 13.sp) }
                }

                val twinActive = com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.isActivated
                if (peer != null && peer.trusted && twinActive) {
                    TextButton(onClick = {
                        val peerId = peer.fingerprint.ifBlank { frameworkName }
                        com.mengpaw.kernel.security.PromptFirewall.untrust(peerId)
                        val twinTrusted = File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED, "${frameworkName}.trusted")
                        if (twinTrusted.exists()) try { twinTrusted.delete() } catch (_: Exception) {}
                        val twinKey = File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED, "${frameworkName}.key")
                        if (twinKey.exists()) try { twinKey.delete() } catch (_: Exception) {}
                        val twinKeyFp = File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED, "${peerId}.key")
                        if (twinKeyFp.exists()) try { twinKeyFp.delete() } catch (_: Exception) {}
                        com.mengpaw.plugin.framework.FrameworkPeerStore.save(peer.copy(trusted = false))
                        android.util.Log.i("MengPawTwin", "解除孪生: $frameworkName")
                        onDismiss()
                    }) { Text("解除孪生", color = ArcoColors.Orange6, fontSize = 13.sp) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
