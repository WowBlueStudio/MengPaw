// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import com.mengpaw.shell.ui.components.NotifyBannerHost
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.tokens.ArcoColors
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mengpaw.design.components.ArcoDivider
import com.mengpaw.design.components.MarkdownText
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.shell.ui.MAX_CONTENT_WIDTH
import com.mengpaw.shell.ui.isWide
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToSettings: () -> Unit = {},
    onNavigateToPlugins: () -> Unit = {},
    onNavigateToBrowser: () -> Unit = {},
    strings: com.mengpaw.shell.ui.localization.AppStrings = com.mengpaw.shell.ui.localization.EnglishStrings,
    settingsViewModel: SettingsViewModel? = null,
    viewModel: AgentViewModel = viewModel(),
    pluginViewModel: PluginViewModel = viewModel(),
    agentViewModel: AgentViewModel? = null,
    leftSidebarContent: @Composable (close: () -> Unit) -> Unit = {},
    rightSidebarContent: @Composable (close: () -> Unit) -> Unit = {}
) {
    val messages by viewModel.messages.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val inputEnabled by viewModel.inputEnabled.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showExpandSheet by remember { mutableStateOf(false) }
    var showMissionOverlay by remember { mutableStateOf(false) }

    // FIX U17+U6: Derive filtered list once to avoid allocation per recomposition
    val displayedMessages by remember(messages) {
        derivedStateOf { messages.filter { it !is ChatMessageUi.System } }
    }

    val settingsState = settingsViewModel?.state?.collectAsState()
    val activeAgentState = agentViewModel?.activeAgent?.collectAsState()
    val displayAgentName = activeAgentState?.value ?: "MengPaw"
    // React to language-only changes without full reconfig
    LaunchedEffect(settingsState?.value?.agentLanguageMode, settingsState?.value?.useChinese) {
        settingsState?.value?.let { s ->
            viewModel.setAgentLanguage(s.effectiveAgentLanguage)
        }
    }

    val agentFramework: String? = remember(displayAgentName) {
        agentViewModel?.frameworkFor(displayAgentName)
    }
    var showLeftSidebar by remember { mutableStateOf(false) }
    var showRightSidebar by remember { mutableStateOf(false) }
    // FIX U2: Scroll uses filtered list size, and only auto-scrolls when user is at bottom
    LaunchedEffect(displayedMessages.size) {
        if (displayedMessages.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val nearBottom = lastVisible >= displayedMessages.size - 3
            if (nearBottom) listState.animateScrollToItem(displayedMessages.size - 1)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxSize().widthIn(max = MAX_CONTENT_WIDTH.dp)) {
            // ── Header bar ──
            Surface(
                shadowElevation = 2.dp,
                color = ThemeColors.bgPrimary.copy(alpha = 0.92f)
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left sidebar toggle
                    IconButton(onClick = { showLeftSidebar = !showLeftSidebar }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Menu, "侧边栏", tint = ThemeColors.textSecondary)
                    }
                    Spacer(Modifier.width(4.dp))
                    // Agent avatar — 44dp circle, matching send button
                    val avatarFile = File(com.mengpaw.kernel.DataPaths.AGENTS, "avatar.png")
                    val avatarBitmap = remember { if (avatarFile.exists()) BitmapFactory.decodeFile(avatarFile.absolutePath) else null }
                    if (avatarBitmap != null) {
                        Image(bitmap = avatarBitmap.asImageBitmap(), null, Modifier.size(44.dp).clip(CircleShape))
                    } else {
                        Surface(shape = CircleShape, color = ThemeColors.brandContainer, modifier = Modifier.size(44.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(displayAgentName.take(1), color = ThemeColors.brand, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            }
                        }
                    }
                    Spacer(Modifier.width(ArcoSpacing.sm))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(displayAgentName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                            if (agentFramework != null) {
                                Spacer(Modifier.width(4.dp))
                                Text("@$agentFramework",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ThemeColors.textSecondary,
                                    maxLines = 1)
                            }
                        }
                        Text(agentViewModel?.activeSessionLabel ?: "MengPaw / 未配置",
                            style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, maxLines = 1)
                    }
                    if (isRunning) {
                        LinearProgressIndicator(Modifier.width(60.dp).height(4.dp), color = ThemeColors.brand)
                        Spacer(Modifier.width(ArcoSpacing.sm))
                    }
                    IconButton(onClick = { viewModel.newSession() }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Add, "新建会话", tint = ThemeColors.textSecondary)
                    }
                    // Mission monitor toggle (visible when mission is active)
                    if (com.mengpaw.kernel.mission.MissionMonitor.missionActive) {
                        IconButton(onClick = { showMissionOverlay = !showMissionOverlay }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Outlined.Monitor, "Mission", tint = ThemeColors.brand)
                        }
                    }
                    // FIX: Dynamic plugin buttons from HEADER_BAR placement
                    val headerButtons = remember { pluginViewModel.activeButtons[com.mengpaw.kernel.plugin.ButtonPlacement.HEADER_BAR] ?: emptyList() }
                    if (headerButtons.isNotEmpty()) {
                        headerButtons.take(2).forEach { btn ->
                            IconButton(
                                onClick = {
                                    if (btn.command.isNotBlank()) inputText = btn.command
                                },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(pluginIconForName(btn.iconName), btn.label, tint = ThemeColors.brand)
                            }
                        }
                    }
                    IconButton(onClick = { showRightSidebar = !showRightSidebar }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.History, "历史", tint = ThemeColors.textSecondary)
                    }
                }
            }
            ArcoDivider()

            // ── Content area: adaptive — persistent sidebar on wide, overlay on compact ──
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.fillMaxSize()) {
                    // Persistent left sidebar (tablet only)
                    if (isWide() && showLeftSidebar) {
                        Surface(color = ThemeColors.bgSecondary) {
                            leftSidebarContent { showLeftSidebar = false }
                        }
                    }

                    // Agent-pushed banner notifications — click navigates to MengPaw agent
                    NotifyBannerHost(
                        onMessage = { text ->
                            viewModel.notifyAgentMessage(text)
                        },
                        onBannerClick = {
                            (agentViewModel ?: viewModel).switchAgent("MengPaw")
                        }
                    )

                    // Messages
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = ArcoSpacing.lg),
                        state = listState, verticalArrangement = Arrangement.spacedBy(ArcoSpacing.sm),
                        contentPadding = PaddingValues(vertical = ArcoSpacing.md)
                    ) {
                        items(displayedMessages, key = { it.stableId }) { message ->
                            BubbleWrapper(
                                message = message,
                                viewModel = viewModel,
                                onRetract = { inputText = it },
                                onQuote = { quoteText -> inputText = "$quoteText\n$inputText" },
                                pluginViewModel = pluginViewModel,
                                onNavigateToPlugins = onNavigateToPlugins
                            ) {
                                when (message) {
                                    is ChatMessageUi.User -> UserBubble(message.content)
                                    is ChatMessageUi.Agent -> AgentBubble(message.content)
                                    is ChatMessageUi.AgentWithTrace -> AgentBubbleWithTrace(message)
                                    is ChatMessageUi.Suggestion -> PluginSuggestionCard(message.suggestion,
                                        onInstall = { pluginViewModel.installPlugin(message.suggestion.pluginId) },
                                        onViewDetail = onNavigateToPlugins)
                                    else -> {}
                                }
                            }
                        }
                    }

                    // Persistent right sidebar (tablet only)
                    if (isWide() && showRightSidebar) {
                        Surface(color = ThemeColors.bgSecondary) {
                            rightSidebarContent { showRightSidebar = false }
                        }
                    }
                }

                // Overlays — outside Row to avoid scope conflict
                if (!isWide()) {
                    SidebarOverlay(showLeftSidebar, fromLeft = true,
                        onDismiss = { showLeftSidebar = false },
                        content = { leftSidebarContent { showLeftSidebar = false } })
                    SidebarOverlay(showRightSidebar, fromLeft = false,
                        onDismiss = { showRightSidebar = false },
                        content = { rightSidebarContent { showRightSidebar = false } })
                }
            }

            // ── Bottom input bar ──
            Surface(shadowElevation = 8.dp, color = ThemeColors.bgPrimary) {
                Row(
                    Modifier.fillMaxWidth()
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(start = ArcoSpacing.lg, end = 8.dp, bottom = ArcoSpacing.sm, top = ArcoSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Expand button — circular 44dp, linear "+" icon, matching send button
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(ThemeColors.surfaceContainerHigh, CircleShape)
                            .clickable { showExpandSheet = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Add, "扩展", tint = ThemeColors.textSecondary, modifier = Modifier.size(24.dp))
                    }
                    // Input field
                    OutlinedTextField(value = inputText, onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp), enabled = inputEnabled,
                        placeholder = { Text(strings.inputPlaceholder) },
                        shape = RoundedCornerShape(ArcoRadius.lg),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ThemeColors.brand, unfocusedBorderColor = ThemeColors.border),
                        minLines = 1, maxLines = 4)
                    // Send button — circular 44dp, animated ↑ icon
                    val maxSteps = settingsState?.value?.maxSteps ?: 50
                    val scope = rememberCoroutineScope()
                    val arrowOffsetY = remember { Animatable(0f) }
                    val arrowAlpha = remember { Animatable(1f) }
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                if (inputEnabled) ThemeColors.brand
                                else ThemeColors.brand.copy(alpha = 0.38f),
                                CircleShape
                            )
                            .clickable(enabled = inputEnabled) {
                                if (inputText.isNotBlank()) {
                                    val text = inputText; inputText = ""
                                    scope.launch {
                                        // ↑ flies upward and out
                                        launch { arrowOffsetY.animateTo(-60f, tween(280)) }
                                        launch { arrowAlpha.animateTo(0f, tween(280)) }
                                        // snap below, then submit
                                        arrowOffsetY.snapTo(60f)
                                        viewModel.submitTask(text, pluginViewModel, maxSteps = maxSteps)
                                        // ↑ flies in from below
                                        launch { arrowOffsetY.animateTo(0f, tween(280)) }
                                        launch { arrowAlpha.animateTo(1f, tween(280)) }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (inputText.isEmpty()) Icons.Outlined.ArrowUpward else Icons.Filled.ArrowUpward,
                            "发送",
                            tint = Color.White,
                            modifier = Modifier
                                .offset(y = arrowOffsetY.value.dp)
                                .alpha(arrowAlpha.value)
                        )
                    }
                }
            }
        }
    }

    // ── Expand bottom sheet (upload tools + plugins) ──
    if (showExpandSheet) {
        ModalBottomSheet(onDismissRequest = { showExpandSheet = false }, sheetState = sheetState,
            containerColor = ThemeColors.bgPrimary) {
            Column(Modifier.padding(ArcoSpacing.lg).padding(bottom = 32.dp)) {
                Text("扩展功能", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(ArcoSpacing.lg))

                val sheetButtons = pluginViewModel.activeButtons[com.mengpaw.kernel.plugin.ButtonPlacement.BOTTOM_SHEET] ?: emptyList()
                if (sheetButtons.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        sheetButtons.take(4).forEach { btn ->
                            ExpandItem(pluginIconForName(btn.iconName), btn.label) {
                                showExpandSheet = false; inputText = btn.command
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }

                Spacer(Modifier.height(24.dp))
                Text("插件工具", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(ArcoSpacing.sm))

                val installed = pluginViewModel.pluginItems.collectAsState().value.filter { it.isActive }
                if (installed.isEmpty()) {
                    Text("暂无已激活插件。在插件管理中安装并启用。", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                } else {
                    installed.take(6).forEach { p ->
                        Row(Modifier.fillMaxWidth().clickable { showExpandSheet = false; onNavigateToPlugins() }.padding(vertical = ArcoSpacing.sm)) {
                            Icon(Icons.Outlined.Extension, null, Modifier.size(20.dp), tint = ThemeColors.brand)
                            Spacer(Modifier.width(ArcoSpacing.sm))
                            Text(p.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(ArcoSpacing.lg))
            }
        }
    }
}

@Composable
private fun ExpandItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onClick, shape = RoundedCornerShape(ArcoRadius.lg), color = ThemeColors.bgCardHigh,
            modifier = Modifier.size(56.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(28.dp), tint = ThemeColors.textSecondary)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
    }
}

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
@Composable private fun UserBubble(content: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(shape = RoundedCornerShape(ArcoRadius.lg, ArcoRadius.lg, ArcoRadius.lg, ArcoRadius.sm), color = ThemeColors.brand) {
            SelectionContainer {
                MarkdownText(
                    content = content,
                    modifier = Modifier.padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.md),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    inlineCodeColor = Color.White.copy(alpha = 0.9f),
                    linkColor = Color.White
                )
            }
        }
    }
}

@Composable private fun AgentBubble(content: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(shape = RoundedCornerShape(ArcoRadius.lg, ArcoRadius.lg, ArcoRadius.sm, ArcoRadius.lg), color = ThemeColors.bgCardHigh) {
            Column(Modifier.padding(ArcoSpacing.lg)) {
                Text("MengPaw", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = ThemeColors.brand)
                Spacer(Modifier.height(ArcoSpacing.xs))
                SelectionContainer {
                    MarkdownText(
                        content = content,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = ThemeColors.textPrimary)
                    )
                }
            }
        }
    }
}

// ── Agent Bubble with Trace (expandable thinking steps) ──
@Composable private fun AgentBubbleWithTrace(message: ChatMessageUi.AgentWithTrace) {
    var expanded by remember { mutableStateOf(false) }
    val traces = message.traces

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(ArcoRadius.lg, ArcoRadius.lg, ArcoRadius.sm, ArcoRadius.lg),
            color = ThemeColors.bgCardHigh
        ) {
            Column(Modifier.padding(ArcoSpacing.lg)) {
                Text("MengPaw", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = ThemeColors.brand)
                Spacer(Modifier.height(ArcoSpacing.xs))

                // Collapsible trace steps
                if (traces.isNotEmpty()) {
                    Surface(
                        onClick = { expanded = !expanded },
                        shape = RoundedCornerShape(ArcoRadius.sm),
                        color = ArcoColors.Blue1.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = ArcoSpacing.sm, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                null, Modifier.size(16.dp), tint = ThemeColors.brand
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (expanded) "收起思考过程" else "思考过程 (${traces.size} 步)",
                                style = MaterialTheme.typography.labelSmall,
                                color = ThemeColors.brand,
                                fontWeight = FontWeight.Medium
                            )
                            if (message.isRunning) {
                                Spacer(Modifier.width(ArcoSpacing.sm))
                                LinearProgressIndicator(
                                    Modifier.width(40.dp).height(4.dp),
                                    color = ThemeColors.brand
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = expanded) {
                        Column(Modifier.padding(top = ArcoSpacing.xs)) {
                            traces.forEach { trace -> TraceStepItem(trace) }
                        }
                    }

                    Spacer(Modifier.height(ArcoSpacing.sm))
                } else if (message.isRunning) {
                    // Initial "thinking" with no traces yet
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(Modifier.width(80.dp).height(4.dp), color = ThemeColors.brand)
                        Spacer(Modifier.width(ArcoSpacing.sm))
                        Text("思考中...", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                    }
                    Spacer(Modifier.height(ArcoSpacing.sm))
                }

                // Final content
                SelectionContainer {
                    MarkdownText(
                        content = message.finalContent,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = ThemeColors.textPrimary)
                    )
                }
            }
        }
    }
}

@Composable private fun TraceStepItem(trace: AgentTrace) {
    Surface(
        shape = RoundedCornerShape(ArcoRadius.sm),
        color = ThemeColors.bgCard,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Column(Modifier.padding(ArcoSpacing.sm)) {
            Row(verticalAlignment = Alignment.Top) {
                Text("Step ${trace.step}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = ThemeColors.brand)
                Spacer(Modifier.width(ArcoSpacing.xs))
                Text(
                    "🤔 ${trace.thought.take(150)}${if (trace.thought.length > 150) "..." else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ThemeColors.textSecondary,
                    maxLines = 3
                )
            }
            if (trace.action != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "🔧 ${trace.action}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ThemeColors.brand,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }
            if (trace.observation != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "📊 ${trace.observation.take(120)}${if (trace.observation.length > 120) "..." else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ThemeColors.textSecondary,
                    maxLines = 2
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Bubble long-press context menu
// ═══════════════════════════════════════════════════════════════════════


// Helper: resolve icon name string to Material ImageVector
private fun pluginIconForName(name: String): androidx.compose.ui.graphics.vector.ImageVector = when (name.lowercase()) {
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
}@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BubbleWrapper(
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val bubbleText = when (message) {
        is ChatMessageUi.User -> message.content
        is ChatMessageUi.Agent -> message.content
        is ChatMessageUi.AgentWithTrace -> message.finalContent
        else -> ""
    }

    Box {
        Box(
            Modifier.combinedClickable(
                onClick = {},
                onLongClick = { showMenu = true }
            )
        ) { content() }

        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(text = { Text("📋 复制") }, onClick = {
                (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("MengPaw", bubbleText))
                showMenu = false
            })
            DropdownMenuItem(text = { Text("💥 大爆炸") }, onClick = { showBigBang = true; showMenu = false })
            DropdownMenuItem(text = { Text("💬 引用") }, onClick = {
                onQuote(viewModel.formatQuote(message)); showMenu = false
            })
            if (message is ChatMessageUi.User && viewModel.isLastUserMessage(message)) {
                DropdownMenuItem(text = { Text("↩ 撤回") }, onClick = {
                    viewModel.retractLastUserMessage()?.let { onRetract(it) }; showMenu = false
                })
            }
            // Image save for Agent messages
            val imgs = Regex("!\\[.*?]\\((.*?)\\)").findAll(bubbleText).toList()
            if (imgs.isNotEmpty()) {
                DropdownMenuItem(text = { Text("💾 保存图片 (${imgs.size})") }, onClick = {
                    imgs.forEach { m ->
                        val p = m.groupValues[1]
                        if (!p.startsWith("http")) try {
                            java.io.File(p).copyTo(java.io.File(com.mengpaw.kernel.DataPaths.SCREENSHOTS, "saved_${System.currentTimeMillis()}.png"), overwrite = true)
                        } catch (_: Exception) { }
                    }
                    showMenu = false
                })
                DropdownMenuItem(text = { Text("✏️ 标注图片发回") }, onClick = {
                    imgs.firstOrNull()?.groupValues?.get(1)?.let { if (!it.startsWith("http")) onQuote("📎 标注图片: $it") }
                    showMenu = false
                })
            }
            DropdownMenuItem(text = { Text("📤 一键分享") }, onClick = {
                val si = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, bubbleText.take(500))
                }
                context.startActivity(android.content.Intent.createChooser(si, "分享到"))
                showMenu = false
            })
        }

        if (showBigBang) {
            com.mengpaw.shell.ui.components.BigBangPopup(
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

/** Standalone composable to escape RowScope/ColumnScope for overlay sidebars. */
@Composable
private fun SidebarOverlay(
    visible: Boolean,
    fromLeft: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200)) +
                slideInHorizontally(animationSpec = tween(280)) { if (fromLeft) -it else it },
        exit = fadeOut(animationSpec = tween(200)) +
              slideOutHorizontally(animationSpec = tween(280)) { if (fromLeft) -it else it }
    ) {
        Row(Modifier.fillMaxSize()) {
            if (fromLeft) {
                content()
                Box(Modifier.fillMaxHeight().weight(1f)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable { onDismiss() })
            } else {
                Box(Modifier.fillMaxHeight().weight(1f)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable { onDismiss() })
                content()
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
