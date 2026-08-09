// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoRadius
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.shell.ui.localization.AppStrings
import java.io.File

/**
 * Left sidebar — Agent switcher + Framework directory.
 *
 * Changes from v0.6.x:
 * - Long-press "申请智能体调度权限" removed.
 * - Tapping an agent opens an Agent Card dialog (avatar, name, workspace, intro).
 * - "+ New Agent" opens a creation dialog with name / folder / intro fields.
 *
 * Agent 列表 → SidebarAgentList.kt; 框架通讯录 → SidebarFrameworkDirectory.kt;
 * 通讯录加载 → SidebarContacts.kt; 孪生对话框 → sidebar-dialogs/ (2026-08-06, 批次4)。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun SidebarContent(
    strings: AppStrings,
    onNavigateToPlugins: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onClose: () -> Unit,
    activeAgent: String = "MengPaw",
    onSwitchAgent: (String, String?) -> Unit = { _, _ -> },
    onCreateAgent: (String) -> Unit = {},
    // Extended create — passes name, workspace folder name, and intro
    onCreateAgentWithDetails: (name: String, workspaceFolder: String, intro: String) -> Unit = { name, _, _ -> onCreateAgent(name) },
    onActivateMemoryTwin: () -> Unit = {},
    isRunning: Boolean = false
) {
    var frameworkStatus by remember { mutableStateOf(FrameworkStatus.ONLINE) }
    var wasAutoBusy by remember { mutableStateOf(false) }

    // Auto-switch: running → BUSY, done → back to ONLINE
    LaunchedEffect(isRunning) {
        if (isRunning && frameworkStatus == FrameworkStatus.ONLINE) {
            frameworkStatus = FrameworkStatus.BUSY
            wasAutoBusy = true
        } else if (!isRunning && wasAutoBusy) {
            frameworkStatus = FrameworkStatus.ONLINE
            wasAutoBusy = false
        }
    }

    // ── Card dialog states ──
    var cardAgentName by remember { mutableStateOf<String?>(null) }
    // v0.35.4: 名片直接持联系人 — 手机端 ACP 配对未入册的框架也能正确显示信任按钮
    var cardFramework by remember { mutableStateOf<FrameworkContact?>(null) }

    // ── 框架配对请求弹窗 (v0.35.4) — 收到 FRAMEWORK_PAIR_REQUEST 时提醒用户 ──
    var incomingPairRequest by remember { mutableStateOf<com.mengpaw.plugin.framework.FrameworkPairStore.PairRequest?>(null) }
    val knownPendingIds = remember { mutableStateOf<Set<String>>(emptySet()) }
    val pairScope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        val listener: () -> Unit = {
            val pending = com.mengpaw.plugin.framework.FrameworkPairStore.pending()
            val newIds = pending.map { it.requestId }.toSet() - knownPendingIds.value
            knownPendingIds.value = pending.map { it.requestId }.toSet()
            // 已处理 (同意/拒绝) 的请求自动收起弹窗
            incomingPairRequest?.let { cur ->
                if (pending.none { it.requestId == cur.requestId }) incomingPairRequest = null
            }
            // 仅对新到达的请求弹窗 (App 重启后的历史 pending 不弹)
            if (newIds.isNotEmpty() && incomingPairRequest == null) {
                incomingPairRequest = pending.firstOrNull { it.requestId in newIds }
            }
        }
        listener()
        com.mengpaw.plugin.framework.FrameworkPairStore.addListener(listener)
        onDispose { com.mengpaw.plugin.framework.FrameworkPairStore.removeListener(listener) }
    }

    // ── New Agent dialog state ──
    var showNewAgentDialog by remember { mutableStateOf(false) }
    var showAddFramework by remember { mutableStateOf(false) }
    var showTwinConfirmDialog by remember { mutableStateOf(false) }
    var twinPairTarget by remember { mutableStateOf<FrameworkContact?>(null) }

    // Discover agents from disk — P2: remember(refreshTick) 替代裸 listFiles,
    // 目录扫描只发生在 创建/切换 等事件后, 不再每次重组主线程 IO
    var refreshTick by remember { mutableStateOf(0) }
    val onCreateAgentWrapped: (String) -> Unit = { name -> onCreateAgent(name); refreshTick++ }
    val onCreateAgentWithDetailsWrapped: (String, String, String) -> Unit = { name, wsFolder, intro ->
        onCreateAgentWithDetails(name, wsFolder, intro); refreshTick++
    }
    val agentsDir = File(com.mengpaw.kernel.DataPaths.AGENTS)
    // Exclude system dirs from agent list — 统一判定 DataPaths.isAgentWorkspaceDir
    // (v0.34.x: 散落名单漏 default/twin, 无主进化档案目录被识别为假 Agent)
    val discoveredAgents = remember(refreshTick) {
        try { agentsDir.listFiles()
            ?.filter { it.isDirectory && com.mengpaw.kernel.DataPaths.isAgentWorkspaceDir(it.name) }
            ?.map { it.name }?.sorted()
            ?.ifEmpty { listOf("MengPaw") } ?: listOf("MengPaw") } catch (_: Exception) { listOf("MengPaw") }
    }

    // Load saved framework contacts from ACP_TRUSTED + discovered peers
    val frameworks = remember {
        mutableStateListOf<FrameworkContact>().also { it.addAll(loadFrameworkContacts()) }
    }

    // v0.34.3 框架发现调整: 打开侧边栏 → 启动 10s 发现循环 + 周期刷新通讯录; 关闭 → 停止
    LaunchedEffect(Unit) {
        com.mengpaw.plugin.framework.FrameworkDiscovery.instance?.startContinuousDiscovery()
        while (true) {
            delay(10_000)
            val fresh = loadFrameworkContacts()
            frameworks.clear()
            frameworks.addAll(fresh)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            com.mengpaw.plugin.framework.FrameworkDiscovery.instance?.stopContinuousDiscovery()
        }
    }

    Column(Modifier.fillMaxHeight().width(280.dp).background(ThemeColors.bgPrimary).padding(ArcoSpacing.lg).verticalScroll(rememberScrollState())) {
        AgentListSection(
            discoveredAgents = discoveredAgents,
            activeAgent = activeAgent,
            strings = strings,
            onSwitchAgent = onSwitchAgent,
            onClose = onClose,
            onAgentLongClick = { cardAgentName = it },
            onAddAgent = { showNewAgentDialog = true }
        )

        Spacer(Modifier.height(ArcoSpacing.md))

        // ── Framework Status ──
        Text(strings.sidebarFrameworkStatus, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(ArcoSpacing.sm))
        FrameworkStatus.entries.forEach { status ->
            val selected = frameworkStatus == status
            Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                    .clickable { frameworkStatus = status; wasAutoBusy = false },
                shape = RoundedCornerShape(ArcoRadius.md),
                color = if (selected) status.indicatorColor.copy(alpha = 0.1f) else Color.Transparent
            ) {
                Row(Modifier.padding(horizontal = ArcoSpacing.sm, vertical = ArcoSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(status.indicatorColor, CircleShape))
                    Spacer(Modifier.width(ArcoSpacing.sm))
                    Column(Modifier.weight(1f)) {
                        Text(status.label(strings), fontWeight = FontWeight.Medium, fontSize = 14.sp,
                            color = if (selected) status.indicatorColor else ThemeColors.textPrimary)
                        Text(status.desc(strings), fontSize = 11.sp, color = ThemeColors.textSecondary)
                    }
                    if (selected) {
                        Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = status.indicatorColor)
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = ArcoSpacing.lg))

        FrameworkDirectorySection(
            frameworks = frameworks,
            frameworkStatus = frameworkStatus,
            strings = strings,
            activeAgent = activeAgent,
            onSwitchAgent = onSwitchAgent,
            onAddFramework = { showAddFramework = true },
            onFrameworkLongClick = { cardFramework = it },
            onTwinActivate = { framework ->
                twinPairTarget = framework
                showTwinConfirmDialog = true
            }
        )

        HorizontalDivider(Modifier.padding(vertical = ArcoSpacing.lg))

        SidebarQuickNav(
            strings = strings,
            onNavigateToPlugins = onNavigateToPlugins,
            onNavigateToSettings = onNavigateToSettings
        )

        // Bottom safe area for nav bar
        Spacer(Modifier.height(ArcoSpacing.lg))
    }

    // ═══════════════════════════════════════════════════════════════════
    // Agent Card Dialog — replaces old long-press "申请智能体调度权限"
    // ═══════════════════════════════════════════════════════════════════
    cardAgentName?.let { name ->
        AgentCardDialog(
            strings = strings,
            agentName = name,
            onDismiss = { cardAgentName = null }
        )
    }

    cardFramework?.let { contact ->
        FrameworkCardDialog(
            strings = strings,
            framework = contact,
            onDismiss = { cardFramework = null }
        )
    }

    // ── 收到框架配对请求 → 弹窗 (添加框架页面打开时不弹, 请求已在列表内) ──
    incomingPairRequest?.let { req ->
        if (!showAddFramework) {
            AlertDialog(
                onDismissRequest = { incomingPairRequest = null },
                title = { Text(strings.frameworkPairRequestTitle, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text(String.format(strings.pairRequestBody, req.fromName),
                            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        Text("${req.fromAddress}:${req.fromPort} · ${com.mengpaw.plugin.framework.FrameworkPeerStore.shortCodeOf(req.fromFingerprint)}",
                            style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                        Spacer(Modifier.height(8.dp))
                        Text(strings.pairRequestHint,
                            style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val r = req
                        pairScope.launch(Dispatchers.IO) { com.mengpaw.plugin.framework.FrameworkPairEngine.accept(r) }
                        incomingPairRequest = null
                    }) { Text(strings.pairAccept, color = ThemeColors.brand, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { incomingPairRequest = null }) { Text(strings.pairRequestLater) }
                        TextButton(onClick = {
                            val r = req
                            pairScope.launch(Dispatchers.IO) { com.mengpaw.plugin.framework.FrameworkPairEngine.decline(r) }
                            incomingPairRequest = null
                        }) { Text(strings.pairDecline, color = ArcoColors.Red6) }
                    }
                }
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 添加框架独立页面 (v0.35.1 请求-同意流程) — 待处理请求 + 扫描 + 手动添加
    // 修复: 原 showAddFramework 置 true 后从未渲染 (添加按钮无效根因)
    // ═══════════════════════════════════════════════════════════════════
    if (showAddFramework) {
        AddFrameworkScreen(
            strings = strings,
            onDismiss = { showAddFramework = false }
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // 记忆孪生配对对话框 (请求/验证码/5连击确认) — 已拆至 TwinPairingDialogs.kt
    // ═══════════════════════════════════════════════════════════════════
    TwinPairingDialogs(
        strings = strings,
        onActivateMemoryTwin = onActivateMemoryTwin,
        twinPairTarget = twinPairTarget,
        showTwinConfirmDialog = showTwinConfirmDialog,
        onDismissTwinConfirm = {
            showTwinConfirmDialog = false
            twinPairTarget = null
        }
    )

    // ═══════════════════════════════════════════════════════════════════
    // New Agent Dialog
    // ═══════════════════════════════════════════════════════════════════
    if (showNewAgentDialog) {
        NewAgentDialog(
            strings = strings,
            initialName = String.format(strings.newAgentDefaultName, discoveredAgents.size + 1),
            onDismiss = { showNewAgentDialog = false },
            onConfirm = { form ->
                val wsFolder = form.workspaceFolder.ifBlank { form.name }
                onCreateAgentWithDetailsWrapped(form.name, wsFolder, form.intro)
                showNewAgentDialog = false
            }
        )
    }
}
