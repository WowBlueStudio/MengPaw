// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.shell.ui.components.BigBangPopup

// ═══════════════════════════════════════════════════════════════════════
// Bubble long-press context menu — 拆自 ChatBubbles.kt (2026-08-06, 批次4)
// ═══════════════════════════════════════════════════════════════════════

// Helper: resolve icon name string to Material ImageVector
fun pluginIconForName(name: String): ImageVector = when (name.lowercase()) {
    "image", "photo", "picture" -> Icons.Outlined.Image
    "search" -> Icons.Outlined.Search
    "description", "document" -> Icons.Outlined.Description
    "attachfile", "file" -> Icons.Outlined.AttachFile
    "camera", "photocamera" -> Icons.Outlined.PhotoCamera
    "star", "favorite" -> Icons.Outlined.Star
    "extension" -> Icons.Outlined.Extension
    "language", "translate" -> Icons.Outlined.Language
    "terminal", "code" -> Icons.Outlined.Terminal
    "settings" -> Icons.Outlined.Settings
    "notifications" -> Icons.Outlined.Notifications
    "contentpaste", "clipboard" -> Icons.Outlined.ContentPaste
    "touchapp", "gesture" -> Icons.Outlined.TouchApp
    "android" -> Icons.Outlined.Android
    "smarttoy", "robot" -> Icons.Outlined.SmartToy
    "send" -> Icons.Outlined.Send
    "share" -> Icons.Outlined.Share
    "lock" -> Icons.Outlined.Lock
    "history" -> Icons.Outlined.History
    else -> Icons.Outlined.Extension
}

@Composable
fun BubbleWrapper(
    message: ChatMessageUi,
    viewModel: AgentViewModel,
    onRetract: (String) -> Unit,
    onQuote: (String) -> Unit,
    pluginViewModel: PluginViewModel,
    onNavigateToPlugins: () -> Unit,
    content: @Composable () -> Unit
) {
    var showBigBang by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val bubbleText = when (message) {
        is ChatMessageUi.User -> message.content
        is ChatMessageUi.Agent -> message.content
        is ChatMessageUi.AgentWithTrace -> message.finalContent
        is ChatMessageUi.FinalAnswer -> message.content
        is ChatMessageUi.CommandResult -> message.content
        else -> ""
    }

    // v0.35.1: 去掉长按气泡动作 + 点击动画 — 原长按菜单功能改为气泡下方线性图标
    Column(Modifier.fillMaxWidth()) {
        // 气泡本体 — 无点击/长按 (indication = null 去点击动画)
        Box(Modifier.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
            onClick = {}
        )) { content() }

        // 输出气泡 (Agent) 下方操作图标行
        if (message is ChatMessageUi.Agent || message is ChatMessageUi.AgentWithTrace ||
            message is ChatMessageUi.FinalAnswer || message is ChatMessageUi.CommandResult) {
            val imgs = MARKDOWN_IMAGE_REGEX.findAll(bubbleText).toList()
            BubbleActionBar(
                hasImages = imgs.isNotEmpty(),
                onCopy = {
                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("MengPaw", bubbleText))
                },
                onBigBang = { showBigBang = true },
                onQuote = { onQuote(viewModel.formatQuote(message)) },
                onSaveImages = {
                    imgs.forEach { m ->
                        val p = m.groupValues[1]
                        if (!p.startsWith("http")) try {
                            java.io.File(p).copyTo(
                                java.io.File(com.mengpaw.kernel.DataPaths.SCREENSHOTS, "saved_${System.currentTimeMillis()}.png"),
                                overwrite = true
                            )
                        } catch (_: Exception) {}
                    }
                },
                onMarkImage = {
                    imgs.firstOrNull()?.groupValues?.get(1)
                        ?.let { if (!it.startsWith("http")) onQuote("标注图片: $it") }
                },
                onShare = {
                    val si = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, bubbleText.take(500))
                    }
                    context.startActivity(android.content.Intent.createChooser(si, "分享到"))
                }
            )
        } else if (message is ChatMessageUi.User) {
            // 用户气泡: 复制 / 大爆炸 / 撤回 (最后一条) / 分享
            val canRetract = viewModel.isLastUserMessage(message)
            UserActionBar(
                canRetract = canRetract,
                onCopy = {
                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("MengPaw", bubbleText))
                },
                onBigBang = { showBigBang = true },
                onRetract = { viewModel.retractLastUserMessage()?.let { onRetract(it) } },
                onShare = {
                    val si = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, bubbleText.take(500))
                    }
                    context.startActivity(android.content.Intent.createChooser(si, "分享到"))
                }
            )
        }
    }

    if (showBigBang) {
        BigBangPopup(
            text = bubbleText,
            onDismiss = { showBigBang = false },
            onCopy = { sel ->
                (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("MengPaw", sel))
                showBigBang = false
            }
        )
    }
}

/** 输出气泡下方操作图标行 (v0.35.1) — 复制/大爆炸/引用/保存图片/标注图片/分享。 */
@Composable
private fun BubbleActionBar(
    hasImages: Boolean,
    onCopy: () -> Unit,
    onBigBang: () -> Unit,
    onQuote: () -> Unit,
    onSaveImages: () -> Unit,
    onMarkImage: () -> Unit,
    onShare: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionIcon(Icons.Outlined.ContentCopy, "复制", onCopy)
        ActionIcon(Icons.Outlined.AutoAwesome, "大爆炸", onBigBang)
        ActionIcon(Icons.Outlined.FormatQuote, "引用", onQuote)
        if (hasImages) {
            ActionIcon(Icons.Outlined.SaveAlt, "保存图片", onSaveImages)
            ActionIcon(Icons.Outlined.Edit, "标注图片", onMarkImage)
        }
        ActionIcon(Icons.Outlined.Share, "分享", onShare)
    }
}

/** 用户气泡下方操作图标行 (v0.35.1) — 复制/大爆炸/撤回/分享。 */
@Composable
private fun UserActionBar(
    canRetract: Boolean,
    onCopy: () -> Unit,
    onBigBang: () -> Unit,
    onRetract: () -> Unit,
    onShare: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionIcon(Icons.Outlined.ContentCopy, "复制", onCopy)
        ActionIcon(Icons.Outlined.AutoAwesome, "大爆炸", onBigBang)
        if (canRetract) {
            ActionIcon(Icons.Outlined.Undo, "撤回", onRetract)
        }
        ActionIcon(Icons.Outlined.Share, "分享", onShare)
    }
}

/** 单个线性操作图标 (无点击动画)。 */
@Composable
private fun ActionIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Icon(
        icon, label, tint = com.mengpaw.design.theme.ThemeColors.textSecondary,
        modifier = Modifier
            .size(18.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick
            )
    )
}
