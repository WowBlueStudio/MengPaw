// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing

/** Color palette for chart lines — distinct hues per model line. */
private val chartColors = listOf(
    ArcoColors.Blue6,
    ArcoColors.Green6,
    ArcoColors.Orange6,
    ArcoColors.Red6,
    ArcoColors.ChartPurple,
    ArcoColors.ChartCyan,
    ArcoColors.ChartPink,
)
private val cacheColor = ArcoColors.Gray6

/**
 * Token 用量柱状图 (v0.35.1 用户定案) — 每日期一根堆叠柱 (模型分段着色),
 * 固定柱宽 22dp + 间隙 8dp, 日期从左往右排列; 超出容器宽度时横向滚动,
 * 数据更新自动拉到最右侧 (最新日期)。
 */
@Composable
fun TokenBarChart(
    series: List<Pair<String, List<Pair<String, Long>>>>,  // (model/line name, [(label, value)]) 
    cacheSeries: List<Pair<String, Long>> = emptyList(),    // cache hit data
    emptyLabel: String = "暂无数据",
    modifier: Modifier = Modifier
) {
    val dataSize = series.firstOrNull()?.second?.size ?: 0
    if (dataSize == 0) {
        Box(modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            Text(emptyLabel, color = ThemeColors.textSecondary, fontSize = 14.sp)
        }
        return
    }

    // v0.39.0 动画增强: 柱状生长 — 首次进入/数据变化时从 0 生长到 1 (FastOutSlowIn 缓动)
    // started 门控: animateFloatAsState 首次组合直接取目标值, 必须经状态翻转触发动画
    var growStarted by remember { mutableStateOf(false) }
    val growProgress by animateFloatAsState(
        targetValue = if (growStarted) 1f else 0f,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "tokenBarGrow"
    )
    LaunchedEffect(dataSize) { growStarted = true }

    val labels = series.first().second.map { it.first }

    Column(modifier) {
        // Legend — 模型色点 + 缓存节省 (静态)
        Row(
            Modifier.fillMaxWidth().padding(bottom = ArcoSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.md)
        ) {
            series.forEachIndexed { i, (name, _) ->
                LegendDot(chartColors[i % chartColors.size], name)
            }
            if (cacheSeries.isNotEmpty()) {
                LegendDot(cacheColor, "缓存节省")
            }
        }

        // 固定柱宽 22dp + 间隙 8dp — 数据量超过容器宽度时横向滚动
        val scrollState = rememberScrollState()
        LaunchedEffect(dataSize) {
            // 等布局完成 → 自动拉到最右侧 (最新日期); 内容不超出容器 (maxValue==0) 时直接返回
            kotlinx.coroutines.delay(80)
            if (scrollState.maxValue > 0) scrollState.scrollTo(scrollState.maxValue)
        }
        val barWidth = 22.dp
        val barGap = 8.dp
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // 内容总宽 = 数据量 × (柱宽+间隙), 至少铺满容器
            val contentWidth = ((barWidth + barGap) * dataSize - barGap).coerceAtLeast(maxWidth)
            Row(Modifier.horizontalScroll(scrollState)) {
                Canvas(
                    Modifier.width(contentWidth).height(220.dp)
                        .background(ThemeColors.bgCard, RoundedCornerShape(ArcoRadius.md))
                        .padding(horizontal = 8.dp, vertical = 12.dp)
                ) {
                    val w = size.width
                    val h = size.height
                    val padLeft = 44f
                    val padRight = 8f
                    val padTop = 12f
                    val padBottom = 30f
                    val chartW = w - padLeft - padRight
                    val chartH = h - padTop - padBottom

                    // Y 轴 0 起动态上限: 每日堆叠总量最大值 × 1.15
                    val totals = (0 until dataSize).map { i ->
                        series.sumOf { it.second.getOrNull(i)?.second ?: 0L }
                    }
                    val rawMax = (totals.maxOrNull() ?: 1L).coerceAtLeast(1L)
                    val yMax = rawMax * 115 / 100
                    val yScale = chartH / yMax.toFloat()

                    val textPaint = android.graphics.Paint().apply {
                        color = 0xFF86909C.toInt() // ArcoColors.Gray6
                        textSize = 22f
                        isAntiAlias = true
                    }
                    // Y 刻度 (0..yMax 4 格) + 网格
                    for (i in 0..4) {
                        val yVal = yMax * i / 4
                        val y = padTop + chartH - yVal * yScale
                        drawContext.canvas.nativeCanvas.drawText(formatTokenCount(yVal), 2f, y + 6f, textPaint)
                        drawLine(ArcoColors.Gray3, Offset(padLeft, y), Offset(w - padRight, y), strokeWidth = 1f)
                    }

                    // 堆叠柱 — 每日一根, 模型分段着色 (自底向上)
                    val barWidthPx = barWidth.toPx()
                    val barGapPx = barGap.toPx()
                    (0 until dataSize).forEach { i ->
                        val x = padLeft + i * (barWidthPx + barGapPx)
                        var acc = 0f
                        series.forEachIndexed { sIdx, (_, sData) ->
                            val v = sData.getOrNull(i)?.second ?: 0L
                            // 柱高乘生长进度 — 图表出现时逐段从 0 长高 (v0.39.0 动画增强)
                            val barH = v * yScale * growProgress
                            if (barH > 0.5f) {
                                drawRoundRect(
                                    chartColors[sIdx % chartColors.size],
                                    topLeft = Offset(x, padTop + chartH - acc - barH),
                                    size = Size(barWidthPx, barH),
                                    cornerRadius = CornerRadius(3f, 3f)
                                )
                                acc += barH
                            } else {
                                // v0.37.1 重构 (用户定案): 0 值条形必须可见 — 该区间无用量
                                // 也是信息, 画 2f 高浅灰占位条, 不跳空不产生"底部空位"错觉。
                                val ph = 2f * growProgress
                                drawRoundRect(
                                    ArcoColors.Gray4,
                                    topLeft = Offset(x, padTop + chartH - ph),
                                    size = Size(barWidthPx, ph),
                                    cornerRadius = CornerRadius(1f, 1f)
                                )
                            }
                        }
                        // 日期标签 (间隔显示, 最后一天必显示)
                        if (i % maxOf(1, dataSize / 6) == 0 || i == dataSize - 1) {
                            drawContext.canvas.nativeCanvas.drawText(
                                labels[i].takeLast(5),
                                x - 14f, h - 2f, textPaint
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String, enabled: Boolean = true, onClick: () -> Unit = {}) {
    Row(
        Modifier.clickable(enabled = enabled) { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(10.dp)) {
            drawCircle(color.copy(alpha = if (enabled) 1f else 0.3f), 5f, Offset(5f, 5f))
        }
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = if (enabled) ThemeColors.textSecondary else ArcoColors.Gray4)
    }
}

fun formatTokenCount(n: Long): String = when {
    n >= 1_000_000 -> "${"%.1f".format(n / 1_000_000.0)}M"
    n >= 1_000 -> "${"%.1f".format(n / 1_000.0)}K"
    else -> n.toString()
}
