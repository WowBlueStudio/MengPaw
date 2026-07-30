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
                    Text(strings.securityTrustedFramework, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text(String.format(strings.securityTrustedCount, trustedPeers.size), fontSize = 12.sp, color = ThemeColors.textSecondary)
                }
                Icon(if (showTrusted) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = ThemeColors.textSecondary)
            }
            AnimatedVisibility(visible = showTrusted) {
                Column(Modifier.padding(top = ArcoSpacing.sm)) {
                    if (trustedPeers.isEmpty()) {
                        Text(strings.securityNoTrusted, fontSize = 12.sp, color = ThemeColors.textSecondary)
                    } else {
                        trustedPeers.forEach { peerId ->
                            val fingerprint = remember(peerId) {
                                try { java.io.File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED, "$peerId.trusted").readText().take(16) } catch (e: Exception) { KernelLog.w("SecurityRules", "read fingerprint failed: ${e.message}"); "—" }
                            }
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Devices, null, Modifier.size(16.dp), tint = ArcoColors.Green6)
                                Spacer(Modifier.width(ArcoSpacing.sm))
                                Column(Modifier.weight(1f)) {
                                    Text(peerId, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text(String.format(strings.securityFingerprint, fingerprint), fontSize = 11.sp, color = ThemeColors.textSecondary)
                                }
                                Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Green1) {
                                    Text(strings.securityTrusted, Modifier.padding(horizontal = 6.dp, vertical = 1.dp), fontSize = 10.sp, color = ArcoColors.Green6)
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
