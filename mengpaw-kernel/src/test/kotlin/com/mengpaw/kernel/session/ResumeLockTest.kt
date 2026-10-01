// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ResumeLock] 单会话锁测试。
 *
 * 断言意图: ① 基本互斥 (同锁文件只有一个持有者); ② 释放后可重新获取;
 * ③ TTL 过期后必须可抢 (崩溃残留的幽灵锁不能把用户永久挡住);
 * ④ 并发抢锁恰好一个成功 (防"两个执行体同时续跑同一会话");
 * ⑤ 续租只在临近过期时发生 (长任务不被误判过期而遭抢占 → 防双跑), 非持有者与未到期时零 IO。
 * 全部用可注入时钟, 无墙钟阈值断言。
 */
class ResumeLockTest {

    private val dir: File = createTempDir("resume_lock").apply { deleteOnExit() }

    @Test
    fun `获取与释放的基本语义`() {
        val lock = ResumeLock(dir.absolutePath)
        assertTrue(lock.tryAcquire("sess_1"))
        assertEquals("sess_1", lock.heldSessionId)
        val holder = lock.peek()
        assertNotNull("锁文件必须落盘持有者信息", holder)
        assertEquals("sess_1", holder?.sessionId)

        assertTrue(lock.release())
        assertNull("释放后不得再有持有者", lock.peek())
        assertNull(lock.heldSessionId)
    }

    @Test
    fun `被占用时其他会话拿不到锁`() {
        val first = ResumeLock(dir.absolutePath)
        val second = ResumeLock(dir.absolutePath)
        assertTrue(first.tryAcquire("sess_a"))

        assertFalse(second.tryAcquire("sess_b"))
        assertEquals(ResumeLock.DeniedReason.HELD_BY_OTHER_SESSION, second.lastDeniedReason)
        // 未获取成功的一方不得留下 held 状态 (否则会误 release 别人的锁)
        assertNull(second.heldSessionId)

        first.release()
        assertTrue("释放后应可获取", second.tryAcquire("sess_b"))
    }

    @Test
    fun `同会话重入幂等`() {
        val first = ResumeLock(dir.absolutePath)
        assertTrue(first.tryAcquire("sess_a"))
        // 同一会话的第二个实例 (如 UI 重复触发) 不得互相阻塞
        assertTrue(ResumeLock(dir.absolutePath).tryAcquire("sess_a"))
    }

    @Test
    fun `TTL 过期后锁可被抢占`() {
        var now = 10_000L
        val clock = { now }
        val first = ResumeLock(dir.absolutePath, clock)
        assertTrue(first.tryAcquire("sess_a"))
        assertFalse("未过期时不可抢", first.isExpired())

        now += ResumeLock.DEFAULT_TTL_MS // 恰好到期 (>= ttl 即视为过期)
        assertTrue("到期即应判为过期", first.isExpired())
        assertTrue("过期锁必须可被新执行体抢占", ResumeLock(dir.absolutePath, clock).tryAcquire("sess_b"))
    }

    @Test
    fun `时钟回拨时不误判过期`() {
        var now = 10_000_000L
        val clock = { now }
        val lock = ResumeLock(dir.absolutePath, clock)
        assertTrue(lock.tryAcquire("sess_a"))

        now -= 60_000L // 回拨 1 分钟
        assertFalse("时钟回拨应保守视为未过期 (不可抢占他人锁)", lock.isExpired())
    }

    @Test
    fun `并发抢锁恰好一个成功`() {
        val threads = 4
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val successes = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) { i ->
                pool.execute {
                    try {
                        start.await()
                        // 每个线程独立实例 (模拟多进程/多入口)
                        if (ResumeLock(dir.absolutePath).tryAcquire("sess_$i")) successes.incrementAndGet()
                    } catch (_: Exception) {
                        // 抢锁失败不算测试失败 — 由 successes 计数裁决
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue("并发抢锁应在 5s 内结束", done.await(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals("同一时刻只允许一个执行体持有续跑锁", 1, successes.get())
    }

    @Test
    fun `空 sessionId 直接拒绝`() {
        val lock = ResumeLock(dir.absolutePath)
        assertFalse(lock.tryAcquire(""))
        assertEquals(ResumeLock.DeniedReason.IO_ERROR, lock.lastDeniedReason)
    }

    // ── ③ 续租 (防长任务被误判过期而遭第二次触发抢占 → 双跑) ────────────────

    @Test
    fun `临近过期时续租刷新 acquiredAt 且推后过期时刻`() {
        var now = 10_000L
        val lock = ResumeLock(dir.absolutePath, { now })
        assertTrue(lock.tryAcquire("sess_renew"))
        val t0 = lock.peek()?.acquiredAt

        // 推进到"剩余 < TTL/3" (剩余 2 分钟 < 3.33 分钟) — 此时才该续租
        now += ResumeLock.DEFAULT_TTL_MS - ResumeLock.DEFAULT_TTL_MS / 3 / 2
        assertTrue("临近过期应续租", lock.renewIfDue())
        val renewed = lock.peek()
        assertEquals("续租必须重写 acquiredAt", now, renewed?.acquiredAt)
        assertTrue("续租必须推后过期时刻", (renewed?.acquiredAt ?: 0L) > (t0 ?: 0L))
        assertFalse("续租后不得处于过期态", lock.isExpired())
        // 续租是原地重写: 不产生第二个持有者 (会话标识不变)
        assertEquals("sess_renew", renewed?.sessionId)
        assertTrue(lock.release())
    }

    @Test
    fun `未临近过期时不续租也不产生 IO`() {
        var now = 10_000L
        val lock = ResumeLock(dir.absolutePath, { now })
        assertTrue(lock.tryAcquire("sess_a"))
        val before = lock.peek()?.acquiredAt

        now += 1_000L // 刚取锁即续租判定: 剩余远大于 TTL/3
        assertFalse("未临近过期不得续租", lock.renewIfDue())
        assertEquals("acquiredAt 必须原封不动 (无 IO)", before, lock.peek()?.acquiredAt)
    }

    @Test
    fun `非持有者实例不续租`() {
        var now = 10_000L
        val owner = ResumeLock(dir.absolutePath, { now })
        assertTrue(owner.tryAcquire("sess_owner"))
        val ownerAcquiredAt = owner.peek()?.acquiredAt

        // 另一个实例从未获取过锁 (heldSessionId == null) → 无资格续租, 且不得改动锁文件
        val other = ResumeLock(dir.absolutePath, { now })
        assertFalse("未持有锁的实例不得续租", other.renewIfDue())
        assertEquals("他人锁文件不得被改写", ownerAcquiredAt, other.peek()?.acquiredAt)
    }
}
