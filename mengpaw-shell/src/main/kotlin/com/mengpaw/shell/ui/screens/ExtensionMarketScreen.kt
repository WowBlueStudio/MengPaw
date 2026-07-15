package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing

/**
 * Extension market — browse and install MengPaw extensions.
 * Shows available extensions from the ecosystem.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionMarketScreen(onNavigateBack: () -> Unit) {
    // Built-in extension definitions
    val extensions = remember {
        listOf(
            ExtensionItem("MengPaw Browser", "com.mengpaw.browser", "Chromium 浏览器扩展，支持多页面并发、CDP 控制、悬浮窗", "1.0.0", true),
            ExtensionItem("MengPaw Device", "com.mengpaw.device", "设备操控扩展，支持点击、滑动、截图、ADB Shell", "0.1.0", false),
            ExtensionItem("MengPaw Code", "com.mengpaw.code", "代码执行扩展，支持 JS/Python 沙箱执行、文件生成", "0.1.0", false),
            ExtensionItem("MengPaw Network", "com.mengpaw.network", "高级网络工具，WebSocket 支持、请求拦截、代理配置", "0.1.0", false),
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Storefront, null, tint = ArcoColors.Blue6, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(ArcoSpacing.sm))
                Text("扩展市场", fontWeight = FontWeight.SemiBold)
            }},
            navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "返回") }},
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ArcoColors.BgPrimary))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = ArcoSpacing.lg)) {
            Spacer(Modifier.height(ArcoSpacing.md))

            Text("扩展让 Agent 拥有更多能力。安装扩展后可在设置中配置。",
                style = MaterialTheme.typography.bodyMedium, color = ArcoColors.TextSecondary)
            Spacer(Modifier.height(ArcoSpacing.lg))

            extensions.forEach { ext ->
                ExtensionCard(ext)
                Spacer(Modifier.height(ArcoSpacing.sm))
            }

            Spacer(Modifier.height(ArcoSpacing.xxxl))
        }
    }
}

private data class ExtensionItem(
    val name: String,
    val packageName: String,
    val description: String,
    val version: String,
    val installed: Boolean
)

@Composable
private fun ExtensionCard(ext: ExtensionItem) {
    Card(
        shape = RoundedCornerShape(ArcoRadius.lg),
        colors = CardDefaults.cardColors(containerColor = ArcoColors.BgPrimary),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.padding(ArcoSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Extension, null, tint = ArcoColors.Blue6, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(ArcoSpacing.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ext.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(ArcoSpacing.sm))
                    Text("v${ext.version}", style = MaterialTheme.typography.labelSmall, color = ArcoColors.TextSecondary)
                    Spacer(Modifier.width(ArcoSpacing.sm))
                    if (ext.installed) {
                        Surface(shape = RoundedCornerShape(ArcoRadius.sm), color = ArcoColors.Green1) {
                            Text("已安装", modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall, color = ArcoColors.Green6)
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(ext.description, style = MaterialTheme.typography.bodySmall, color = ArcoColors.TextSecondary)
                Text(ext.packageName, style = MaterialTheme.typography.labelSmall, color = ArcoColors.Gray5)
            }
            if (!ext.installed) {
                OutlinedButton(
                    onClick = { /* TODO: download and install */ },
                    shape = RoundedCornerShape(ArcoRadius.md),
                    contentPadding = PaddingValues(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.xs)
                ) { Text("安装", color = ArcoColors.Blue6) }
            }
        }
    }
}
