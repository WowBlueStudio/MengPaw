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
     * 滚动到列表真正末端 — 跟随流式末尾且避免高频闪烁。
     *
     * 修复 (v0.44): 原实现每 100ms 先 `scrollToItem(total-1)` 把末条**顶部**对齐视口,
     * 再 `scrollBy(remaining)` 且 remaining 是按"末条底部绝对坐标 - 视口高"算的绝对目标、
     * 却用**累加**语义的 scrollBy 应用 → 每次都过滚被 clamp, 视口"上跳(对齐顶部)再下跳(回到底部)"
     * 往复 → 生成期末条(流式答案)较长时高频闪烁。
     * 新实现: 末条尚未可见才 scrollToItem(一次性定位); 正在跟随末条时只按
     * "末条底部超出视口底部"的真实溢出向下滚, 不再顶部对齐, 消除上跳下跳。
     */
    suspend fun scrollToBottom() {
        val total = listState.layoutInfo.totalItemsCount
        if (total == 0) return
        try {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return
            // 末条尚未可见 → 定位到末条 (对齐其顶部, 一次性)
            if (lastVisible.index != total - 1) {
                listState.scrollToItem(total - 1)
                return
            }
            // 正在跟随末条 → 只按真实溢出向下滚 (当前滚动位置 = 首可见项内容偏移 + 首项已滚过像素)
            val first = info.visibleItemsInfo.firstOrNull() ?: return
            val currentScroll = first.offset + listState.firstVisibleItemScrollOffset
            val viewportBottom = currentScroll + info.viewportSize.height
            val lastBottom = lastVisible.offset + lastVisible.size
            val overflow = lastBottom - viewportBottom
            if (overflow > 0) listState.scroll { scrollBy(overflow.toFloat()) }
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
