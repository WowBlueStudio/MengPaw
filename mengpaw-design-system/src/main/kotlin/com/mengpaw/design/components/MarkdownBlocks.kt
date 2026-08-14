// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.design.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import androidx.core.content.FileProvider
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import java.io.File

/** 链接点击 — http(s) 与本地文件统一抛给 Android 系统 (用户自选打开方式)。
 *  本地路径经 FileProvider 转 content:// 再 ACTION_VIEW — 直接对 file:// 起
 *  ACTION_VIEW 会触发 FileUriExposedException 闪退 (v0.34.2 平板实锤崩溃堆栈)。
 *  目标不存在 / 打开失败 → Toast 提示, 不再静默无反应。 */
private fun openLinkSafely(context: Context, url: String) {
    val trimmed = url.trim()
    try {
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(trimmed)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val path = trimmed.removePrefix("file://")
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            android.widget.Toast.makeText(context, "文件未找到: $path", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val type = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context, "无法打开: ${e.message?.take(80) ?: url}", android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

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
    val context = LocalContext.current
    val annotated = remember(paragraph, context) {
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
                if (seg.link != null) {
                    // v0.34.3: Url 注解的默认行为是 LocalUriHandler 直接 ACTION_VIEW —
                    // 对 file:// 链接抛 FileUriExposedException 闪退。改用 Clickable
                    // 自定义处理: http/file 统一经 FileProvider 转 content:// 抛系统选择器。
                    val url = seg.link
                    addLink(LinkAnnotation.Clickable(url) { openLinkSafely(context, url) }, start, length)
                }
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

/** 表格框线色 — 50% 灰度灰线 (v0.37.4 用户定案, 气泡内表格统一使用)。 */
val MarkdownTableBorderColor: Color = Color(0xFF808080)

/** 表格渲染 — 共享列宽（全表测量取列内最宽单元格）+ 单条圆角外框 + 网格分隔线。
 *  v0.38.3: 行间横线改用 Box+background (与竖线同一画法, 修复 HorizontalDivider
 *  在 IntrinsicSize.Min 行间不渲染的问题); 表头白底自身 clip 顶部圆角; 外框整表
 *  border 四角圆角。表头最多 2 行，数据行完整显示。 */
@Composable
internal fun TableTextView(
    block: MdBlock.Table,
    baseStyle: TextStyle,
    background: Color,
    tableTextColor: Color? = null,
    tableBorderColor: Color? = null
) {
    if (block.header.isEmpty() && block.rows.isEmpty()) return

    // 框线: 50% 灰度灰线 (可被调用方覆盖); 文字色 null = 主题自适应 (日间深色/夜间浅色)
    val borderColor = tableBorderColor ?: MarkdownTableBorderColor
    val cellTextColor = tableTextColor ?: ThemeColors.textPrimary
    val corner = 4.dp
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

    Box(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
    ) {
        // 整表 border+clip: 四角圆角一次到位, 表头白底也被裁成圆角, 无缺角
        Column(
            Modifier
                .border(0.5.dp, borderColor, RoundedCornerShape(corner))
                .clip(RoundedCornerShape(corner))
        ) {
            // Header row — 90% 白底 + 深色文字 (日间/夜间一致, 白底上保证可读);
            // 白底自身 clip 顶部两角, 避免右上角依赖整表裁剪而缺圆角
            Row(
                Modifier
                    .height(IntrinsicSize.Min)
                    .clip(RoundedCornerShape(topStart = corner, topEnd = corner))
                    .background(Color.White.copy(alpha = 0.9f))
            ) {
                block.header.forEachIndexed { i, cell ->
                    if (i > 0) {
                        Box(Modifier.width(0.5.dp).fillMaxHeight().background(borderColor))
                    }
                    Text(cell, modifier = cellMod(i), style = headerStyle,
                        color = Color(0xFF1D2129), maxLines = 2)
                }
            }
            // Data rows — 透明底 (去 zebra), 行间/列间 0.5dp 同色网格线 (与竖线同一 Box 画法), 文字色主题自适应/用户气泡白
            block.rows.forEachIndexed { rowIdx, row ->
                if (block.header.isNotEmpty() || rowIdx > 0) {
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(borderColor))
                }
                Row(Modifier.height(IntrinsicSize.Min).background(Color.Transparent)) {
                    row.forEachIndexed { i, cell ->
                        if (i > 0) {
                            Box(Modifier.width(0.5.dp).fillMaxHeight().background(borderColor))
                        }
                        Text(cell, modifier = cellMod(i), style = cellStyle,
                            color = cellTextColor) // 无 maxLines — 长单元格完整显示，行高自适应
                    }
                }
            }
        }
    }
}
