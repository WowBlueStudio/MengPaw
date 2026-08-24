// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * GoalSessionStore 持久化回归 (P2-4): 序列化往返 + 损坏文件容错 + clear。
 */
class GoalSessionStoreTest {

    private val tmp = File(System.getProperty("java.io.tmpdir"), "goal-store-test")
    private val file = File(tmp, "goal.json")

    @Before
    fun setUp() {
        tmp.mkdirs()
    }

    @After
    fun cleanup() {
        // 尽力清理测试目录
        try { tmp.deleteRecursively() } catch (_: Exception) {}
    }

    @Test
    fun `save then load round-trips all fields`() {
        val session = GoalSession(
            goal = "完成报告",
            active = true,
            iteration = 3,
            maxIterations = 20,
            maxTokens = 300_000,
            tokensUsed = 1234,
            lastVerdict = "NEEDS_REVISION",
            lastFeedback = "还差数据"
        )
        GoalSessionStore.save(session, file)
        val loaded = GoalSessionStore.load(file)
        assertEquals("goal 应往返一致", "完成报告", loaded?.goal)
        assertEquals("iteration 应往返一致", 3, loaded?.iteration)
        assertEquals("tokensUsed 应往返一致", 1234, loaded?.tokensUsed)
        assertEquals("lastVerdict 应往返一致", "NEEDS_REVISION", loaded?.lastVerdict)
        assertEquals("lastFeedback 应往返一致", "还差数据", loaded?.lastFeedback)
        assertEquals("maxIterations 应往返一致", 20, loaded?.maxIterations)
    }

    @Test
    fun `load missing or corrupt file returns null`() {
        assertNull("不存在文件应返回 null", GoalSessionStore.load(File(tmp, "nope.json")))
        file.writeText("{ invalid json }")
        assertNull("损坏文件应返回 null", GoalSessionStore.load(file))
    }

    @Test
    fun `clear removes the persisted file`() {
        GoalSessionStore.save(GoalSession(goal = "x"), file)
        GoalSessionStore.clear(file)
        assertNull("clear 后不应再能加载", GoalSessionStore.load(file))
    }
}
