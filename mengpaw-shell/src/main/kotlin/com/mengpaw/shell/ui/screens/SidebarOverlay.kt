// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput

/** Standalone composable to escape RowScope/ColumnScope for overlay sidebars. */
@Composable
fun SidebarOverlay(
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
                Row(Modifier.fillMaxSize().pointerInput(fromLeft) {
                    var totalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (fromLeft && totalDrag < -100f) onDismiss()
                            else if (!fromLeft && totalDrag > 100f) onDismiss()
                            totalDrag = 0f
                        }
                    ) { _, dragAmount ->
                        totalDrag += dragAmount
                    }
                }) {
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
