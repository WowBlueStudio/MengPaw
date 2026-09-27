// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import com.mengpaw.harness.CheckpointStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 检查点管理器基础行为测试 (写入 / 读取 / 清理 / 会话隔离)。
 *
 * 升级说明 (工作流 D): 档名改为 `{sessionId 消毒后}__step_{step}.json` — 本类的用例
 * 不依赖具体档名, 故仅补一条档名格式断言 (新格式一旦回退成裸拼, 这里先报警)。
 * 状态 / 消息序列 / 截断 / 指标 / 前缀歧义见 CheckpointStoreUpgradeTest。
 */
class CheckpointManagerTest {

    @Test
    fun `checkpoint file name uses double underscore separator`() = runBlocking {
        val dir = createTempDir("checkpoint_name").apply { deleteOnExit() }
        val manager = CheckpointManager(dir.absolutePath)
        manager.save(Checkpoint("n1", 4, "命名", emptyMap()))

        val names = dir.list()?.toList() ?: emptyList()
        assertTrue("档名应为 {id}__step_{n}.json, 实际: $names", names.contains("n1__step_4.json"))
    }

    @Test
    fun `save and load checkpoint roundtrip`() = runBlocking {
        val dir = createTempDir("checkpoint_test").apply { deleteOnExit() }
        val manager = CheckpointManager(dir.absolutePath)
        val sessionId = "test_session"

        val cp = Checkpoint(sessionId = sessionId, step = 5, remainingTask = "remaining task", context = mapOf("key" to "value"))
        manager.save(cp)
        val loaded = manager.loadLatest(sessionId)

        assertNotNull(loaded)
        assertEquals(sessionId, loaded?.sessionId)
        assertEquals(5, loaded?.step)
        assertEquals("remaining task", loaded?.remainingTask)
        assertEquals("value", loaded?.context?.get("key"))
    }

    @Test
    fun `checkpoint status defaults to RUNNING and survives roundtrip`() = runBlocking {
        val dir = createTempDir("checkpoint_status").apply { deleteOnExit() }
        val manager = CheckpointManager(dir.absolutePath)

        // updatedAt 显式给值: 连写两次常落在同一毫秒, 若靠墙钟排序会让本用例在负载下随机变红
        // (文件系统 mtime 精度不稳) —— 排序主判据必须是确定性的, 不是"写得多快"。
        manager.save(Checkpoint("st1", 1, "默认态", emptyMap(), updatedAt = 1_000L))
        assertEquals(CheckpointStatus.RUNNING, manager.loadLatest("st1")?.status)

        manager.save(
            Checkpoint(
                "st1", 2, "终态", emptyMap(),
                status = CheckpointStatus.COMPLETED, terminationReason = "final_answer",
                updatedAt = 2_000L
            )
        )
        val terminal = manager.loadLatest("st1")
        assertEquals(CheckpointStatus.COMPLETED, terminal?.status)
        assertEquals("final_answer", terminal?.terminationReason)
    }

    @Test
    fun `loadLatest returns null when no checkpoints`() = runBlocking {
        val dir = createTempDir("checkpoint_empty").apply { deleteOnExit() }
        val manager = CheckpointManager(dir.absolutePath)
        assertNull(manager.loadLatest("nonexistent"))
    }

    @Test
    fun `loadLatest returns most recent checkpoint`() = runBlocking {
        val dir = createTempDir("checkpoint_multi").apply { deleteOnExit() }
        val manager = CheckpointManager(dir.absolutePath)

        // 同上: 显式 updatedAt 消除"同一毫秒连写"的排序抖动
        manager.save(Checkpoint("s1", 1, "step 1", emptyMap(), updatedAt = 1_000L))
        manager.save(Checkpoint("s1", 2, "step 2", emptyMap(), updatedAt = 2_000L))
        manager.save(Checkpoint("s1", 3, "step 3", emptyMap(), updatedAt = 3_000L))

        val loaded = manager.loadLatest("s1")
        assertNotNull(loaded)
        assertEquals(3, loaded?.step)
        assertEquals("step 3", loaded?.remainingTask)
    }

    @Test
    fun `cleanup removes old checkpoints beyond keep count`() = runBlocking {
        val dir = createTempDir("checkpoint_cleanup").apply { deleteOnExit() }
        val manager = CheckpointManager(dir.absolutePath)

        // 同上: 显式 updatedAt 消除"同一毫秒连写"的排序抖动
        manager.save(Checkpoint("s1", 1, "step 1", emptyMap(), updatedAt = 1_000L))
        manager.save(Checkpoint("s1", 2, "step 2", emptyMap(), updatedAt = 2_000L))
        manager.save(Checkpoint("s1", 3, "step 3", emptyMap(), updatedAt = 3_000L))
        manager.cleanup("s1", keep = 2)

        val checkpointDir = dir
        val files = checkpointDir.listFiles() ?: emptyArray()
        assertTrue("Expected at most 2 checkpoint files, got ${files.size}", files.size <= 2)
        // 保留的必须是最近两份 (排序主判据为档内 step/updatedAt, 不再只看 lastModified)
        assertTrue(
            "最新一份必须留下: ${files.map { it.name }}",
            files.any { it.name == "s1__step_3.json" }
        )
        assertTrue(
            "次新一份必须留下: ${files.map { it.name }}",
            files.any { it.name == "s1__step_2.json" }
        )
        assertFalse(
            "最旧一份应被清理: ${files.map { it.name }}",
            files.any { it.name == "s1__step_1.json" }
        )
    }

    @Test
    fun `cleanup does not fail on empty directory`() = runBlocking {
        val dir = createTempDir("checkpoint_cleanup_empty").apply { deleteOnExit() }
        val manager = CheckpointManager(dir.absolutePath)
        manager.cleanup("nonexistent")
    }

    @Test
    fun `loadLatestSync returns null synchronously for missing checkpoint`() {
        val dir = createTempDir("checkpoint_sync").apply { deleteOnExit() }
        val manager = CheckpointManager(dir.absolutePath)
        assertNull(manager.loadLatestSync("nonexistent"))
    }

    @Test
    fun `save with different sessions are isolated`() = runBlocking {
        val dir = createTempDir("checkpoint_isolated").apply { deleteOnExit() }
        val manager = CheckpointManager(dir.absolutePath)

        manager.save(Checkpoint("session_a", 1, "task A", emptyMap()))
        manager.save(Checkpoint("session_b", 1, "task B", emptyMap()))

        assertEquals("task A", manager.loadLatest("session_a")?.remainingTask)
        assertEquals("task B", manager.loadLatest("session_b")?.remainingTask)
        assertNull(manager.loadLatest("session_c"))
    }

    private fun createTempDir(prefix: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), prefix + "_" + System.nanoTime())
        dir.mkdirs()
        return dir
    }
}
