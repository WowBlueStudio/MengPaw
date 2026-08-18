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
import com.mengpaw.shell.ui.screens.model.ChatMessageUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged

// ── 消息列表自动滚动行为 — 拆自 MainScreen.kt (2026-08-06, >400 行文件拆分批次4) ──
// v0.42.2 重构 (用户反馈: 生成时不动 + 结束后跳回上一条开头):
// 1. 贴底改为 scrollToBottom — 先定位末条, 再补滚到列表真正末端。原实现用
//    animateScrollToItem(total-1), 对末条高于视口 (最终答案流式增长) 只对齐 item 顶部,
//    视口钉在答案开头, 内容增长也不动 → "生成时不动"。
// 2. 删除"思考结束回顶 + 自动聚焦" — 原实现 indexOfLast { Agent || AgentWithTrace },
//    主路径消息是 ThinkingProcess/FinalAnswer, 该查找命中历史旧 Agent 消息 →
//    "突然跳到上一条的开头"。生成结束保持贴底, 不再强制滚动/聚焦输入框。
// 3. 手势冻结保留: 用户上滑 → 冻结跟随; 回到底部 → 自动恢复。

@Composable
internal fun rememberAutoScrollBehavior(
    listState: LazyListState,
    displayedMessages: List<ChatMessageUi>,
    isRunning: Boolean,
) {
    /**
     * 滚动到列表真正末端 — 末条高于视口时 scrollToItem 只对齐其顶部,
     * 需按剩余距离补滚; 内容流式增长时循环校正直到 canScrollForward=false。
     */
    suspend fun scrollToBottom() {
        val total = listState.layoutInfo.totalItemsCount
        if (total == 0) return
        try {
            listState.scrollToItem(total - 1)
            var guard = 0
            while (listState.canScrollForward && guard < 10) {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull() ?: break
                if (last.index != total - 1) {
                    // 末条尚未可见 (布局重排/内容剧增) — 重新定位
                    listState.scrollToItem(total - 1)
                    guard++
                    continue
                }
                // 末条底部超出视口底部的距离 (底部 contentPadding 由 canScrollForward 收口)
                val remaining = last.offset + last.size - listState.layoutInfo.viewportSize.height
                if (remaining <= 0) break
                listState.scroll { scrollBy(remaining.toFloat()) }
                guard++
            }
        } catch (e: CancellationException) {
            throw e // 协程取消正常传播 (组件退出/效果重启)
        } catch (_: Exception) { /* layout not ready, ignore */ }
    }

    // Initial load: scroll to bottom
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(200) // wait for layout
        if (displayedMessages.isNotEmpty()) scrollToBottom()
    }

    // 用户手势: 开始 (非程序滚动) → 冻结跟随; 滚动停止且已在真正底部 → 恢复
    var userLeftBottom by remember { mutableStateOf(false) }
    var autoScrolling by remember { mutableStateOf(false) }

    /** 是否已滚到列表真正的末尾 — canScrollForward=false 即无法再向下滚
     *  (含内容短于视口的全显场景)。 */
    fun atTrueBottom(): Boolean =
        !listState.canScrollForward && listState.layoutInfo.totalItemsCount > 0

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling && !autoScrolling) userLeftBottom = true
                else if (!scrolling && atTrueBottom()) userLeftBottom = false
            }
    }

    // 生成期间: 每 100ms 贴底 (未冻结 / 不在底部 / 无用户手势时)
    LaunchedEffect(isRunning) {
        if (!isRunning) return@LaunchedEffect
        userLeftBottom = false
        while (true) {
            if (!userLeftBottom && !atTrueBottom() && !listState.isScrollInProgress) {
                autoScrolling = true
                try {
                    scrollToBottom()
                } finally {
                    autoScrolling = false
                }
            }
            kotlinx.coroutines.delay(100)
        }
    }
}
