// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CheckpointManagerTest {

    @Test
    fun `save and load checkpoint roundtrip`() = runBlocking {
        val dir = createTempDir("checkpoint_test").apply { deleteOnExit() }
        val manager = CheckpointManager(dir.absolutePath)
        val sessionId = "test_session"

        val cp = Checkpoint(sessionId = sessionId, step = 5, remainingTask = "remaining task", context = mapOf("key" to "value"))
        manager.save(cp)
        val loaded = manager.loadLatest(sessionId)

        assertNotNull(loaded)
        assertEquals(sessionId, loaded!!.sessionId)
        assertEquals(5, loaded.step)
        assertEquals("remaining task", loaded.remainingTask)
        assertEquals("value", loaded.context["key"])
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

        manager.save(Checkpoint("s1", 1, "step 1", emptyMap()))
        manager.save(Checkpoint("s1", 2, "step 2", emptyMap()))
        manager.save(Checkpoint("s1", 3, "step 3", emptyMap()))

        val loaded = manager.loadLatest("s1")
        assertNotNull(loaded)
        assertEquals(3, loaded!!.step)
        assertEquals("step 3", loaded.remainingTask)
    }

    @Test
    fun `cleanup removes old checkpoints beyond keep count`() = runBlocking {
        val dir = createTempDir("checkpoint_cleanup").apply { deleteOnExit() }
        val manager = CheckpointManager(dir.absolutePath)

        manager.save(Checkpoint("s1", 1, "step 1", emptyMap()))
        manager.save(Checkpoint("s1", 2, "step 2", emptyMap()))
        manager.save(Checkpoint("s1", 3, "step 3", emptyMap()))
        manager.cleanup("s1", keep = 2)

        val checkpointDir = dir
        val files = checkpointDir.listFiles() ?: emptyArray()
        assertTrue("Expected at most 2 checkpoint files, got ${files.size}", files.size <= 2)
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

        assertEquals("task A", manager.loadLatest("session_a")!!.remainingTask)
        assertEquals("task B", manager.loadLatest("session_b")!!.remainingTask)
        assertNull(manager.loadLatest("session_c"))
    }

    private fun createTempDir(prefix: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), prefix + "_" + System.nanoTime())
        dir.mkdirs()
        return dir
    }
}
