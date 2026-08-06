// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.hermes

import com.mengpaw.kernel.DataPaths
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * TribeKanbanBoard 文件持久化看板测试 (插件零测试补齐)。
 *
 * DataPaths.BASE 指向临时目录 — 看板文件落在 temp, 不污染真实数据。
 * 用 runTest 跑 suspend 看板 API。
 */
class TribeKanbanBoardTest {

    private lateinit var tempBase: File
    private lateinit var board: TribeKanbanBoard

    @Before
    fun setUp() {
        tempBase = File(System.getProperty("java.io.tmpdir"), "mengpaw-hermes-test-${System.nanoTime()}")
        DataPaths.initialize(tempBase.absolutePath)
        board = TribeKanbanBoard()
    }

    @After
    fun tearDown() {
        DataPaths.initialize("/sdcard/MengPaw")  // 还原默认值
        tempBase.deleteRecursively()
    }

    private fun newTask(status: TaskStatus = TaskStatus.PENDING, title: String = "任务") = TribeTask(
        title = title,
        fromAgent = "agent-a",
        toAgent = "agent-b",
        status = status,
        createdAt = System.currentTimeMillis() - 100_000,  // 便于归档测试
        updatedAt = System.currentTimeMillis() - 100_000
    )

    @Test
    fun `create 强制 PENDING 并持久化`() = runTest {
        val saved = board.create(newTask(title = "看板任务"))
        assertEquals(TaskStatus.PENDING, saved.status)
        assertNotNull(saved.id)

        val loaded = board.get(saved.id)
        assertNotNull(loaded)
        assertEquals("看板任务", loaded!!.title)
        assertEquals(TaskStatus.PENDING, loaded.status)
    }

    @Test
    fun `create 后 index 与 list 可见`() = runTest {
        val t1 = board.create(newTask(title = "A"))
        val t2 = board.create(newTask(title = "B"))
        val all = board.list()
        assertEquals(setOf("A", "B"), all.map { it.title }.toSet())
        assertEquals(2, board.snapshotStatuses().size)  // 同步快照同样可见
    }

    @Test
    fun `transition 成功路径 PENDING→ASSIGNED→RUNNING→COMPLETED 带结果`() = runTest {
        val t = board.create(newTask())
        board.transition(t.id, TaskStatus.ASSIGNED)
        board.transition(t.id, TaskStatus.RUNNING)
        val done = board.transition(t.id, TaskStatus.COMPLETED, result = "全部完成")

        assertEquals(TaskStatus.COMPLETED, done.status)
        assertEquals("全部完成", done.result)
        assertNotNull(done.completedAt)
        // 持久化后读取一致
        assertEquals(TaskStatus.COMPLETED, board.get(t.id)!!.status)
    }

    @Test
    fun `transition 未知任务抛 NoSuchElementException`() = runTest {
        try {
            board.transition("no-such-id", TaskStatus.ASSIGNED)
            fail("未知任务应抛 NoSuchElementException")
        } catch (e: NoSuchElementException) {
            assertTrue(e.message!!.contains("no-such-id"))
        }
    }

    @Test
    fun `transition 非法状态转换抛 IllegalArgumentException`() = runTest {
        val t = board.create(newTask())
        try {
            board.transition(t.id, TaskStatus.RUNNING)  // PENDING → RUNNING 非法
            fail("非法转换应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Illegal transition"))
        }
        // 状态未被破坏
        assertEquals(TaskStatus.PENDING, board.get(t.id)!!.status)
    }

    @Test
    fun `cancel 仅限 PENDING ASSIGNED RUNNING 之外的状态拒绝`() = runTest {
        val pending = board.create(newTask())
        val cancelled = board.cancel(pending.id)
        assertEquals(TaskStatus.CANCELLED, cancelled.status)

        // COMPLETED 任务不可取消 (实现上 RUNNING 也不可 — 见状态机)
        val done = board.create(newTask())
        board.transition(done.id, TaskStatus.ASSIGNED)
        board.transition(done.id, TaskStatus.COMPLETED)
        try {
            board.cancel(done.id)
            fail("COMPLETED 任务不可取消")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
    }

    @Test
    fun `retry 将 FAILED 重派为 ASSIGNED 并递增计数`() = runTest {
        val t = board.create(newTask())
        board.transition(t.id, TaskStatus.ASSIGNED)
        board.transition(t.id, TaskStatus.FAILED, error = "执行失败")

        val retried = board.retry(t.id)
        assertEquals(TaskStatus.ASSIGNED, retried.status)
        assertEquals(1, retried.retryCount)
        assertNull(retried.errorMessage)

        // 再次失败后仍可重试
        board.transition(t.id, TaskStatus.RUNNING)
        board.transition(t.id, TaskStatus.FAILED)
        assertEquals(2, board.retry(t.id).retryCount)
    }

    @Test
    fun `retry 不可重试任务抛异常`() = runTest {
        val t = board.create(newTask(status = TaskStatus.COMPLETED))
        try {
            board.retry(t.id)
            fail("COMPLETED 任务不可重试")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("cannot be retried"))
        }
    }

    @Test
    fun `archive 归档旧终态任务且 list includeArchived 可见`() = runTest {
        val done = board.create(newTask())
        board.transition(done.id, TaskStatus.ASSIGNED)
        board.transition(done.id, TaskStatus.COMPLETED)

        val count = board.archive(olderThanMs = 0)  // 全部视为过期
        assertEquals(1, count)
        assertTrue(board.list().isEmpty())                              // 活跃列表已清空
        assertEquals(1, board.list(includeArchived = true).size)        // 含归档可见
        assertNotNull(board.get(done.id))                               // get 可跨归档读取
    }

    @Test
    fun `recoverInFlight 将 RUNNING 转 FAILED 并保留 ASSIGNED`() = runTest {
        val running = board.create(newTask())
        board.transition(running.id, TaskStatus.ASSIGNED)
        board.transition(running.id, TaskStatus.RUNNING)
        val assigned = board.create(newTask())
        board.transition(assigned.id, TaskStatus.ASSIGNED)

        val recovered = board.recoverInFlight()
        assertEquals(2, recovered.size)
        val r = recovered.first { it.id == running.id }
        assertEquals(TaskStatus.FAILED, r.status)
        assertTrue(r.errorMessage!!.contains("in-flight"))
        val a = recovered.first { it.id == assigned.id }
        assertEquals(TaskStatus.ASSIGNED, a.status)  // ASSIGNED 保持原状
    }

    @Test
    fun `snapshotStatuses 返回全部任务的轻量状态`() = runTest {
        val t1 = board.create(newTask(title = "快照1"))
        board.transition(t1.id, TaskStatus.ASSIGNED)
        board.create(newTask(title = "快照2"))

        val snaps = board.snapshotStatuses()
        assertEquals(2, snaps.size)
        assertEquals(TaskStatus.ASSIGNED, snaps.first { it.toAgent == "agent-b" && it.status == TaskStatus.ASSIGNED }.status)
    }
}
