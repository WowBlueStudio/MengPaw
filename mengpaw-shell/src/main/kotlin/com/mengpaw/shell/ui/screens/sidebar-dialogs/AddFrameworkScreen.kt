// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.plugin.framework.FrameworkDiscovery
import com.mengpaw.plugin.framework.FrameworkPairEngine
import com.mengpaw.plugin.framework.FrameworkPairStore
import com.mengpaw.plugin.framework.FrameworkPeerStore
import com.mengpaw.shell.ui.localization.AppStrings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 添加框架悬浮窗口 (v0.35.1 重构) — 布局参考框架名片:
 * 居中类型图标 + 标题, 无标题栏文字 (右上关闭), 分区卡片:
 * ① 待处理配对请求 (同意/拒绝) ② 刷新局域网发现列表 → 发送配对请求
 * ③ 手动添加 (MengPaw 发请求 / 其他框架本地入册)。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AddFrameworkScreen(strings: AppStrings, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var requests by remember { mutableStateOf(FrameworkPairStore.pending()) }
    var processedCount by remember { mutableStateOf(0) }
    var discovered by remember { mutableStateOf<List<FrameworkPeerStore.FrameworkPeer>>(emptyList()) }
    var isDiscovering by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }

    // 请求列表响应式: 监听 Store 变化 (同意/拒绝/新请求到达即时刷新)
    DisposableEffect(Unit) {
        val listener: () -> Unit = {
            requests = FrameworkPairStore.pending()
            processedCount = FrameworkPairStore.loadAll().count { it.status != FrameworkPairStore.PairStatus.PENDING }
        }
        FrameworkPairStore.addListener(listener)
        onDispose { FrameworkPairStore.removeListener(listener) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // 无标题文字 — 右上角关闭按钮 (与名片同风格)
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, strings.cancel, tint = ThemeColors.textSecondary)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(max = 480.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── 居中类型图标 + 标题 (名片风格) ──
                Surface(shape = RoundedCornerShape(ArcoRadius.lg), color = ThemeColors.brandContainer,
                    modifier = Modifier.size(64.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.DeviceHub, null, Modifier.size(30.dp), tint = ThemeColors.brand)
                    }
                }
                Spacer(Modifier.height(ArcoSpacing.sm))
                Text(strings.addFrameworkTitle, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                    color = ThemeColors.textPrimary)
                Spacer(Modifier.height(ArcoSpacing.lg))
                HorizontalDivider(color = ThemeColors.border)
                Spacer(Modifier.height(ArcoSpacing.sm))

                // ── ① 待处理配对请求 ──
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(strings.pairPendingRequests, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        color = ThemeColors.textPrimary, modifier = Modifier.weight(1f))
                    if (processedCount > 0) {
                        TextButton(onClick = {
                            FrameworkPairStore.clearProcessed()
                            requests = FrameworkPairStore.pending()
                            processedCount = 0
                        }) { Text(strings.pairClearProcessed, fontSize = 11.sp, color = ThemeColors.textSecondary) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (requests.isEmpty()) {
                    Text(strings.pairNoPending, fontSize = 12.sp, color = ThemeColors.textSecondary,
                        modifier = Modifier.fillMaxWidth().padding(vertical = ArcoSpacing.xs))
                } else {
                    requests.forEach { req ->
                        PairRequestCard(strings = strings, req = req, scope = scope) { message ->
                            feedback = message
                        }
                    }
                }
                Spacer(Modifier.height(ArcoSpacing.md))

                // ── ② 刷新局域网发现列表 ──
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(strings.addFrameworkDiscovered, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        color = ThemeColors.textPrimary, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        isDiscovering = true
                        scope.launch {
                            FrameworkDiscovery.instance?.startDiscovery()
                            delay(2500)
                            discovered = FrameworkDiscovery.instance?.discoveredPeers?.toList().orEmpty()
                            isDiscovering = false
                            if (discovered.isEmpty()) feedback = strings.pairScanEmpty
                        }
                    }) {
                        if (isDiscovering) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(strings.addFrameworkScanLan)
                    }
                }
                if (discovered.isEmpty()) {
                    Text(strings.pairScanHint, fontSize = 11.sp, color = ThemeColors.textSecondary,
                        modifier = Modifier.fillMaxWidth().padding(vertical = ArcoSpacing.xs))
                } else {
                    discovered.forEach { peer ->
                        Row(
                            Modifier.fillMaxWidth().background(
                                ThemeColors.bgCardHigh, RoundedCornerShape(ArcoRadius.sm)
                            ).padding(ArcoSpacing.sm).padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ThemeColors.brandContainer,
                                modifier = Modifier.size(28.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Devices, null, Modifier.size(16.dp), tint = ThemeColors.brand)
                                }
                            }
                            Spacer(Modifier.width(ArcoSpacing.sm))
                            Column(Modifier.weight(1f)) {
                                Text(peer.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("${peer.address}:${peer.port} · ${peer.frameworkName} v${peer.version}",
                                    fontSize = 10.sp, color = ThemeColors.textSecondary)
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    val ok = FrameworkPairEngine.sendRequest(
                                        address = peer.address,
                                        port = peer.port,
                                        displayName = FrameworkIdentityDisplay(),
                                        fingerprint = FrameworkIdentityFingerprint()
                                    )
                                    feedback = if (ok) strings.pairRequestSent else strings.pairRequestFailed
                                }
                            }) { Text(strings.add, color = ThemeColors.brand, fontSize = 12.sp) }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                Spacer(Modifier.height(ArcoSpacing.md))

                // ── ③ 手动添加 ──
                Text(strings.pairManualAdd, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    color = ThemeColors.textPrimary, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                ManualAddRow(strings = strings) { name, host, port, type ->
                    scope.launch {
                        // MengPaw 节点发配对请求; 其他框架无 ACP 配对, 直接本地入册
                        if (type == "mengpaw") {
                            val ok = FrameworkPairEngine.sendRequest(
                                address = host, port = port,
                                displayName = FrameworkIdentityDisplay(),
                                fingerprint = FrameworkIdentityFingerprint()
                            )
                            feedback = if (ok) strings.pairRequestSent else strings.pairRequestFailed
                        } else {
                            FrameworkPeerStore.save(
                                FrameworkPeerStore.FrameworkPeer(
                                    fingerprint = FrameworkPeerStore.computeFingerprint(type, "$host:$port"),
                                    name = name, version = "手动添加",
                                    frameworkName = "MengPaw",
                                    address = host, port = port,
                                    frameworkType = type,
                                    lastSeen = System.currentTimeMillis()
                                )
                            )
                            feedback = strings.pairAddedLocal
                        }
                    }
                }

                if (feedback.isNotBlank()) {
                    Spacer(Modifier.height(ArcoSpacing.sm))
                    Text(feedback, fontSize = 12.sp, color = ThemeColors.brand)
                }
            }
        },
        confirmButton = {}
    )
}

/** 待处理配对请求卡片 — 名称/地址/指纹 + 同意/拒绝 (名片风格)。 */
@Composable
private fun PairRequestCard(
    strings: AppStrings,
    req: FrameworkPairStore.PairRequest,
    scope: kotlinx.coroutines.CoroutineScope,
    onFeedback: (String) -> Unit
) {
    Surface(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(ArcoRadius.md),
        color = ThemeColors.bgCardHigh
    ) {
        Column(Modifier.padding(ArcoSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ThemeColors.brandContainer,
                    modifier = Modifier.size(28.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.DeviceHub, null, Modifier.size(16.dp), tint = ThemeColors.brand)
                    }
                }
                Spacer(Modifier.width(ArcoSpacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(req.fromName, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Text("${req.fromAddress}:${req.fromPort} · ${FrameworkPeerStore.shortCodeOf(req.fromFingerprint)}",
                        fontSize = 10.sp, color = ThemeColors.textSecondary)
                }
            }
            Spacer(Modifier.height(ArcoSpacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
                Button(
                    onClick = {
                        scope.launch {
                            val ok = FrameworkPairEngine.accept(req)
                            onFeedback(if (ok) strings.pairAccepted else strings.pairRespondFailed)
                        }
                    },
                    shape = RoundedCornerShape(ArcoRadius.md),
                    colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.brand),
                    contentPadding = PaddingValues(horizontal = ArcoSpacing.md, vertical = 4.dp)
                ) { Text(strings.pairAccept, color = Color.White, fontSize = 12.sp) }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val ok = FrameworkPairEngine.decline(req)
                            onFeedback(if (ok) strings.pairDeclined else strings.pairRespondFailed)
                        }
                    },
                    shape = RoundedCornerShape(ArcoRadius.md),
                    contentPadding = PaddingValues(horizontal = ArcoSpacing.md, vertical = 4.dp)
                ) { Text(strings.pairDecline, fontSize = 12.sp) }
            }
        }
    }
}

/** 本机名片: 自定义名缺省显示指纹短码 (与 mDNS 名片规则一致)。 */
private fun FrameworkIdentityDisplay(): String {
    val identity = com.mengpaw.plugin.framework.FrameworkIdentity
    return identity.displayName.ifBlank { identity.shortCode }
}

private fun FrameworkIdentityFingerprint(): String =
    com.mengpaw.plugin.framework.FrameworkIdentity.fingerprint

/** 手动添加表单 (类型/名称/地址 + 添加)。 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ManualAddRow(
    strings: AppStrings,
    onAdd: (name: String, host: String, port: Int, type: String) -> Unit
) {
    var frameworkType by remember { mutableStateOf("mengpaw") }
    var typeExpanded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }

    val typeOptions = listOf(
        "mengpaw" to "MengPaw (ACP)", "claude-code" to "Claude Code (MCP)",
        "openclaw" to "OpenClaw (WS)", "qwenpaw" to "QwenPaw (REST)",
        "collab-cli" to "collab-cli (FILE)", "custom" to (if (strings.isChinese) "自定义协议" else "Custom Protocol")
    )

    Column {
        ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
            OutlinedTextField(
                value = typeOptions.first { it.first == frameworkType }.second,
                onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth().menuAnchor(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                shape = RoundedCornerShape(ArcoRadius.md)
            )
            ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                typeOptions.forEach { (type, label) ->
                    DropdownMenuItem(text = { Text(label, fontSize = 14.sp) }, onClick = {
                        frameworkType = type; typeExpanded = false
                        val defaultPort = FrameworkPeerStore.FRAMEWORK_TYPES[type] ?: 0
                        if (defaultPort > 0 && address.isBlank()) address = ":$defaultPort"
                    })
                }
            }
        }
        Spacer(Modifier.height(ArcoSpacing.sm))
        OutlinedTextField(value = name, onValueChange = { name = it },
            label = { Text(strings.addFrameworkName) },
            placeholder = { Text(strings.addFrameworkNamePlaceholder) },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(ArcoSpacing.sm))
        OutlinedTextField(value = address, onValueChange = { address = it },
            label = { Text(strings.addFrameworkAddress) },
            placeholder = {
                val defaultPort = FrameworkPeerStore.FRAMEWORK_TYPES[frameworkType] ?: 0
                if (defaultPort > 0) Text(String.format(strings.addFrameworkAddrPortPlaceholder, defaultPort))
                else Text(strings.addFrameworkAddrGenericPlaceholder)
            },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(ArcoSpacing.sm))
        Button(
            onClick = {
                if (name.isNotBlank() && address.isNotBlank()) {
                    val host = address.substringBeforeLast(':').ifBlank { address }
                    val port = address.substringAfterLast(':', "").toIntOrNull()
                        ?: (FrameworkPeerStore.FRAMEWORK_TYPES[frameworkType] ?: 0)
                        .takeIf { it > 0 } ?: com.mengpaw.kernel.ports.Ports.ACP
                    onAdd(name.trim(), host, port, frameworkType)
                    name = ""; address = ""
                }
            },
            enabled = name.isNotBlank() && address.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.brand),
            shape = RoundedCornerShape(ArcoRadius.md),
            modifier = Modifier.fillMaxWidth()
        ) { Text(strings.add, color = Color.White) }
    }
}
