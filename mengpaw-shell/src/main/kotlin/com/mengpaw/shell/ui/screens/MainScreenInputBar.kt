// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.kernel.session.AttachmentData
import com.mengpaw.shell.ui.localization.AppStrings
import com.mengpaw.shell.ui.screens.model.ExecutionMode
import com.mengpaw.shell.ui.screens.model.InputTag
import com.mengpaw.shell.ui.screens.model.VoiceCapability
import com.mengpaw.shell.ui.screens.model.buildTaskContent
import kotlinx.coroutines.launch

// ── 底部输入栏 + @mention/!bang 补全下拉 — 拆自 MainScreen.kt (2026-08-06, >400 行文件拆分批次4) ──
// 下拉状态/语音状态/发送入口 (performSend/doSend) 全部内聚于此,
// 与拆分前行为逐行一致 (输入文本与焦点仍由 MainScreen 持有)。

@Composable
internal fun MainScreenInputBar(
    inputText: String,
    onInputTextChange: (String) -> Unit,
    inputFocus: FocusRequester,
    activeTags: List<InputTag>,
    onRemoveTag: (InputTag) -> Unit,
    pendingAttachments: List<AttachmentData>,
    onRemoveAttachment: (AttachmentData) -> Unit,
    onClearAttachments: () -> Unit,
    strings: AppStrings,
    maxSteps: Int,
    viewModel: AgentViewModel,
    pluginViewModel: PluginViewModel,
    onExpandSheet: () -> Unit
) {
    // ── @mention state ──
    var showMentionDropdown by remember { mutableStateOf(false) }
    var mentionQuery by remember { mutableStateOf("") }
    // ── !bang 命令补全 state (与 @mention 共用同一悬浮控件) ──
    var showBangDropdown by remember { mutableStateOf(false) }
    var bangQuery by remember { mutableStateOf("") }

    // ── @mention / !bang 命令补全内联下拉（拆至 MainScreenCommandDropdown.kt）──
    CommandCompletionDropdown(
        showBangDropdown = showBangDropdown,
        showMentionDropdown = showMentionDropdown,
        bangQuery = bangQuery,
        mentionQuery = mentionQuery,
        inputFocus = inputFocus,
        viewModel = viewModel,
        onApplyBang = { cmdName ->
            val current = inputText
            val bangIdx = current.lastIndexOf('!')
            if (bangIdx >= 0) {
                val beforeBang = current.substring(0, bangIdx)
                val afterQuery = current.substring(bangIdx + 1 + bangQuery.length)
                onInputTextChange("$beforeBang!$cmdName $afterQuery")
            }
            showBangDropdown = false
            try { inputFocus.requestFocus() } catch (_: Exception) {}
        },
        onApplyMention = { name ->
            val current = inputText
            val atIdx = current.lastIndexOf('@')
            if (atIdx >= 0) {
                val beforeAt = current.substring(0, atIdx)
                val afterQuery = current.substring(atIdx + 1 + mentionQuery.length)
                onInputTextChange("$beforeAt@$name $afterQuery")
            }
            showMentionDropdown = false
            viewModel.addTag(InputTag.AgentRef(name))
            try { inputFocus.requestFocus() } catch (_: Exception) {}
        }
    )

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
                AnimatedVisibility(
                    visible = activeTags.isNotEmpty() || pendingAttachments.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    PendingAttachmentsBar(
                        activeTags = activeTags,
                        attachments = pendingAttachments,
                        strings = strings,
                        onRemoveTag = onRemoveTag,
                        onRemoveAttachment = onRemoveAttachment
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
                            .clickable { onExpandSheet() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Add, "扩展", tint = ThemeColors.textSecondary, modifier = Modifier.size(24.dp))
                    }
                    // Input field — soft keyboard Enter sends, Ctrl+Enter inserts newline
                    val keyMaxSteps = maxSteps
                    var lastSendTime by remember { mutableLongStateOf(0L) }
                    // P2 修复: 统一发送入口 — 键盘 doSend / 语音松手即发 / 发送按钮 三处共用,
                    // 原三份重复的"标签提取 + submitTask 调用"合并为一份 (语音路径不清空输入框, 只传附件)
                    fun performSend(text: String, atts: List<AttachmentData>) {
                        // 发送后立即收起悬浮下拉 — 程序化清空输入不触发 onValueChange, 需手动关闭
                        showMentionDropdown = false; showBangDropdown = false
                        val modeTag = activeTags.filterIsInstance<InputTag.Mode>().firstOrNull()
                        val agentTag = activeTags.filterIsInstance<InputTag.AgentRef>().firstOrNull()
                        viewModel.submitTask(buildTaskContent(text, atts), pluginViewModel, maxSteps = keyMaxSteps,
                            executionMode = modeTag?.mode, agentRef = agentTag?.agentName,
                            attachments = atts)
                    }
                    fun doSend() {
                        if (inputText.isNotBlank() || pendingAttachments.isNotEmpty()) {
                            val now = System.currentTimeMillis()
                            if (now - lastSendTime < 300) return  // debounce: prevent double-fire from onPreviewKeyEvent + IME
                            lastSendTime = now
                            val text = inputText; onInputTextChange("")
                            // v0.33.0+: 附件随消息发送 — 文本合成 `[图片附件] path` 标注
                            val atts = pendingAttachments; onClearAttachments()
                            performSend(text, atts)
                            inputFocus.requestFocus()
                        }
                    }
                    OutlinedTextField(value = inputText, onValueChange = { newVal ->
                        onInputTextChange(newVal)
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
                                            onInputTextChange(inputText + "\n")
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
                        // P2 修复: 原 imeAction=Default → 软键盘 Enter 只插换行, 与注释
                        // "soft keyboard Enter sends" 不符 — 改 Send 使软键盘 Enter 触发 doSend
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { doSend() }))
                    // Voice button (v0.33.0+) — 透明底线性话筒, 按住录音松开发送;
                    // 仅支持音频输入的模型显示 (VoiceCapability 判定)
                    if (voiceSupported) {
                        VoiceInputButton(
                            supported = voiceSupported,
                            strings = strings,
                            onRecorded = { att ->
                                // 按住发送语音 = 松手即发 — 录音文件直发模型 (input_audio 通道);
                                // 不清空输入框草稿 (P2 修复: 走统一 performSend, 语音路径仅传附件)
                                performSend("", listOf(att))
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
                                    onInputTextChange("")
                                    val atts = pendingAttachments; onClearAttachments()
                                    inputFocus.requestFocus()
                                    scope.launch {
                                        // ↑ flies upward and out
                                        launch { arrowOffsetY.animateTo(-60f, tween(280)) }
                                        launch { arrowAlpha.animateTo(0f, tween(280)) }
                                        // snap below, then submit (P2 修复: 走统一 performSend)
                                        arrowOffsetY.snapTo(60f)
                                        performSend(text, atts)
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
        AnimatedVisibility(
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
}
