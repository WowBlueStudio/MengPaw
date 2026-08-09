// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

@OptIn(ExperimentalFoundationApi::class)
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
    var showMenu by remember { mutableStateOf(false) }
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

    Box {
        Box(
            Modifier.combinedClickable(
                onClick = { showMenu = false },
                onLongClick = { showMenu = true }
            )
        ) { content() }

        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(text = { Text("复制") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)) }, onClick = {
                (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("MengPaw", bubbleText))
                showMenu = false
            })
            DropdownMenuItem(text = { Text("大爆炸") }, leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp)) }, onClick = { showBigBang = true; showMenu = false })
            DropdownMenuItem(text = { Text("引用") }, leadingIcon = { Icon(Icons.Outlined.FormatQuote, null, Modifier.size(18.dp)) }, onClick = {
                onQuote(viewModel.formatQuote(message)); showMenu = false
            })
            if (message is ChatMessageUi.User && viewModel.isLastUserMessage(message)) {
                DropdownMenuItem(text = { Text("撤回") }, leadingIcon = { Icon(Icons.Outlined.Undo, null, Modifier.size(18.dp)) }, onClick = {
                    viewModel.retractLastUserMessage()?.let { onRetract(it) }; showMenu = false
                })
            }
            // Image save for Agent messages
            val imgs = MARKDOWN_IMAGE_REGEX.findAll(bubbleText).toList()
            if (imgs.isNotEmpty()) {
                DropdownMenuItem(text = { Text("保存图片 (${imgs.size})") }, leadingIcon = { Icon(Icons.Outlined.SaveAlt, null, Modifier.size(18.dp)) }, onClick = {
                    imgs.forEach { m ->
                        val p = m.groupValues[1]
                        if (!p.startsWith("http")) try {
                            java.io.File(p).copyTo(java.io.File(com.mengpaw.kernel.DataPaths.SCREENSHOTS, "saved_${System.currentTimeMillis()}.png"), overwrite = true)
                        } catch (_: Exception) { }
                    }
                    showMenu = false
                })
                DropdownMenuItem(text = { Text("标注图片发回") }, leadingIcon = { Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)) }, onClick = {
                    imgs.firstOrNull()?.groupValues?.get(1)?.let { if (!it.startsWith("http")) onQuote("标注图片: $it") }
                    showMenu = false
                })
            }
            DropdownMenuItem(text = { Text("一键分享") }, leadingIcon = { Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)) }, onClick = {
                val si = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, bubbleText.take(500))
                }
                context.startActivity(android.content.Intent.createChooser(si, "分享到"))
                showMenu = false
            })
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
}
