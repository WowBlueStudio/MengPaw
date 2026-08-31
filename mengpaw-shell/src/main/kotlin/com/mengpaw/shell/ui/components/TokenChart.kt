// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
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

/** 品牌色渐变对 (深→浅) — Chart.js 渐变柱风格, 色相保持 Arco 品牌色, 浅端向白 lerp 55%。 */
private fun gradientPair(color: Color): Pair<Color, Color> = color to lerp(color, Color.White, 0.55f)

/** 堆叠柱圆角路径 — Chart.js borderRadius 语义: 仅边缘段圆角 (顶部段上角/底部段下角), 中间段直角。 */
private fun barPath(
    left: Float, top: Float, right: Float, bottom: Float,
    radius: Float, roundTop: Boolean, roundBottom: Boolean
): Path = Path().apply {
    val r = radius.coerceAtMost((right - left) / 2f).coerceAtMost((bottom - top) / 2f)
    if (!roundTop && !roundBottom) {
        addRect(androidx.compose.ui.geometry.Rect(left, top, right, bottom))
        return@apply
    }
    moveTo(left, bottom)
    if (roundBottom) {
        lineTo(left, bottom - r); quadraticBezierTo(left, bottom, left + r, bottom)
    } else {
        lineTo(left, top)
    }
    if (roundTop) {
        lineTo(left, top + r); quadraticBezierTo(left, top, left + r, top)
        lineTo(right - r, top); quadraticBezierTo(right, top, right, top + r)
    } else {
        lineTo(right, top)
    }
    if (roundBottom) {
        lineTo(right, bottom - r); quadraticBezierTo(right, bottom, right - r, bottom)
    } else {
        lineTo(right, bottom)
    }
    close()
}

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
    // v0.39.0 Chart.js 参考: 点击柱交互 — 选中某日显示 tooltip (模型明细/缓存/总计)
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current

    Column(modifier) {
        // Tooltip 浮层 — 选中柱时显示 (Chart.js tooltip 交互)
        AnimatedVisibility(
            visible = selectedIndex != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            selectedIndex?.let { i ->
                TokenBarTooltip(
                    label = labels[i],
                    series = series,
                    cache = cacheSeries.getOrNull(i)?.second,
                    index = i
                )
            }
        }

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

        // 布局: [冻结 Y 轴] + [数据区(横向滚动)] — v0.44.3 纵坐标冻结, 滚动只动数据区
        val scrollState = rememberScrollState()
        LaunchedEffect(dataSize) {
            // 等布局完成 → 自动拉到最右侧 (最新日期); 内容不超出容器 (maxValue==0) 时直接返回
            kotlinx.coroutines.delay(80)
            if (scrollState.maxValue > 0) scrollState.scrollTo(scrollState.maxValue)
        }
        val barWidth = 22.dp
        val barGap = 8.dp
        val yAxisWidth = 44.dp
        val padLeftData = 0f   // 数据区左边无 Y 轴 (Y 轴独立冻结)
        val padRight = 8f
        val padTop = 42f       // 顶部留出数据标注两行空间 (总Token/缓存命中)
        val padBottom = 30f

        // Y 轴 0 起动态上限: 每日堆叠总量最大值 × 1.15
        val totals = (0 until dataSize).map { i ->
            series.sumOf { it.second.getOrNull(i)?.second ?: 0L }
        }
        val rawMax = (totals.maxOrNull() ?: 1L).coerceAtLeast(1L)
        val yMax = rawMax * 115 / 100

        // 固定 Y 轴刻度常量 — 供冻结 Y 轴 + 数据区共用, 保证网格对齐
        val yScaleFor = { chartHeight: Float -> chartHeight / yMax.toFloat() }

        Row(Modifier.fillMaxWidth()) {
            // ── 冻结 Y 轴 (不随滚动移动) ──
            Box(
                Modifier.width(yAxisWidth).height(220.dp)
                    .background(ThemeColors.bgCard, RoundedCornerShape(ArcoRadius.md))
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val chartHeight = size.height - padTop - padBottom
                    val yScale = yScaleFor(chartHeight)
                    val textPaint = android.graphics.Paint().apply {
                        color = 0xFF86909C.toInt() // ArcoColors.Gray6
                        textSize = 22f
                        isAntiAlias = true
                    }
                    for (i in 0..4) {
                        val yVal = yMax * i / 4
                        val y = padTop + chartHeight - yVal * yScale
                        drawContext.canvas.nativeCanvas.drawText(
                            formatTokenCount(yVal), 2f, y + 6f, textPaint
                        )
                    }
                }
            }
            Spacer(Modifier.width(6.dp))

            // ── 数据区 (横向滚动) ──
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val dataAreaW = maxWidth - yAxisWidth - 6.dp
                val contentWidth = ((barWidth + barGap) * dataSize - barGap).coerceAtLeast(dataAreaW)
                Row(Modifier.horizontalScroll(scrollState)) {
                    Canvas(
                        Modifier.width(contentWidth).height(220.dp)
                            .background(ThemeColors.bgCard, RoundedCornerShape(ArcoRadius.md))
                            .padding(horizontal = 8.dp)
                            .pointerInput(dataSize) {
                                detectTapGestures { offset ->
                                    val barW = with(density) { barWidth.toPx() }
                                    val barG = with(density) { barGap.toPx() }
                                    val idx = ((offset.x + barG / 2f) / (barW + barG)).toInt()
                                    selectedIndex = if (idx in 0 until dataSize) idx else null
                                }
                            }
                    ) {
                        val w = size.width
                        val h = size.height
                        val chartHeight = h - padTop - padBottom
                        val yScale = yScaleFor(chartHeight)

                        val textPaint = android.graphics.Paint().apply {
                            color = 0xFF86909C.toInt() // ArcoColors.Gray6
                            textSize = 22f
                            isAntiAlias = true
                        }
                        // 网格线 (Y 轴冻结时网格也固定, 数据区只画横向网格参考线, 纵向无)
                        for (i in 0..4) {
                            val yVal = yMax * i / 4
                            val y = padTop + chartHeight - yVal * yScale
                            drawLine(ArcoColors.Gray3, Offset(0f, y), Offset(w - padRight, y), strokeWidth = 1f)
                        }

                        // 堆叠柱 — 每日一根, 模型分段着色 (自底向上)
                        val barWidthPx = barWidth.toPx()
                        val barGapPx = barGap.toPx()
                        (0 until dataSize).forEach { i ->
                            val x = padLeftData + i * (barWidthPx + barGapPx)
                            val dayTotal = totals[i].toFloat() * growProgress
                            var acc = 0f
                            series.forEachIndexed { sIdx, (_, sData) ->
                                val v = sData.getOrNull(i)?.second ?: 0L
                                val barH = v * yScale * growProgress
                                if (barH > 0.5f) {
                                    val topY = padTop + chartHeight - acc - barH
                                    val bottomY = topY + barH
                                    val roundTop = acc + barH >= dayTotal - 0.5f
                                    val roundBottom = acc <= 0.5f
                                    val (deep, light) = gradientPair(chartColors[sIdx % chartColors.size])
                                    val path = barPath(x, topY, x + barWidthPx, bottomY, 3f, roundTop, roundBottom)
                                    drawPath(
                                        path,
                                        brush = Brush.verticalGradient(
                                            colors = listOf(deep, light),
                                            startY = topY, endY = bottomY
                                        )
                                    )
                                    if (selectedIndex == i) {
                                        drawPath(path, Color.White.copy(alpha = 0.9f), style = Stroke(width = 1.5f))
                                    }
                                    acc += barH
                                } else {
                                    val ph = 2f * growProgress
                                    drawRoundRect(
                                        ArcoColors.Gray4,
                                        topLeft = Offset(x, padTop + chartHeight - ph),
                                        size = Size(barWidthPx, ph),
                                        cornerRadius = CornerRadius(1f, 1f)
                                    )
                                }
                            }
                            // 数据≠0 时标注两行: 总Token / 总缓存命中 (v0.44.3)
                            val dayTotalVal = totals[i]
                            val dayCache = cacheSeries.getOrNull(i)?.second ?: 0L
                            if (dayTotalVal > 0) {
                                val labelPaint = android.graphics.Paint().apply {
                                    color = if (dayCache > 0) 0xFF0E4397.toInt() else 0xFF86909C.toInt() // Blue6 / Gray6
                                    textSize = 20f
                                    isAntiAlias = true
                                }
                                // 柱顶上方两行: 第一行总Token, 第二行缓存命中 (缓存>0 时显示)
                                val barTop = padTop + chartHeight - dayTotal.toFloat() * yScale * growProgress
                                val row1Y = barTop - 6f
                                drawContext.canvas.nativeCanvas.drawText(
                                    formatTokenCount(dayTotalVal), x - 6f, row1Y, labelPaint
                                )
                                if (dayCache > 0) {
                                    drawContext.canvas.nativeCanvas.drawText(
                                        "缓存${formatTokenCount(dayCache)}", x - 6f, row1Y + 14f, labelPaint
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

/**
 * Tooltip 浮层 (v0.39.0, Chart.js tooltip 参考) — 点击柱后显示该日用量明细。
 * 品牌色: 模型色点沿用 chartColors (Arco 6 阶主色), 文字白/浅灰, 深色底 Gray10。
 */
@Composable
private fun TokenBarTooltip(
    label: String,
    series: List<Pair<String, List<Pair<String, Long>>>>,
    cache: Long?,
    index: Int
) {
    val total = series.sumOf { it.second.getOrNull(index)?.second ?: 0L }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ArcoRadius.md),
        color = ArcoColors.Gray10.copy(alpha = 0.94f),
        shadowElevation = 4.dp
    ) {
        Column(Modifier.padding(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.sm)) {
            Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            series.forEachIndexed { sIdx, (name, data) ->
                val v = data.getOrNull(index)?.second ?: 0L
                if (v > 0) {
                    TooltipRow(chartColors[sIdx % chartColors.size], name, v)
                }
            }
            cache?.takeIf { it > 0 }?.let {
                TooltipRow(cacheColor, "缓存节省", it)
            }
            HorizontalDivider(
                Modifier.padding(vertical = 4.dp),
                color = ArcoColors.Gray6.copy(alpha = 0.4f)
            )
            TooltipRow(Color.White, "总计", total, bold = true)
        }
    }
}

@Composable
private fun TooltipRow(color: Color, name: String, value: Long, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(8.dp)) {
            drawCircle(color, 4f, Offset(4f, 4f))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            name,
            modifier = Modifier.weight(1f),
            color = if (bold) Color.White else Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal
        )
        Text(
            formatTokenCount(value),
            color = if (bold) Color.White else Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
