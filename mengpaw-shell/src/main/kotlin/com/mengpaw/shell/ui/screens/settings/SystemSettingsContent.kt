// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.mengpaw.shell.ui.components.TokenLineChart
import com.mengpaw.shell.ui.components.TokenStatsCollector
import com.mengpaw.shell.ui.components.formatTokenCount

@Composable
fun SystemSettingsContent(
    onNavigateToLicense: () -> Unit,
    onNavigateToAttribution: () -> Unit,
    state: SettingsState,
    viewModel: SettingsViewModel,
    onNavigateToPluginMarket: () -> Unit
) {
    SectionHeader(state.strings.appearance)
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { viewModel.cycleThemeMode() },
        shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.bgCardHigh
    ) {
        Row(Modifier.padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.DarkMode, null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(ArcoSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(state.strings.darkTheme, style = MaterialTheme.typography.bodyMedium)
                Text(state.strings.darkThemeDesc, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
            }
            Text(state.themeMode.label, style = MaterialTheme.typography.labelMedium, color = ThemeColors.brand, fontWeight = FontWeight.SemiBold)
        }
    }
    Spacer(Modifier.height(ArcoSpacing.lg))

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.Translate, null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(state.strings.language, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(state.strings.languageDesc, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        OutlinedButton(onClick = { viewModel.toggleLanguage() }, shape = RoundedCornerShape(ArcoRadius.md),
            contentPadding = PaddingValues(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.sm)) {
            Text(if (state.useChinese) state.strings.languageEn else state.strings.languageZh,
                fontWeight = FontWeight.SemiBold, color = ThemeColors.brand)
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.Language, null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text("时区", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text(state.timezone, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        TextButton(onClick = { viewModel.updateTimezone(if (state.timezone == "Asia/Shanghai") java.util.TimeZone.getDefault().id else "Asia/Shanghai") }) {
            Text(if (state.timezone == "Asia/Shanghai") "自动" else "上海")
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    SectionHeader("后台运行")

    val notifyContext = androidx.compose.ui.platform.LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth().clickable {
            viewModel.cycleBackgroundMode()
            com.mengpaw.shell.service.ShellService.refreshNotification(notifyContext)
        },
        shape = RoundedCornerShape(ArcoRadius.md), color = ThemeColors.bgCardHigh
    ) {
        Row(Modifier.padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Notifications, null, tint = ArcoColors.Gray6, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(ArcoSpacing.md))
            Column(Modifier.weight(1f)) {
                Text("后台运行策略", style = MaterialTheme.typography.bodyMedium)
                Text(state.backgroundMode.desc, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
            }
            Text(state.backgroundMode.label, style = MaterialTheme.typography.labelMedium, color = ThemeColors.brand, fontWeight = FontWeight.SemiBold)
        }
    }
    Spacer(Modifier.height(ArcoSpacing.md))

    var powerSaverEnabled by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("后台省电模式", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text("降低后台轮询频率和动画帧率，延长续航", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        Switch(checked = powerSaverEnabled, onCheckedChange = { powerSaverEnabled = it })
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
    val isIgnoring = pm?.isIgnoringBatteryOptimizations("com.mengpaw.shell") == true

    Row(Modifier.fillMaxWidth().padding(vertical = ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (isIgnoring) Icons.Outlined.CheckCircle else Icons.Outlined.BatteryAlert,
            null, Modifier.size(20.dp), tint = if (isIgnoring) ArcoColors.Green6 else ArcoColors.Orange6)
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(if (isIgnoring) "已忽略电池优化" else "电池优化未忽略", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text(if (isIgnoring) "系统不会在息屏时限制后台运行" else "点击跳转系统设置，关闭后可防止息屏限制",
                style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        if (!isIgnoring) {
            TextButton(onClick = {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:com.mengpaw.shell")
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent.resolveActivity(ctx.packageManager) != null) ctx.startActivity(intent)
                    else ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            }) { Text("前往设置 →", style = MaterialTheme.typography.labelSmall, color = ThemeColors.brand) }
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    SectionHeader("Token 用量统计")
    var statRange by remember { mutableIntStateOf(0) }

    Row(Modifier.fillMaxWidth().padding(bottom = ArcoSpacing.sm), horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
        listOf("每日", "每周", "每月").forEachIndexed { i, label ->
            Surface(modifier = Modifier.clickable { statRange = i }, shape = RoundedCornerShape(ArcoRadius.sm),
                color = if (statRange == i) ThemeColors.brand else ThemeColors.bgCard) {
                Text(label, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 12.sp,
                    fontWeight = if (statRange == i) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (statRange == i) Color.White else ThemeColors.textSecondary)
            }
        }
    }

    val collector = TokenStatsCollector
    val models = collector.allModels()
    val chartData = remember(statRange) {
        when (statRange) {
            0 -> collector.dailyRecords().map { it.date.substring(5) to it }
            1 -> collector.weeklyRecords().map { it.weekLabel to it }
            2 -> collector.monthlyRecords().map { it.weekLabel to it }
            else -> emptyList()
        }
    }

    if (chartData.isNotEmpty()) {
        @Suppress("UNCHECKED_CAST")
        val modelSeries = models.map { model ->
            model to chartData.map { (label, record) ->
                val tokens = when (statRange) {
                    0 -> (record as TokenStatsCollector.DayRecord).modelTokens[model] ?: 0L
                    else -> (record as TokenStatsCollector.WeeklySummary).modelTokens[model] ?: 0L
                }
                label to tokens
            }
        }
        val cacheSeries = chartData.map { (label, record) ->
            val cache = when (statRange) {
                0 -> (record as TokenStatsCollector.DayRecord).cacheHitTokens
                else -> (record as TokenStatsCollector.WeeklySummary).cacheHitTokens
            }
            label to cache
        }
        TokenLineChart(series = modelSeries, cacheSeries = cacheSeries)
    } else {
        Text("暂无 Token 用量数据。开始使用后自动记录。",
            style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary, modifier = Modifier.padding(vertical = ArcoSpacing.lg))
    }

    val totalTokens = collector.dailyRecords().sumOf { it.totalTokens }
    val cacheSaved = collector.totalCacheSaved()
    if (totalTokens > 0) {
        Spacer(Modifier.height(ArcoSpacing.md))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
            StatCard("总用量", formatTokenCount(totalTokens), Icons.Outlined.BarChart, ArcoColors.Blue1, ArcoColors.Blue6)
            StatCard("缓存节省", formatTokenCount(cacheSaved), Icons.Outlined.Cached, ArcoColors.Green1, ArcoColors.Green6)
            StatCard("预估节省", "\$" + "%.2f".format(collector.estimatedSavingsUsd()),
                Icons.Outlined.AttachMoney, ArcoColors.Orange1, ArcoColors.Orange6)
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    SectionHeader(state.strings.about)
    InfoRow(state.strings.version, com.mengpaw.kernel.AgentEngine.CORE_VERSION)
    InfoRow(state.strings.core, "mengpaw-core")

    Spacer(Modifier.height(ArcoSpacing.md))
    SectionHeader("法律与联系")

    Row(Modifier.fillMaxWidth().clickable { onNavigateToLicense() }.padding(vertical = ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Description, null, Modifier.size(20.dp), tint = ThemeColors.textSecondary)
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text("许可证", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text("AGPL-3.0 · GNU Affero General Public License v3.0", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = ArcoColors.Gray5)
    }
    Row(Modifier.fillMaxWidth().clickable { onNavigateToAttribution() }.padding(vertical = ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.MenuBook, null, Modifier.size(20.dp), tint = ThemeColors.textSecondary)
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text("开源声明与致谢", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text("代码参考、灵感来源与许可合规", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = ArcoColors.Gray5)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Email, null, Modifier.size(20.dp), tint = ThemeColors.textSecondary)
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text("联系我们", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text("1138018324@qq.com", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Info, null, Modifier.size(20.dp), tint = ThemeColors.textSecondary)
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text("版权声明", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text("© 2026 深圳哇蓝文化科技有限公司", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        color = ThemeColors.brand, modifier = Modifier.padding(bottom = ArcoSpacing.sm))
}
