// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.shell.ui.screens.model.AgentTrace
import com.mengpaw.shell.ui.screens.model.ChatMessageUi

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

// ═══════════════════════════════════════════════════════════════════════
// v0.34.3 气泡 UI 重构: 思考过程容器 + 最终答案
// 时间轴主导: 思考/调用/观察循环收进单一可折叠容器, 最终答案独立气泡。
// 工具行只显示命令名 (失败红字), 观察全文点击展开; 思考全文保留可回看;
// 折叠态显示 "N 轮思考 · M 次调用" 摘要。
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun ThinkingProcessBubble(message: ChatMessageUi.ThinkingProcess, agentName: String = "MengPaw") {
    // 自动折叠: 最终答案开始 (collapsed=true) 默认收起; 运行中强制展开 (思考可见)
    var expanded by rememberSaveable(message.stableId) { mutableStateOf(!message.collapsed) }
    LaunchedEffect(message.collapsed, message.isRunning) {
        if (message.isRunning) expanded = true
        else if (message.collapsed) expanded = false
    }

    Column(Modifier.fillMaxWidth()) {
        // ── 头部: "N 轮思考 · M 次调用" 摘要 + 折叠开关 + 运行中反馈 ──
        Row(
            Modifier.fillMaxWidth(0.95f).padding(horizontal = ArcoSpacing.sm, vertical = 6.dp)
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                null, Modifier.size(16.dp), tint = ThemeColors.brand)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Outlined.Psychology, null, Modifier.size(16.dp), tint = ThemeColors.brand)
            Spacer(Modifier.width(6.dp))
            Text(
                if (message.isRunning) "思考中…" else "思考过程",
                style = MaterialTheme.typography.labelSmall, color = ThemeColors.brand
            )
            Spacer(Modifier.width(8.dp))
            Text("${message.steps.size} 轮思考 · ${message.toolCount} 次调用",
                style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
            if (message.isRunning) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp,
                    color = ThemeColors.brand)
            }
        }

        // ── 展开内容: 每轮思考全文 + 折叠工具行 ──
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth(0.95f).padding(start = ArcoSpacing.md, end = ArcoSpacing.sm, bottom = ArcoSpacing.sm)) {
                message.steps.forEachIndexed { i, step ->
                    if (step.thought.isNotBlank()) {
                        Text(if (message.steps.size > 1) "第 ${i + 1} 轮思考" else "思考",
                            style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
                        Spacer(Modifier.height(2.dp))
                        Text(step.thought, style = MaterialTheme.typography.bodySmall,
                            color = ThemeColors.textSecondary)
                        Spacer(Modifier.height(6.dp))
                    }
                    step.tools.forEach { tool -> ProcessToolRow(tool) }
                }
            }
        }
    }
}

/** 工具调用折叠行 — 只显示命令名; 失败红字; 点击展开参数与观察全文。 */
@Composable
private fun ProcessToolRow(tool: ChatMessageUi.ProcessTool) {
    var showDetail by remember(tool.command) { mutableStateOf(false) }
    val fg = if (tool.isError) ArcoColors.Red6 else ThemeColors.textPrimary
    Row(
        Modifier.fillMaxWidth().clickable { showDetail = !showDetail }.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (tool.isError) Icons.Outlined.Error else Icons.Outlined.Terminal,
            null, Modifier.size(14.dp), tint = if (tool.isError) ArcoColors.Red6 else ThemeColors.textSecondary)
        Spacer(Modifier.width(6.dp))
        Text(tool.command, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = fg)
        if (tool.observation.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(if (tool.isError) "✗ 失败" else "✓",
                fontSize = 11.sp, color = if (tool.isError) ArcoColors.Red6 else ArcoColors.Green6)
        } else {
            Spacer(Modifier.width(6.dp))
            Text("…", fontSize = 11.sp, color = ThemeColors.textSecondary)
        }
    }
    // ── 展开态: 完整参数 + 观察全文 (可选中复制) ──
    AnimatedVisibility(visible = showDetail && tool.observation.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().padding(start = ArcoSpacing.md, bottom = 4.dp)) {
            if (tool.actionInput.isNotBlank()) {
                Text("参数: ${tool.actionInput}", fontSize = 11.sp,
                    color = ThemeColors.textSecondary, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(2.dp))
            }
            SelectionContainer {
                Text(tool.observation, fontSize = 11.sp, color = ThemeColors.textSecondary)
            }
        }
    }
}

/** 最终答案气泡 — 与思考过程容器分离, 流式输出, 完成后提取附件卡片。 */
@Composable
fun FinalAnswerBubble(message: ChatMessageUi.FinalAnswer, agentName: String = "MengPaw") {
    val (cleanFinal, mediaCards) = remember(message.content) { extractMedia(message.content) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(ArcoRadius.lg, ArcoRadius.lg, ArcoRadius.lg, ArcoRadius.sm),
            color = ThemeColors.bgCardHigh, modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Column(Modifier.padding(ArcoSpacing.lg)) {
                AgentBubbleHeader(agentName = agentName, executionMode = message.executionMode,
                    agentRef = message.agentRef)
                Spacer(Modifier.height(ArcoSpacing.xs))
                if (message.isRunning &&
                    (cleanFinal.isBlank() || cleanFinal == "思考中...")) {
                    WaitingIndicator("思考中...")
                } else if (cleanFinal.isNotBlank()) {
                    SelectionContainer {
                        MarkdownText(content = cleanFinal,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = ThemeColors.textPrimary),
                            nestedScroll = true)
                    }
                }
                if (mediaCards.isNotEmpty() && !message.isRunning) {
                    Spacer(Modifier.height(ArcoSpacing.sm))
                    AttachmentCardList(mediaCards, isUserSide = false)
                }
            }
        }
    }
}

// ── Agent Step Bubble (v0.3x): 每个 ReAct 步骤一个独立气泡 ──
// 形态: [Step N 思考(折叠, 展开=完整思考全文+工具调用)] + 正文(中间输出/最终答案)
// 默认展开 — 用户要求展开必须看到全程 (完整思考不截断)
@Composable
fun AgentStepBubble(message: ChatMessageUi.AgentStep, agentName: String = "MengPaw") {
    // P2 修复: rememberSaveable — 思考折叠状态跨配置变更/滚动保留 (LazyColumn 有 stableId 键)
    var thinkingExpanded by rememberSaveable { mutableStateOf(true) }

    Column(Modifier.fillMaxWidth()) {
        // ── 思考折叠头: 完整思考 + 工具调用, 点击折叠/展开 ──
        if (message.thought.isNotBlank() || message.action != null) {
            Column(Modifier.fillMaxWidth(0.95f).padding(bottom = 2.dp)
                .clickable { thinkingExpanded = !thinkingExpanded }) {
                Row(Modifier.padding(horizontal = ArcoSpacing.sm, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Psychology, null, Modifier.size(16.dp), tint = ThemeColors.brand)
                    Spacer(Modifier.width(6.dp))
                    Text(if (message.isFinal) "思考" else "Step ${message.step} 思考",
                        style = MaterialTheme.typography.labelSmall, color = ThemeColors.brand)
                    Spacer(Modifier.width(4.dp))
                    Icon(if (thinkingExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        null, Modifier.size(16.dp), tint = ThemeColors.brand)
                }
                AnimatedVisibility(visible = thinkingExpanded) {
                    Column(Modifier.padding(start = ArcoSpacing.sm, end = ArcoSpacing.sm, bottom = ArcoSpacing.sm)) {
                        // 完整思考全文 — 全程可见, 不截断
                        Text(message.thought, style = MaterialTheme.typography.bodySmall,
                            color = ThemeColors.textSecondary)
                        // 工具调用行 (终端风格)
                        if (message.action != null) {
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Terminal, null, Modifier.size(14.dp),
                                    tint = ThemeColors.textSecondary)
                                Spacer(Modifier.width(4.dp))
                                Text(message.action, fontSize = 12.sp, color = ThemeColors.textSecondary,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }

        // ── 正文: 等待态 / 流式 / 中间输出 / 最终答案 ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Surface(shape = RoundedCornerShape(ArcoRadius.lg, ArcoRadius.lg, ArcoRadius.lg, ArcoRadius.sm),
                color = ThemeColors.bgCardHigh, modifier = Modifier.fillMaxWidth(0.9f)) {
                Column(Modifier.padding(ArcoSpacing.lg)) {
                    AgentBubbleHeader(agentName = agentName, executionMode = message.executionMode, agentRef = message.agentRef)
                    Spacer(Modifier.height(ArcoSpacing.xs))
                    // 等待期反馈: 思考中/正在执行 → spinner + 秒数
                    if (message.isRunning &&
                        (message.content == "思考中..." || message.content.isBlank() ||
                            message.content.startsWith(EXECUTING_TOOL_PREFIX))) {
                        WaitingIndicator(message.content)
                    } else if (message.content.isNotBlank()) {
                        SelectionContainer {
                            MarkdownText(content = message.content,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = ThemeColors.textPrimary),
                                nestedScroll = true)
                        }
                    }
                }
            }
        }
    }
}

// ── Agent Bubble with Trace (legacy, 历史会话 agent_trace 兼容) ──
@Composable
fun AgentBubbleWithTrace(message: ChatMessageUi.AgentWithTrace, agentName: String = "MengPaw") {
    val traces = message.traces
    // v0.3x: 运行结束不再自动折叠 — 用户需要看到完整结果 (此前结束时面板收起 +
    // finalContent 替换, 中间输出在视觉上"被最终答案覆盖"; 保留展开状态,
    // 完整思考过程/工具调用/结果全程可见, 用户可手动折叠长会话)
    // P2 修复: rememberSaveable — 思考折叠状态跨配置变更/滚动保留
    var thinkingExpanded by rememberSaveable { mutableStateOf(true) }

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
internal fun WaitingIndicator(waitingText: String) {
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
    // P2 修复: rememberSaveable — 工具块折叠状态跨配置变更保留
    var mergedExpanded by rememberSaveable { mutableStateOf(false) }

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
