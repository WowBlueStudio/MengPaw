// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private val appJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

@Serializable
data class FrameworkFileData(
    val name: String = "",
    val address: String = "",
    val frameworkType: String = "mengpaw",
    val addedAt: Long = 0L
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFrameworkDialog(onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var frameworkType by remember { mutableStateOf("mengpaw") }
    var typeExpanded by remember { mutableStateOf(false) }
    var isDiscovering by remember { mutableStateOf(false) }
    var discovered by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    val typeOptions = listOf(
        "mengpaw" to "MengPaw (ACP)", "claude-code" to "Claude Code (MCP)",
        "trea-ide" to "Trea IDE (MCP)", "trea-work" to "Trea Work (MCP)",
        "cursor" to "Cursor (MCP)", "opencode" to "OpenCode (MCP)",
        "reasonix" to "Reasonix (MCP)", "workbuddy" to "Workbuddy (MCP)",
        "openclaw" to "OpenClaw (WS)", "qclaw" to "Qclaw (WS)",
        "hermes" to "Hermes (WS)", "codex" to "Codex (WS)",
        "qwenpaw" to "QwenPaw (REST)", "coze" to "Coze (REST)",
        "collab-cli" to "collab-cli (FILE)", "kimi-desktop" to "Kimi Desktop (?)",
        "custom" to "自定义协议"
    )
    val typeLabels = mapOf(
        "mengpaw" to "MengPaw · ACP · 端口 9876 · mDNS 自动发现 · 双向实时",
        "claude-code" to "Claude Code · MCP · JSON-RPC · 手动配置 · 单向实时",
        "trea-ide" to "Trea IDE · MCP · JSON-RPC · 手动配置 · 单向实时",
        "trea-work" to "Trea Work · MCP · JSON-RPC · 云端执行 · 单向实时",
        "cursor" to "Cursor · MCP · JSON-RPC · IDE 扩展 · 单向实时",
        "opencode" to "OpenCode · MCP · JSON-RPC · 手动配置 · 单向实时",
        "reasonix" to "Reasonix · MCP · JSON-RPC · MCP 插件 · 单向实时",
        "workbuddy" to "Workbuddy · MCP · JSON-RPC · MCP 连接器 · 单向实时",
        "openclaw" to "OpenClaw · WebSocket · 端口 18789 · 手动配置 · 单向实时",
        "qclaw" to "Qclaw · WebSocket · 端口 18789 · OpenClaw 衍生 · 单向实时",
        "hermes" to "Hermes · WebSocket · Gateway 模式 · 单向实时",
        "codex" to "Codex · Unix Socket · 本地进程 · 单向实时",
        "qwenpaw" to "QwenPaw · REST · FastAPI HTTP · 手动配置 · 单向轮询",
        "coze" to "Coze · REST · 云端 API · 单向轮询",
        "collab-cli" to "collab-cli · FILE · 文件系统共享 · UDP 广播 :9528 · 双向 · MIT 开源",
        "kimi-desktop" to "Kimi Desktop · 协议待验证 · Electron 桌面应用",
        "custom" to "自定义框架 · 手动配置协议和端口"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加框架", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(typeLabels[frameworkType] ?: "", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                Spacer(Modifier.height(ArcoSpacing.md))

                Text("框架类型", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
                Spacer(Modifier.height(4.dp))
                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                    OutlinedTextField(
                        value = typeOptions.first { it.first == frameworkType }.second,
                        onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth().menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                        shape = RoundedCornerShape(ArcoRadius.md))
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        typeOptions.forEach { (type, label) ->
                            DropdownMenuItem(text = { Text(label, fontSize = 14.sp) }, onClick = {
                                frameworkType = type; typeExpanded = false
                                val defaultPort = com.mengpaw.plugin.framework.FrameworkPeerStore.FRAMEWORK_TYPES[type] ?: 0
                                if (defaultPort > 0 && address.isBlank()) address = ":$defaultPort"
                            })
                        }
                    }
                }

                Spacer(Modifier.height(ArcoSpacing.sm))
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("框架名称") }, placeholder = { Text("如: 办公室电脑") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(ArcoSpacing.sm))
                OutlinedTextField(value = address, onValueChange = { address = it },
                    label = { Text("地址 (可选)") },
                    placeholder = {
                        val defaultPort = com.mengpaw.plugin.framework.FrameworkPeerStore.FRAMEWORK_TYPES[frameworkType] ?: 0
                        if (defaultPort > 0) Text("如: 192.168.1.100:$defaultPort") else Text("如: localhost:8080 或 /path/to/socket")
                    },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(ArcoSpacing.sm))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { isDiscovering = true; discovered = com.mengpaw.plugin.framework.FrameworkPeerStore.loadAll().map { it.name to "${it.address}:${it.port}" }; isDiscovering = false }) {
                        if (isDiscovering) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(4.dp)) }
                        Text("扫描局域网")
                    }
                }
                if (discovered.isNotEmpty()) {
                    Spacer(Modifier.height(ArcoSpacing.sm))
                    Text("发现的设备:", style = MaterialTheme.typography.labelSmall)
                    discovered.forEach { (n, addr) -> TextButton(onClick = { name = n; address = addr }) { Text("$n ($addr)", fontSize = 12.sp) } }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        val trustedDir = File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED)
                        trustedDir.mkdirs()
                        val fwFile = File(trustedDir, "$name.json")
                        val tmp = File(trustedDir, "$name.tmp.json")
                        val fwData = FrameworkFileData(name = name, address = address, frameworkType = frameworkType, addedAt = System.currentTimeMillis())
                        try { tmp.writeText(appJson.encodeToString(FrameworkFileData.serializer(), fwData)); if (fwFile.exists()) fwFile.delete(); tmp.renameTo(fwFile); if (tmp.exists()) try { tmp.delete() } catch (_: Exception) { KernelLog.w("AddFramework", "delete tmp failed") }; onDismiss() } catch (e: Exception) { ErrorCollector.report(e, "SidebarContent.saveFramework") }
                    }
                },
                enabled = name.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.brand),
                shape = RoundedCornerShape(ArcoRadius.md)
            ) { Text("添加", color = Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
