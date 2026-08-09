// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.design.tokens.ArcoSpacing
import com.mengpaw.kernel.PlanStepStatus
import com.mengpaw.kernel.agent.PlanListener
import com.mengpaw.kernel.agent.PlanMonitor

/**
 * /plan 模式 UI (v0.34.3) — 区别于其他框架的普通计划文本:
 * - PlanStatusRail: Chat 消息区右侧纵向状态标识 (仅标识, 12dp 宽不挤消息)
 * - PlanListSection: 右侧边栏底部完整计划列表 (状态标识动画在列表项左侧)
 * - 状态语义: 灰空心圈=未处理 / 粉呼吸=正在处理 / 蓝点=已完成 / 红叉=失败
 */

/** 计划步骤状态标识 — 竖列与列表共用。 */
@Composable
fun PlanStatusDot(status: PlanStepStatus, size: Dp = 10.dp) {
    val breathing = rememberInfiniteTransition(label = "planBreath")
    val alpha by breathing.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "planAlpha"
    )
    when (status) {
        PlanStepStatus.PENDING -> Box(Modifier.size(size).border(2.dp, ArcoColors.Gray5, CircleShape))
        PlanStepStatus.RUNNING -> Box(Modifier.size(size).background(ArcoColors.ChartPink.copy(alpha = alpha), CircleShape))
        PlanStepStatus.COMPLETED -> Box(Modifier.size(size).background(ArcoColors.Blue6, CircleShape))
        PlanStepStatus.FAILED -> Icon(Icons.Outlined.Close, null,
            tint = ArcoColors.Red6, modifier = Modifier.size(size))
    }
}

/** 观察 PlanMonitor 快照的通用 hook。 */
@Composable
private fun planSnapshotState(): State<com.mengpaw.kernel.agent.PlanSnapshot> {
    val state = remember { mutableStateOf(PlanMonitor.currentSnapshot()) }
    DisposableEffect(Unit) {
        val listener: PlanListener = { state.value = it }
        PlanMonitor.addListener(listener)
        onDispose { PlanMonitor.removeListener(listener) }
    }
    return state
}

/** Chat 消息区右侧纵向状态标识 (隐藏侧边栏时可见, 点击打开右侧边栏)。 */
@Composable
fun PlanStatusRail(onOpen: () -> Unit) {
    val snapshot by planSnapshotState()
    if (!snapshot.active) return
    val steps = snapshot.plan?.steps.orEmpty()
    if (steps.isEmpty()) return
    Column(
        Modifier.clickable(onClick = onOpen).padding(horizontal = 2.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        steps.forEach { step -> PlanStatusDot(step.status, 10.dp) }
    }
}

/** 右侧边栏底部完整计划列表 — 状态标识在列表项左侧, 当前步骤高亮。 */
@Composable
fun PlanListSection() {
    val snapshot by planSnapshotState()
    if (!snapshot.active) return
    val plan = snapshot.plan ?: return
    Column(Modifier.fillMaxWidth().padding(ArcoSpacing.lg)) {
        Text("计划模式 · ${plan.completedSteps}/${plan.totalSteps} 步",
            fontWeight = FontWeight.Bold, style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
            color = ThemeColors.brand)
        Spacer(Modifier.height(ArcoSpacing.sm))
        plan.steps.forEach { step ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlanStatusDot(step.status, 10.dp)
                Spacer(Modifier.width(ArcoSpacing.sm))
                Text(
                    "${step.index + 1}. ${step.description}",
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    fontWeight = if (step.status == PlanStepStatus.RUNNING) FontWeight.Bold else FontWeight.Normal,
                    color = when (step.status) {
                        PlanStepStatus.RUNNING -> ThemeColors.brand
                        PlanStepStatus.FAILED -> ArcoColors.Red6
                        else -> ThemeColors.textPrimary
                    }
                )
            }
        }
    }
}
