// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import android.net.Uri
import com.mengpaw.kernel.session.AttachmentData
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import com.mengpaw.shell.ui.screens.model.ExecutionMode
import com.mengpaw.shell.ui.screens.model.InputTag
import com.mengpaw.shell.ui.screens.model.PendingTask
import com.mengpaw.shell.ui.screens.model.VoiceCapability
import com.mengpaw.shell.ui.screens.model.buildTaskContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import com.mengpaw.shell.ui.components.MissionMonitorOverlay
import com.mengpaw.shell.ui.components.NotifyBannerHost
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
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
    leftSidebarContent: @Composable (close: () -> Unit, isRunning: Boolean) -> Unit = { _, _ -> },
    rightSidebarContent: @Composable (close: () -> Unit) -> Unit = {}
) {
    val messages by viewModel.messages.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
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
    // ── !bang 命令补全 state (与 @mention 共用同一悬浮控件) ──
    var showBangDropdown by remember { mutableStateOf(false) }
    var bangQuery by remember { mutableStateOf("") }

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

    // ── File picker launchers (v0.33.0+: 产出结构化附件, 不再插文本进输入框) ──
    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingUploadDir by remember { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf(listOf<AttachmentData>()) }
    val onAttError: (String) -> Unit = { msg ->
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let {
        handleFilePicked(it, context, pendingUploadDir,
            onAttachment = { att -> pendingAttachments = pendingAttachments + att }, onError = onAttError)
    } }

    val docPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let {
        handleFilePicked(it, context, pendingUploadDir,
            onAttachment = { att -> pendingAttachments = pendingAttachments + att }, onError = onAttError)
    } }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let {
        handleFilePicked(it, context, pendingUploadDir,
            onAttachment = { att -> pendingAttachments = pendingAttachments + att }, onError = onAttError)
    } }

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
            handleFilePicked(cameraUri, context, pendingUploadDir,
                onAttachment = { att -> pendingAttachments = pendingAttachments + att }, onError = onAttError)
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

    // Initial load: scroll to bottom
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(200) // wait for layout
        if (displayedMessages.isNotEmpty()) safeScrollTo(displayedMessages.size - 1, animated = false)
    }

    // During streaming: auto-scroll to bottom with debounce (avoids per-step layout reads)
    var lastAutoScroll by remember { mutableLongStateOf(0L) }
    LaunchedEffect(displayedMessages.size) {
        if (displayedMessages.isNotEmpty()) {
            val now = System.currentTimeMillis()
            if (now - lastAutoScroll < 150) return@LaunchedEffect  // debounce: ~6.7 fps max
            lastAutoScroll = now
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
            // ── Header bar (拆至 MainScreenHeader.kt) ──
            MainScreenHeader(
                strings = strings,
                displayAgentName = displayAgentName,
                agentFramework = agentFramework,
                sessionLabel = agentViewModel?.activeSessionLabel(strings) ?: "MengPaw / ${strings.agentUnconfigured}",
                missionActiveState = missionActiveState,
                pluginViewModel = pluginViewModel,
                onPluginCommand = { inputText = it },
                onToggleLeftSidebar = { showLeftSidebar = !showLeftSidebar },
                onToggleRightSidebar = { showRightSidebar = !showRightSidebar },
                onToggleMissionOverlay = { showMissionOverlay = !showMissionOverlay },
                onNewSession = { viewModel.newSession() }
            )

            // ── Content area: adaptive — persistent sidebar on wide, overlay on compact ──
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.fillMaxSize()) {
                    // Persistent left sidebar (tablet only) — 拆至 MainScreenSidebars.kt
                    PersistentLeftSidebar(
                        show = showLeftSidebar,
                        isWide = isWide(),
                        isRunning = isRunning,
                        onDismiss = { showLeftSidebar = false },
                        content = leftSidebarContent
                    )

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
                        Column(Modifier.fillMaxWidth(msgWidth)) {
                            // 消息列表 — 占据剩余空间
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize().heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp),
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
                                            is ChatMessageUi.User -> UserBubble(message)
                                            is ChatMessageUi.Agent -> AgentBubble(message.content, displayAgentName,
                                                executionMode = message.executionMode, agentRef = message.agentRef)
                                            is ChatMessageUi.AgentWithTrace -> AgentBubbleWithTrace(message, displayAgentName)
                                            is ChatMessageUi.AgentStep -> AgentStepBubble(message, displayAgentName)
                                            is ChatMessageUi.CommandResult -> CommandResultBubble(message, strings)
                                            is ChatMessageUi.Suggestion -> PluginSuggestionCard(message.suggestion,
                                                onInstall = { pluginViewModel.installPlugin(message.suggestion.pluginId) },
                                                onViewDetail = onNavigateToPlugins)
                                            else -> {}
                                        }
                                    }
                                }
                            }

                                    // ── 待办列表 — 浮动在气泡层上方，有内容时弹出 ──
                                    val pendingTasks by viewModel.pendingTasks.collectAsState()
                                    androidx.compose.animation.AnimatedVisibility(
                                        visible = pendingTasks.isNotEmpty(),
                                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                                        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                            .padding(horizontal = ArcoSpacing.sm, vertical = ArcoSpacing.xs)
                                    ) {
                                        PendingTasksBar(
                                            tasks = pendingTasks,
                                            onRemove = { viewModel.removePendingTask(it) },
                                            onClearAll = { viewModel.clearPendingTasks() }
                                        )
                                    }
                                } // close inner Box wrapping LazyColumn

                            } // close Column
                    } // close Box wrapping LazyColumn

                    // Persistent right sidebar (tablet only) — 拆至 MainScreenSidebars.kt
                    PersistentRightSidebar(
                        show = showRightSidebar,
                        isWide = isWide(),
                        onDismiss = { showRightSidebar = false },
                        content = rightSidebarContent
                    )
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

                // Overlays — outside Row to avoid scope conflict (拆至 MainScreenSidebars.kt)
                PhoneSidebarOverlays(
                    showLeft = showLeftSidebar,
                    showRight = showRightSidebar,
                    isWide = isWide(),
                    isRunning = isRunning,
                    onDismissLeft = { showLeftSidebar = false },
                    onDismissRight = { showRightSidebar = false },
                    leftContent = leftSidebarContent,
                    rightContent = rightSidebarContent
                )
            }

            // ── @mention / !bang 命令补全内联下拉（不走 Popup，不干扰输入法）──
            if (showMentionDropdown || showBangDropdown) {
                val bangCandidates = if (showBangDropdown)
                    // P2 修复: key 只依赖 bangQuery — bangCommands() 有 buildPipeline 副作用,
                    // 放 key 里每次重组都执行（主线程全量重建）
                    remember(bangQuery) {
                        val all = viewModel.bangCommands()
                        val q = bangQuery.trim()
                        if (q.isEmpty()) all
                        else all.filter {
                            it.name.startsWith(q, ignoreCase = true) || it.name.contains(q, ignoreCase = true)
                        }
                    }
                else emptyList()
                val mentionAgents = if (!showBangDropdown)
                    remember(mentionQuery, viewModel.agentNamesForMention().size) {
                        viewModel.agentNamesForMention().filter { (name, _) ->
                            mentionQuery.isBlank() || name.contains(mentionQuery, ignoreCase = true)
                        }
                    }
                else emptyList()
                if (bangCandidates.isNotEmpty() || mentionAgents.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = ArcoSpacing.lg),
                        shape = RoundedCornerShape(ArcoRadius.md),
                        shadowElevation = 6.dp,
                        color = ThemeColors.bgPrimary
                    ) {
                        Column(Modifier.padding(vertical = ArcoSpacing.xs)) {
                            if (showBangDropdown) {
                                bangCandidates.take(6).forEach { cmd ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable {
                                                val current = inputText
                                                val bangIdx = current.lastIndexOf('!')
                                                if (bangIdx >= 0) {
                                                    val beforeBang = current.substring(0, bangIdx)
                                                    val afterQuery = current.substring(bangIdx + 1 + bangQuery.length)
                                                    inputText = "$beforeBang!${cmd.name} $afterQuery"
                                                }
                                                showBangDropdown = false
                                                try { inputFocus.requestFocus() } catch (_: Exception) {}
                                            }
                                            .padding(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.sm),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("!${cmd.name}", fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = ThemeColors.brand)
                                        if (cmd.description.isNotBlank()) {
                                            Spacer(Modifier.width(8.dp))
                                            Text(cmd.description,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = ThemeColors.textSecondary,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            } else {
                                mentionAgents.take(6).forEach { (name, framework) ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable {
                                                val current = inputText
                                                val atIdx = current.lastIndexOf('@')
                                                if (atIdx >= 0) {
                                                    val beforeAt = current.substring(0, atIdx)
                                                    val afterQuery = current.substring(atIdx + 1 + mentionQuery.length)
                                                    inputText = "$beforeAt@$name $afterQuery"
                                                }
                                                showMentionDropdown = false
                                                viewModel.addTag(InputTag.AgentRef(name))
                                                try { inputFocus.requestFocus() } catch (_: Exception) {}
                                            }
                                            .padding(horizontal = ArcoSpacing.md, vertical = ArcoSpacing.sm),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(shape = CircleShape, color = ThemeColors.brandContainer,
                                            modifier = Modifier.size(24.dp)) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(name.take(1), color = ThemeColors.brand,
                                                    fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            }
                                        }
                                        Spacer(Modifier.width(ArcoSpacing.sm))
                                        Text("@$name", fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium)
                                        if (framework != null) {
                                            Spacer(Modifier.width(4.dp))
                                            Text("· $framework",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = ThemeColors.textSecondary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } // close if(showMentionDropdown || showBangDropdown)

            // ── Bottom input bar ──
            Box {
            // v0.33.0+: 语音录制状态 — 能力判定 (不支持语音的模型不显示按钮,
            // 用户用 Android 输入法自带语音转译); 录音时收起键盘
            val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            var isRecordingVoice by remember { mutableStateOf(false) }
            var recordElapsedMs by remember { mutableLongStateOf(0L) }
            val voiceSupported = VoiceCapability.supportsVoice(viewModel.activeModelName())
            LaunchedEffect(isRecordingVoice) {
                if (isRecordingVoice) keyboardController?.hide()
            }
            Surface(shadowElevation = 8.dp, color = ThemeColors.bgPrimary) {
            Column {
                // ── 待发栏 (v0.34.0+): 斜杠/@标签 + 附件缩略图/名称块统一一行,
                // 位于输入框顶部; 无内容不显示; 随输入栏整体上移 (imePadding 在输入 Row 上) ──
                androidx.compose.animation.AnimatedVisibility(
                    visible = activeTags.isNotEmpty() || pendingAttachments.isNotEmpty(),
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()
                ) {
                    PendingAttachmentsBar(
                        activeTags = activeTags,
                        attachments = pendingAttachments,
                        strings = strings,
                        onRemoveTag = viewModel::removeTag,
                        onRemoveAttachment = { att -> pendingAttachments = pendingAttachments - att }
                    )
                }
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
                        if (inputText.isNotBlank() || pendingAttachments.isNotEmpty()) {
                            val now = System.currentTimeMillis()
                            if (now - lastSendTime < 300) return  // debounce: prevent double-fire from onPreviewKeyEvent + IME
                            lastSendTime = now
                            val text = inputText; inputText = ""
                            // v0.33.0+: 附件随消息发送 — 文本合成 `[图片附件] path` 标注
                            val atts = pendingAttachments; pendingAttachments = emptyList()
                            // 发送后立即收起悬浮下拉 — 程序化清空输入不触发 onValueChange, 需手动关闭
                            showMentionDropdown = false; showBangDropdown = false
                            val modeTag = activeTags.filterIsInstance<InputTag.Mode>().firstOrNull()
                            val agentTag = activeTags.filterIsInstance<InputTag.AgentRef>().firstOrNull()
                            viewModel.submitTask(buildTaskContent(text, atts), pluginViewModel, maxSteps = keyMaxSteps,
                                executionMode = modeTag?.mode, agentRef = agentTag?.agentName,
                                attachments = atts)
                            inputFocus.requestFocus()
                        }
                    }
                    OutlinedTextField(value = inputText, onValueChange = { newVal ->
                        inputText = newVal
                        // !bang 命令补全检测 — ! 之前缀须全空白 (与 submitTask 的 startsWith("!") 语义对齐)
                        val bangIdx = newVal.lastIndexOf('!')
                        if (bangIdx >= 0 && newVal.substring(0, bangIdx).isBlank()) {
                            val query = newVal.substring(bangIdx + 1)
                            if (!query.contains(' ') && !query.contains('\n')) {
                                bangQuery = query
                                showBangDropdown = true
                                showMentionDropdown = false
                            } else {
                                showBangDropdown = false
                            }
                        } else {
                            showBangDropdown = false
                        }
                        // @mention 检测 — 在空格/换行/行首后输入 @
                        val atIdx = newVal.lastIndexOf('@')
                        if (atIdx >= 0) {
                            val beforeAt = if (atIdx > 0) newVal[atIdx - 1] else ' '
                            if (beforeAt == ' ' || beforeAt == '\n' || atIdx == 0) {
                                val query = newVal.substring(atIdx + 1)
                                if (!query.contains(' ') && !query.contains('\n')) {
                                    mentionQuery = query
                                    showMentionDropdown = true
                                    showBangDropdown = false
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
                        enabled = true,
                        placeholder = {
                            val modeTag = activeTags.filterIsInstance<InputTag.Mode>().firstOrNull()
                            val hint = when (modeTag?.mode) {
                                ExecutionMode.MISSION -> strings.placeholderMission
                                ExecutionMode.SWARM -> strings.placeholderSwarm
                                ExecutionMode.GOAL -> "描述目标，Agent 自动评估完成度..."
                                ExecutionMode.PLAN -> "描述任务，Agent 先分解计划再逐步执行..."
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
                    // Voice button (v0.33.0+) — 透明底线性话筒, 按住录音松开发送;
                    // 仅支持音频输入的模型显示 (VoiceCapability 判定)
                    if (voiceSupported) {
                        VoiceInputButton(
                            supported = voiceSupported,
                            strings = strings,
                            onRecorded = { att ->
                                // 按住发送语音 = 松手即发 — 录音文件直发模型 (input_audio 通道)
                                val modeTag = activeTags.filterIsInstance<InputTag.Mode>().firstOrNull()
                                val agentTag = activeTags.filterIsInstance<InputTag.AgentRef>().firstOrNull()
                                viewModel.submitTask(buildTaskContent("", listOf(att)), pluginViewModel,
                                    maxSteps = keyMaxSteps, executionMode = modeTag?.mode,
                                    agentRef = agentTag?.agentName, attachments = listOf(att))
                            },
                            onRecordStateChanged = { rec -> isRecordingVoice = rec },
                            onElapsed = { ms -> recordElapsedMs = ms }
                        )
                    }
                    // Send button — circular 44dp, animated ↑ icon
                    val scope = rememberCoroutineScope()
                    val arrowOffsetY = remember { Animatable(0f) }
                    val arrowAlpha = remember { Animatable(1f) }
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(ThemeColors.brand, CircleShape)
                            .clickable {
                                val text = inputText
                                if (text.isNotBlank() || pendingAttachments.isNotEmpty()) {
                                    inputText = ""
                                    val atts = pendingAttachments; pendingAttachments = emptyList()
                                    // 发送后立即收起悬浮下拉
                                    showMentionDropdown = false; showBangDropdown = false
                                    inputFocus.requestFocus()
                                    val modeTag = activeTags.filterIsInstance<InputTag.Mode>().firstOrNull()
                                    val agentTag = activeTags.filterIsInstance<InputTag.AgentRef>().firstOrNull()
                                    scope.launch {
                                        // ↑ flies upward and out
                                        launch { arrowOffsetY.animateTo(-60f, tween(280)) }
                                        launch { arrowAlpha.animateTo(0f, tween(280)) }
                                        // snap below, then submit
                                        arrowOffsetY.snapTo(60f)
                                        viewModel.submitTask(buildTaskContent(text, atts), pluginViewModel, maxSteps = keyMaxSteps,
                                            executionMode = modeTag?.mode, agentRef = agentTag?.agentName,
                                            attachments = atts)
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
                } // close Row (input field row)
            } // close Column (input bar container)
            } // close Surface (input bar)

            // ── 录音指示条 (v0.33.0+) — 悬浮在输入栏上缘, 红点脉冲 + 计时 ──
            // 全限定名: BoxScope 版 AnimatedVisibility (material3 裸名是 ColumnScope 版)
            androidx.compose.animation.AnimatedVisibility(
                visible = isRecordingVoice,
                modifier = Modifier.align(Alignment.TopCenter).padding(bottom = 8.dp)
            ) {
                Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.72f)) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        val pulse = rememberInfiniteTransition(label = "recPulse")
                        val pulseAlpha by pulse.animateFloat(
                            initialValue = 1f, targetValue = 0.3f,
                            animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
                            label = "recAlpha"
                        )
                        Box(Modifier.size(8.dp).background(Color(0xFFFF4D4F).copy(alpha = pulseAlpha), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text("${strings.voiceReleaseToSend} · ${strings.voiceSlideToCancel}",
                            color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(8.dp))
                        Text("${recordElapsedMs / 1000}s", color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } // close Box (input bar)
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

    // ── Expand bottom sheet (3-section layout) — 拆至 MainScreenExpandSheet.kt ──
    MainScreenExpandSheet(
        show = showExpandSheet,
        sheetState = sheetState,
        strings = strings,
        panelOrder = panelOrder,
        activeTags = activeTags,
        pluginViewModel = pluginViewModel,
        onAddTag = viewModel::addTag,
        onRemoveTag = viewModel::removeTag,
        onPluginCommand = { inputText = it },
        onDismiss = { showExpandSheet = false },
        onPickImage = {
            pendingUploadDir = com.mengpaw.kernel.DataPaths.AGENTS + "/${displayAgentName}/workspace"
            imagePicker.launch("image/*")
        },
        onPickDocument = {
            pendingUploadDir = com.mengpaw.kernel.DataPaths.AGENTS + "/${displayAgentName}/workspace"
            docPicker.launch(arrayOf(
                "application/pdf", "text/plain", "text/markdown",
                "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            ))
        },
        onPickFile = {
            pendingUploadDir = com.mengpaw.kernel.DataPaths.AGENTS + "/${displayAgentName}/workspace"
            filePicker.launch(arrayOf("*/*"))
        },
        onPickCamera = {
            pendingUploadDir = com.mengpaw.kernel.DataPaths.AGENTS + "/${displayAgentName}/workspace"
            cameraLauncher.launch(cameraUri)
        }
    )
} // close outermost Box
} // close MainScreen composable


