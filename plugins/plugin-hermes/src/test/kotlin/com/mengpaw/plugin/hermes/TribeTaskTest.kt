// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * TribeTask 状态机纯逻辑测试 (插件零测试补齐 — P0 状态机死锁修复回归)。
 *
 * 断言以代码实现为准: canTransitionTo 对非法转换返回 false,
 * withStatus 对非法转换抛 IllegalArgumentException。
 */
class TribeTaskTest {

    private fun task(status: TaskStatus, retryCount: Int = 0, maxRetries: Int = 3) = TribeTask(
        title = "测试任务",
        fromAgent = "agent-a",
        status = status,
        retryCount = retryCount,
        maxRetries = maxRetries
    )

    // ── 合法转换 ───────────────────────────────────────────────

    @Test
    fun `PENDING 可转 ASSIGNED 与 CANCELLED`() {
        assertTrue(TaskStatus.PENDING.canTransitionTo(TaskStatus.ASSIGNED))
        assertTrue(TaskStatus.PENDING.canTransitionTo(TaskStatus.CANCELLED))
    }

    @Test
    fun `ASSIGNED 可直落 RUNNING 及全部终态 (P0 fix 新增转换点)`() {
        for (next in listOf(TaskStatus.RUNNING, TaskStatus.COMPLETED, TaskStatus.FAILED,
                TaskStatus.TIMED_OUT, TaskStatus.CANCELLED)) {
            assertTrue("ASSIGNED → $next 应合法", TaskStatus.ASSIGNED.canTransitionTo(next))
        }
    }

    @Test
    fun `RUNNING 可转 COMPLETED FAILED TIMED_OUT`() {
        for (next in listOf(TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.TIMED_OUT)) {
            assertTrue("RUNNING → $next 应合法", TaskStatus.RUNNING.canTransitionTo(next))
        }
    }

    @Test
    fun `FAILED 与 TIMED_OUT 可重试重派为 ASSIGNED`() {
        assertTrue(TaskStatus.FAILED.canTransitionTo(TaskStatus.ASSIGNED))
        assertTrue(TaskStatus.FAILED.canTransitionTo(TaskStatus.CANCELLED))
        assertTrue(TaskStatus.TIMED_OUT.canTransitionTo(TaskStatus.ASSIGNED))
        assertTrue(TaskStatus.TIMED_OUT.canTransitionTo(TaskStatus.CANCELLED))
    }

    // ── 非法转换 ───────────────────────────────────────────────

    @Test
    fun `非法转换返回 false 不抛异常`() {
        assertFalse(TaskStatus.PENDING.canTransitionTo(TaskStatus.RUNNING))      // 未分配不可直接运行
        assertFalse(TaskStatus.PENDING.canTransitionTo(TaskStatus.COMPLETED))
        assertFalse(TaskStatus.ASSIGNED.canTransitionTo(TaskStatus.PENDING))     // 不许回退
        assertFalse(TaskStatus.RUNNING.canTransitionTo(TaskStatus.CANCELLED))    // 执行中取消走 FAILED/TIMED_OUT
        assertFalse(TaskStatus.RUNNING.canTransitionTo(TaskStatus.ASSIGNED))
        assertFalse(TaskStatus.FAILED.canTransitionTo(TaskStatus.RUNNING))       // 重试 = 重派 ASSIGNED
        assertFalse(TaskStatus.FAILED.canTransitionTo(TaskStatus.FAILED))
        assertFalse(TaskStatus.TIMED_OUT.canTransitionTo(TaskStatus.COMPLETED))
        assertFalse(TaskStatus.COMPLETED.canTransitionTo(TaskStatus.RUNNING))    // 终态不可再变
        assertFalse(TaskStatus.COMPLETED.canTransitionTo(TaskStatus.CANCELLED))
        assertFalse(TaskStatus.CANCELLED.canTransitionTo(TaskStatus.ASSIGNED))   // 终态不可再变
        assertFalse(TaskStatus.CANCELLED.canTransitionTo(TaskStatus.CANCELLED))
    }

    // ── withStatus ─────────────────────────────────────────────

    @Test
    fun `withStatus 合法转换返回副本并更新时间`() {
        val t = task(TaskStatus.PENDING)
        val assigned = t.withStatus(TaskStatus.ASSIGNED)
        assertEquals(TaskStatus.ASSIGNED, assigned.status)
        assertTrue(assigned.updatedAt >= t.updatedAt)
        assertNull(assigned.completedAt)  // 仅 COMPLETED 置完成时间
    }

    @Test
    fun `withStatus 到 COMPLETED 自动设置 completedAt`() {
        val t = task(TaskStatus.RUNNING)
        val done = t.withStatus(TaskStatus.COMPLETED)
        assertEquals(TaskStatus.COMPLETED, done.status)
        assertTrue(done.completedAt != null)
    }

    @Test
    fun `withStatus 非法转换抛 IllegalArgumentException`() {
        val t = task(TaskStatus.COMPLETED)
        try {
            t.withStatus(TaskStatus.RUNNING)
            fail("COMPLETED → RUNNING 应抛异常")
        } catch (e: IllegalArgumentException) {
            // 预期: 消息带状态描述
            assertTrue(e.message!!.contains("COMPLETED"))
        }
    }

    // ── 重试语义 ───────────────────────────────────────────────

    @Test
    fun `canRetry 仅限 FAILED TIMED_OUT 且未超上限`() {
        assertTrue(task(TaskStatus.FAILED).canRetry())
        assertTrue(task(TaskStatus.TIMED_OUT, retryCount = 2, maxRetries = 3).canRetry())
        assertFalse(task(TaskStatus.FAILED, retryCount = 3, maxRetries = 3).canRetry())  // 达上限
        assertFalse(task(TaskStatus.COMPLETED).canRetry())
        assertFalse(task(TaskStatus.RUNNING).canRetry())
        assertFalse(task(TaskStatus.PENDING).canRetry())
    }

    @Test
    fun `nextRetryDelayMs 指数退避并封顶`() {
        assertEquals(30_000L, task(TaskStatus.FAILED, retryCount = 0).nextRetryDelayMs())
        assertEquals(60_000L, task(TaskStatus.FAILED, retryCount = 1).nextRetryDelayMs())
        assertEquals(120_000L, task(TaskStatus.FAILED, retryCount = 2).nextRetryDelayMs())
        assertEquals(120_000L, task(TaskStatus.FAILED, retryCount = 9).nextRetryDelayMs())  // 封顶
    }

    // ── 序列化 ─────────────────────────────────────────────────

    @Test
    fun `JSON 序列化往返保真`() {
        val original = task(TaskStatus.RUNNING, retryCount = 1).copy(
            priority = TaskPriority.P0,
            result = "完成",
            parentTaskId = "parent-1",
            depth = 2,
            delegateMode = DelegateMode.ACP
        )
        val roundTrip = TribeTask.fromJson(TribeTask.toJson(original))
        assertEquals(original, roundTrip)
    }
}
