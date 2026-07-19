// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing

/**
 * "Big Bang" text selection popup — splits text into word chips
 * for quick selection. Inspired by Smartisan OS Big Bang feature.
 */
@Composable
fun BigBangPopup(
    text: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // Split text into words by common delimiters
    val words = remember(text) {
        text.split(Regex("(?<=[，。！？；：、,.!?;:\\s])|(?=[，。！？；：、,.!?;:\\s])"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    var selectedIndices by remember { mutableStateOf(setOf<Int>()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(ArcoRadius.lg),
            color = ThemeColors.bgPrimary,
            modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.6f)
        ) {
            Column(Modifier.padding(ArcoSpacing.lg)) {
                // Title
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("大爆炸", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }

                Spacer(Modifier.height(ArcoSpacing.md))

                // Word chips grid
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Group into rows
                    val rows = mutableListOf<List<String>>()
                    var currentRow = mutableListOf<String>()
                    var rowLen = 0
                    words.forEachIndexed { idx, word ->
                        val display = if (word.length > 15) word.take(15) + "…" else word
                        if (rowLen + display.length > 30 && currentRow.isNotEmpty()) {
                            rows.add(currentRow.toList())
                            currentRow = mutableListOf()
                            rowLen = 0
                        }
                        currentRow.add(word)
                        rowLen += display.length
                    }
                    if (currentRow.isNotEmpty()) rows.add(currentRow.toList())

                    items(rows) { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            row.forEach { word ->
                                val isSelected = selectedIndices.contains(words.indexOf(word))
                                SuggestionChip(
                                    onClick = {
                                        selectedIndices = if (isSelected) {
                                            selectedIndices - words.indexOf(word)
                                        } else {
                                            selectedIndices + words.indexOf(word)
                                        }
                                    },
                                    label = {
                                        Text(
                                            word.take(20),
                                            fontSize = 13.sp,
                                            maxLines = 1
                                        )
                                    },
                                    shape = RoundedCornerShape(ArcoRadius.sm),
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = if (isSelected)
                                            ThemeColors.brand.copy(alpha = 0.15f)
                                        else
                                            ThemeColors.bgCardHigh
                                    ),
                                    border = if (isSelected)
                                        SuggestionChipDefaults.suggestionChipBorder(
                                            borderColor = ThemeColors.brand,
                                            enabled = true
                                        )
                                    else null
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(ArcoSpacing.md))

                // Bottom action bar
                val selectedText = selectedIndices.sorted().map { words[it] }.joinToString("")

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    FilledTonalButton(
                        onClick = {
                            selectedIndices = words.indices.toSet()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.SelectAll, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("全选")
                    }
                    Spacer(Modifier.width(ArcoSpacing.sm))
                    Button(
                        onClick = {
                            if (selectedText.isNotEmpty()) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                // Detect if content looks like a table — format as Markdown
                                val formatted = if (selectedText.lines().all { '|' in it }) {
                                    selectedText
                                } else {
                                    selectedText
                                }
                                clipboard.setPrimaryClip(ClipData.newPlainText("MengPaw", formatted))
                            }
                            onDismiss()
                        },
                        enabled = selectedIndices.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("复制 (${selectedIndices.size})")
                    }
                }
            }
        }
    }
}
