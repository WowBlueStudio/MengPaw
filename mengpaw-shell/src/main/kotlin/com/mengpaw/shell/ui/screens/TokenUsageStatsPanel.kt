// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.components.SectionHeader
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.shell.ui.components.TokenBarChart
import com.mengpaw.shell.ui.components.TokenStatsCollector
import com.mengpaw.shell.ui.components.formatTokenCount

/**
 * 系统设置 — Token 用量统计面板 (拆分自 SystemSettingsContent, 独立代码文件)。
 *
 * 统计口径 (v0.37.1 用户定案): 日/周/月切换, 每档查询范围改"全量历史" —
 * 连续序列补 0 值占位 (中间没用量的区间条形必须可见, 不跳空), 图表横向滑动查询。
 * 历史保留上限 (用户定案): 日 90 天 / 周 50 周 / 月 24 月。
 * 统计卡与图表同口径 — 全部历史总量 (原最近 14 天口径导致 "图表有数据但统计卡消失"
 * 的容器底部空位)。
 */
@Composable
fun TokenUsageStatsPanel(state: SettingsState) {
    SectionHeader(state.strings.systemTokenStats)

    val collector = TokenStatsCollector
    val models = collector.allModels()
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

    val (seriesData, cacheData) = remember(statRange) {
        when (statRange) {
            0 -> collector.dailySeries().let { s ->
                s.map { it.date.substring(5) to it.modelTokens } to
                    s.map { it.date.substring(5) to it.cacheHitTokens }
            }
            1 -> collector.weeklySeries().let { s ->
                s.map { it.weekLabel to it.modelTokens } to
                    s.map { it.weekLabel to it.cacheHitTokens }
            }
            2 -> collector.monthlySeries().let { s ->
                s.map { it.weekLabel to it.modelTokens } to
                    s.map { it.weekLabel to it.cacheHitTokens }
            }
            else -> emptyList<Pair<String, Map<String, Long>>>() to emptyList<Pair<String, Long>>()
        }
    }

    if (seriesData.isNotEmpty()) {
        val modelSeries = models.map { model ->
            model to seriesData.map { (label, tokens) -> label to (tokens[model] ?: 0L) }
        }
        TokenBarChart(series = modelSeries, cacheSeries = cacheData,
            emptyLabel = state.strings.systemNoTokenData)
    } else {
        Text(state.strings.systemNoTokenData,
            style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary, modifier = Modifier.padding(vertical = ArcoSpacing.lg))
    }

    // v0.45.0: 第一行 4 项 — 总调用次数 / 输入 Token / 输出 Token / 总 Token
    // 修复: 原 4 卡在 Row 里均 fillMaxWidth 无 weight → 相互抢占/溢出挤压截断。
    // 改为每个 weight(1f) 均分, 卡片内 padding 紧凑, 数字/标题限行防换行撑高。
    val totalTokens = collector.totalTokens()
    val totalCalls = collector.totalCalls()
    val totalPrompt = collector.totalPromptTokens()
    val totalCompletion = collector.totalCompletionTokens()
    if (totalTokens > 0 || totalCalls > 0) {
        Spacer(Modifier.height(ArcoSpacing.md))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
            StatCard(state.strings.systemTotalCalls, formatTokenCount(totalCalls), Icons.Outlined.Call, ArcoColors.Blue1, ArcoColors.Blue6, Modifier.weight(1f))
            StatCard(state.strings.systemInputTokens, formatTokenCount(totalPrompt), Icons.Outlined.ArrowForward, ArcoColors.Green1, ArcoColors.Green6, Modifier.weight(1f))
            StatCard(state.strings.systemOutputTokens, formatTokenCount(totalCompletion), Icons.Outlined.ArrowBack, ArcoColors.Orange1, ArcoColors.Orange6, Modifier.weight(1f))
            StatCard(state.strings.systemTotalUsage, formatTokenCount(totalTokens), Icons.Outlined.BarChart, ArcoColors.Pink1, ArcoColors.Pink6, Modifier.weight(1f))
        }
    }

    // v0.44.3: 按模型统计表格 — 模型名称 / 总调用次数 / 输入 Token / 输出 Token / 总 Token
    val byModel = collector.byModel()
    if (byModel.isNotEmpty()) {
        Spacer(Modifier.height(ArcoSpacing.lg))
        HorizontalDivider(color = ThemeColors.border)
        Spacer(Modifier.height(ArcoSpacing.lg))
        SectionHeader(state.strings.systemModelStats)
        Column(Modifier.fillMaxWidth()) {
            // 表头
            Row(Modifier.fillMaxWidth().padding(vertical = ArcoSpacing.sm)) {
                Text(state.strings.systemModelName, Modifier.weight(1.2f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ThemeColors.textPrimary)
                Text(state.strings.systemTotalCalls, Modifier.weight(0.8f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ThemeColors.textSecondary)
                Text(state.strings.systemInputTokens, Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ThemeColors.textSecondary)
                Text(state.strings.systemOutputTokens, Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ThemeColors.textSecondary)
                Text(state.strings.systemModelTotalTokens, Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ThemeColors.textSecondary)
            }
            HorizontalDivider(color = ThemeColors.border)
            byModel.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = ArcoSpacing.sm)) {
                    Text(m.model, Modifier.weight(1.2f), fontSize = 12.sp, color = ThemeColors.textPrimary)
                    Text(formatTokenCount(m.calls), Modifier.weight(0.8f), fontSize = 12.sp, color = ThemeColors.textSecondary)
                    Text(formatTokenCount(m.promptTokens), Modifier.weight(1f), fontSize = 12.sp, color = ThemeColors.textSecondary)
                    Text(formatTokenCount(m.completionTokens), Modifier.weight(1f), fontSize = 12.sp, color = ThemeColors.textSecondary)
                    Text(formatTokenCount(m.totalTokens), Modifier.weight(1f), fontSize = 12.sp, color = ThemeColors.textPrimary)
                }
            }
        }
    }
}
