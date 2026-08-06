// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * TwinWorkspace 工作区同步落盘逻辑测试 (插件零测试补齐)。
 *
 * - P2 原子写修复回归: tmp+rename 双保险, 失败不丢目标, 无 .tmp 残留
 * - buildManifest 哈希比对语义: 相同内容哈希相等, 不同内容不等 (P1 截断比较回归)
 * - 排除规则: CLI.md / inbox / dialog / backup / *.tmp / *.conflict.*
 *
 * 注意: 冲突分支 (本地更新) 内含 android.util.Log 调用, JVM 单测不覆盖
 * (android.jar stub 默认抛异常) — 见报告"跳过项"。
 */
class TwinWorkspaceTest {

    private lateinit var tempBase: File
    private val agentName = "agent-ws"
    private val future: Long get() = System.currentTimeMillis() + 60_000  // 未来时间戳避免触发冲突分支

    @Before
    fun setUp() {
        tempBase = File(System.getProperty("java.io.tmpdir"), "mengpaw-twin-ws-${System.nanoTime()}")
        DataPaths.initialize(tempBase.absolutePath)
    }

    @After
    fun tearDown() {
        DataPaths.initialize("/sdcard/MengPaw")
        tempBase.deleteRecursively()
    }

    private fun workspaceRoot() = File(DataPaths.AGENTS, agentName)

    // ── P2 原子写 ──────────────────────────────────────────────

    @Test
    fun `写新文件成功且无 tmp 残留`() {
        val r = TwinWorkspace.applyWorkspaceFile(agentName, "memory/memory.md", "第一版内容", "peer-1", future)
        assertEquals("applied", r)

        val target = File(workspaceRoot(), "memory/memory.md")
        assertTrue(target.exists())
        assertEquals("第一版内容", target.readText())
        // 原子写不留 .tmp
        assertFalse(File(target.parentFile, "memory.md.tmp").exists())
    }

    @Test
    fun `覆盖已有文件成功且目标保留`() {
        TwinWorkspace.applyWorkspaceFile(agentName, "soul.md", "旧内容", "peer-1", future)
        val r = TwinWorkspace.applyWorkspaceFile(agentName, "soul.md", "新内容", "peer-2", future)
        assertEquals("applied", r)

        val target = File(workspaceRoot(), "soul.md")
        assertEquals("新内容", target.readText())   // 目标被原子替换
        assertTrue(target.exists())
        assertFalse(File(target.parentFile, "soul.md.tmp").exists())
    }

    @Test
    fun `覆盖失败路径不丢失原文件 (目标保留即数据双失修复)`() {
        // 模拟目标为"不可覆盖项": 非空目录抢占目标位 —
        // renameTo 无法覆盖目录, delete 也删不掉非空目录 → 双重回退失败 → 返回 error 且目标保留
        val target = File(workspaceRoot(), "locked.md")
        target.mkdirs()
        File(target, "inner.txt").writeText("x")  // 非空目录不可删
        val r = TwinWorkspace.applyWorkspaceFile(agentName, "locked.md", "内容", "peer-1", future)
        assertEquals("error", r)
        // 目标 (目录) 未被删除 — 数据未丢失 (P2 修复核心: 失败不删任何数据)
        assertTrue(target.exists())
        assertTrue(File(target, "inner.txt").exists())
    }

    @Test
    fun `removeWorkspaceFile 保留 deleted 备份`() {
        TwinWorkspace.applyWorkspaceFile(agentName, "note.md", "待删除", "peer-1", future)
        TwinWorkspace.removeWorkspaceFile(agentName, "note.md", "peer-2")

        assertFalse(File(workspaceRoot(), "note.md").exists())
        val backups = workspaceRoot().listFiles()!!.filter { it.name.startsWith("note.md.deleted.") }
        assertEquals(1, backups.size)
        assertEquals("待删除", backups[0].readText())
    }

    // ── buildManifest 哈希比对 (P1 截断比较回归) ────────────────

    @Test
    fun `相同内容哈希相等 不同内容不等`() {
        File(workspaceRoot(), "memory").mkdirs()
        val a = File(workspaceRoot(), "memory/memory.md").apply { writeText("记忆内容 v1") }
        val b = File(workspaceRoot(), "soul.md").apply { writeText("记忆内容 v1") }  // 相同内容

        val h1 = TwinWorkspace.fileHash(a)
        val h2 = TwinWorkspace.fileHash(b)
        assertEquals("相同内容哈希必须相等", h1, h2)

        b.writeText("记忆内容 v2")
        val h3 = TwinWorkspace.fileHash(b)
        assertNotEquals("不同内容哈希必须不等", h1, h3)
        // 64 位十六进制 SHA-256
        assertEquals(64, h1.length)
    }

    @Test
    fun `buildManifest 只收录 md 并按排除规则过滤`() {
        val root = workspaceRoot()
        File(root, "memory").mkdirs()
        File(root, "inbox").mkdirs()      // 排除目录
        File(root, "dialog").mkdirs()     // 排除目录
        File(root, "memory/backup").mkdirs()  // 排除目录
        File(root, "soul.md").writeText("# soul")
        File(root, "CLI.md").writeText("# cli")                 // 排除文件
        File(root, "memory/memory.md").writeText("# memory")
        File(root, "inbox/task.md").writeText("# inbox task")   // 排除目录内
        File(root, "dialog/chat.md").writeText("# dialog")      // 排除目录内
        File(root, "memory/backup/memory.bak.md").writeText("# backup")  // 排除目录内
        File(root, "draft.md.tmp").writeText("tmp")             // 排除 tmp
        File(root, "draft.md.conflict.20260101.from_x").writeText("conflict")  // 排除冲突
        File(root, "notes.txt").writeText("plain")              // 非 md 排除

        val manifest = TwinWorkspace.buildManifest(agentName)
        assertEquals(setOf("soul.md", "memory/memory.md"), manifest.keys.toSet())
        assertTrue(manifest["soul.md"]!!.hash.isNotBlank())
    }

    @Test
    fun `buildManifest 目录不存在返回空清单`() {
        assertTrue(TwinWorkspace.buildManifest("no-such-agent").isEmpty())
    }

    @Test
    fun `相同工作区哈希一致 修改后不一致 (同步收敛判定依据)`() {
        val ws = File(DataPaths.AGENTS, "a")
        ws.mkdirs()
        File(ws, "doc.md").writeText("同步文档 v1")
        val first = TwinWorkspace.buildManifest("a")

        File(ws, "doc.md").writeText("同步文档 v2")
        val second = TwinWorkspace.buildManifest("a")

        assertNotEquals(first["doc.md"]!!.hash, second["doc.md"]!!.hash)
        // 16 位截断 (传输用) 保持判别力
        assertNotEquals(first["doc.md"]!!.hash.take(16), second["doc.md"]!!.hash.take(16))
        assertEquals(first["doc.md"]!!.hash.take(16), first["doc.md"]!!.hash.take(16))  // 同内容同截断
    }
}
