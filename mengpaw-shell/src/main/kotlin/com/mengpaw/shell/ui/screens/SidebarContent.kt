// SPDX-FileCopyrightText: 2026 MengPaw
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing

/**
 * Left sidebar — Agent switcher + ACP contacts.
 */
@Composable
fun SidebarContent(
    onNavigateToPlugins: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onClose: () -> Unit
) {
    var activeAgent by remember { mutableStateOf("MengPaw") }

    Column(Modifier.fillMaxHeight().width(280.dp).padding(ArcoSpacing.lg)) {
        // ── Agent Switcher ──
        Text("多 Agent", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(ArcoSpacing.sm))

        val agents = remember { listOf("MengPaw", "代理B", "代理C") }
        agents.forEach { name ->
            Row(Modifier.fillMaxWidth().clickable { activeAgent = name }
                .padding(vertical = ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = if (name == activeAgent) ThemeColors.brand else ThemeColors.bgCardHigh) {
                    Text(name.take(1), modifier = Modifier.padding(8.dp), color = if (name == activeAgent) androidx.compose.ui.graphics.Color.White else ThemeColors.textSecondary, fontSize = 14.sp)
                }
                Spacer(Modifier.width(ArcoSpacing.sm))
                Text(name, fontWeight = if (name == activeAgent) FontWeight.SemiBold else FontWeight.Normal, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                if (name == activeAgent) {
                    Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ThemeColors.brand.copy(alpha = 0.15f)) {
                        Text("当前", Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = ThemeColors.brand)
                    }
                }
            }
        }
        OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(ArcoRadius.md)) {
            Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("新建 Agent", style = MaterialTheme.typography.labelSmall)
        }

        HorizontalDivider(Modifier.padding(vertical = ArcoSpacing.lg))

        // ── ACP Contacts ──
        Text("ACP 通讯录", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(ArcoSpacing.sm))

        val contacts = remember {
            listOf(
                Triple("平板 Agent", "192.168.0.100:9876", true),
                Triple("同事的工作站", "192.168.0.101:9876", false)
            )
        }
        contacts.forEach { (name, addr, online) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).then(
                    Modifier.background(if (online) ThemeColors.brand else ThemeColors.bgCardHigh, CircleShape)))
                Spacer(Modifier.width(ArcoSpacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodySmall)
                    Text(addr, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
                }
                if (online) {
                    Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ThemeColors.brand.copy(alpha = 0.1f)) {
                        Text("在线", Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = ThemeColors.brand)
                    }
                }
            }
        }
        OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(ArcoRadius.md)) {
            Icon(Icons.Outlined.PersonAdd, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("发现设备 (ACP)", style = MaterialTheme.typography.labelSmall)
        }

        HorizontalDivider(Modifier.padding(vertical = ArcoSpacing.lg))

        // ── Quick Nav ──
        Text("功能", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(ArcoSpacing.sm))
        SidebarNavItem(Icons.Outlined.Extension, "插件管理", onClick = onNavigateToPlugins)
        SidebarNavItem(Icons.Outlined.Settings, "设置", onClick = onNavigateToSettings)
        SidebarNavItem(Icons.Outlined.Terminal, "CLI 参考", onClick = {})

        Spacer(Modifier.weight(1f))
        Text("MengPaw v0.2 · ACP 已启用", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, modifier = Modifier.padding(bottom = ArcoSpacing.sm))
    }
}

@Composable
private fun SidebarNavItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = ThemeColors.textSecondary)
        Spacer(Modifier.width(ArcoSpacing.sm))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
