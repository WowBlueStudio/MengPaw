// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.hermes

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Tribe 消息优先级 — Hermes Agent P0/P1/P2 语义对齐。
 *
 * P0 (强制): 必须执行，必须回复，自动重试直到成功或 maxRetries 耗尽
 * P1 (期望): 期望回复，非阻塞，单次重试
 * P2 (尽力): Fire-and-forget，无跟踪，无结果期望
 */
enum class TaskPriority(val level: Int, val label: String) {
    P0(0, "强制 Mandatory"),
    P1(1, "期望 Expected"),
    P2(2, "尽力 Best-effort")
}

/**
 * Kanban 任务状态，带有效转换校验。
 *
 * 状态图:
 *   PENDING ──→ ASSIGNED ──→ RUNNING ──→ COMPLETED
 *       │            │            │
 *       │            │       ┌────┴────┐
 *       │            │       │         │
 *       │            │       v         v
 *       │            │    FAILED   TIMED_OUT
 *       │            │       │         │
 *       │            │       └────┬────┘
 *       │            │            │
 *       │            │   (retryCount < maxRetries) ────┘
 *       v            v
 *   CANCELLED    CANCELLED
 */
enum class TaskStatus {
    PENDING,        // 已创建，未分配
    ASSIGNED,       // 已分配到目标 Agent
    RUNNING,        // Agent 已接受并执行中
    COMPLETED,      // 成功完成
    FAILED,         // 执行失败（可重试）
    TIMED_OUT,      // 超时无响应（可重试）
    CANCELLED;      // 被发起者取消

    /** 有效状态转换 — 非法转换抛出 IllegalArgumentException。 */
    fun canTransitionTo(next: TaskStatus): Boolean = when (this) {
        PENDING -> next in setOf(ASSIGNED, CANCELLED)
        ASSIGNED -> next in setOf(RUNNING, CANCELLED)
        RUNNING -> next in setOf(COMPLETED, FAILED, TIMED_OUT)
        FAILED -> next in setOf(ASSIGNED, CANCELLED)    // retry = reassign
        TIMED_OUT -> next in setOf(ASSIGNED, CANCELLED) // retry = reassign
        COMPLETED, CANCELLED -> false
    }
}

/** 委派模式。 */
enum class DelegateMode(val label: String) {
    FILE("文件系统"),
    ACP("ACP 实时"),
    AUTO("自动选择")
}

/**
 * 单个部落任务 — 以 JSON 持久化在 kanban/ 目录。
 * 预留了嵌套委派字段（parentTaskId / depth）。
 */
@Serializable
data class TribeTask(
    val id: String = UUID.randomUUID().toString().take(8),
    val title: String,
    val description: String = "",
    val priority: TaskPriority = TaskPriority.P1,
    val status: TaskStatus = TaskStatus.PENDING,
    val fromAgent: String,
    val toAgent: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val result: String? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val timeoutMs: Long = 120_000L,
    val parentTaskId: String? = null,
    val depth: Int = 0,
    val delegateMode: DelegateMode = DelegateMode.AUTO
) {
    /** 是否还可以重试。 */
    fun canRetry(): Boolean =
        status in setOf(TaskStatus.FAILED, TaskStatus.TIMED_OUT) &&
                retryCount < maxRetries

    /** 指数退避延迟: 30s → 60s → 120s */
    fun nextRetryDelayMs(): Long = listOf(30_000L, 60_000L, 120_000L)
        .getOrElse(retryCount) { 120_000L }

    /** 返回状态更新后的副本（自动校验合法性）。 */
    fun withStatus(newStatus: TaskStatus): TribeTask {
        require(status.canTransitionTo(newStatus)) {
            "Illegal transition: $status → $newStatus (task $id)"
        }
        return copy(
            status = newStatus,
            updatedAt = System.currentTimeMillis(),
            completedAt = if (newStatus == TaskStatus.COMPLETED) System.currentTimeMillis() else completedAt
        )
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

        fun fromJson(text: String): TribeTask = json.decodeFromString(serializer(), text)
        fun toJson(task: TribeTask): String = json.encodeToString(serializer(), task)
    }
}
