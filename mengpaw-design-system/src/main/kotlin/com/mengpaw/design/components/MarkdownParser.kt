// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.design.components

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

// ── Parser — commonmark-java AST → MdBlock ──

private val mdParser: Parser = Parser.builder()
    .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
    .build()

/** 渲染预算: 超过后按块边界截断 (每块完整, 不撕裂代码围栏)。 */
private const val MAX_RENDER_CHARS = 100_000
/** 节点数防御上限 (极端大文档兜底)。 */
private const val MAX_BLOCKS = 500

fun parseMarkdown(raw: String): List<MdBlock> {
    if (raw.isBlank()) return emptyList()
    // 完整解析 — 代码围栏在解析期必然闭合。旧实现 100KB 预截断在任意字符边界硬切,
    // 切点落在 ``` 内时闭合围栏丢失, 后续整段 (含截断标记) 被解析成巨型代码块 —
    // 「内容掉出代码块」根因。预算截断改为在块边界停, 每个加入的块永远完整。
    val document = mdParser.parse(raw)
    val blocks = mutableListOf<MdBlock>()
    var used = 0
    var count = 0
    var truncated = false
    var child = document.firstChild
    while (child != null && count < MAX_BLOCKS) {
        count++
        val block = convertNode(child)
        child = child.next
        if (block == null) continue
        val len = blockLength(block)
        if (used + len > MAX_RENDER_CHARS) { truncated = true; break }  // 块边界截断, 不撕裂代码块
        blocks.add(block); used += len
    }
    if (truncated) blocks.add(MdBlock.Paragraph(listOf(MdSegment("…(内容过长，已截断)"))))
    return blocks
}

/** 按渲染输出量度预算 (比 source span 更贴近实际开销)。 */
private fun blockLength(b: MdBlock): Int = when (b) {
    is MdBlock.CodeBlock -> b.code.length + b.languageHint.length + 32
    is MdBlock.Table -> b.header.sumOf { it.length + 4 } + b.rows.sumOf { r -> r.sumOf { it.length + 4 } }
    is MdBlock.BulletList -> b.items.sumOf { it.length + 4 }
    is MdBlock.BlockQuote -> b.text.length + 8
    is MdBlock.Heading -> b.text.length + 16
    is MdBlock.Paragraph -> b.segments.sumOf { it.text.length + 2 }
    MdBlock.HorizontalRule -> 8
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
        override fun visit(node: Image) { visitChildren(node) }
        override fun visit(node: HtmlInline) { sb.append(node.literal) }
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
        if (bothPlain) { val l = last ?: continue; merged[merged.lastIndex] = l.copy(text = l.text + seg.text) }
        else merged.add(seg)
    }
    return merged
}

/** 轻量级内联解析 — 用于没有 commonmark 上下文时（如 BulletList 子项）。 */
internal fun parseInlineFallback(text: String): List<MdSegment> {
    val doc = mdParser.parse(text)
    val segs = mutableListOf<MdSegment>()
    var node = doc.firstChild
    while (node != null) {
        if (node is Paragraph) walkInline(node, segs, setOf())
        node = node.next
    }
    return mergeAdjacentPlain(segs)
}
