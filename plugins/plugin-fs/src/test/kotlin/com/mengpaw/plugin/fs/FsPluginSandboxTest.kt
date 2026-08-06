// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.fs

import com.mengpaw.kernel.cli.ExecutionContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * FsPlugin 沙箱边界与 symlink 检测测试 (插件零测试补齐 — P2 symlink 真检测回归)。
 *
 * resolveSafe 为纯 JVM 路径解析 (FsPlugin 零 Android 依赖), 直接经
 * internal 可见性访问。工作目录指向临时目录, 不触碰真实文件系统。
 *
 * symlink 用例: Windows 需开发者模式/管理员权限, 创建失败时 Assume 跳过 (可移植)。
 */
class FsPluginSandboxTest {

    private lateinit var tempRoot: File
    private lateinit var sandbox: File
    private lateinit var outside: File
    private lateinit var plugin: FsPlugin
    private lateinit var ctx: ExecutionContext

    @Before
    fun setUp() {
        tempRoot = File(System.getProperty("java.io.tmpdir"), "mengpaw-fs-test-${System.nanoTime()}")
        sandbox = File(tempRoot, "sandbox").apply { mkdirs() }
        outside = File(tempRoot, "outside").apply { mkdirs() }
        File(sandbox, "file.txt").writeText("内部文件")
        File(sandbox, "sub").mkdirs()
        File(File(sandbox, "sub"), "nested.txt").writeText("嵌套文件")
        File(outside, "secret.txt").writeText("外部秘密")

        plugin = FsPlugin()
        ctx = ExecutionContext(sessionId = "test-session", workDir = sandbox.absolutePath)
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    // ── 普通路径: 不误判 ────────────────────────────────────────

    @Test
    fun `沙箱内相对路径放行且解析到工作目录`() {
        val r = plugin.resolveSafe("file.txt", ctx)
        assertFalse("$r", r.isFailure)
        assertEquals(File(sandbox, "file.txt").canonicalPath, r.file.canonicalPath)
    }

    @Test
    fun `沙箱内绝对路径放行`() {
        val r = plugin.resolveSafe(File(sandbox, "file.txt").absolutePath, ctx)
        assertFalse("$r", r.isFailure)
        assertTrue(r.file.exists())
    }

    @Test
    fun `点号与点点段在沙箱内归一放行 规范化不误判`() {
        val r = plugin.resolveSafe("sub/../file.txt", ctx)
        assertFalse("$r", r.isFailure)
        assertEquals(File(sandbox, "file.txt").canonicalPath, r.file.canonicalPath)
    }

    // ── 沙箱边界 ────────────────────────────────────────────────

    @Test
    fun `越界相对路径被拒`() {
        val r = plugin.resolveSafe("../outside/secret.txt", ctx)
        assertTrue(r.isFailure)
        assertTrue(r.error.contains("outside allowed directory"))
    }

    @Test
    fun `越界绝对路径被拒`() {
        val r = plugin.resolveSafe(File(outside, "secret.txt").absolutePath, ctx)
        assertTrue(r.isFailure)
        assertTrue(r.error.contains("outside allowed directory"))
    }

    @Test
    fun `穿越路径不读取沙箱外文件`() {
        val r = plugin.resolveSafe("../outside/secret.txt", ctx)
        assertTrue(r.isFailure)
        assertEquals("外部秘密", File(outside, "secret.txt").readText())  // 文件安然无恙
    }

    // ── symlink 检测 (P2 修复回归) ──────────────────────────────
    //
    // 平台前提探测: ① Windows 需开发者模式/管理员才能创建 symlink;
    // ② 部分 Windows JVM (实测 Corretto 17) 的 File.canonicalPath 不解析 symlink —
    //    canonical 与 absolute 恒等, P2 的 absolutePath != canonicalPath 比较在
    //    Windows JVM 上天然失效 (生产环境 Android/Linux 正常解析, 修复有效)。
    // 任一前提不满足 → Assume 跳过, 并在报告中记录。

    @Test
    fun `指向沙箱外的 symlink 被拦截`() {
        assumeTrue("symlink 不可创建或 canonicalPath 不解析 (Windows 特性), 跳过", symlinkDetectionSupported())
        val link = File(sandbox, "escape-link.txt")
        assumeTrue("无法创建 symlink (需开发者模式/管理员), 跳过", tryCreateSymlink(link, File(outside, "secret.txt")))
        try {
            val r = plugin.resolveSafe("escape-link.txt", ctx)
            // 真实 symlink 下 canonical 越界 → 第一道前缀检查即拦截 (P2 比较 absolutePath != canonicalPath 生效)
            assertTrue("symlink 越界应拦截: $r", r.isFailure)
            assertTrue(r.error.contains("outside allowed directory"))
        } finally {
            link.delete()
        }
    }

    @Test
    fun `指向沙箱内的 symlink 放行 不误判`() {
        assumeTrue("symlink 不可创建或 canonicalPath 不解析 (Windows 特性), 跳过", symlinkDetectionSupported())
        val link = File(sandbox, "inside-link.txt")
        assumeTrue("无法创建 symlink (需开发者模式/管理员), 跳过", tryCreateSymlink(link, File(sandbox, "file.txt")))
        try {
            val r = plugin.resolveSafe("inside-link.txt", ctx)
            assertFalse("沙箱内 symlink 应放行: $r", r.isFailure)
            assertEquals(File(sandbox, "file.txt").canonicalPath, r.file.canonicalPath)
        } finally {
            link.delete()
        }
    }

    @Test
    fun `普通路径 symlink 比较不误判 (absolutePath == canonicalPath)`() {
        // 无 symlink 的普通路径: absolutePath 与 canonicalPath 相等 → 双检查均放行
        val r = plugin.resolveSafe("sub/nested.txt", ctx)
        assertFalse("$r", r.isFailure)
        assertEquals(File(sandbox, "sub/nested.txt").canonicalPath, r.file.canonicalPath)
        // 对比断言: 普通路径两者相等, 证明 P2 修复仅在真实 symlink 时进入复核分支
        val abs = File(sandbox, "sub/nested.txt").absolutePath
        assertEquals(abs, File(sandbox, "sub/nested.txt").canonicalPath)
    }

    // ── 辅助 ────────────────────────────────────────────────────

    private fun tryCreateSymlink(link: File, target: File): Boolean = try {
        Files.createSymbolicLink(link.toPath(), target.toPath())
        true
    } catch (e: Exception) {
        false
    }

    /** 探测本 JVM 的 canonicalPath 是否解析 symlink (Windows 部分 JVM 不解析)。 */
    private fun symlinkDetectionSupported(): Boolean {
        val probeDir = File(tempRoot, "probe").apply { mkdirs() }
        val probeTarget = File(outside, "probe-target.txt").apply { writeText("x") }
        val probeLink = File(probeDir, "probe-link.txt")
        return try {
            Files.createSymbolicLink(probeLink.toPath(), probeTarget.toPath())
            probeLink.canonicalPath != probeLink.absolutePath
        } catch (e: Exception) {
            false
        } finally {
            probeLink.delete()
            probeDir.delete()
            probeTarget.delete()
        }
    }
}
