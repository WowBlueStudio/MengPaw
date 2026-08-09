// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import com.mengpaw.kernel.KernelLog
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.design.components.SectionHeader
import com.mengpaw.shell.ui.localization.AppStrings

@Composable
fun SecurityRulesSection(
    strings: AppStrings = com.mengpaw.shell.ui.localization.EnglishStrings
) {
    SectionHeader(strings.securityRules)

    var showTrusted by remember { mutableStateOf(false) }
    var trustVersion by remember { mutableStateOf(0) }
    // v0.34.3 修复"未起效": 框架信任列表以 FrameworkPeerStore.trusted 为准
    // (侧边栏/框架命令操作的真实信任源) — 此前只读 ACP 配对信任, 侧边栏信任的
    // 框架不显示且无操作按钮。ACP 配对 (PromptFirewall) 作为次级展示。
    // 修复: 折叠时不再返回 emptyList — 计数"已信任 N 个框架设备"折叠/展开保持一致
    val frameworkTrusted = remember(trustVersion) {
        com.mengpaw.plugin.framework.FrameworkPeerStore.loadAll().filter { it.trusted }
    }
    val acpTrusted = remember(showTrusted, trustVersion) {
        if (!showTrusted) emptyList()
        else com.mengpaw.kernel.security.PromptFirewall.listTrusted()
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { showTrusted = !showTrusted },
        shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.bgCard
    ) {
        Column(Modifier.padding(ArcoSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.VerifiedUser, null, Modifier.size(20.dp), tint = ArcoColors.Blue6)
                Spacer(Modifier.width(ArcoSpacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(strings.securityTrustedFramework, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text(String.format(strings.securityTrustedCount, frameworkTrusted.size), fontSize = 12.sp, color = ThemeColors.textSecondary)
                }
                Icon(if (showTrusted) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = ThemeColors.textSecondary)
            }
            AnimatedVisibility(visible = showTrusted) {
                Column(Modifier.padding(top = ArcoSpacing.sm)) {
                    if (frameworkTrusted.isEmpty() && acpTrusted.isEmpty()) {
                        Text(strings.securityNoTrusted, fontSize = 12.sp, color = ThemeColors.textSecondary)
                    } else {
                        // ── 框架通讯录信任 (可撤销) ──
                        frameworkTrusted.forEach { peer ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Devices, null, Modifier.size(16.dp), tint = ArcoColors.Green6)
                                Spacer(Modifier.width(ArcoSpacing.sm))
                                Column(Modifier.weight(1f)) {
                                    Text(peer.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("${peer.address}:${peer.port} · ${com.mengpaw.plugin.framework.FrameworkPeerStore.shortCodeOf(peer.fingerprint)}",
                                        fontSize = 11.sp, color = ThemeColors.textSecondary)
                                }
                                TextButton(onClick = {
                                    com.mengpaw.plugin.framework.FrameworkPeerStore.save(peer.copy(trusted = false))
                                    trustVersion++
                                }) {
                                    Text(if (strings.isChinese) "撤销" else "Untrust",
                                        fontSize = 12.sp, color = ArcoColors.Red6)
                                }
                            }
                        }
                        // ── ACP 已配对设备 (次级, 可解除) ──
                        if (acpTrusted.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(if (strings.isChinese) "ACP 已配对设备" else "ACP paired devices",
                                fontSize = 11.sp, color = ThemeColors.textSecondary)
                            acpTrusted.forEach { peerId ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Lock, null, Modifier.size(14.dp), tint = ArcoColors.Green6)
                                    Spacer(Modifier.width(ArcoSpacing.sm))
                                    Text(peerId, fontSize = 12.sp, color = ThemeColors.textPrimary,
                                        modifier = Modifier.weight(1f), maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    TextButton(onClick = {
                                        com.mengpaw.kernel.security.PromptFirewall.untrust(peerId)
                                        trustVersion++
                                    }) {
                                        Text(if (strings.isChinese) "解除" else "Untrust",
                                            fontSize = 12.sp, color = ArcoColors.Red6)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.bgCard) {
        Row(Modifier.padding(ArcoSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Security, null, Modifier.size(20.dp), tint = ArcoColors.Green6)
            Spacer(Modifier.width(ArcoSpacing.sm))
            Column(Modifier.weight(1f)) {
                Text(strings.securityKernelIntegrity, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(strings.securityKernelIntegrityDesc, fontSize = 12.sp, color = ThemeColors.textSecondary)
            }
        }
    }

    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.bgCard) {
        Row(Modifier.padding(ArcoSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Shield, null, Modifier.size(20.dp), tint = ArcoColors.Green6)
            Spacer(Modifier.width(ArcoSpacing.sm))
            Column(Modifier.weight(1f)) {
                Text(strings.securityPluginIntegrity, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(strings.securityPluginIntegrityDesc, fontSize = 12.sp, color = ThemeColors.textSecondary)
            }
        }
    }

    var showProtectedPaths by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { showProtectedPaths = !showProtectedPaths },
        shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.bgCard
    ) {
        Column(Modifier.padding(ArcoSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Folder, null, Modifier.size(20.dp), tint = ArcoColors.Green6)
                Spacer(Modifier.width(ArcoSpacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(strings.securityFileIntegrity, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text(strings.securityFileIntegrityDesc, fontSize = 12.sp, color = ThemeColors.textSecondary)
                }
                // 展开标识（此前可展开但无 chevron 提示 — 用户看不到可点）
                Icon(if (showProtectedPaths) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null, tint = ThemeColors.textSecondary, modifier = Modifier.size(20.dp))
            }
            AnimatedVisibility(visible = showProtectedPaths) {
                Column(Modifier.padding(top = ArcoSpacing.sm)) {
                    Text(strings.securityProtectedDirs, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ThemeColors.textSecondary)
                    Spacer(Modifier.height(4.dp))
                    val paths = listOf(
                        strings.securityKernelDir to "/data/data/com.mengpaw/core",
                        strings.securityAgentDocs to com.mengpaw.kernel.DataPaths.AGENTS,
                        strings.securityPluginCache to com.mengpaw.kernel.DataPaths.PLUGIN_CACHE,
                        strings.securityKeyStore to "/data/data/com.mengpaw/shared_prefs/mengpaw_vault"
                    )
                    paths.forEach { (label, path) ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Icon(Icons.Outlined.Lock, null, Modifier.size(12.dp), tint = ArcoColors.Gray5)
                            Spacer(Modifier.width(6.dp))
                            Text("$label  ", fontSize = 11.sp, color = ThemeColors.textSecondary)
                            Text(path, fontSize = 11.sp, color = ArcoColors.Gray5)
                        }
                    }
                }
            }
        }
    }
}
