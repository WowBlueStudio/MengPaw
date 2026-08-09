// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.kernel.KernelLog
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.shell.ui.components.TokenBarChart
import com.mengpaw.shell.ui.components.TokenStatsCollector
import com.mengpaw.shell.ui.components.formatTokenCount
import com.mengpaw.design.components.SectionHeader
import kotlinx.coroutines.delay

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
            Text(if (state.useChinese) state.themeMode.label else state.themeMode.enLabel, style = MaterialTheme.typography.labelMedium, color = ThemeColors.brand, fontWeight = FontWeight.SemiBold)
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

    // v0.34.3: 时区选项删除 — 跟随系统即可, 无手动切换意义

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    SectionHeader(state.strings.systemBackground)

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
                Text(state.strings.systemBackgroundMode, style = MaterialTheme.typography.bodyMedium)
                Text(if (state.useChinese) state.backgroundMode.desc else state.backgroundMode.enDesc, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
            }
            Text(if (state.useChinese) state.backgroundMode.label else state.backgroundMode.enLabel, style = MaterialTheme.typography.labelMedium, color = ThemeColors.brand, fontWeight = FontWeight.SemiBold)
        }
    }
    Spacer(Modifier.height(ArcoSpacing.md))

    // 修复: 原为局部 remember 状态 — 切换即丢且不接任何真实逻辑（假开关）。
    // 接线到 SettingsViewModel 持久化设置（CONFIG/power_saver），重启恢复；
    // 偏好同时经 self.config 对 Agent 可见。
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(state.strings.systemBackgroundPowerSaver, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(state.strings.systemBackgroundPowerSaverDesc, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
        Switch(checked = state.powerSaverEnabled, onCheckedChange = { viewModel.togglePowerSaver() })
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
    val isIgnoring = pm?.isIgnoringBatteryOptimizations("com.mengpaw.shell") == true

    Row(Modifier.fillMaxWidth().padding(vertical = ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (isIgnoring) Icons.Outlined.CheckCircle else Icons.Outlined.BatteryAlert,
            null, Modifier.size(20.dp), tint = if (isIgnoring) ArcoColors.Green6 else ArcoColors.Orange6)
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(if (isIgnoring) state.strings.systemBatteryIgnored else state.strings.systemBatteryNotIgnored, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text(if (isIgnoring) state.strings.systemBatteryIgnoredDesc else state.strings.systemBatteryNotIgnoredDesc,
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
                } catch (_: Exception) { KernelLog.w("SystemSettings", "start battery settings intent failed") }
            }) { Text(state.strings.systemGoToBatterySettings, style = MaterialTheme.typography.labelSmall, color = ThemeColors.brand) }
        }
    }

    // ── 输出目录 (v0.35.1 独立区块): 点击整块 → 系统文件管理器打开目录;
    //    未授权 (不可写) → 点击跳『所有文件访问』授权页 ──
    SectionHeader(state.strings.outputDirTitle)
    var outPath by remember { mutableStateOf(com.mengpaw.kernel.DataPaths.OUTPUT) }
    LaunchedEffect(Unit) {
        while (true) {
            outPath = com.mengpaw.kernel.DataPaths.OUTPUT
            delay(2000)
        }
    }
    val outDir = java.io.File(outPath)
    val outWritable = outDir.canWrite()
    Surface(
        modifier = Modifier.fillMaxWidth().clickable {
            if (outWritable) openOutputDir(ctx, outDir)
            else startAllFilesAccess(ctx)
        },
        shape = RoundedCornerShape(ArcoRadius.md),
        color = ThemeColors.bgCardHigh
    ) {
        Row(Modifier.padding(ArcoSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (outWritable) Icons.Outlined.FolderOpen else Icons.Outlined.Warning,
                null, Modifier.size(24.dp), tint = if (outWritable) ArcoColors.Green6 else ArcoColors.Orange6)
            Spacer(Modifier.width(ArcoSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(state.strings.outputDirTitle,
                    fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                Text(outPath,
                    style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!outWritable) {
                    Text(state.strings.outputDirUnwritable,
                        style = MaterialTheme.typography.labelSmall, color = ArcoColors.Orange6)
                }
            }
            if (outWritable) {
                Icon(Icons.Outlined.ChevronRight, null,
                    tint = ThemeColors.textSecondary, modifier = Modifier.size(20.dp))
            } else {
                TextButton(onClick = { startAllFilesAccess(ctx) }) {
                    Text(state.strings.outputDirGrant, style = MaterialTheme.typography.labelSmall, color = ThemeColors.brand)
                }
            }
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
    HorizontalDivider(color = ThemeColors.border)
    Spacer(Modifier.height(ArcoSpacing.lg))

    SectionHeader(state.strings.systemTokenStats)
    var statRange by remember { mutableIntStateOf(0) }

    Row(Modifier.fillMaxWidth().padding(bottom = ArcoSpacing.sm), horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
        listOf(state.strings.systemDaily, state.strings.systemWeekly, state.strings.systemMonthly).forEachIndexed { i, label ->
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
        TokenBarChart(series = modelSeries, cacheSeries = cacheSeries,
            emptyLabel = state.strings.systemNoTokenData)
    } else {
        Text(state.strings.systemNoTokenData,
            style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary, modifier = Modifier.padding(vertical = ArcoSpacing.lg))
    }

    val totalTokens = collector.dailyRecords().sumOf { it.totalTokens }
    val cacheSaved = collector.totalCacheSaved()
    if (totalTokens > 0) {
        Spacer(Modifier.height(ArcoSpacing.md))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
            StatCard(state.strings.systemTotalUsage, formatTokenCount(totalTokens), Icons.Outlined.BarChart, ArcoColors.Blue1, ArcoColors.Blue6)
            StatCard(state.strings.systemCacheSaved, formatTokenCount(cacheSaved), Icons.Outlined.Cached, ArcoColors.Green1, ArcoColors.Green6)
            StatCard(state.strings.systemEstimatedSavings, "\$" + "%.2f".format(collector.estimatedSavingsUsd()),
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
    SectionHeader(state.strings.systemLegalContact)

    Row(Modifier.fillMaxWidth().clickable { onNavigateToLicense() }.padding(vertical = ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Description, null, Modifier.size(20.dp), tint = ThemeColors.textSecondary)
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(state.strings.systemLicense, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text("AGPL-3.0 · GNU Affero General Public License v3.0", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = ArcoColors.Gray5)
    }
    Row(Modifier.fillMaxWidth().clickable { onNavigateToAttribution() }.padding(vertical = ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.MenuBook, null, Modifier.size(20.dp), tint = ThemeColors.textSecondary)
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(state.strings.systemAttribution, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text(state.strings.systemAttributionDesc, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = ArcoColors.Gray5)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Email, null, Modifier.size(20.dp), tint = ThemeColors.textSecondary)
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(state.strings.systemContactUs, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text("1138018324@qq.com", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = ArcoSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Info, null, Modifier.size(20.dp), tint = ThemeColors.textSecondary)
        Spacer(Modifier.width(ArcoSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(state.strings.systemCopyright, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text(state.strings.systemCopyrightDesc, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
        }
    }

    Spacer(Modifier.height(ArcoSpacing.lg))
}

/**
 * 打开系统文件管理器定位到输出目录 (v0.35.1) —
 * Android 8+ DocumentsUI 支持 EXTRA_INITIAL_URI 初始定位; 公共存储用
 * primary:<相对路径> 文档 URI, 私有路径 (旧 Android/data 回退) 用 file:// 初值。
 * 不可用 (部分设备 DocumentsUI 无定位) 时兜底打开外部存储根。
 */
private fun openOutputDir(context: android.content.Context, dir: java.io.File) {
    try {
        val uri = try {
            val p = dir.absolutePath
            if (p.startsWith("/storage/emulated/0/")) {
                android.provider.DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:${p.removePrefix("/storage/emulated/0/")}"
                )
            } else android.net.Uri.fromFile(dir)
        } catch (_: Exception) { null }
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            if (android.os.Build.VERSION.SDK_INT >= 26 && uri != null) {
                putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, uri)
            }
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            return
        }
    } catch (_: Exception) {}
    try {
        // 兜底: 打开外部存储根目录
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            data = android.provider.DocumentsContract.buildRootUri(
                "com.android.externalstorage.documents", "primary"
            )
            type = "vnd.android.document/root"
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
    } catch (_: Exception) {}
}

/** 跳转『所有文件访问』授权页 (MANAGE_EXTERNAL_STORAGE)。 */
private fun startAllFilesAccess(context: android.content.Context) {
    try {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
        ).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
        else {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    } catch (_: Exception) {
        KernelLog.w("SystemSettings", "start all-files-access settings failed")
    }
}
