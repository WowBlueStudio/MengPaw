// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.shell.ui.screens.model.AgentTrace
import com.mengpaw.shell.ui.screens.model.ChatMessageUi

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.components.MarkdownText
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.shell.ui.components.BigBangPopup
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
                Text("需要安装插件", fontWeight = FontWeight.SemiBold, color = ArcoColors.Orange6, style = MaterialTheme.typography.bodySmall)
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
                            nestedScroll = true
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

// ── Agent Bubble with Trace (expandable thinking steps) ──
@Composable
fun AgentBubbleWithTrace(message: ChatMessageUi.AgentWithTrace, agentName: String = "MengPaw") {
    val traces = message.traces
    var thinkingExpanded by remember { mutableStateOf(true) }
    LaunchedEffect(message.isRunning) { if (!message.isRunning) thinkingExpanded = false }

    Column(Modifier.fillMaxWidth()) {
        // ── Thinking process (outside main bubble, visible while running) ──
        if (traces.isNotEmpty()) {
            Column(Modifier.fillMaxWidth(0.95f).padding(bottom = 2.dp)
                .clickable { thinkingExpanded = !thinkingExpanded }) {
                Row(Modifier.padding(horizontal = ArcoSpacing.sm, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Psychology, null, Modifier.size(16.dp), tint = ThemeColors.brand)
                    Spacer(Modifier.width(6.dp))
                    Text("思考过程 (${traces.size})",
                        style = MaterialTheme.typography.labelSmall, color = ThemeColors.brand)
                    Spacer(Modifier.width(4.dp))
                    Icon(if (thinkingExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        null, Modifier.size(16.dp), tint = ThemeColors.brand)
                }
                AnimatedVisibility(visible = thinkingExpanded) {
                    Column(Modifier.padding(start = ArcoSpacing.sm, end = ArcoSpacing.sm, bottom = ArcoSpacing.sm)) {
                        traces.forEach { trace -> TraceStepItem(trace) }
                    }
                }
            }
        }

        // ── Final answer bubble ──
        // v0.33.0+: 运行中(流式)不提取媒体 — 文件/图片未落盘时无意义, 完成态提取一次
        val (cleanFinal, mediaCards) = remember(message.finalContent) { extractMedia(message.finalContent) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Surface(shape = RoundedCornerShape(ArcoRadius.lg, ArcoRadius.lg, ArcoRadius.lg, ArcoRadius.sm),
                color = ThemeColors.bgCardHigh, modifier = Modifier.fillMaxWidth(0.9f)) {
                Column(Modifier.padding(ArcoSpacing.lg)) {
                    AgentBubbleHeader(agentName = agentName, executionMode = message.executionMode, agentRef = message.agentRef)
                    Spacer(Modifier.height(ArcoSpacing.xs))
                    if (cleanFinal.isNotBlank()) {
                        SelectionContainer {
                            MarkdownText(content = cleanFinal,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = ThemeColors.textPrimary), nestedScroll = true)
                        }
                    }
                    if (mediaCards.isNotEmpty() && !message.isRunning) {
                        Spacer(Modifier.height(ArcoSpacing.sm))
                        AttachmentCardList(mediaCards, isUserSide = false)
                    }
                    // ── 等待期反馈 (v0.28.6): 思考中 → spinner + 已等待秒数, 流式文本到达后自动消失
                    //    (v0.29.2): 工具轮显示 "正在执行 X… Ns" — 流式检测到 Action 行即推送 (Reasonix ③) ──
                    if (message.isRunning &&
                        (message.finalContent == "思考中..." || message.finalContent.isBlank() ||
                            message.finalContent.startsWith(EXECUTING_TOOL_PREFIX))) {
                        Spacer(Modifier.height(ArcoSpacing.xs))
                        WaitingIndicator(message.finalContent)
                    }
                }
            }
        }
    }
}

/**
 * 等待期指示器: spinner + 已等待秒数 — 让 4-13s 的 LLM 准备期有"活着"的反馈.
 * [waitingText] = "思考中..." (无工具) 或 "$EXECUTING_TOOL_PREFIX<tool>…" (工具轮, v0.29.2).
 */
@Composable
private fun WaitingIndicator(waitingText: String) {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); seconds++ } }
    val label = if (waitingText.startsWith(EXECUTING_TOOL_PREFIX))
        waitingText.removePrefix(EXECUTING_TOOL_PREFIX) else "思考中…"
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = ThemeColors.brand)
        Spacer(Modifier.width(6.dp))
        Text("$label ${seconds}s", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
    }
}

@Composable
fun TraceStepItem(trace: AgentTrace) {
    val actionLong = (trace.action?.length ?: 0) > 60
    val observationLong = (trace.observation?.length ?: 0) > 150
    var mergedExpanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(vertical = 1.dp).padding(horizontal = ArcoSpacing.sm, vertical = 4.dp)) {
        // ── Thought: step number + brain icon, always fully visible ──
        // 多 Action 并行的后续工具 thought 为空 → 缩进渲染纯工具行, 不重复显示思考
        val isParallelTool = trace.thought.isBlank()
        if (!isParallelTool) {
            Row(verticalAlignment = Alignment.Top) {
                Text("Step${trace.step}", fontSize = 10.sp, color = ArcoColors.Blue5,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Outlined.Psychology, null, Modifier.size(14.dp).padding(top = 2.dp), tint = ArcoColors.Blue4)
                Spacer(Modifier.width(4.dp))
                Text(trace.thought, style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
            }
        }
        // ── Action + Observation: terminal icon, merged into one collapsible block ──
        if (trace.action != null || !trace.observation.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Column(Modifier.fillMaxWidth()
                .padding(horizontal = if (isParallelTool) 26.dp else 6.dp, vertical = 3.dp)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { mergedExpanded = !mergedExpanded }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Terminal, null, Modifier.size(13.dp), tint = ArcoColors.Gray6)
                    Spacer(Modifier.width(4.dp))
                    Text(trace.action ?: "(observation)", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = ThemeColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    if (actionLong || observationLong) {
                        Text(if (mergedExpanded) "▲" else "▼", fontSize = 9.sp, color = ThemeColors.textSecondary)
                    }
                }
                AnimatedVisibility(visible = mergedExpanded) {
                    Column {
                        if (actionLong && trace.action != null) {
                            Text(trace.action, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp), color = ArcoColors.Gray6)
                        }
                        if (!trace.observation.isNullOrBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(trace.observation.take(if (mergedExpanded) 5000 else 200),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp), color = ArcoColors.Gray5,
                                maxLines = if (mergedExpanded) Int.MAX_VALUE else 3)
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Bubble long-press context menu
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
