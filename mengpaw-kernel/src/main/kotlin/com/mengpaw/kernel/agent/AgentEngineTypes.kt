// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel

/**
 * Represents the current state of the AgentEngine.
 */
sealed class AgentState {
    data object Idle : AgentState()
    data class Running(val task: String, val step: Int, val maxSteps: Int) : AgentState()
    data class Finished(val result: String) : AgentState()
    data class Error(val message: String) : AgentState()
}

/**
 * A single step in a structured execution plan.
 */
data class PlanStep(
    val index: Int,
    val description: String,
    val action: String,
    val expectedOutcome: String,
    var status: PlanStepStatus = PlanStepStatus.PENDING
)

enum class PlanStepStatus { PENDING, RUNNING, COMPLETED, FAILED }

/**
 * A complete execution plan composed of [PlanStep]s.
 */
data class TaskPlan(
    val task: String,
    val steps: List<PlanStep>,
    val createdAt: Long = System.currentTimeMillis()
) {
    val totalSteps: Int get() = steps.size
    val completedSteps: Int get() = steps.count { it.status == PlanStepStatus.COMPLETED }
    val isComplete: Boolean get() = steps.all { it.status == PlanStepStatus.COMPLETED }
}
