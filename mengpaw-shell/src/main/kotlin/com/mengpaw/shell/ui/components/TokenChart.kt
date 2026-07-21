// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing

/** Color palette for chart lines — distinct hues per model. */
// Chart line palette — uses ArcoColors where available, custom for distinctiveness
private val chartPurple = Color(0xFF722ED1)
private val chartTeal = Color(0xFF13C2C2)
private val chartPink = Color(0xFFEB2F96)

private val chartColors = listOf(
    ArcoColors.Blue6,
    ArcoColors.Green6,
    ArcoColors.Orange6,
    ArcoColors.Red6,
    chartPurple,
    chartTeal,
    chartPink,
)
private val cacheColor = ArcoColors.Gray6

/**
 * Simple line chart for token usage over time.
 *
 * Each series is a list of (label, value) pairs.
 * Supports multiple model lines + a cache-hit line.
 */
@Composable
fun TokenLineChart(
    series: List<Pair<String, List<Pair<String, Long>>>>,  // (model/line name, [(label, value)])
    cacheSeries: List<Pair<String, Long>> = emptyList(),    // cache hit data
    modifier: Modifier = Modifier
) {
    if (series.all { it.second.isEmpty() } && cacheSeries.isEmpty()) {
        Box(modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            Text("暂无数据", color = ThemeColors.textSecondary, fontSize = 14.sp)
        }
        return
    }

    var showCacheLine by remember { mutableStateOf(true) }

    Column(modifier) {
        // Legend
        Row(
            Modifier.fillMaxWidth().padding(bottom = ArcoSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.md)
        ) {
            series.forEachIndexed { i, (name, _) ->
                LegendDot(chartColors[i % chartColors.size], name)
            }
            if (cacheSeries.isNotEmpty()) {
                LegendDot(cacheColor, "缓存节省", showCacheLine) { showCacheLine = !showCacheLine }
            }
        }

        // Chart canvas
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(ThemeColors.bgCard, RoundedCornerShape(ArcoRadius.md))
                .padding(horizontal = 8.dp, vertical = 12.dp)
        ) {
            val w = size.width
            val h = size.height
            val padLeft = 48f
            val padRight = 16f
            val padTop = 16f
            val padBottom = 32f
            val chartW = w - padLeft - padRight
            val chartH = h - padTop - padBottom

            // Find max value for Y scale
            val allValues = series.flatMap { it.second.map { p -> p.second } } + cacheSeries.map { it.second }
            val maxVal = (allValues.maxOrNull() ?: 1L).coerceAtLeast(1L)
            val yScale = chartH / maxVal.toFloat()

            // Y-axis labels (3 ticks)
            val textPaint = android.graphics.Paint().apply {
                color = 0xFF86909C.toInt() // ArcoColors.Gray6 — Canvas Paint requires Int
                textSize = 24f
                isAntiAlias = true
            }
            for (i in 0..3) {
                val yVal = maxVal * i / 3
                val y = padTop + chartH - yVal * yScale
                drawContext.canvas.nativeCanvas.drawText(
                    formatTokenCount(yVal),
                    4f, y + 8f, textPaint
                )
                // Grid line
                drawLine(
                    ArcoColors.Gray3, Offset(padLeft, y), Offset(w - padRight, y),
                    strokeWidth = 1f
                )
            }

            // X-axis labels
            fun drawSeriesLine(
                data: List<Pair<String, Long>>,
                color: Color,
                strokeWidth: Float = 2.5f
            ) {
                if (data.size < 2) return
                val path = Path()
                data.forEachIndexed { i, (_, value) ->
                    val x = padLeft + chartW * i / (data.size - 1).coerceAtLeast(1)
                    val y = padTop + chartH - value * yScale
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(strokeWidth))
                // Dots
                data.forEachIndexed { i, (_, value) ->
                    val x = padLeft + chartW * i / (data.size - 1).coerceAtLeast(1)
                    val y = padTop + chartH - value * yScale
                    drawCircle(color, 4f, Offset(x, y))
                }
            }

            // Draw each model series
            series.forEachIndexed { i, (_, data) ->
                drawSeriesLine(data, chartColors[i % chartColors.size])
            }

            // Draw cache-hit line (dashed style via lower alpha)
            if (showCacheLine && cacheSeries.isNotEmpty()) {
                drawSeriesLine(cacheSeries, cacheColor, 2f)
            }

            // X-axis labels
            val allLabels = series.firstOrNull()?.second?.map { it.first } ?: emptyList()
            if (allLabels.isNotEmpty()) {
                allLabels.forEachIndexed { i, label ->
                    if (i % maxOf(1, allLabels.size / 5) == 0 || i == allLabels.size - 1) {
                        val x = padLeft + chartW * i / (allLabels.size - 1).coerceAtLeast(1)
                        drawContext.canvas.nativeCanvas.drawText(
                            label.takeLast(5),  // e.g. "07-15" or "W29"
                            x - 16f, h - 4f, textPaint
                        )
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
