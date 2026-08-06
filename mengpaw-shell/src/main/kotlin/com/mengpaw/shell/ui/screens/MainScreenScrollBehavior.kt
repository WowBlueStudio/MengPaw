// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import com.mengpaw.shell.ui.screens.model.ChatMessageUi

// ── 消息列表自动滚动行为 — 拆自 MainScreen.kt (2026-08-06, >400 行文件拆分批次4) ──
// 初始到底 / 流式期间防抖跟随 / 思考结束回顶 + 自动聚焦输入框, 逻辑逐行迁移。

@Composable
internal fun rememberAutoScrollBehavior(
    listState: LazyListState,
    displayedMessages: List<ChatMessageUi>,
    isRunning: Boolean,
    inputFocus: FocusRequester
) {
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
    var wasRunning by remember { mutableStateOf(false) }
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
}
