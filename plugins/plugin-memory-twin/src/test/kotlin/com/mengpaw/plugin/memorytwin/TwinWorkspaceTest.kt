// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.memory.TwinFileClass
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    // ── 清单哈希形态 (P0: 发送端与接收端必须同一函数) ──────────

    @Test
    fun `manifestHash 输出恒为 16 字符且同一输入稳定`() {
        val full = "a".repeat(64)
        val short = TwinWorkspace.manifestHash(full)
        assertEquals("协议比较形态固定 16 字符", 16, short.length)
        assertEquals("a".repeat(16), short)
        assertEquals("同一输入必须稳定", short, TwinWorkspace.manifestHash(full))
        assertNotEquals("不同内容仍可区分",
            short, TwinWorkspace.manifestHash("b".repeat(64)))
    }

    @Test
    fun `manifestHash 短输入原样返回`() {
        assertEquals("abc", TwinWorkspace.manifestHash("abc"))
        assertEquals("", TwinWorkspace.manifestHash(""))
        val exact = "c".repeat(TwinWorkspace.MANIFEST_HASH_CHARS)
        assertEquals("恰好达到上限时原样返回", exact, TwinWorkspace.manifestHash(exact))
    }

    // ── 内容相同不落盘 (P0) ───────────────────────────────────

    @Test
    fun `applyWorkspaceFile 内容相同时返回 same 且不重写文件`() {
        TwinWorkspace.applyWorkspaceFile(agentName, "soul.md", "稳定内容", "peer-1", future)
        val target = File(workspaceRoot(), "soul.md")
        val fixedOld = 1_600_000_000_000L   // 2020 年 — 任何重写都会得到当前时间
        assertTrue("预置旧时间戳失败", target.setLastModified(fixedOld))
        val before = target.lastModified()
        val audit = File(DataPaths.TWIN_AUDIT)
        val auditBefore = if (audit.exists()) audit.readText() else ""

        val r = TwinWorkspace.applyWorkspaceFile(agentName, "soul.md", "稳定内容", "peer-2", future)

        assertEquals("same", r)
        assertEquals("内容一致不得重写文件", before, target.lastModified())
        assertFalse("内容一致不得留 tmp", File(target.parentFile, "soul.md.tmp").exists())
        val auditAfter = if (audit.exists()) audit.readText() else ""
        assertEquals("内容一致不得写审计", auditBefore, auditAfter)
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
    fun `模型能力规则文件纳入同步_其它非md仍排除`() {
        // 2026-09-10: 规则文件按根目录白名单破例同步 —
        // Agent 在一台设备登记/修正的模型能力必须能扩散到全部设备 (一处学到, 全网共享),
        // 否则"跟上 LLM 迭代"只能停在单机。
        val root = workspaceRoot()
        root.mkdirs()
        File(root, "soul.md").writeText("# soul")
        File(root, ModelCapabilityRules.RULES_FILE_NAME).writeText("""{"version":1,"rules":[]}""")
        File(root, "random.json").writeText("{}")   // 白名单外的非 md 仍不同步
        File(root, "notes.txt").writeText("plain")

        val manifest = TwinWorkspace.buildManifest(agentName)
        assertEquals(
            setOf("soul.md", ModelCapabilityRules.RULES_FILE_NAME),
            manifest.keys.toSet()
        )
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
        // 协议形态 (manifestHash) 保持判别力 — 不再手写 take(16), 避免与常量脱节
        assertNotEquals(
            TwinWorkspace.manifestHash(first["doc.md"]!!.hash),
            TwinWorkspace.manifestHash(second["doc.md"]!!.hash)
        )
    }

    // ── 冲突分支 (P0 补测) ─────────────────────────────────────
    // 此前该分支零 JVM 覆盖: android.util.Log 在单测里抛 "Stub!" 被外层 catch 吞成 "error"。
    // build.gradle.kts 开 unitTests.isReturnDefaultValues 之后才能断言真实的冲突语义。

    @Test
    fun `本地较新且内容不同时落 conflict 备份且不覆盖本地`() {
        TwinWorkspace.applyWorkspaceFile(agentName, "memory.md", "本地版本", "peer-1", 0L)
        val target = File(workspaceRoot(), "memory.md")
        assertTrue("预置本地较新的 mtime 失败", target.setLastModified(1_700_000_000_000L))

        val r = TwinWorkspace.applyWorkspaceFile(agentName, "memory.md", "对端版本", "peer-2", 1_000L)

        assertEquals("conflict", r)
        assertEquals("本地内容不得被远端覆盖", "本地版本", target.readText())
        val backups = target.parentFile!!.listFiles()!!.filter { it.name.contains(".conflict.") }
        assertEquals("必须留下冲突备份", 1, backups.size)
        assertEquals("备份内容应为对端版本", "对端版本", backups[0].readText())
    }

    @Test
    fun `穿越路径在写盘入口被拒绝且不落文件`() {
        assertEquals("rejected",
            TwinWorkspace.applyWorkspaceFile(agentName, "../escape.md", "x", "peer-1", 0L))
        assertFalse("工作区外不得出现文件", File(DataPaths.AGENTS, "escape.md").exists())
    }

    @Test
    fun `classifyWorkspace 按 2_1 分出 L M D 且不含 S`() {
        val root = workspaceRoot()
        File(root, "inbox").mkdirs()
        File(root, "dialog").mkdirs()
        File(root, "devices").mkdirs()
        File(root, "soul.md").writeText("# soul")
        File(root, "memory").mkdirs()
        File(root, "memory/memory.md").writeText("# 长期")
        File(root, "memory/memory_2026-10-22.md").writeText("# 中期")
        File(root, "memory/project_mengpaw_memory.md").writeText("# 项目")
        File(root, "2026-10-22_dream.md").writeText("# 梦境")
        File(root, "devices/dev-a.md").writeText("---\ndeviceId: dev-a\n---\n")
        File(root, ModelCapabilityRules.RULES_FILE_NAME).writeText("""{"version":1,"rules":[]}""")
        File(root, "inbox/task.md").writeText("# 短期")
        File(root, "dialog/chat.md").writeText("# 短期")

        val grouped = TwinWorkspace.classifyWorkspace(agentName)

        assertEquals(setOf("soul.md", "memory/memory.md"), grouped[TwinFileClass.L]?.toSet())
        assertEquals(
            setOf("memory/memory_2026-10-22.md", "memory/project_mengpaw_memory.md", "2026-10-22_dream.md"),
            grouped[TwinFileClass.M]?.toSet()
        )
        assertEquals(setOf("devices/dev-a.md"), grouped[TwinFileClass.D]?.toSet())
        assertEquals(setOf(ModelCapabilityRules.RULES_FILE_NAME), grouped[TwinFileClass.RULES]?.toSet())
        assertNull("S 类必须保持排除", grouped[TwinFileClass.S])
    }

    @Test
    fun `buildManifest 委托 kernel 扫描 mtime 有值`() {
        val root = workspaceRoot()
        root.mkdirs()
        File(root, "soul.md").writeText("# soul")
        val manifest = TwinWorkspace.buildManifest(agentName)
        assertEquals(setOf("soul.md"), manifest.keys)
        assertTrue("mtime 必须来自真实文件", manifest.getValue("soul.md").mtime > 0L)
    }
}
