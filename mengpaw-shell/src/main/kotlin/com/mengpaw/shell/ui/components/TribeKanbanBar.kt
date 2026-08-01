// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.plugin.hermes.TaskStatus
import com.mengpaw.plugin.hermes.TribeKanbanBoard

/**
 * 部落看板竖条状态 — 聚合规则（枚举顺序即优先级）:
 * 红(错误) > 黄闪烁(执行中) > 黄(排队) > 绿(完成/无任务)
 */
enum class TribeBarState { GREEN, YELLOW, YELLOW_BLINK, RED }

/**
 * 聚合一个框架下所有 Agent 的任务状态为竖条颜色。
 * @param agentNames 框架托管的 Agent 名集合
 * @param tasks Kanban 任务快照
 */
fun aggregateTribeBarState(agentNames: Set<String>, tasks: List<TribeKanbanBoard.KanbanTaskLite>): TribeBarState {
    var state = TribeBarState.GREEN
    for (t in tasks) {
        if (t.toAgent !in agentNames) continue
        state = when {
            t.status == TaskStatus.FAILED || t.status == TaskStatus.TIMED_OUT -> TribeBarState.RED
            t.status == TaskStatus.RUNNING && state < TribeBarState.YELLOW_BLINK -> TribeBarState.YELLOW_BLINK
            (t.status == TaskStatus.PENDING || t.status == TaskStatus.ASSIGNED) && state < TribeBarState.YELLOW -> TribeBarState.YELLOW
            else -> state
        }
    }
    return state
}

/**
 * 框架通讯录条目右侧的竖条指示器。
 * - 绿: 全部完成或无任务
 * - 黄: 有排队任务
 * - 黄闪烁: 有执行中任务（600ms 透明度往复动画）
 * - 红: 有失败/超时任务
 */
@Composable
fun KanbanStatusBar(state: TribeBarState) {
    val color: Color = when (state) {
        TribeBarState.GREEN -> ArcoColors.Green6
        TribeBarState.YELLOW, TribeBarState.YELLOW_BLINK -> ArcoColors.Orange6
        TribeBarState.RED -> ArcoColors.Red6
    }
    val alpha = if (state == TribeBarState.YELLOW_BLINK) blinkAlpha() else 1f
    Box(
        Modifier
            .width(3.dp)
            .height(14.dp)
            .alpha(alpha)
            .background(color, RoundedCornerShape(1.5.dp))
    )
}

/** 黄闪烁透明度动画：0.25 ↔ 1.0，600ms 往复。仅 YELLOW_BLINK 分支调用。 */
@Composable
private fun blinkAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "tribeBlink")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "tribeBlinkAlpha"
    )
    return alpha
}
