// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.PlanStepStatus
import com.mengpaw.kernel.TaskPlan
import java.util.concurrent.CopyOnWriteArrayList

/** 计划快照 — UI 观察 PlanModeExecutor 的计划与步骤状态。 */
data class PlanSnapshot(
    val active: Boolean,
    val plan: TaskPlan?,
    val agentName: String
)

typealias PlanListener = (PlanSnapshot) -> Unit

/**
 * 计划模式监控 (v0.34.3 /plan UI) — 仿 MissionMonitor 的监听范式。
 *
 * PlanModeExecutor 在计划生成后 [start], 每步状态变更 [updateStep],
 * 计划结束/取消/异常 [stop]。UI (shell) 经 [addListener] 保持响应式 —
 * 竖列显示状态标识, 右侧边栏底部显示完整计划列表。
 *
 * 全局当前计划 (与 MissionMonitor 同级): 新计划覆盖旧计划; 无活跃计划 active=false。
 */
object PlanMonitor {

    @Volatile private var currentPlan: TaskPlan? = null
    @Volatile private var currentAgent: String = ""
    private val listeners = CopyOnWriteArrayList<PlanListener>()

    fun addListener(l: PlanListener) { listeners.add(l) }
    fun removeListener(l: PlanListener) { listeners.remove(l) }

    /** 当前快照 — UI 初始值读取用。 */
    fun currentSnapshot(): PlanSnapshot = synchronized(this) {
        PlanSnapshot(currentPlan != null, currentPlan, currentAgent)
    }

    private fun emit() {
        val snapshot = synchronized(this) {
            PlanSnapshot(currentPlan != null, currentPlan, currentAgent)
        }
        listeners.toList().forEach { it(snapshot) }
    }

    /** 计划生成后发布 — 覆盖旧计划。 */
    fun start(plan: TaskPlan, agentName: String) {
        synchronized(this) { currentPlan = plan; currentAgent = agentName }
        emit()
    }

    /** 步骤状态变更 (PENDING→RUNNING→COMPLETED/FAILED)。 */
    fun updateStep(index: Int, status: PlanStepStatus) {
        synchronized(this) { currentPlan?.steps?.getOrNull(index)?.status = status }
        emit()
    }

    /** 计划结束/取消/异常 — 清除当前计划。 */
    fun stop() {
        synchronized(this) { currentPlan = null; currentAgent = "" }
        emit()
    }
}
