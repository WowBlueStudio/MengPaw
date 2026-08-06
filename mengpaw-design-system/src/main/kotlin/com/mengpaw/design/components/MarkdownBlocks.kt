// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing

// ── Block composables — 拆自 MarkdownText.kt (2026-08-06, >400 行文件拆分批次4) ──

@Composable internal fun HeadingView(heading: MdBlock.Heading, baseStyle: TextStyle) {
    val scale = when (heading.level) { 1 -> 1.35f; 2 -> 1.2f; else -> 1.1f }
    Text(heading.text, style = baseStyle.copy(fontWeight = FontWeight.Bold,
        fontSize = (baseStyle.fontSize.value * scale).sp),
        color = ThemeColors.textPrimary,
        modifier = Modifier.padding(top = if (heading.level <= 2) 6.dp else 2.dp))
}

@Composable internal fun BulletListView(list: MdBlock.BulletList, baseStyle: TextStyle, codeColor: Color, linkColor: Color) {
    Column(Modifier.padding(start = 8.dp)) {
        list.items.forEach { item ->
            Row(Modifier.padding(vertical = 1.dp)) {
                Text("•  ", style = baseStyle, color = ThemeColors.textSecondary)
                ParagraphBlock(MdBlock.Paragraph(parseInlineFallback(item)), baseStyle, codeColor, linkColor)
            }
        }
    }
}

@Composable internal fun BlockQuoteView(quote: MdBlock.BlockQuote, baseStyle: TextStyle) {
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(ThemeColors.brand.copy(alpha = 0.4f)))
        Spacer(Modifier.width(8.dp))
        Text(quote.text, style = baseStyle.copy(fontStyle = FontStyle.Italic),
            color = ThemeColors.textSecondary, modifier = Modifier.weight(1f).padding(vertical = 4.dp))
    }
}

@Composable internal fun ParagraphBlock(paragraph: MdBlock.Paragraph, baseStyle: TextStyle, codeColor: Color, linkColor: Color) {
    val annotated = remember(paragraph) {
        buildAnnotatedString {
            paragraph.segments.forEach { seg ->
                val style = when {
                    seg.code -> SpanStyle(fontFamily = FontFamily.Monospace,
                        fontSize = (baseStyle.fontSize.value * 0.9f).sp,
                        background = codeColor.copy(alpha = 0.12f), color = codeColor)
                    seg.bold && seg.italic -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                    seg.strikethrough -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    seg.bold -> SpanStyle(fontWeight = FontWeight.Bold)
                    seg.italic -> SpanStyle(fontStyle = FontStyle.Italic)
                    seg.link != null -> SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                    else -> SpanStyle()
                }
                val start = length; append(seg.text); addStyle(style, start, length)
                if (seg.link != null) addLink(LinkAnnotation.Url(seg.link), start, length)
            }
        }
    }
    Text(text = annotated, style = baseStyle)
}

@Composable internal fun CodeBlockView(block: MdBlock.CodeBlock, baseStyle: TextStyle, background: Color) {
    Surface(shape = RoundedCornerShape(ArcoRadius.md), color = background, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(ArcoSpacing.md)) {
            Text(block.code, style = baseStyle.copy(fontFamily = FontFamily.Monospace,
                fontSize = (baseStyle.fontSize.value * 0.85f).sp), color = ThemeColors.textPrimary)
        }
    }
}

/** 表格渲染 — 共享列宽（全表测量取列内最宽单元格）+ 网格边框。表头最多 2 行，数据行完整显示。 */
@Composable
internal fun TableTextView(block: MdBlock.Table, baseStyle: TextStyle, background: Color) {
    if (block.header.isEmpty() && block.rows.isEmpty()) return

    val borderColor = ThemeColors.border
    val maxCellWidth = 360.dp
    val headerStyle = baseStyle.copy(fontWeight = FontWeight.Bold,
        fontSize = (baseStyle.fontSize.value * 0.95f).sp)
    val cellStyle = baseStyle.copy(fontSize = (baseStyle.fontSize.value * 0.9f).sp)

    // TextMeasurer 测量表头 + 全部数据行的每列单元格宽度，取列内最大值 → 所有 Row 共享 colWidths 保证竖线对齐
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val colWidths: List<Dp> = remember(block, baseStyle) {
        val maxWidthPx = with(density) { maxCellWidth.roundToPx() }
        fun widthOf(cell: String, style: TextStyle) = textMeasurer.measure(
            text = AnnotatedString(cell), style = style,
            constraints = Constraints(maxWidth = maxWidthPx)
        ).size.width
        val headerWidths = block.header.map { widthOf(it, headerStyle) }
        val rowWidths = block.rows.map { row -> row.map { widthOf(it, cellStyle) } }
        val colCount = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
        List(colCount) { col ->
            val headerW = headerWidths.getOrNull(col) ?: 0
            val rowW = rowWidths.maxOfOrNull { row -> row.getOrNull(col) ?: 0 } ?: 0
            with(density) { maxOf(headerW, rowW).toDp() }
        }
    }
    val cellMod = { col: Int -> Modifier.width(colWidths[col]).padding(horizontal = 8.dp, vertical = 4.dp) }

    Surface(
        shape = RoundedCornerShape(ArcoRadius.sm),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
    ) {
        Column {
            // Header row
            Row(
                Modifier.background(ThemeColors.brandContainer)
                    .border(0.5.dp, borderColor, RoundedCornerShape(topStart = ArcoRadius.sm, topEnd = ArcoRadius.sm))
            ) {
                block.header.forEachIndexed { i, cell ->
                    Text(cell, modifier = cellMod(i), style = headerStyle,
                        color = ThemeColors.textPrimary, maxLines = 2)
                }
            }
            // Data rows
            block.rows.forEachIndexed { rowIdx, row ->
                val isLast = rowIdx == block.rows.lastIndex
                Row(
                    Modifier.background(
                        if (rowIdx % 2 == 0) ThemeColors.bgCard else Color.Transparent
                    ).border(
                        width = 0.5.dp, color = borderColor,
                        shape = if (isLast) RoundedCornerShape(bottomStart = ArcoRadius.sm, bottomEnd = ArcoRadius.sm)
                        else RoundedCornerShape(0.dp)
                    )
                ) {
                    row.forEachIndexed { i, cell ->
                        Text(cell, modifier = cellMod(i), style = cellStyle,
                            color = ThemeColors.textPrimary) // 无 maxLines — 长单元格完整显示，行高自适应
                    }
                }
            }
        }
    }
}
