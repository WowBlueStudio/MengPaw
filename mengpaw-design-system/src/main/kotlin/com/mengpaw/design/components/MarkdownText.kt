// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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

// ── Data types ──

/** A segment of inline text with optional styling. */
data class MdSegment(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null
)

/** A block-level Markdown element. */
sealed class MdBlock {
    data class Paragraph(val segments: List<MdSegment>) : MdBlock()
    data class CodeBlock(val code: String, val languageHint: String = "") : MdBlock()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock()
}

// ── Parser ──

/**
 * Parse a raw Markdown string into [MdBlock] elements.
 * Handles code fences (```) at block level and inline formatting within paragraphs.
 */
fun parseMarkdown(raw: String): List<MdBlock> {
    if (raw.isBlank()) return emptyList()

    val blocks = mutableListOf<MdBlock>()

    // Split by ``` fences — alternate between text and code
    val parts = raw.split("```")
    parts.forEachIndexed { index, part ->
        if (index % 2 == 0) {
            // Text block — parse tables, then inline paragraphs
            val trimmed = part.trim()
            if (trimmed.isNotEmpty()) {
                val tableBlocks = parseTextWithTables(trimmed)
                blocks.addAll(tableBlocks)
            }
        } else {
            // Code block — drop optional language hint on first line
            val lines = part.lines()
            val langHint = lines.firstOrNull()?.trim()?.takeIf {
                it.isNotEmpty() && !it.contains(' ')
            } ?: ""
            val code = if (langHint.isNotEmpty()) lines.drop(1).joinToString("\n")
            else part.trim()
            if (code.isNotBlank()) {
                blocks.add(MdBlock.CodeBlock(code.trimEnd(), langHint))
            }
        }
    }

    return blocks
}

/**
 * Parse a text block that may contain tables mixed with paragraphs.
 * A table is: consecutive lines starting/ending with '|', with a separator row.
 */
private fun parseTextWithTables(text: String): List<MdBlock> {
    val lines = text.lines()
    val blocks = mutableListOf<MdBlock>()
    val buffer = mutableListOf<String>()

    fun flushBuffer() {
        val paragraph = buffer.joinToString("\n").trim()
        if (paragraph.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(parseInline(paragraph)))
        }
        buffer.clear()
    }

    fun isTableSeparator(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith('|') && trimmed.endsWith('|') &&
            trimmed.all { it in setOf('|', '-', ':', ' ') }
    }

    fun isTableRow(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith('|') && trimmed.endsWith('|') && !isTableSeparator(line)
    }

    fun parseTable(startIdx: Int): Int {
        // Collect table lines
        val tableLines = mutableListOf<String>()
        var i = startIdx
        while (i < lines.size && isTableRow(lines[i])) {
            tableLines.add(lines[i])
            i++
        }
        if (tableLines.size < 2) return startIdx // need at least header + separator

        // First should be header, second should be separator
        val headerLine = tableLines[0]
        val sepLine = tableLines[1]

        if (!isTableSeparator(sepLine)) {
            // Not a proper table with separator
            buffer.addAll(tableLines)
            return i
        }

        val header = headerLine.trim().split('|').map { it.trim() }.filter { it.isNotEmpty() }
        val dataRows = tableLines.drop(2).map { line ->
            line.trim().split('|').map { it.trim() }.filter { it.isNotEmpty() }
        }

        if (header.isNotEmpty()) {
            blocks.add(MdBlock.Table(header, dataRows))
        }
        return i
    }

    var idx = 0
    while (idx < lines.size) {
        val line = lines[idx]
        if (isTableRow(line) && idx + 1 < lines.size && isTableSeparator(lines[idx + 1])) {
            flushBuffer()
            idx = parseTable(idx)
        } else {
            buffer.add(line)
            idx++
        }
    }
    flushBuffer()

    return blocks
}

/**
 * Parse inline Markdown: **bold**, *italic*, `code`, [text](url).
 * Priority: code > bold > italic > link > plain.
 */
fun parseInline(text: String): List<MdSegment> {
    if (text.isEmpty()) return emptyList()

    val segments = mutableListOf<MdSegment>()
    var remaining = text

    while (remaining.isNotEmpty()) {
        when {
            // Inline code: `...`
            remaining.startsWith('`') -> {
                val end = remaining.indexOf('`', startIndex = 1)
                if (end > 0) {
                    val code = remaining.substring(1, end)
                    if (code.isNotEmpty()) segments.add(MdSegment(code, code = true))
                    remaining = remaining.substring(end + 1)
                } else {
                    // unclosed backtick — treat remaining as plain
                    segments.add(MdSegment(remaining))
                    remaining = ""
                }
            }
            // Bold: **...**
            remaining.startsWith("**") -> {
                val end = remaining.indexOf("**", startIndex = 2)
                if (end > 2) {
                    val boldText = remaining.substring(2, end)
                    if (boldText.isNotEmpty()) segments.add(MdSegment(boldText, bold = true))
                    remaining = remaining.substring(end + 2)
                } else {
                    segments.add(MdSegment("**"))
                    remaining = remaining.substring(2)
                }
            }
            // Italic: *...*
            remaining.startsWith('*') -> {
                val end = remaining.indexOf('*', startIndex = 1)
                if (end > 1) {
                    val italicText = remaining.substring(1, end)
                    if (italicText.isNotEmpty()) segments.add(MdSegment(italicText, italic = true))
                    remaining = remaining.substring(end + 1)
                } else {
                    segments.add(MdSegment("*"))
                    remaining = remaining.substring(1)
                }
            }
            // Link: [text](url)
            remaining.startsWith('[') -> {
                val closeBracket = remaining.indexOf(']')
                val openParen = if (closeBracket > 0) remaining.indexOf('(', startIndex = closeBracket) else -1
                if (closeBracket > 1 && openParen == closeBracket + 1) {
                    val end = remaining.indexOf(')', startIndex = openParen)
                    if (end > openParen) {
                        val linkText = remaining.substring(1, closeBracket)
                        val url = remaining.substring(openParen + 1, end)
                        if (linkText.isNotEmpty()) segments.add(MdSegment(linkText, link = url))
                        remaining = remaining.substring(end + 1)
                    } else {
                        segments.add(MdSegment("["))
                        remaining = remaining.substring(1)
                    }
                } else {
                    segments.add(MdSegment("["))
                    remaining = remaining.substring(1)
                }
            }
            // Plain text — accumulate until next special char
            else -> {
                val nextSpecial = remaining.indexOfAny(charArrayOf('`', '*', '['))
                if (nextSpecial == -1) {
                    segments.add(MdSegment(remaining))
                    remaining = ""
                } else {
                    if (nextSpecial > 0) {
                        segments.add(MdSegment(remaining.substring(0, nextSpecial)))
                    }
                    remaining = remaining.substring(nextSpecial)
                }
            }
        }
    }

    return mergeAdjacentPlain(segments)
}

/** Combine consecutive plain-text segments to reduce AnnotatedString calls. */
private fun mergeAdjacentPlain(segments: List<MdSegment>): List<MdSegment> {
    val merged = mutableListOf<MdSegment>()
    for (seg in segments) {
        val last = merged.lastOrNull()
        if (last != null && !last.bold && !last.italic && !last.code && last.link == null
            && !seg.bold && !seg.italic && !seg.code && seg.link == null) {
            merged[merged.lastIndex] = last.copy(text = last.text + seg.text)
        } else {
            merged.add(seg)
        }
    }
    return merged
}

// ── Renderer ──

/**
 * Render Markdown text with full inline formatting and code block support.
 * Uses [buildAnnotatedString] for inline styles and Material3 theming.
 */
@Composable
fun MarkdownText(
    content: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    codeBackgroundColor: Color = ThemeColors.bgCardHigh,
    inlineCodeColor: Color = ThemeColors.brand,
    linkColor: Color = ThemeColors.brand
) {
    if (content.isBlank()) return

    val blocks = remember(content) { parseMarkdown(content) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ArcoSpacing.xs)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> ParagraphBlock(
                    block, textStyle, inlineCodeColor, linkColor
                )
                is MdBlock.CodeBlock -> CodeBlockView(
                    block, textStyle, codeBackgroundColor
                )
                is MdBlock.Table -> TableView(
                    block, textStyle, codeBackgroundColor
                )
            }
        }
    }
}

// ── Block composables ──

@Composable
private fun ParagraphBlock(
    paragraph: MdBlock.Paragraph,
    baseStyle: TextStyle,
    codeColor: Color,
    linkColor: Color
) {
    val annotated = remember(paragraph) {
        buildAnnotatedString {
            paragraph.segments.forEach { seg ->
                val style = when {
                    // Single style — use SpanStyle directly
                    seg.bold && seg.italic -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                    seg.bold -> SpanStyle(fontWeight = FontWeight.Bold)
                    seg.italic -> SpanStyle(fontStyle = FontStyle.Italic)
                    seg.code -> SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = (baseStyle.fontSize.value * 0.9f).sp,
                        background = codeColor.copy(alpha = 0.12f),
                        color = codeColor
                    )
                    seg.link != null -> SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline
                    )
                    else -> SpanStyle()
                }

                val start = length
                append(seg.text)
                addStyle(style, start, length)

                if (seg.link != null) {
                    addLink(LinkAnnotation.Url(seg.link), start, length)
                }
            }
        }
    }

    Text(text = annotated, style = baseStyle)
}

@Composable
private fun CodeBlockView(
    block: MdBlock.CodeBlock,
    baseStyle: TextStyle,
    background: Color
) {
    Surface(
        shape = RoundedCornerShape(ArcoRadius.md),
        color = background,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(ArcoSpacing.md)
        ) {
            Text(
                text = block.code,
                style = baseStyle.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = (baseStyle.fontSize.value * 0.85f).sp
                ),
                color = ThemeColors.textPrimary
            )
        }
    }
}

@Composable
private fun TableView(
    block: MdBlock.Table,
    baseStyle: TextStyle,
    background: Color
) {
    Surface(
        shape = RoundedCornerShape(ArcoRadius.md),
        color = background,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(ArcoSpacing.md)
        ) {
            // Header row
            Row(Modifier.fillMaxWidth()) {
                block.header.forEach { cell ->
                    Text(
                        text = cell,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = ArcoSpacing.sm, vertical = 4.dp),
                        style = baseStyle.copy(fontWeight = FontWeight.Bold),
                        color = ThemeColors.textPrimary
                    )
                }
            }
            HorizontalDivider(color = ThemeColors.border, thickness = 1.dp)
            // Data rows
            block.rows.forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    val padded = if (row.size < block.header.size) {
                        row + List(block.header.size - row.size) { "" }
                    } else row
                    padded.take(block.header.size).forEach { cell ->
                        Text(
                            text = cell,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = ArcoSpacing.sm, vertical = 2.dp),
                            style = baseStyle.copy(fontSize = (baseStyle.fontSize.value * 0.9f).sp),
                            color = ThemeColors.textPrimary
                        )
                    }
                }
            }
        }
    }
}
