// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.browser.data.BrowserPrefs
import com.mengpaw.design.theme.ThemeColors

/** Agent collaboration settings: Quick Click, auto-inject bridge, screenshot config. */
@Composable
fun BrowserAgentSettingsDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    prefs: BrowserPrefs,
    quickClickEnabled: Boolean,
    autoInjectBridge: Boolean,
    screenshotMaxH: Int,
    screenshotQuality: Int,
    onQuickClickToggled: (Boolean) -> Unit,
    onAutoInjectToggled: (Boolean) -> Unit,
    onScreenshotMaxHChanged: (Int) -> Unit,
    onScreenshotQualityChanged: (Int) -> Unit
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("智能体协同设置") },
        text = {
            LazyColumn {
                // Section: MCP bridge status (设备内 MCP 通道)
                item {
                    val mcpOnline = com.mengpaw.browser.mcp.McpHttpServer.isRunning
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("设备内 MCP 桥", fontWeight = FontWeight.Medium)
                            Text("Shell 经 127.0.0.1:${com.mengpaw.kernel.ports.Ports.BROWSER_MCP} 调用浏览器 MCP 工具", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
                        }
                        Text(
                            if (mcpOnline) "● 运行中" else "○ 未运行",
                            color = if (mcpOnline) ThemeColors.primary else ThemeColors.textSecondary,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

                // Section: Quick Click (experimental)
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp), tint = ThemeColors.brand)
                        Spacer(Modifier.width(4.dp))
                        Text("实验性功能", style = MaterialTheme.typography.labelLarge, color = ThemeColors.brand, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("使用全页截图 + 坐标点击替代 CSS 选择器。Vision 模型友好，对 Canvas/Shadow DOM/验证码有效。",
                        style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                    Spacer(Modifier.height(12.dp))
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("快速点击", fontWeight = FontWeight.Medium)
                            Text("browser.screenshot.full + coord.click", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
                        }
                        Switch(checked = quickClickEnabled, onCheckedChange = onQuickClickToggled)
                    }
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

                // Section: Auto-inject bridge
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("自动注入桥接", fontWeight = FontWeight.Medium)
                            Text("每页自动注入 __mp 加速命令 (~33x)", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
                        }
                        Switch(checked = autoInjectBridge, onCheckedChange = onAutoInjectToggled)
                    }
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

                // Section: Screenshot quality
                item {
                    Text("全页截图最大高度", fontWeight = FontWeight.Medium)
                    Text("${screenshotMaxH}px (更大=更完整, 更小=更快)", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
                    Spacer(Modifier.height(4.dp))
                    Slider(
                        value = screenshotMaxH.toFloat(),
                        onValueChange = { onScreenshotMaxHChanged(it.toInt()) },
                        valueRange = 5000f..30000f,
                        steps = 4,
                        colors = SliderDefaults.colors(thumbColor = ThemeColors.brand, activeTrackColor = ThemeColors.brand)
                    )
                }
                item {
                    Text("截图质量", fontWeight = FontWeight.Medium)
                    Text("${screenshotQuality}% (更低=文件更小)", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
                    Spacer(Modifier.height(4.dp))
                    Slider(
                        value = screenshotQuality.toFloat(),
                        onValueChange = { onScreenshotQualityChanged(it.toInt()) },
                        valueRange = 30f..100f,
                        steps = 6,
                        colors = SliderDefaults.colors(thumbColor = ThemeColors.brand, activeTrackColor = ThemeColors.brand)
                    )
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

                // Quick Click workflow tips
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.MenuBook, null, Modifier.size(16.dp), tint = ThemeColors.textSecondary)
                        Spacer(Modifier.width(4.dp))
                        Text("使用流程", fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("1. browser.screenshot.full → 得到全页长图\n2. Vision 模型识别目标坐标\n3. browser.coord.click <x> <y>\n4. browser.coord.scroll <y> 验证位置",
                        style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary, lineHeight = 18.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {}
    )
}
