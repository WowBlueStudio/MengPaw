// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

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
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing

@Composable
fun SecurityRulesSection() {
    SectionHeader("安全规则")

    var showTrusted by remember { mutableStateOf(false) }
    val trustedPeers = remember { com.mengpaw.kernel.security.PromptFirewall.listTrusted() }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { showTrusted = !showTrusted },
        shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.bgCard
    ) {
        Column(Modifier.padding(ArcoSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.VerifiedUser, null, Modifier.size(20.dp), tint = ArcoColors.Blue6)
                Spacer(Modifier.width(ArcoSpacing.sm))
                Column(Modifier.weight(1f)) {
                    Text("框架信任列表", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text("已信任 ${trustedPeers.size} 个框架设备", fontSize = 12.sp, color = ThemeColors.textSecondary)
                }
                Icon(if (showTrusted) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = ThemeColors.textSecondary)
            }
            AnimatedVisibility(visible = showTrusted) {
                Column(Modifier.padding(top = ArcoSpacing.sm)) {
                    if (trustedPeers.isEmpty()) {
                        Text("暂无受信任的框架设备。通过 ACP 配对添加。", fontSize = 12.sp, color = ThemeColors.textSecondary)
                    } else {
                        trustedPeers.forEach { peerId ->
                            val fingerprint = remember(peerId) {
                                try { java.io.File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED, "$peerId.trusted").readText().take(16) } catch (_: Exception) { "—" }
                            }
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Devices, null, Modifier.size(16.dp), tint = ArcoColors.Green6)
                                Spacer(Modifier.width(ArcoSpacing.sm))
                                Column(Modifier.weight(1f)) {
                                    Text(peerId, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("指纹: $fingerprint", fontSize = 11.sp, color = ThemeColors.textSecondary)
                                }
                                Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Green1) {
                                    Text("已信任", Modifier.padding(horizontal = 6.dp, vertical = 1.dp), fontSize = 10.sp, color = ArcoColors.Green6)
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
                Text("内核完整性防护", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text("已启用 — 阻止 Agent 执行危险命令", fontSize = 12.sp, color = ThemeColors.textSecondary)
            }
        }
    }

    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.bgCard) {
        Row(Modifier.padding(ArcoSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Shield, null, Modifier.size(20.dp), tint = ArcoColors.Green6)
            Spacer(Modifier.width(ArcoSpacing.sm))
            Column(Modifier.weight(1f)) {
                Text("插件完整性防护", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text("已启用 — 验证插件签名和版本兼容性", fontSize = 12.sp, color = ThemeColors.textSecondary)
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
                    Text("文件完整性防护", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text("已启用 — 保护核心目录不被修改", fontSize = 12.sp, color = ThemeColors.textSecondary)
                }
            }
            AnimatedVisibility(visible = showProtectedPaths) {
                Column(Modifier.padding(top = ArcoSpacing.sm)) {
                    Text("受保护的目录：", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ThemeColors.textSecondary)
                    Spacer(Modifier.height(4.dp))
                    val paths = listOf(
                        "内核目录" to "/data/data/com.mengpaw/core",
                        "Agent 文档" to com.mengpaw.kernel.DataPaths.AGENTS,
                        "插件缓存" to com.mengpaw.kernel.DataPaths.PLUGIN_CACHE,
                        "密钥存储" to "/data/data/com.mengpaw/shared_prefs/mengpaw_vault"
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

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        color = ThemeColors.brand, modifier = Modifier.padding(bottom = ArcoSpacing.sm))
}
