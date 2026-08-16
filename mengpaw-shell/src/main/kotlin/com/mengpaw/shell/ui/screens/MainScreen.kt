// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.session.AttachmentData
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import com.mengpaw.shell.ui.screens.model.InputTag
import com.mengpaw.shell.ui.screens.model.PendingTask
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mengpaw.shell.ui.components.NotifyBannerHost
import com.mengpaw.shell.ui.components.PlanStatusRail
import com.mengpaw.shell.ui.MAX_CONTENT_WIDTH
import com.mengpaw.shell.ui.isWide
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoSpacing

/**
 * 历史消息统一重排 (v0.34.3 气泡 UI 重构) — 旧 agent_step 序列 / agent_trace
 * 合并为「思考过程容器 + 最终答案」结构, 新旧会话视觉一致。
 * 规则: 连续非 final 的 AgentStep → ThinkingProcess (每步 = 思考 + 工具行);
 * isFinal 的 AgentStep / Agent / AgentWithTrace.finalContent → FinalAnswer。
 */
internal fun reflowLegacyMessages(messages: List<ChatMessageUi>): List<ChatMessageUi> {
    val out = mutableListOf<ChatMessageUi>()
    val pending = mutableListOf<ChatMessageUi.AgentStep>()

    fun flushPending() {
        if (pending.isEmpty()) return
        val first = pending.first()
        val anyRunning = pending.any { it.isRunning }
        val steps = pending.map { s ->
            ChatMessageUi.ProcessStep(
                thought = s.thought,
                tools = s.action?.takeIf { it.isNotBlank() }?.let { action ->
                    val name = action.substringBefore(' ')
                    listOf(ChatMessageUi.ProcessTool(
                        command = name,
                        actionInput = action.removePrefix(name).trim(),
                        observation = s.content,
                        isError = s.content.startsWith("Error [")
                    ))
                } ?: emptyList()
            )
        }
        out.add(ChatMessageUi.ThinkingProcess(
            steps = steps, isRunning = anyRunning, collapsed = !anyRunning,
            executionMode = first.executionMode, agentRef = first.agentRef
        ))
        pending.clear()
    }

    messages.forEach { m ->
        when (m) {
            is ChatMessageUi.AgentStep -> {
                if (m.isFinal) {
                    flushPending()
                    out.add(ChatMessageUi.FinalAnswer(
                        content = m.content, isRunning = m.isRunning,
                        executionMode = m.executionMode, agentRef = m.agentRef))
                } else {
                    pending.add(m)
                }
            }
            is ChatMessageUi.AgentWithTrace -> {
                flushPending()
                val steps = m.traces.map { t ->
                    ChatMessageUi.ProcessStep(
                        thought = t.thought,
                        tools = t.action?.takeIf { it.isNotBlank() }?.let { action ->
                            val name = action.substringBefore(' ')
                            listOf(ChatMessageUi.ProcessTool(
                                command = name,
                                actionInput = action.removePrefix(name).trim(),
                                observation = t.observation ?: "",
                                isError = t.observation?.startsWith("Error [") == true
                            ))
                        } ?: emptyList()
                    )
                }
                out.add(ChatMessageUi.ThinkingProcess(
                    steps = steps, isRunning = m.isRunning, collapsed = !m.isRunning,
                    executionMode = m.executionMode, agentRef = m.agentRef))
                if (m.finalContent.isNotBlank()) {
                    out.add(ChatMessageUi.FinalAnswer(
                        content = m.finalContent, isRunning = m.isRunning,
                        executionMode = m.executionMode, agentRef = m.agentRef))
                }
            }
            is ChatMessageUi.Agent -> {
                flushPending()
                out.add(ChatMessageUi.FinalAnswer(
                    content = m.content, isRunning = false,
                    executionMode = m.executionMode, agentRef = m.agentRef))
            }
            else -> {
                flushPending()
                out.add(m)
            }
        }
    }
    flushPending()
    return out
}

// 底部输入栏/@mention·!bang 下拉 → MainScreenInputBar.kt; 文件选择器 → MainScreenPickers.kt;
// 自动滚动 → MainScreenScrollBehavior.kt; 头栏/侧栏/底表 → 既有拆分 (2026-08-06, 批次4)

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
    // v0.37.3: 高危操作确认改通知栏横幅 (HighRiskNotification), 应用内不再弹 AlertDialog —
    // 后台/其他页面触发时用户也能从通知栏看到并确认 (原弹窗不可见 + 30s 静默拒绝)
    // P2 修复: rememberSaveable — 输入框草稿/展开状态跨配置变更与进程重建保留
    var inputText by rememberSaveable { mutableStateOf("") }
    val inputFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val listState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showExpandSheet by rememberSaveable { mutableStateOf(false) }

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
    var showLeftSidebar by rememberSaveable { mutableStateOf(false) }
    var showRightSidebar by rememberSaveable { mutableStateOf(false) }

    // ── Active tags from ViewModel ──────────────────────────────────
    val activeTags by viewModel.activeTags.collectAsState()

    // ── Panel order state ──────────────────────────────────────────
    var panelOrder by remember { mutableStateOf(com.mengpaw.shell.ui.components.PanelOrderStore.load()) }

    // ── File picker launchers (拆至 MainScreenPickers.kt) ──
    // v0.33.0+: 产出结构化附件, 不再插文本进输入框
    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingUploadDir by remember { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf(listOf<AttachmentData>()) }
    val pickers = rememberFilePickers(
        context = context,
        pendingUploadDir = pendingUploadDir,
        onAttachment = { att -> pendingAttachments = pendingAttachments + att },
        onError = { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    )

    // ── Auto-scroll behavior (拆至 MainScreenScrollBehavior.kt) ──
    rememberAutoScrollBehavior(listState, displayedMessages, isRunning, inputFocus)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxSize().widthIn(max = MAX_CONTENT_WIDTH.dp)) {
            // ── Header bar (拆至 MainScreenHeader.kt) ──
            MainScreenHeader(
                strings = strings,
                displayAgentName = displayAgentName,
                agentFramework = agentFramework,
                sessionLabel = agentViewModel?.activeSessionLabel(strings) ?: "MengPaw / ${strings.agentUnconfigured}",
                pluginViewModel = pluginViewModel,
                onPluginCommand = { inputText = it },
                onToggleLeftSidebar = { showLeftSidebar = !showLeftSidebar },
                onToggleRightSidebar = { showRightSidebar = !showRightSidebar },
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
                                    // v0.39.0 动画增强: 列表项平滑出现/移动/移除 (Compose 1.7 animateItem)
                                    Box(Modifier.animateItem()) {
                                    BubbleWrapper(
                                        strings = strings,
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
                                            is ChatMessageUi.ThinkingProcess -> ThinkingProcessBubble(message, displayAgentName)
                                            is ChatMessageUi.FinalAnswer -> FinalAnswerBubble(message, displayAgentName)
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
                            }

                                    // ── 待办列表 — 浮动在气泡层上方，有内容时弹出 ──
                                    val pendingTasks by viewModel.pendingTasks.collectAsState()
                                    // 全限定名: BoxScope 版 AnimatedVisibility (裸名会解析到 ColumnScope 版)
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

                            // v0.34.3 /plan UI: 消息区右侧状态竖列 (隐藏侧边栏时可见)
                            Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                                PlanStatusRail(onOpen = { showRightSidebar = true })
                            }
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
                        // P2 修复: 横幅切回默认主 Agent — 原字面量硬编码, 改用唯一事实源常量
                        (agentViewModel ?: viewModel).switchAgent(DEFAULT_AGENT_NAME)
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

            // ── Bottom input bar + @mention/!bang 下拉 (拆至 MainScreenInputBar.kt) ──
            MainScreenInputBar(
                inputText = inputText,
                onInputTextChange = { inputText = it },
                inputFocus = inputFocus,
                activeTags = activeTags,
                onRemoveTag = viewModel::removeTag,
                pendingAttachments = pendingAttachments,
                onRemoveAttachment = { att -> pendingAttachments = pendingAttachments - att },
                onClearAttachments = { pendingAttachments = emptyList() },
                strings = strings,
                maxSteps = settingsState?.value?.maxSteps ?: 50,
                isRunning = isRunning,
                viewModel = viewModel,
                pluginViewModel = pluginViewModel,
                onExpandSheet = { showExpandSheet = true }
            )
        } // close Column
    } // close outermost Box

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
            pickers.imagePicker.launch("image/*")
        },
        onPickDocument = {
            pendingUploadDir = com.mengpaw.kernel.DataPaths.AGENTS + "/${displayAgentName}/workspace"
            pickers.docPicker.launch(arrayOf(
                "application/pdf", "text/plain", "text/markdown",
                "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            ))
        },
        onPickFile = {
            pendingUploadDir = com.mengpaw.kernel.DataPaths.AGENTS + "/${displayAgentName}/workspace"
            pickers.filePicker.launch(arrayOf("*/*"))
        },
        onPickCamera = {
            pendingUploadDir = com.mengpaw.kernel.DataPaths.AGENTS + "/${displayAgentName}/workspace"
            pickers.cameraLauncher.launch(pickers.cameraUri)
        }
    )
}
