// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import com.mengpaw.shell.ui.components.MissionMonitorOverlay
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
    val inputFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val listState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showExpandSheet by remember { mutableStateOf(false) }
    var showMissionOverlay by remember { mutableStateOf(false) }
    // Reactive Mission state synced from kernel via listener
    var missionActiveState by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val listener: com.mengpaw.kernel.mission.MissionListener = { missionActiveState = it.active }
        com.mengpaw.kernel.mission.MissionMonitor.addListener(listener)
        missionActiveState = com.mengpaw.kernel.mission.MissionMonitor.missionActive
        onDispose { com.mengpaw.kernel.mission.MissionMonitor.removeListener(listener) }
    }

    // ── @mention state ────────────────────────────────────────────
    var showMentionDropdown by remember { mutableStateOf(false) }
    var mentionQuery by remember { mutableStateOf("") }

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
    // Track previous isRunning to detect thinking→done transition
    var wasRunning by remember { mutableStateOf(false) }

    // ── Active tags from ViewModel ──────────────────────────────────
    val activeTags by viewModel.activeTags.collectAsState()

    // ── Panel order state ──────────────────────────────────────────
    var panelOrder by remember { mutableStateOf(com.mengpaw.shell.ui.components.PanelOrderStore.load()) }

    // ── File picker launchers ──────────────────────────────────────
    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingUploadDir by remember { mutableStateOf("") }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { handleFilePicked(it, context, viewModel, pendingUploadDir) { p -> inputText = "$p$inputText" } } }

    val docPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { handleFilePicked(it, context, viewModel, pendingUploadDir) { p -> inputText = "$p$inputText" } } }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { handleFilePicked(it, context, viewModel, pendingUploadDir) { p -> inputText = "$p$inputText" } } }

    val cameraUri = remember {
        val file = java.io.File(com.mengpaw.kernel.DataPaths.SCREENSHOTS, "camera_${System.currentTimeMillis()}.jpg")
        file.parentFile?.mkdirs()
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraUri != null) {
            handleFilePicked(cameraUri, context, viewModel, pendingUploadDir) { p -> inputText = "$p$inputText" }
        }
    }

    /** Bounds-checked scroll helper — swallows out-of-range errors. */
    suspend fun safeScrollTo(index: Int, animated: Boolean = true) {
        val size = displayedMessages.size
        if (size == 0 || index < 0 || index >= size) return
        try {
            if (animated) listState.animateScrollToItem(index)
            else listState.scrollToItem(index)
        } catch (_: Exception) { /* layout not ready, ignore */ }
    }

    // During streaming: auto-scroll to bottom when user is near the end
    LaunchedEffect(displayedMessages.size) {
        if (displayedMessages.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val nearBottom = lastVisible >= displayedMessages.size - 3
            if (nearBottom) safeScrollTo(displayedMessages.size - 1)
        }
    }

    // When thinking ends: scroll to top of output + auto-focus input
    LaunchedEffect(isRunning) {
        if (wasRunning && !isRunning && displayedMessages.isNotEmpty()) {
            val targetIdx = displayedMessages.indexOfLast {
                it is ChatMessageUi.Agent || it is ChatMessageUi.AgentWithTrace
            }
            if (targetIdx >= 0) {
                kotlinx.coroutines.delay(80) // let layout settle first
                safeScrollTo(targetIdx)
            }
            // Auto-focus input field for immediate next question
            kotlinx.coroutines.delay(200)
            try { inputFocus.requestFocus() } catch (_: Exception) {}
        }
        wasRunning = isRunning
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
                    // Agent avatar — 44dp circle, 点击打开左侧栏
                    val avatarFile = File(com.mengpaw.kernel.DataPaths.AGENTS, "$displayAgentName/avatar.png")
                    val avatarBitmap = remember(displayAgentName) { if (avatarFile.exists()) BitmapFactory.decodeFile(avatarFile.absolutePath) else null }
                    Box(modifier = Modifier.size(44.dp).clip(CircleShape)
                        .pointerInput(Unit) { detectTapGestures { showLeftSidebar = !showLeftSidebar } }) {
                        if (avatarBitmap != null) {
                            Image(bitmap = avatarBitmap.asImageBitmap(), null, Modifier.fillMaxSize())
                        } else {
                            Surface(shape = CircleShape, color = ThemeColors.brandContainer, modifier = Modifier.fillMaxSize()) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(displayAgentName.take(1), color = ThemeColors.brand, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                }
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
                    if (missionActiveState) {
                        IconButton(onClick = { showMissionOverlay = !showMissionOverlay }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Outlined.Monitor, "Mission", tint = ThemeColors.brand)
                        }
                    }
                    // FIX: Dynamic plugin buttons from HEADER_BAR placement
                    val headerButtons = remember(pluginViewModel.activeButtons) { pluginViewModel.activeButtons[com.mengpaw.kernel.plugin.ButtonPlacement.HEADER_BAR] ?: emptyList() }
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
                        Surface(
                            color = ThemeColors.bgPrimary,
                            shadowElevation = 8.dp
                        ) {
                            leftSidebarContent { showLeftSidebar = false }
                        }
                    }

                    // Messages — container centered, tablet 80% / phone 95%
                    val msgWidth = if (isWide()) 0.8f else 0.95f
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            // 全局手势：右滑→左侧栏，左滑→右侧栏
                            .pointerInput(Unit) {
                                var totalDrag = 0f
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        if (totalDrag < -200f) showRightSidebar = true
                                        else if (totalDrag > 200f) showLeftSidebar = true
                                        totalDrag = 0f
                                    }
                                ) { _, dragAmount ->
                                    totalDrag += dragAmount
                                }
                            },
                        contentAlignment = Alignment.TopCenter
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(msgWidth).heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp),
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
                                    is ChatMessageUi.Agent -> AgentBubble(message.content, displayAgentName,
                                        executionMode = message.executionMode, agentRef = message.agentRef)
                                    is ChatMessageUi.AgentWithTrace -> AgentBubbleWithTrace(message, displayAgentName)
                                    is ChatMessageUi.Suggestion -> PluginSuggestionCard(message.suggestion,
                                        onInstall = { pluginViewModel.installPlugin(message.suggestion.pluginId) },
                                        onViewDetail = onNavigateToPlugins)
                                    else -> {}
                                }
                            }
                        }
                    }
                    } // close Box wrapping LazyColumn

                    // Persistent right sidebar (tablet only)
                    if (isWide() && showRightSidebar) {
                        Surface(
                            color = ThemeColors.bgPrimary,
                            shadowElevation = 8.dp
                        ) {
                            rightSidebarContent { showRightSidebar = false }
                        }
                    }
                }

                // Agent-pushed banner notifications — overlay at top of content area
                NotifyBannerHost(
                    onMessage = { text ->
                        viewModel.notifyAgentMessage(text)
                    },
                    onBannerClick = {
                        (agentViewModel ?: viewModel).switchAgent("MengPaw")
                    }
                )

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

            // ── Active tags row (above input) ──
            if (activeTags.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(ArcoSpacing.xs)
                ) {
                    activeTags.forEach { tag ->
                        val chipLabel = when (tag) {
                            is InputTag.Mode -> tag.mode.prefix
                            is InputTag.AgentRef -> "@${tag.agentName}"
                        }
                        AssistChip(
                            onClick = {},
                            label = { Text(chipLabel, style = MaterialTheme.typography.labelSmall) },
                            trailingIcon = {
                                Icon(Icons.Filled.Close, strings.tagDismiss,
                                    Modifier.size(14.dp).clickable { viewModel.removeTag(tag) })
                            },
                            shape = RoundedCornerShape(ArcoRadius.sm),
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = ThemeColors.brandContainer,
                                labelColor = ThemeColors.brand
                            )
                        )
                    }
                }
            }

            // ── Bottom input bar ──
            Box {
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
                    // Input field — soft keyboard Enter sends, Ctrl+Enter inserts newline
                    val keyMaxSteps = settingsState?.value?.maxSteps ?: 50
                    var lastSendTime by remember { mutableLongStateOf(0L) }
                    fun doSend() {
                        if (inputText.isNotBlank()) {
                            val now = System.currentTimeMillis()
                            if (now - lastSendTime < 300) return  // debounce: prevent double-fire from onPreviewKeyEvent + IME
                            lastSendTime = now
                            val text = inputText; inputText = ""
                            val modeTag = activeTags.filterIsInstance<InputTag.Mode>().firstOrNull()
                            val agentTag = activeTags.filterIsInstance<InputTag.AgentRef>().firstOrNull()
                            viewModel.submitTask(text, pluginViewModel, maxSteps = keyMaxSteps,
                                executionMode = modeTag?.mode, agentRef = agentTag?.agentName)
                            inputFocus.requestFocus()
                        }
                    }
                    OutlinedTextField(value = inputText, onValueChange = { newVal ->
                        inputText = newVal
                        // @mention 检测 — 在空格/换行/行首后输入 @
                        val atIdx = newVal.lastIndexOf('@')
                        if (atIdx >= 0) {
                            val beforeAt = if (atIdx > 0) newVal[atIdx - 1] else ' '
                            if (beforeAt == ' ' || beforeAt == '\n' || atIdx == 0) {
                                val query = newVal.substring(atIdx + 1)
                                if (!query.contains(' ') && !query.contains('\n')) {
                                    mentionQuery = query
                                    showMentionDropdown = true
                                } else {
                                    showMentionDropdown = false
                                }
                            } else {
                                showMentionDropdown = false
                            }
                        } else {
                            showMentionDropdown = false
                        }
                    },
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                            .focusRequester(inputFocus)
                            .onPreviewKeyEvent { event ->
                                if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                                    if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                                        if (event.nativeKeyEvent.isCtrlPressed || event.nativeKeyEvent.isShiftPressed) {
                                            inputText += "\n"
                                        } else {
                                            doSend()
                                        }
                                    }
                                    true  // consume ALL Enter events (DOWN + UP) — prevents focus-leak to sidebar
                                } else false
                            },
                        enabled = inputEnabled,
                        placeholder = {
                            val modeTag = activeTags.filterIsInstance<InputTag.Mode>().firstOrNull()
                            val hint = when (modeTag?.mode) {
                                ExecutionMode.MISSION -> strings.placeholderMission
                                ExecutionMode.RESEARCH -> strings.placeholderResearch
                                ExecutionMode.TRANSLATE -> strings.placeholderTranslate
                                ExecutionMode.SILENT -> strings.placeholderSilent
                                else -> strings.inputPlaceholder
                            }
                            Text(hint)
                        },
                        shape = RoundedCornerShape(ArcoRadius.lg),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = ThemeColors.brand, unfocusedBorderColor = ThemeColors.border),
                        minLines = 1, maxLines = 4,
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Default),
                        keyboardActions = KeyboardActions(onSend = { doSend() }))
                    // Send button — circular 44dp, animated ↑ icon
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
                                val text = inputText
                                if (text.isNotBlank()) {
                                    inputText = ""
                                    inputFocus.requestFocus()
                                    val modeTag = activeTags.filterIsInstance<InputTag.Mode>().firstOrNull()
                                    val agentTag = activeTags.filterIsInstance<InputTag.AgentRef>().firstOrNull()
                                    scope.launch {
                                        // ↑ flies upward and out
                                        launch { arrowOffsetY.animateTo(-60f, tween(280)) }
                                        launch { arrowAlpha.animateTo(0f, tween(280)) }
                                        // snap below, then submit
                                        arrowOffsetY.snapTo(60f)
                                        viewModel.submitTask(text, pluginViewModel, maxSteps = keyMaxSteps,
                                            executionMode = modeTag?.mode, agentRef = agentTag?.agentName)
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
            } // close Surface (input bar)

            // ── @mention dropdown ──
            val mentionAgents = remember(mentionQuery, viewModel.agentNamesForMention().size) {
                viewModel.agentNamesForMention().filter { (name, _) ->
                    mentionQuery.isBlank() || name.contains(mentionQuery, ignoreCase = true)
                }
            }
            if (showMentionDropdown) {
                if (mentionAgents.isNotEmpty()) {
                    DropdownMenu(
                        expanded = true,
                        onDismissRequest = { showMentionDropdown = false }
                    ) {
                        mentionAgents.take(6).forEach { (name, framework) ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("@$name", fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium)
                                        if (framework != null) {
                                            Spacer(Modifier.width(4.dp))
                                            Text("· $framework",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = ThemeColors.textSecondary)
                                        }
                                    }
                                },
                                onClick = {
                                    // 替换 @query 为 @agentName
                                    val current = inputText
                                    val atIdx = current.lastIndexOf('@')
                                    if (atIdx >= 0) {
                                        val beforeAt = current.substring(0, atIdx)
                                        val afterQuery = current.substring(atIdx + 1 + mentionQuery.length)
                                        inputText = "$beforeAt@$name $afterQuery"
                                    }
                                    showMentionDropdown = false
                                    // 添加 mention 标签
                                    viewModel.addTag(InputTag.AgentRef(name))
                                    try { inputFocus.requestFocus() } catch (_: Exception) {}
                                },
                                leadingIcon = {
                                    Surface(shape = CircleShape, color = ThemeColors.brandContainer,
                                        modifier = Modifier.size(24.dp)) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(name.take(1), color = ThemeColors.brand,
                                                fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        } // close mention anchor Box
    } // close Column

    // ── Mission Monitor overlay ──
    // Auto-dismiss when mission ends (reactive via missionActiveState)
    LaunchedEffect(missionActiveState) {
        if (!missionActiveState) showMissionOverlay = false
    }
    MissionMonitorOverlay(
        visible = showMissionOverlay,
        onDismiss = { showMissionOverlay = false }
    )

    // ── Expand bottom sheet (3-section layout) ──
    if (showExpandSheet) {
        ModalBottomSheet(onDismissRequest = { showExpandSheet = false }, sheetState = sheetState,
            containerColor = ThemeColors.bgPrimary) {
            Column(Modifier.padding(ArcoSpacing.lg).padding(bottom = 32.dp)) {
                // ═══ Section 1: 文件提交 ═══
                Text(strings.expandFileSection, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(ArcoSpacing.md))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ExpandItem(Icons.Outlined.Image, strings.filePickImage) {
                        showExpandSheet = false
                        pendingUploadDir = com.mengpaw.kernel.DataPaths.AGENTS + "/${displayAgentName}/workspace"
                        imagePicker.launch("image/*")
                    }
                    ExpandItem(Icons.Outlined.Description, strings.filePickDocument) {
                        showExpandSheet = false
                        pendingUploadDir = com.mengpaw.kernel.DataPaths.AGENTS + "/${displayAgentName}/workspace"
                        docPicker.launch(arrayOf(
                            "application/pdf", "text/plain", "text/markdown",
                            "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                        ))
                    }
                    ExpandItem(Icons.Outlined.AttachFile, strings.filePickFile) {
                        showExpandSheet = false
                        pendingUploadDir = com.mengpaw.kernel.DataPaths.AGENTS + "/${displayAgentName}/workspace"
                        filePicker.launch(arrayOf("*/*"))
                    }
                    ExpandItem(Icons.Outlined.PhotoCamera, strings.filePickCamera) {
                        showExpandSheet = false
                        pendingUploadDir = com.mengpaw.kernel.DataPaths.AGENTS + "/${displayAgentName}/workspace"
                        cameraLauncher.launch(cameraUri)
                    }
                }
                Spacer(Modifier.height(ArcoSpacing.xl))

                // ═══ Section 2: 执行模式 ═══
                Text(strings.expandModeSection, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(ArcoSpacing.sm))
                val orderedModes = panelOrder.modes.mapNotNull { id ->
                    ExecutionMode.entries.find { it.name.lowercase() == id }
                }.ifEmpty { ExecutionMode.entries.toList() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    orderedModes.take(4).forEach { mode ->
                        val isActive = activeTags.any { it is InputTag.Mode && it.mode == mode }
                        ModeItem(mode = mode, isActive = isActive, onClick = {
                            showExpandSheet = false
                            if (isActive) viewModel.removeTag(InputTag.Mode(mode))
                            else {
                                viewModel.addTag(InputTag.Mode(mode))
                            }
                        })
                    }
                }
                Spacer(Modifier.height(ArcoSpacing.xl))

                // ═══ Section 3: 插件工具 ═══
                Text(strings.expandPluginSection, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(ArcoSpacing.sm))
                val sheetButtons = pluginViewModel.activeButtons[com.mengpaw.kernel.plugin.ButtonPlacement.BOTTOM_SHEET] ?: emptyList()
                val orderedPlugins = panelOrder.plugins.mapNotNull { btnId ->
                    sheetButtons.find { it.id == btnId }
                } + sheetButtons.filter { btn -> btn.id !in panelOrder.plugins }
                if (orderedPlugins.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        orderedPlugins.take(4).forEach { btn ->
                            ExpandItem(pluginIconForName(btn.iconName), btn.label) {
                                showExpandSheet = false; inputText = btn.command
                            }
                        }
                    }
                } else {
                    Text("暂无已激活插件。在插件管理中安装并启用。",
                        style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                }
                Spacer(Modifier.height(ArcoSpacing.lg))
            }
        }
    }
} // close outermost Box
} // close MainScreen composable

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

/** 执行模式按钮 — 带激活态高亮。 */
@Composable
private fun ModeItem(mode: ExecutionMode, isActive: Boolean, onClick: () -> Unit) {
    val icon = when (mode) {
        ExecutionMode.MISSION -> Icons.Outlined.AccountTree
        ExecutionMode.RESEARCH -> Icons.Outlined.TravelExplore
        ExecutionMode.TRANSLATE -> Icons.Outlined.Translate
        ExecutionMode.SILENT -> Icons.Outlined.NotificationsOff
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onClick,
            shape = RoundedCornerShape(ArcoRadius.lg),
            color = if (isActive) ThemeColors.brand.copy(alpha = 0.15f) else ThemeColors.bgCardHigh,
            modifier = Modifier.size(56.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, mode.label, Modifier.size(28.dp),
                    tint = if (isActive) ThemeColors.brand else ThemeColors.textSecondary)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(mode.prefix, style = MaterialTheme.typography.labelSmall,
            color = if (isActive) ThemeColors.brand else ThemeColors.textSecondary)
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
/** Right-aligned, auto-width capped at 400dp, tail at bottom-right. */
@Composable private fun UserBubble(content: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(ArcoRadius.lg, ArcoRadius.lg, ArcoRadius.sm, ArcoRadius.lg),
            color = ThemeColors.brand,
            modifier = Modifier.widthIn(max = 400.dp)
        ) {
            SelectionContainer {
                MarkdownText(
                    content = content,
                    modifier = Modifier.padding(horizontal = ArcoSpacing.lg, vertical = ArcoSpacing.md),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    inlineCodeColor = Color.White.copy(alpha = 0.9f),
                    linkColor = Color.White,
                    nestedScroll = true
                )
            }
        }
    }
}

/** Left-aligned, max 90% width, tail at bottom-left. */
@Composable private fun AgentBubble(content: String, agentName: String = "MengPaw",
    executionMode: String? = null, agentRef: String? = null
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(ArcoRadius.lg, ArcoRadius.lg, ArcoRadius.lg, ArcoRadius.sm),
            color = ThemeColors.bgCardHigh,
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Column(Modifier.padding(ArcoSpacing.lg)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(agentName, style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold, color = ThemeColors.brand)
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
                }
                Spacer(Modifier.height(ArcoSpacing.xs))
                SelectionContainer {
                    MarkdownText(
                        content = content,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = ThemeColors.textPrimary),
                        nestedScroll = true
                    )
                }
            }
        }
    }
}

// ── Agent Bubble with Trace (expandable thinking steps) ──
@Composable private fun AgentBubbleWithTrace(message: ChatMessageUi.AgentWithTrace, agentName: String = "MengPaw") {
    var expanded by remember { mutableStateOf(false) }
    val traces = message.traces

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(ArcoRadius.lg, ArcoRadius.lg, ArcoRadius.lg, ArcoRadius.sm),
            color = ThemeColors.bgCardHigh,
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Column(Modifier.padding(ArcoSpacing.lg)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(agentName, style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold, color = ThemeColors.brand)
                    if (message.executionMode != null) {
                        Text(" · ${message.executionMode}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ThemeColors.brand)
                        // Mission 模式显示步数
                        if (message.executionMode == "/Mission") {
                            Text(" · ${traces.size} 步",
                                style = MaterialTheme.typography.labelSmall,
                                color = ThemeColors.textSecondary)
                        }
                    }
                    if (message.agentRef != null) {
                        Text(" · @${message.agentRef}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ArcoColors.Orange6)
                    }
                }
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
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = ThemeColors.textPrimary),
                        nestedScroll = true
                    )
                }
            }
        }
    }
}

@Composable private fun TraceStepItem(trace: AgentTrace) {
    var thoughtExpanded by remember { mutableStateOf(false) }
    var actionExpanded by remember { mutableStateOf(false) }
    var observationExpanded by remember { mutableStateOf(false) }
    val thoughtLong = trace.thought.length > 150
    val actionLong = (trace.action?.length ?: 0) > 80 || (trace.action?.contains("\n") == true)
    val observationLong = (trace.observation?.length ?: 0) > 200 || (trace.observation?.contains("\n") == true)

    Surface(
        shape = RoundedCornerShape(ArcoRadius.sm),
        color = ThemeColors.bgCard,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Column(Modifier.padding(ArcoSpacing.sm)) {
            // ── Thought ──
            Row(verticalAlignment = Alignment.Top) {
                Text("Step ${trace.step}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = ThemeColors.brand)
                Spacer(Modifier.width(ArcoSpacing.xs))
                Column(Modifier.weight(1f)) {
                    Text(
                        "🤔 ${if (thoughtExpanded || !thoughtLong) trace.thought else trace.thought.take(150) + "..."}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ThemeColors.textSecondary,
                        maxLines = if (thoughtExpanded) Int.MAX_VALUE else 3
                    )
                    if (thoughtLong) {
                        TextButton(onClick = { thoughtExpanded = !thoughtExpanded },
                            contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                            Text(if (thoughtExpanded) "▲ 收起" else "▼ 展开全部 (${trace.thought.length} 字)",
                                fontSize = 10.sp, color = ThemeColors.brand)
                        }
                    }
                }
            }

            // ── Action (command) ──
            if (trace.action != null) {
                Spacer(Modifier.height(2.dp))
                Column {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            "🔧 ${if (actionExpanded || !actionLong) trace.action else trace.action.take(80) + "..."}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ThemeColors.brand,
                            fontFamily = FontFamily.Monospace,
                            maxLines = if (actionExpanded) Int.MAX_VALUE else 1,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (actionLong) {
                        TextButton(onClick = { actionExpanded = !actionExpanded },
                            contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                            Text(if (actionExpanded) "▲ 收起" else "▼ 展开命令",
                                fontSize = 10.sp, color = ThemeColors.brand)
                        }
                    }
                }
            }

            // ── Observation (tool output) ──
            if (trace.observation != null) {
                Spacer(Modifier.height(2.dp))
                Column {
                    Text(
                        "📊 ${if (observationExpanded || !observationLong) trace.observation else trace.observation.take(200) + "..."}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ThemeColors.textSecondary,
                        maxLines = if (observationExpanded) Int.MAX_VALUE else 10,
                        fontFamily = FontFamily.Monospace
                    )
                    if (observationLong) {
                        TextButton(onClick = { observationExpanded = !observationExpanded },
                            contentPadding = PaddingValues(0.dp), modifier = Modifier.height(24.dp)) {
                            Text(if (observationExpanded) "▲ 收起" else "▼ 展开完整输出 (${trace.observation.length} 字)",
                                fontSize = 10.sp, color = ThemeColors.brand)
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
                onClick = { showMenu = false },
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
    // 遮罩透明度动画 — 固定全屏，只有渐变，不跟随侧边栏滑动
    val dimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "sidebarDim"
    )

    // 只在动画进行中时渲染（visible 为 true 或退出动画未结束）
    if (visible || dimAlpha > 0f) {
        Box(Modifier.fillMaxSize()) {
            // 遮罩层 — 固定全屏，只渐变透明度，不跟随侧边栏滑动
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f * dimAlpha))
                    .clickable(enabled = visible) { onDismiss() }
            )

            // 侧边栏内容 — 在遮罩上方滑入/滑出
            AnimatedVisibility(
                visible = visible,
                enter = slideInHorizontally(animationSpec = tween(300)) { if (fromLeft) -it else it },
                exit = slideOutHorizontally(animationSpec = tween(300)) { if (fromLeft) -it else it }
            ) {
                Row(Modifier.fillMaxSize()) {
                    if (fromLeft) {
                        content()
                        Spacer(Modifier.weight(1f))
                    } else {
                        Spacer(Modifier.weight(1f))
                        content()
                    }
                }
            }
        }
    }
}

/** 将 content:// URI 拷贝到 Agent 工作区，返回文件路径通过回调插入输入框。 */
private fun handleFilePicked(
    uri: Uri,
    context: android.content.Context,
    viewModel: AgentViewModel,
    uploadDir: String,
    onPath: (String) -> Unit
) {
    try {
        val dir = java.io.File(
            if (uploadDir.isNotBlank()) uploadDir
            else com.mengpaw.kernel.DataPaths.AGENTS + "/MengPaw/workspace"
        )
        dir.mkdirs()
        val ext = context.contentResolver.getType(uri)?.let { mime ->
            when {
                mime.contains("png") -> ".png"
                mime.contains("jpeg") || mime.contains("jpg") -> ".jpg"
                mime.contains("pdf") -> ".pdf"
                mime.contains("text/plain") -> ".txt"
                mime.contains("text/html") -> ".html"
                else -> ""
            }
        } ?: ""
        val name = "upload_${System.currentTimeMillis()}$ext"
        val target = java.io.File(dir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, 4096) }
        }
        if (target.exists() && target.length() > 0) {
            val MAX_SIZE = 50L * 1024 * 1024
            if (target.length() > MAX_SIZE) {
                target.delete()
                onPath("⚠️ 文件超过 50MB 上限，已丢弃\n")
            } else {
                onPath("📎 ${target.absolutePath}\n")
            }
        }
    } catch (_: Exception) { }
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
