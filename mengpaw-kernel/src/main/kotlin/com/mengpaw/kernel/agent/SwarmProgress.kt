// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import kotlinx.serialization.Serializable

/**
 * 火种模式 (SWARM) 看板级续跑进度 — 工作流 C 新增。
 *
 * ── 为什么不复用 [GoalSession] ────────────────────────────────────────
 * GOAL/Ralph 的进度是**单条扁平状态** (轮次 + 预算 + 裁决), 塞进 [GoalSession] 天然合适;
 * 而 SWARM 的进度是**看板** — 每个 worker 各自的 id/状态/结论摘要/步数, 且并行 worker
 * 会并发快照。硬塞进 [GoalSession] 只能加 `worker1Status` / `worker2Status` 这类平行字段,
 * 或者再挂一个嵌套列表 — 前者会随子任务数膨胀, 后者让 `maxIterations`/`round` 这些
 * GOAL 专属字段在 SWARM 语义下变成幽灵字段。故本类型独立成档。
 *
 * **仍然只有一个真相**: 落盘目录与 GOAL/Ralph 完全同源
 * (`{DataPaths.CONFIG}/模式进度/{agent}/swarm.json`, 见 [SwarmProgressStore] 与
 * [GoalSessionStore.progressDir]), 并且本类型**不与 [GoalSession] 重叠任何字段语义** —
 * 预算只有 [consumedSteps] 一份, 轮次只有 GOAL/Ralph 一份。两者不会写出互相矛盾的记录。
 *
 * ── worker 级续跑粒度定案 (工具副作用不可回滚) ────────────────────────
 * | 中断时 worker 状态 | 恢复动作 | 理由 |
 * |---|---|---|
 * | `VERIFIED` / `DONE` (已出结论卡片) | **跳过, 复用原卡片** | 该 worker 的工具调用已完成且已有结论; 重跑会重复产生副作用 (写文件/发消息/下单), 且浪费预算 |
 * | `RUNNING` (执行到一半被杀) | **整只重跑该 worker** | 工具副作用不可回滚: 半途状态 (已写一半的文件、已发一半的消息) 无法增量补齐, 只能整只重来。卡片尚未产出, 重跑不产生重复"结论" |
 * | `FAILED` | **不自动重跑, 保留失败卡片** | 失败已是终态结论 (Andon 决策过终止); 自动重跑会在恢复瞬间重复烧预算, 违反"不过预算闸" |
 * | `SKIPPED` | **不重跑** | 预算耗尽跳过是预算闸的结果, 重跑即绕过闸门 |
 * | `PENDING` (规划完成但未拿到许可) | **正常执行** | 无任何副作用, 等同首次执行 |
 *
 * 判定集中在纯函数 [SwarmProgress.needsExecution] / [SwarmProgress.completedCardOf], 可单测。
 * 副作用边界之外的部分 (规划器拆解结果) 由本档持久化, 恢复时不再重新拆解 →
 * 子任务标识 (worker 标识) 稳定, 看板不会因重拆而错位。
 */
@Serializable
data class SwarmProgress(
    /** 原始任务描述 (带注入防护后的形态) — 与恢复请求比对, 不一致则不续跑。 */
    val task: String,
    /** 总步数预算 (看板闸1 上限)。 */
    val totalSteps: Int = 0,
    /** 已消耗步数 — 恢复后剩余 = [totalSteps] − [consumedSteps]。 */
    var consumedSteps: Int = 0,
    /** 子任务 (worker) 标识与看板状态, 顺序即规划器拆解顺序。 */
    var subtasks: List<SubtaskState> = emptyList(),
    /** 最近一次更新时间 (epoch millis)。 */
    var updatedAt: Long = 0L
) {
    /** 剩余步数预算 (派生, 不落盘, 避免两个真相)。 */
    val stepsRemaining: Int get() = (totalSteps - consumedSteps).coerceAtLeast(0)

    /** 按 worker 标识取上次看板状态 (无记录 → null, 视为从未执行)。 */
    fun stateOf(subtaskId: String): SubtaskState? = subtasks.firstOrNull { it.id == subtaskId }

    /** 指定状态的 worker 数量 (续跑报告的累计统计用)。 */
    fun countByStatus(status: String): Int = subtasks.count { it.status == status }

    @Serializable
    data class SubtaskState(
        val id: String,
        val description: String,
        val role: String = "worker",
        val status: String = PENDING,
        /** 结论摘要 (卡片 summary, 已截断) — 恢复后直接复用。 */
        val summary: String = "",
        /** 该 worker 实际消耗步数 (看板展示用)。 */
        var stepsUsed: Int = 0,
        /** 已发生的 Andon 重派次数。 */
        var retries: Int = 0,
        /** Verifier 注记 (卡片 verifierNote)。 */
        val verifierNote: String = ""
    )

    companion object {
        const val PENDING = "PENDING"
        const val RUNNING = "RUNNING"
        const val VERIFIED = "VERIFIED"
        const val DONE = "DONE"
        const val FAILED = "FAILED"
        const val SKIPPED = "SKIPPED"

        /** 已出结论卡片、恢复后必须跳过 (不重复执行) 的状态集合。 */
        val COMPLETED_STATUSES: Set<String> = setOf(VERIFIED, DONE)

        /** 终态 (无论成败都不会再执行) 的状态集合。 */
        val TERMINAL_STATUSES: Set<String> = setOf(VERIFIED, DONE, FAILED, SKIPPED)

        /**
         * 该子任务在恢复后是否需要执行。
         * 只有"从未拿到结论"的 PENDING/RUNNING 需要重做 — 见类 KDoc 的粒度表。
         */
        fun needsExecution(state: SubtaskState?): Boolean =
            state == null || state.status !in TERMINAL_STATUSES

        /** 已完成子任务的结论卡片 (恢复后直接复用, 不再调用 worker); 未完成返回 null。 */
        fun completedCardOf(state: SubtaskState?): SwarmResultCard? = when (state?.status) {
            VERIFIED -> SwarmResultCard(state.id, SwarmSubtaskStatus.VERIFIED, state.summary, 0L, state.stepsUsed, state.verifierNote)
            DONE -> SwarmResultCard(state.id, SwarmSubtaskStatus.DONE, state.summary, 0L, state.stepsUsed, state.verifierNote)
            FAILED -> SwarmResultCard(state.id, SwarmSubtaskStatus.FAILED, state.summary, 0L, state.stepsUsed, state.verifierNote)
            SKIPPED -> SwarmResultCard(state.id, SwarmSubtaskStatus.SKIPPED, state.summary, 0L, state.stepsUsed, state.verifierNote)
            else -> null
        }
    }
}
