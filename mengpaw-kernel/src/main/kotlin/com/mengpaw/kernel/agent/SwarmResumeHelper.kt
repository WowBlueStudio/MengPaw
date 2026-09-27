// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

/**
 * 火种模式断点续跑支撑 (工作流 C) — 从 [SwarmModeExecutor] 拆出的进度档逻辑
 * (400 行文件约束), 与执行器同包, 无额外状态。
 *
 * 设计要点:
 * - 预算连续: [budgetFrom] 用存档的"总额 + 已消耗"预热 [SwarmBudget], 故恢复后
 *   剩余 = 总额 − 已消耗 (不是总额);
 * - 续跑粒度: [planResume] 是纯函数, 已完成 worker 复用卡片、半途 worker 重做,
 *   判定依据全在 [SwarmProgress] 的粒度表 (工具副作用不可回滚);
 * - 无第二状态源: 只读写 [GoalSessionStore.progressDir] 下与 GOAL/Ralph 同级的 `swarm.json`。
 */
internal object SwarmResumeHelper {

    /** 续跑决策结果 — 待执行子任务 + 直接复用的已完成卡片。 */
    data class ResumePlan(
        val pending: List<SwarmSubtask>,
        val restored: List<SwarmResultCard>
    )

    /**
     * 读取看板进度: 仅在 [resume] 为 true 时读盘 (默认 false → 不续跑, 保证既有调用点行为不变)。
     * 显式文件优先, 否则按 agentName 归位; 无/损坏 → null。
     */
    fun load(progressFile: java.io.File?, agentName: String, resume: Boolean): SwarmProgress? {
        if (!resume) return null
        return progressFile?.let { SwarmProgressStore.loadFrom(it) }
            ?: SwarmProgressStore.loadForResume(agentName)
    }

    /** 恢复预算: 续跑时沿用存档上限与已消耗步数, 否则全新预算。 */
    fun budgetFrom(progress: SwarmProgress?, maxTotalSteps: Int): SwarmBudget =
        if (progress == null) SwarmBudget(maxTotalSteps)
        else SwarmBudget.restore(progress.totalSteps.coerceAtLeast(1), progress.consumedSteps)

    /**
     * 纯函数续跑决策 (可单测): 已完成 (VERIFIED/DONE, 或不再重试的 FAILED/SKIPPED) 的 worker
     * 直接复用卡片并从待执行列表移除; 半途 (RUNNING) 与从未执行的 (PENDING) 进入待执行。
     */
    fun planResume(subtasks: List<SwarmSubtask>, progress: SwarmProgress?): ResumePlan {
        val restored = subtasks.mapNotNull { SwarmProgress.completedCardOf(progress?.stateOf(it.id)) }
        val pending = subtasks.filter { SwarmProgress.needsExecution(progress?.stateOf(it.id)) }
        return ResumePlan(pending, restored)
    }

    /** 组装看板进度快照 — 状态以已完成卡片优先, 未完成回落到子任务自身状态。 */
    fun build(
        task: String,
        budget: SwarmBudget,
        subtasks: List<SwarmSubtask>,
        cards: List<SwarmResultCard>
    ): SwarmProgress = SwarmProgress(
        task = task,
        totalSteps = budget.maxSteps,
        consumedSteps = budget.consumedSteps,
        subtasks = subtasks.map { sub ->
            val card = cards.firstOrNull { it.subtaskId == sub.id }
            SwarmProgress.SubtaskState(
                id = sub.id,
                description = sub.description,
                role = sub.role,
                status = card?.status?.name ?: sub.status.name,
                summary = (card?.summary ?: sub.output).take(300),
                stepsUsed = card?.stepsUsed ?: sub.stepsUsed,
                retries = sub.retryCount,
                verifierNote = card?.verifierNote ?: sub.verifierNote
            )
        },
        updatedAt = System.currentTimeMillis()
    )

    /** 终态清理看板进度档 (任务已合成输出, 不应再被续跑)。 */
    fun clear(progressFile: java.io.File?) {
        try { progressFile?.let { SwarmProgressStore.clearFile(it) } } catch (_: Exception) {}
    }
}
