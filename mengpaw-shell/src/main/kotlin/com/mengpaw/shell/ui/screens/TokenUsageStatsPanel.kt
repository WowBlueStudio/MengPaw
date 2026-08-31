// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Cached
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

    val totalTokens = collector.totalTokens()
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
}
