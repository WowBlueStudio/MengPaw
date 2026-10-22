// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.memory

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * memory.* 命令测试 — commit/log/show/diff/restore/gc 单机闭环。
 */
class MemoryHistoryCommandsTest {

    private lateinit var base: File
    private lateinit var workspace: File
    private val agent = "mem-history-test"
    private val ctx = ExecutionContext(
        sessionId = "test-session",
        agentName = "mem-history-test",
        environment = mapOf("deviceId" to "test-device")
    )

    @Before
    fun setUp() {
        base = Files.createTempDirectory("mengpaw-memory-cmd-").toFile()
        DataPaths.initialize(base.absolutePath)
        workspace = File(DataPaths.AGENTS, agent)
        workspace.mkdirs()
        File(workspace, "soul.md").writeText("# 灵魂\n\n最初版本\n")
        File(workspace, "memory").mkdirs()
        File(workspace, "memory/memory.md").writeText("# 长期记忆\n")
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private suspend fun run(cmd: String, vararg args: String): ExecutionResult {
        val handler = requireNotNull(MemoryHistoryCommands.commands[cmd]) { "未注册命令: $cmd" }
        return handler(args.toList(), ctx)
    }

    @Test
    fun `commit 后 log 可见`() = runTest {
        val commit = run("commit", "-m", "第一版")
        assertTrue(commit.error, commit.success)
        assertTrue(commit.output.contains("已创建快照"))
        assertTrue(commit.output.contains("2 个文件"))

        val log = run("log")
        assertTrue(log.success)
        assertTrue(log.output.contains("第一版"))
        assertTrue(log.output.contains("test-device"))
        assertTrue(log.output.contains("最近快照 (1)"))
    }

    @Test
    fun `无变化不再提交`() = runTest {
        assertTrue(run("commit").success)
        val again = run("commit")
        assertTrue(again.success)
        assertTrue("应提示无变化: ${again.output}", again.output.contains("无变化"))
        assertEquals("历史仍只有 1 个快照", 1, SnapshotStore.history("ws-$agent", 20).size)
    }

    @Test
    fun `show 取指定快照内容`() = runTest {
        assertTrue(run("commit", "-m", "旧版").success)
        val oldId = SnapshotStore.history("ws-$agent", 20).first().id
        File(workspace, "soul.md").writeText("# 灵魂\n\n新版本\n")
        assertTrue(run("commit", "-m", "新版").success)

        val latest = run("show", "soul.md")
        assertTrue(latest.success)
        assertTrue("缺省应取最近快照: ${latest.output}", latest.output.contains("新版本"))

        val old = run("show", "soul.md", oldId)
        assertTrue(old.success)
        assertTrue("指定快照应取旧内容: ${old.output}", old.output.contains("最初版本"))
        assertFalse(old.output.contains("新版本"))
    }

    @Test
    fun `diff 输出 unified 差异`() = runTest {
        assertTrue(run("commit", "-m", "基准").success)
        File(workspace, "soul.md").writeText("# 灵魂\n\n改过的内容\n")
        val d = run("diff")
        assertTrue(d.success)
        assertTrue("应含文件头: ${d.output}", d.output.contains("--- a/soul.md"))
        assertTrue(d.output.contains("-最初版本"))
        assertTrue(d.output.contains("+改过的内容"))
        assertTrue(d.output.contains("对比基准: 快照"))
    }

    @Test
    fun `restore 恢复内容且拒绝非 md`() = runTest {
        assertTrue(run("commit", "-m", "旧版").success)
        val oldId = SnapshotStore.history("ws-$agent", 20).first().id
        File(workspace, "soul.md").writeText("# 灵魂\n\n被改坏\n")
        assertTrue(run("commit", "-m", "新版").success)

        val restored = run("restore", "soul.md", oldId)
        assertTrue(restored.error, restored.success)
        assertTrue(restored.output.contains("已恢复"))
        assertEquals("# 灵魂\n\n最初版本\n", File(workspace, "soul.md").readText())

        File(workspace, "twin-model-rules.json").writeText("{}")
        val rejected = run("restore", "twin-model-rules.json")
        assertFalse("非 .md 必须拒绝", rejected.success)
        assertTrue(rejected.error.orEmpty().contains(".md"))
    }

    @Test
    fun `gc 回报删除数与库体积`() = runTest {
        assertTrue(run("commit").success)
        SnapshotStore.writeBlob("孤儿".toByteArray())
        val gc = run("gc", "--keep", "0d")
        assertTrue(gc.success)
        assertTrue("应回报清理数: ${gc.output}", gc.output.contains("已清理"))
        assertTrue(gc.output.contains("对象库"))
        assertTrue(gc.output.contains("B") || gc.output.contains("KB"))
    }
}
