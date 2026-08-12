// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import kotlinx.coroutines.flow.distinctUntilChanged

// ── 消息列表自动滚动行为 — 拆自 MainScreen.kt (2026-08-06, >400 行文件拆分批次4) ──
// 初始到底 / 生成期间持续贴底跟随 (用户上滑停止) / 思考结束回顶 + 自动聚焦输入框。

@Composable
internal fun rememberAutoScrollBehavior(
    listState: LazyListState,
    displayedMessages: List<ChatMessageUi>,
    isRunning: Boolean,
    inputFocus: FocusRequester
) {
    /** Bounds-checked scroll helper — swallows out-of-range errors. */
    suspend fun safeScrollTo(index: Int, animated: Boolean = true) {
        val total = listState.layoutInfo.totalItemsCount
        if (total == 0 || index < 0 || index >= total) return
        try {
            if (animated) listState.animateScrollToItem(index)
            else listState.scrollToItem(index)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // 协程取消正常传播 (组件退出/效果重启)
        } catch (_: Exception) { /* layout not ready, ignore */ }
    }

    // Initial load: scroll to bottom
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(200) // wait for layout
        if (displayedMessages.isNotEmpty()) safeScrollTo(displayedMessages.size - 1, animated = false)
    }

    // During streaming (v0.36.3): 长驻循环每 100ms 把列表钉在底部 — 原实现只在
    // 消息数变化时跟随, 末条气泡内部流式更新不触发, 生成时并不"紧贴底部"。
    // 规则: 未冻结 → 不在底部即滚动到底 (内容增长/新消息/生成开始都跟随);
    //       用户手势开始 → 立即冻结; 滚动停止后若未贴到真正的底部 (含在末条长文
    //       内部上滑) 保持冻结, 回到底部自动恢复。
    var userLeftBottom by remember { mutableStateOf(false) }
    var autoScrolling by remember { mutableStateOf(false) }

    /** 是否已滚到列表真正的末尾 — canScrollForward=false 即无法再向下滚
     *  (含内容短于视口的全显场景); 在末条长文内部上滑时可继续向下 → false。 */
    fun atTrueBottom(): Boolean {
        return !listState.canScrollForward && listState.layoutInfo.totalItemsCount > 0
    }

    // 用户手势检测: 滚动开始 (非自动跟随引发) → 冻结; 滚动完全停止后按是否
    // 真正在底部决定恢复/保持冻结。
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling && !autoScrolling) userLeftBottom = true
                else if (!scrolling && atTrueBottom()) userLeftBottom = false
            }
    }

    LaunchedEffect(isRunning) {
        if (!isRunning) {
            userLeftBottom = false
            return@LaunchedEffect
        }
        userLeftBottom = false
        while (true) {
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0 && !userLeftBottom && !atTrueBottom() &&
                !listState.isScrollInProgress
            ) {
                autoScrolling = true
                safeScrollTo(total - 1)
                autoScrolling = false
                // 兜底: 滚动被用户手势打断 (未停在底部) → 冻结跟随
                val after = listState.layoutInfo
                val afterLast = after.visibleItemsInfo.lastOrNull()
                if (afterLast == null || afterLast.index < after.totalItemsCount - 1) {
                    userLeftBottom = true
                }
            }
            kotlinx.coroutines.delay(100)
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
