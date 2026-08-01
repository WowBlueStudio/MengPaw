// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import java.util.concurrent.atomic.AtomicInteger

/**
 * 火种模式（Swarm Mode）类型定义。
 *
 * 释义: 星星之火，可以燎原——一个任务点燃众多 Worker 的燎原之势。
 * 编排: 规划器拆解 → 并行 Worker（可混合不同模型）→ Verifier 验证 → 合成器输出。
 * 机制: JIT 看板三闸门（总预算/WIP 并行/单任务）+ Andon 失败协议 + 零待命 Worker。
 */
enum class SwarmSubtaskStatus { PENDING, RUNNING, VERIFIED, FAILED, SKIPPED }

/** 拆解出的子任务。role 指定执行角色（roles map 的键，缺省 "worker"）。 */
data class SwarmSubtask(
    val id: String,
    val description: String,
    val expectedOutcome: String = "",
    val role: String = "worker",
    var status: SwarmSubtaskStatus = SwarmSubtaskStatus.PENDING,
    var output: String = "",
    var verifierNote: String = "",
    var stepsUsed: Int = 0,
    var retryCount: Int = 0
)

/**
 * 结构化结果卡片 — 协调器只收卡片，不收 Worker 日志（JIT 看板卡片）。
 * 每张卡是 Worker 生命周期唯一的对外产物，零待命 Worker 无跨任务记忆。
 */
data class SwarmResultCard(
    val subtaskId: String,
    val status: SwarmSubtaskStatus,
    val summary: String,
    val tokensUsed: Long,
    val stepsUsed: Int,
    val verifierNote: String = ""
) {
    val icon: String get() = when (status) {
        SwarmSubtaskStatus.VERIFIED -> "✅"
        SwarmSubtaskStatus.FAILED -> "❌"
        SwarmSubtaskStatus.SKIPPED -> "⏭️"
        else -> "⬜"
    }

    companion object {
        fun skipped(id: String) =
            SwarmResultCard(id, SwarmSubtaskStatus.SKIPPED, "预算耗尽，未执行", 0L, 0, "budget_exhausted")
    }
}

/**
 * 看板总预算闸 — 累计实际 Worker 步数（每轮 LLM turn 计 1 步）。
 * AtomicInteger CAS 保证并行 Worker 间无锁安全。
 */
class SwarmBudget(private val maxTotalSteps: Int) {
    private val consumed = AtomicInteger(0)

    val consumedSteps: Int get() = consumed.get()
    val exhausted: Boolean get() = consumed.get() >= maxTotalSteps

    /** 授予一个步进配额；总预算耗尽返回 false。 */
    fun tryConsume(): Boolean {
        while (true) {
            val cur = consumed.get()
            if (cur >= maxTotalSteps) return false
            if (consumed.compareAndSet(cur, cur + 1)) return true
        }
    }
}
