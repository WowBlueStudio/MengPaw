// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.mengpaw.design.theme.ThemeColors

/**
 * 主界面侧栏区域 — 从 MainScreen.kt 拆出 (2026-08-04, >40KB UI 文件拆分)。
 * 平板持久侧栏 (左右) + 手机 SidebarOverlay (左右), 纯展示 + 回调。
 */

/** 平板持久左侧栏 — 右滑关闭 (拖拽距离 > 120dp)。 */
@Composable
fun PersistentLeftSidebar(
    show: Boolean,
    isWide: Boolean,
    isRunning: Boolean,
    onDismiss: () -> Unit,
    content: @Composable (close: () -> Unit, isRunning: Boolean) -> Unit
) {
    if (isWide && show) {
        Surface(
            color = ThemeColors.bgPrimary,
            shadowElevation = 8.dp,
            modifier = Modifier.pointerInput(Unit) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (totalDrag < -120f) onDismiss()
                        totalDrag = 0f
                    }
                ) { _, dragAmount ->
                    totalDrag += dragAmount
                }
            }
        ) {
            content(onDismiss, isRunning)
        }
        VerticalDivider(
            color = ThemeColors.border,
            thickness = 0.5.dp
        )
    }
}

/** 平板持久右侧栏 — 左滑关闭 (拖拽距离 > 120dp)。 */
@Composable
fun PersistentRightSidebar(
    show: Boolean,
    isWide: Boolean,
    onDismiss: () -> Unit,
    content: @Composable (close: () -> Unit) -> Unit
) {
    if (isWide && show) {
        VerticalDivider(
            color = ThemeColors.border,
            thickness = 0.5.dp
        )
        Surface(
            color = ThemeColors.bgPrimary,
            shadowElevation = 8.dp,
            modifier = Modifier.pointerInput(Unit) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (totalDrag > 120f) onDismiss()
                        totalDrag = 0f
                    }
                ) { _, dragAmount ->
                    totalDrag += dragAmount
                }
            }
        ) {
            content(onDismiss)
        }
    }
}

/** 手机侧栏 — 悬浮覆盖层 (紧凑布局下替代持久侧栏)。 */
@Composable
fun PhoneSidebarOverlays(
    showLeft: Boolean,
    showRight: Boolean,
    isWide: Boolean,
    isRunning: Boolean,
    onDismissLeft: () -> Unit,
    onDismissRight: () -> Unit,
    leftContent: @Composable (close: () -> Unit, isRunning: Boolean) -> Unit,
    rightContent: @Composable (close: () -> Unit) -> Unit
) {
    if (!isWide) {
        SidebarOverlay(showLeft, fromLeft = true,
            onDismiss = onDismissLeft,
            content = { leftContent(onDismissLeft, isRunning) })
        SidebarOverlay(showRight, fromLeft = false,
            onDismiss = onDismissRight,
            content = { rightContent(onDismissRight) })
    }
}
