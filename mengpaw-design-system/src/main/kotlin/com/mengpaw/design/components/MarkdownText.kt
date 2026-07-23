// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension

// ── Data types (stable public API) ──

data class MdSegment(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
    val strikethrough: Boolean = false
)

sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val segments: List<MdSegment>) : MdBlock()
    data class CodeBlock(val code: String, val languageHint: String = "") : MdBlock()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock()
    data class BulletList(val items: List<String>) : MdBlock()
    data class BlockQuote(val text: String) : MdBlock()
    object HorizontalRule : MdBlock()
}

// ── Parser — commonmark-java AST → MdBlock ──

private val mdParser: Parser = Parser.builder()
    .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
    .build()

fun parseMarkdown(raw: String): List<MdBlock> {
    if (raw.isBlank()) return emptyList()
    val safe = if (raw.length > 100_000) raw.take(100_000) + "\n\n...(truncated)" else raw
    val document = mdParser.parse(safe)
    val blocks = mutableListOf<MdBlock>()
    var count = 0
    var child = document.firstChild
    while (child != null && count++ < 500) {
        val block = convertNode(child)
        if (block != null) blocks.add(block)
        child = child.next
    }
    return blocks
}

private fun convertNode(node: Node): MdBlock? {
    return when (node) {
        is Heading -> MdBlock.Heading(node.level, collectText(node))
        is Paragraph -> {
            val segs = collectInline(node)
            if (segs.isEmpty()) null else MdBlock.Paragraph(segs)
        }
        is FencedCodeBlock -> MdBlock.CodeBlock(
            node.literal.trimEnd(),
            node.info?.trim() ?: ""
        )
        is IndentedCodeBlock -> MdBlock.CodeBlock(node.literal.trimEnd())
        is org.commonmark.ext.gfm.tables.TableBlock -> convertTable(node)
        is BulletList -> {
            val items = mutableListOf<String>()
            var c = node.firstChild; while (c != null) {
                if (c is ListItem) items.add(collectListItemText(c, "-"))
                c = c.next
            }
            if (items.isEmpty()) null else MdBlock.BulletList(items)
        }
        is OrderedList -> {
            val items = mutableListOf<String>()
            var i = 0; var c = node.firstChild
            while (c != null) {
                if (c is ListItem) items.add(collectListItemText(c, "${++i}."))
                c = c.next
            }
            if (items.isEmpty()) null else MdBlock.BulletList(items)
        }
        is BlockQuote -> {
            val sb = StringBuilder()
            var c = node.firstChild; while (c != null) { sb.appendLine(collectText(c).trim()); c = c.next }
            MdBlock.BlockQuote(sb.toString().trim())
        }
        is ThematicBreak -> MdBlock.HorizontalRule
        is HtmlBlock -> MdBlock.CodeBlock(node.literal.trimEnd(), "html")
        else -> {
            val text = collectText(node).trim()
            if (text.isNotBlank()) MdBlock.Paragraph(listOf(MdSegment(text))) else null
        }
    }
}

private fun convertTable(tableBlock: org.commonmark.ext.gfm.tables.TableBlock): MdBlock.Table {
    val header = mutableListOf<String>()
    val data = mutableListOf<List<String>>()
    var hasHead = false

    fun extractCells(row: org.commonmark.ext.gfm.tables.TableRow): List<String> {
        val cells = mutableListOf<String>()
        var cell = row.firstChild
        while (cell != null) {
            if (cell is org.commonmark.ext.gfm.tables.TableCell) cells.add(collectText(cell).trim())
            cell = cell.next
        }
        return cells
    }

    // 扁平遍历所有后代行
    fun walkRows(parent: Node, isHeader: Boolean) {
        var child = parent.firstChild
        while (child != null) {
            when (child) {
                is org.commonmark.ext.gfm.tables.TableHead -> {
                    hasHead = true
                    walkRows(child, true)
                }
                is org.commonmark.ext.gfm.tables.TableBody -> walkRows(child, false)
                is org.commonmark.ext.gfm.tables.TableRow -> {
                    val cells = extractCells(child)
                    if (isHeader || (!hasHead && data.isEmpty() && header.isEmpty())) header.addAll(cells)
                    else data.add(cells)
                }
                else -> walkRows(child, false)
            }
            child = child.next
        }
    }

    walkRows(tableBlock, false)
    return MdBlock.Table(header, data)
}

/** 提取列表项文本，嵌套子列表追加到末尾。 */
private fun collectListItemText(item: ListItem, marker: String): String {
    val main = StringBuilder()
    val subs = mutableListOf<String>()
    var c = item.firstChild
    while (c != null) {
        when (c) {
            is Paragraph -> main.append(collectText(c))
            is BulletList -> {
                var sub = c.firstChild
                while (sub != null) {
                    if (sub is ListItem) subs.add("  - ${collectText(sub).trim()}")
                    sub = sub.next
                }
            }
            is OrderedList -> {
                var sub = c.firstChild; var j = 0
                while (sub != null) {
                    if (sub is ListItem) subs.add("  ${++j}. ${collectText(sub).trim()}")
                    sub = sub.next
                }
            }
            else -> main.append(collectText(c))
        }
        c = c.next
    }
    val result = main.toString().trim()
    return if (subs.isEmpty()) result else "$result\n${subs.joinToString("\n")}"
}

/** Recursively collect plain text from a node and its children. */
private fun collectText(node: Node): String {
    val sb = StringBuilder()
    node.accept(object : AbstractVisitor() {
        override fun visit(node: org.commonmark.node.Text) { sb.append(node.literal) }
        override fun visit(node: Code) { sb.append("`${node.literal}`") }
        override fun visit(node: SoftLineBreak) { sb.append(' ') }
        override fun visit(node: HardLineBreak) { sb.append('\n') }
        override fun visit(node: Link) { visitChildren(node) }
        override fun visit(node: Emphasis) { visitChildren(node) }
        override fun visit(node: StrongEmphasis) { visitChildren(node) }
    })
    return sb.toString()
}

/** Collect inline segments with formatting from a paragraph node. */
private fun collectInline(paragraph: Paragraph): List<MdSegment> {
    val segments = mutableListOf<MdSegment>()
    walkInline(paragraph, segments, setOf())
    return mergeAdjacentPlain(segments)
}

private fun walkInline(node: Node, segments: MutableList<MdSegment>, styles: Set<String>) {
    var child = node.firstChild
    while (child != null) {
        when (child) {
            is org.commonmark.node.Text -> {
                if (child.literal.isNotBlank() || child.literal == " ") {
                    segments.add(MdSegment(
                        text = child.literal,
                        bold = "bold" in styles,
                        italic = "italic" in styles,
                        code = "code" in styles,
                        strikethrough = "strike" in styles
                    ))
                }
            }
            is Code -> segments.add(MdSegment(child.literal, code = true))
            is Emphasis -> {
                walkInline(child, segments, styles + setOf("italic"))
            }
            is StrongEmphasis -> {
                walkInline(child, segments, styles + setOf("bold"))
            }
            is Link -> {
                val url = child.destination ?: ""
                val idx = segments.size
                walkInline(child, segments, styles)
                // 将链接 URL 写入最近添加的纯文本段
                for (i in idx until segments.size) {
                    val seg = segments[i]
                    if (seg.link == null) segments[i] = seg.copy(link = url)
                }
            }
            is org.commonmark.ext.gfm.strikethrough.Strikethrough -> {
                walkInline(child, segments, styles + setOf("strike"))
            }
            is Image -> {
                val alt = collectText(child).ifBlank { "图片" }
                val url = child.destination ?: ""
                segments.add(MdSegment(if (url.isNotBlank()) "$alt ($url)" else alt))
            }
            is HtmlInline -> segments.add(MdSegment(child.literal))
            else -> walkInline(child, segments, styles)
        }
        child = child.next
    }
}

private fun mergeAdjacentPlain(segments: List<MdSegment>): List<MdSegment> {
    val merged = mutableListOf<MdSegment>()
    for (seg in segments) {
        val last = merged.lastOrNull()
        val bothPlain = last != null && !last.bold && !last.italic && !last.code && last.link == null && !last.strikethrough
                && !seg.bold && !seg.italic && !seg.code && seg.link == null && !seg.strikethrough
        if (bothPlain) merged[merged.lastIndex] = last!!.copy(text = last.text + seg.text)
        else merged.add(seg)
    }
    return merged
}

// ── Renderer ──

@Composable
fun MarkdownText(
    content: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    codeBackgroundColor: Color = ThemeColors.bgCardHigh,
    inlineCodeColor: Color = ThemeColors.brand,
    linkColor: Color = ThemeColors.brand,
    nestedScroll: Boolean = false  // true = 外部已有 scroll，内部不重复加
) {
    if (content.isBlank()) return

    val blocks = remember(content) { parseMarkdown(content) }

    val colModifier = if (nestedScroll) modifier else modifier.verticalScroll(rememberScrollState())
    Column(
        modifier = colModifier,
        verticalArrangement = Arrangement.spacedBy(ArcoSpacing.xs)
    ) {
        blocks.forEach { block ->
            RenderBlock(block, textStyle, inlineCodeColor, linkColor, codeBackgroundColor)
        }
    }
}

@Composable
private fun RenderBlock(
    block: MdBlock, baseStyle: TextStyle, inlineCodeColor: Color, linkColor: Color, codeBg: Color
) {
    when (block) {
                is MdBlock.Heading -> HeadingView(block, baseStyle)
                is MdBlock.Paragraph -> ParagraphBlock(block, baseStyle, inlineCodeColor, linkColor)
                is MdBlock.CodeBlock -> CodeBlockView(block, baseStyle, codeBg)
                is MdBlock.Table -> TableTextView(block, baseStyle, codeBg)
                is MdBlock.BulletList -> BulletListView(block, baseStyle, inlineCodeColor, linkColor)
                is MdBlock.BlockQuote -> BlockQuoteView(block, baseStyle)
                is MdBlock.HorizontalRule -> HorizontalDivider(color = ThemeColors.border, thickness = 0.5.dp)
            }
}

// ── Block composables ──

@Composable private fun HeadingView(heading: MdBlock.Heading, baseStyle: TextStyle) {
    val scale = when (heading.level) { 1 -> 1.35f; 2 -> 1.2f; else -> 1.1f }
    Text(heading.text, style = baseStyle.copy(fontWeight = FontWeight.Bold,
        fontSize = (baseStyle.fontSize.value * scale).sp),
        color = ThemeColors.textPrimary,
        modifier = Modifier.padding(top = if (heading.level <= 2) 6.dp else 2.dp))
}

@Composable private fun BulletListView(list: MdBlock.BulletList, baseStyle: TextStyle, codeColor: Color, linkColor: Color) {
    Column(Modifier.padding(start = 8.dp)) {
        list.items.forEach { item ->
            Row(Modifier.padding(vertical = 1.dp)) {
                Text("•  ", style = baseStyle, color = ThemeColors.textSecondary)
                ParagraphBlock(MdBlock.Paragraph(parseInlineFallback(item)), baseStyle, codeColor, linkColor)
            }
        }
    }
}

@Composable private fun BlockQuoteView(quote: MdBlock.BlockQuote, baseStyle: TextStyle) {
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(ThemeColors.brand.copy(alpha = 0.4f)))
        Spacer(Modifier.width(8.dp))
        Text(quote.text, style = baseStyle.copy(fontStyle = FontStyle.Italic),
            color = ThemeColors.textSecondary, modifier = Modifier.weight(1f).padding(vertical = 4.dp))
    }
}

@Composable private fun ParagraphBlock(paragraph: MdBlock.Paragraph, baseStyle: TextStyle, codeColor: Color, linkColor: Color) {
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

@Composable private fun CodeBlockView(block: MdBlock.CodeBlock, baseStyle: TextStyle, background: Color) {
    Surface(shape = RoundedCornerShape(ArcoRadius.md), color = background, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(ArcoSpacing.md)) {
            Text(block.code, style = baseStyle.copy(fontFamily = FontFamily.Monospace,
                fontSize = (baseStyle.fontSize.value * 0.85f).sp), color = ThemeColors.textPrimary)
        }
    }
}

/** 表格渲染 — 列宽自适应视觉表格。 */
@Composable
private fun TableTextView(block: MdBlock.Table, baseStyle: TextStyle, background: Color) {
    if (block.header.isEmpty() && block.rows.isEmpty()) return

    // 计算每列最小宽度
    val minWidths = mutableListOf<Int>()
    val allRows = listOf(block.header) + block.rows
    allRows.forEach { row ->
        row.forEachIndexed { i, cell ->
            val w = maxOf(cell.length, 3)
            while (minWidths.size <= i) minWidths.add(0)
            if (w > minWidths[i]) minWidths[i] = w
        }
    }

    Surface(shape = RoundedCornerShape(ArcoRadius.md), color = background, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(ArcoSpacing.md)) {
            // Header
            Row {
                block.header.forEachIndexed { i, cell ->
                    Text(cell,
                        modifier = Modifier.padding(horizontal = ArcoSpacing.sm, vertical = 4.dp)
                            .widthIn(min = (minWidths.getOrElse(i) { 4 } * 7).dp),
                        style = baseStyle.copy(fontWeight = FontWeight.Bold),
                        color = ThemeColors.textPrimary, maxLines = 1)
                }
            }
            HorizontalDivider(color = ThemeColors.border, thickness = 1.dp)
            // Data
            block.rows.forEach { row ->
                Row { row.forEachIndexed { i, cell ->
                    Text(cell,
                        modifier = Modifier.padding(horizontal = ArcoSpacing.sm, vertical = 2.dp)
                            .widthIn(min = (minWidths.getOrElse(i) { 4 } * 7).dp),
                        style = baseStyle.copy(fontSize = (baseStyle.fontSize.value * 0.9f).sp),
                        color = ThemeColors.textPrimary, maxLines = 1)
                } }
            }
        }
    }
}

/** 轻量级内联解析 — 用于没有 commonmark 上下文时（如 BulletList 子项）。 */
private fun parseInlineFallback(text: String): List<MdSegment> {
    val doc = mdParser.parse(text)
    val segs = mutableListOf<MdSegment>()
    var node = doc.firstChild
    while (node != null) {
        if (node is Paragraph) walkInline(node, segs, setOf())
        node = node.next
    }
    return mergeAdjacentPlain(segs)
}
