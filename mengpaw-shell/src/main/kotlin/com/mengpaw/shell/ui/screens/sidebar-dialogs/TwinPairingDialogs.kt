// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.shell.ui.localization.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 记忆孪生配对对话框组 (接收请求 / 验证码 / 5连击确认) — 从 SidebarContent.kt 拆出
 * (2026-08-04, >40KB UI 文件拆分)。
 *
 * 状态契约: twinPairTarget / showTwinConfirmDialog 由 SidebarContent hoisted
 * (框架行 5 连击写入), 此处只读; onDismissTwinConfirm 负责清理两个状态。
 */
@Composable
fun TwinPairingDialogs(
    strings: AppStrings,
    onActivateMemoryTwin: () -> Unit,
    twinPairTarget: FrameworkContact?,
    showTwinConfirmDialog: Boolean,
    onDismissTwinConfirm: () -> Unit
) {
    val scope = rememberCoroutineScope()

    // ═══════════════════════════════════════════════════════════════════
    // 记忆孪生配对请求 (接收方) — 检查 inbox 中的 twin_pair_*.json
    // ═══════════════════════════════════════════════════════════════════
    // 轮询 inbox 中的孪生配对请求 (文件写入不会自动触发 Compose 重组)
    var twinPairFiles by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            val inbox = java.io.File(com.mengpaw.kernel.DataPaths.AGENT_INBOX)
            val files = if (inbox.exists())
                inbox.listFiles()?.filter { it.name.startsWith("twin_pair_") && it.name.endsWith(".json") }?.toList() ?: emptyList()
            else emptyList()
            twinPairFiles = files
            kotlinx.coroutines.delay(2000) // 每2秒检查一次
        }
    }
    twinPairFiles.firstOrNull()?.let { pairFile ->
        val twinData = try { sidebarAppJson.decodeFromString<TwinPairFile>(pairFile.readText()) } catch (_: Exception) { null }
        if (twinData != null) {
            val peerName = twinData.deviceName.ifBlank { twinData.peerId.take(16).ifBlank { strings.unknown } }
            val peerModel = twinData.deviceModel
            val peerId = twinData.peerId
            AlertDialog(
                onDismissRequest = { pairFile.delete() },
                icon = { Icon(Icons.Outlined.Warning, null, tint = ArcoColors.Orange6) },
                title = { Text(strings.twinRequestTitle, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text(strings.twinRequestWarning)
                        Spacer(Modifier.height(12.dp))
                        Text(String.format(strings.twinRequestDevice, peerName), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        if (peerModel.isNotBlank()) {
                            Text(String.format(strings.twinRequestModel, peerModel), style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(strings.twinRequestAgreeDesc, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        // Check if pairing engine already has a session (new protocol)
                        val pairingSession = com.mengpaw.plugin.memorytwin.TwinPairingEngine.getSessionForPeer(peerId)
                        if (pairingSession != null && pairingSession.phase == com.mengpaw.plugin.memorytwin.TwinPairingEngine.PairingPhase.AWAITING_CONFIRM) {
                            // New protocol: pairing engine will handle through verification code dialog
                            android.util.Log.i("MengPawTwin", "使用新配对协议, 等待验证码确认")
                        } else {
                            // Legacy: write trust directly (will be upgraded to new protocol on next sync)
                            val trustedDir = java.io.File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED)
                            trustedDir.mkdirs()
                            java.io.File(trustedDir, "$peerId.trusted").writeText(
                                """{"deviceId":"$peerId","deviceName":"$peerName","pairedAt":${System.currentTimeMillis()}}"""
                            )
                        }
                        pairFile.delete()
                    }) {
                        Text(strings.twinRequestAgree, color = ThemeColors.brand)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pairFile.delete() }) {
                        Text(strings.twinRequestDisagree)
                    }
                }
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 记忆孪生 6位验证码弹窗 (双方) — 观察 TwinPairingEngine StateFlow
    // ═══════════════════════════════════════════════════════════════════
    val twinPairingState by com.mengpaw.plugin.memorytwin.TwinPairingEngine.pairingUiState.collectAsState()
    var showTwinVerifyDialog by remember { mutableStateOf(false) }
    var verifySessionId by remember { mutableStateOf("") }
    var verifyPeerId by remember { mutableStateOf("") }
    var verifyCode by remember { mutableStateOf("") }

    // 当配对引擎状态变为 AWAITING_CONFIRM 时自动弹出验证码对话框
    LaunchedEffect(twinPairingState) {
        if (twinPairingState.phase == com.mengpaw.plugin.memorytwin.TwinPairingEngine.PairingPhase.AWAITING_CONFIRM &&
            twinPairingState.verificationCode.isNotBlank() && !showTwinVerifyDialog) {
            verifySessionId = twinPairingState.sessionId
            verifyPeerId = twinPairingState.peerId
            verifyCode = twinPairingState.verificationCode
            showTwinVerifyDialog = true
        }
        if (twinPairingState.phase == com.mengpaw.plugin.memorytwin.TwinPairingEngine.PairingPhase.ESTABLISHED) {
            showTwinVerifyDialog = false
            com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.appContext?.let { ctx ->
                android.widget.Toast.makeText(
                    ctx,
                    strings.twinPairedToast,
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    if (showTwinVerifyDialog) {
        AlertDialog(
            onDismissRequest = {
                showTwinVerifyDialog = false
                com.mengpaw.plugin.memorytwin.TwinPairingEngine.cancelPairing(verifySessionId)
            },
            icon = { Icon(Icons.Outlined.Security, null, tint = ThemeColors.brand) },
            title = { Text(strings.twinVerifyTitle, fontWeight = FontWeight.Bold) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        strings.twinVerifyDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = ThemeColors.textSecondary
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        verifyCode,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = ThemeColors.brand,
                        letterSpacing = 8.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        String.format(strings.twinVerifyPeer, verifyPeerId.take(16)),
                        style = MaterialTheme.typography.bodySmall,
                        color = ThemeColors.textSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        strings.twinVerifyWarning,
                        style = MaterialTheme.typography.bodySmall,
                        color = ArcoColors.Red6,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showTwinVerifyDialog = false
                    com.mengpaw.plugin.memorytwin.TwinPairingEngine.confirmPairing(verifySessionId)
                }) {
                    Text(strings.twinVerifyConfirm, color = ThemeColors.brand)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showTwinVerifyDialog = false
                    com.mengpaw.plugin.memorytwin.TwinPairingEngine.cancelPairing(verifySessionId)
                }) {
                    Text(strings.cancel, color = ArcoColors.Red6)
                }
            }
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // 记忆孪生配对确认 (发起方) — 5连击触发
    // ═══════════════════════════════════════════════════════════════════
    if (showTwinConfirmDialog) {
        val target = twinPairTarget
        AlertDialog(
            onDismissRequest = onDismissTwinConfirm,
            icon = { Icon(Icons.Outlined.Hub, null, tint = ThemeColors.brand) },
            title = { Text(strings.twinConfirmTitle, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    if (target != null) {
                        Text(strings.twinConfirmIntro)
                        Spacer(Modifier.height(4.dp))
                        Surface(shape = RoundedCornerShape(ArcoRadius.sm),
                            color = ThemeColors.bgCardHigh, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                target.remark.ifBlank { target.name },
                                Modifier.padding(8.dp),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(strings.twinConfirmWarning,
                        style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                }
            },
            confirmButton = {
                val twinAlreadyActive = com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.isActivated
                TextButton(onClick = {
                    onDismissTwinConfirm()
                    val peer = twinPairTarget
                    // 未激活则先激活孪生服务
                    if (!twinAlreadyActive) {
                        onActivateMemoryTwin()
                    }
                    // 发起配对 (已激活的直接配对, 未激活的等 ACP 就绪后配对)
                    if (peer != null) {
                        scope.launch(Dispatchers.IO) {
                            if (!twinAlreadyActive) {
                                // 轮询等待 ACP 就绪 (最多 5 秒)
                                val ready = com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.awaitAcpReady(5000L)
                                if (!ready) {
                                    android.util.Log.w("MengPawTwin", "ACP 未就绪, 放弃配对")
                                    return@launch
                                }
                            }
                            try {
                                val transport = com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.acpTransport
                                if (transport != null) {
                                    val deviceId = try { com.mengpaw.kernel.acp.AcpCrypto.myFingerprint() } catch (_: Exception) { "device-${System.currentTimeMillis()}" }
                                    val ctx = com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.appContext ?: return@launch
                                    val collector = com.mengpaw.plugin.memorytwin.TwinCapabilityCollector(ctx, deviceId, android.os.Build.MODEL ?: "")
                                    val card = collector.collect(null, emptyList())
                                    val result = com.mengpaw.plugin.memorytwin.TwinPairingEngine.initiatePairing(
                                        peerId = peer.name,
                                        myDeviceId = deviceId,
                                        myFingerprint = deviceId,
                                        capabilityCard = card.toJson(),
                                        transport = transport
                                    )
                                    android.util.Log.i("MengPawTwin", "5连击配对发起: session=${result.sessionId} peer=${peer.name}")
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("MengPawTwin", "5连击配对失败: ${e.message}", e)
                            }
                        }
                    }
                }) {
                    Text(strings.twinConfirmAction, color = ThemeColors.brand)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissTwinConfirm) {
                    Text(strings.cancel)
                }
            }
        )
    }
}
