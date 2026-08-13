// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.shell.ui.screens.model.ChatMessageUi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mengpaw.design.components.MarkdownTableBorderColor
import com.mengpaw.design.components.MarkdownText
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.shell.ui.localization.AppStrings

/** Regex for extracting image paths from Markdown: ![alt](path). Cached. */
val MARKDOWN_IMAGE_REGEX = Regex("!\\[.*?]\\((.*?)\\)")

// ── Plugin suggestion card ──
@Composable
fun PluginSuggestionCard(suggestion: PluginSuggestion, onInstall: () -> Unit, onViewDetail: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(ArcoRadius.lg),
        colors = CardDefaults.cardColors(containerColor = ArcoColors.Orange1.copy(alpha = 0.3f))) {
        Column(Modifier.padding(ArcoSpacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Extension, null, tint = ArcoColors.Orange6, modifier = Modifier.size(20.dp))
                Text("需要安装插件", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = ArcoColors.Orange6, style = MaterialTheme.typography.bodySmall)
            }
            Text("${suggestion.pluginName} (${suggestion.pluginId})", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
            Spacer(Modifier.height(ArcoSpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.sm)) {
                Button(onClick = onInstall, shape = RoundedCornerShape(ArcoRadius.md),
                    contentPadding = PaddingValues(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.xs),
                    colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.brand))
                { Text("一键安装", color = Color.White, style = MaterialTheme.typography.labelSmall) }
                OutlinedButton(onClick = onViewDetail, shape = RoundedCornerShape(ArcoRadius.md),
                    contentPadding = PaddingValues(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.xs))
                { Text("查看详情", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

// ── Bubbles ──
/** Right-aligned, auto-width capped at 400dp, tail at bottom-right.
 *  v0.33.0+: 接收完整 User 消息 — 附件在文本下方渲染专用卡片。 */
@Composable
fun UserBubble(message: ChatMessageUi.User) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(ArcoRadius.lg, ArcoRadius.lg, ArcoRadius.sm, ArcoRadius.lg),
            color = ThemeColors.brand,
            modifier = Modifier.widthIn(max = 400.dp)
        ) {
            Column(Modifier.padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.md)) {
                if (message.content.isNotBlank()) {
                    SelectionContainer {
                        MarkdownText(
                            content = message.content,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                            inlineCodeColor = Color.White.copy(alpha = 0.9f),
                            linkColor = Color.White,
                            nestedScroll = true,
                            // 用户气泡: 表格文字白色可读, 框线 50% 灰度灰线 (v0.37.4 统一)
                            tableTextColor = Color.White,
                            tableBorderColor = MarkdownTableBorderColor
                        )
                    }
                }
                if (message.attachments.isNotEmpty()) {
                    Spacer(Modifier.height(ArcoSpacing.xs))
                    AttachmentCardList(message.attachments, isUserSide = true)
                }
            }
        }
    }
}

/** Shared header for Agent bubbles — agent name + execution mode + agent ref. */
@Composable
fun AgentBubbleHeader(
    agentName: String,
    executionMode: String?,
    agentRef: String?,
    extraBadge: @Composable (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (executionMode != null) {
            Text(" · $executionMode",
                style = MaterialTheme.typography.labelSmall,
                color = ThemeColors.brand)
        }
        if (agentRef != null) {
            Text(" · @$agentRef",
                style = MaterialTheme.typography.labelSmall,
                color = ArcoColors.Orange6)
        }
        extraBadge?.invoke()
    }
}

/** Left-aligned, max 90% width, tail at bottom-left.
 *  v0.33.0+: content 先经 extractMedia 提取媒体引用 → 卡片渲染在文本下方。 */
@Composable
fun AgentBubble(content: String, agentName: String = "MengPaw",
    executionMode: String? = null, agentRef: String? = null
) {
    val (cleanContent, media) = remember(content) { extractMedia(content) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(ArcoRadius.lg, ArcoRadius.lg, ArcoRadius.lg, ArcoRadius.sm),
            color = ThemeColors.bgCardHigh,
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Column(Modifier.padding(ArcoSpacing.lg)) {
                AgentBubbleHeader(agentName, executionMode, agentRef)
                Spacer(Modifier.height(ArcoSpacing.xs))
                if (cleanContent.isNotBlank()) {
                    SelectionContainer {
                        MarkdownText(
                            content = cleanContent,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = ThemeColors.textPrimary),
                            nestedScroll = true
                        )
                    }
                }
                if (media.isNotEmpty()) {
                    Spacer(Modifier.height(ArcoSpacing.sm))
                    AttachmentCardList(media, isUserSide = false)
                }
            }
        }
    }
}

/** Result of a "!command" — left-aligned, agent-bubble style; red-tinted on failure. */
@Composable
fun CommandResultBubble(message: ChatMessageUi.CommandResult, strings: AppStrings) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(ArcoRadius.lg, ArcoRadius.lg, ArcoRadius.lg, ArcoRadius.sm),
            color = if (message.isError) ArcoColors.Red1.copy(alpha = 0.35f) else ThemeColors.bgCardHigh,
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Column(Modifier.padding(ArcoSpacing.lg)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (message.isError) Icons.Outlined.Error else Icons.Outlined.ChevronRight, null,
                        Modifier.size(13.dp), tint = if (message.isError) ArcoColors.Red6 else ThemeColors.textSecondary)
                    Spacer(Modifier.width(4.dp))
                    Text(if (message.isError) strings.commandFailedLabel else strings.commandOutputLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.isError) ArcoColors.Red6 else ThemeColors.textSecondary)
                }
                Spacer(Modifier.height(ArcoSpacing.xs))
                SelectionContainer {
                    MarkdownText(
                        content = message.content,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = ThemeColors.textPrimary),
                        nestedScroll = true
                    )
                }
            }
        }
    }
}

/**
 * Loading card shown in chat while Agent silently initializes.
 * Static layout — no animation to avoid CPU overhead during init.
 */
@Composable
fun AgentInitializingCard() {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "正在初始化 Agent...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
