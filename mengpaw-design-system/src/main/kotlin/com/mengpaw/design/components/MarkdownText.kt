// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoSpacing

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

// ── Parser (拆至 MarkdownParser.kt) / Block composables (拆至 MarkdownBlocks.kt) ──

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
