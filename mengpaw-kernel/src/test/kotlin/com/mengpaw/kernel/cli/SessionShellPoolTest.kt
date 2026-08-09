// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.cli

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 会话式进程池测试 — 每次调用、汇报后自动初始化。
 *
 * runBlocking 真实时间（runTest 虚拟时间与 30s withTimeout 冲突，同 BangCommandExecutionTest）。
 * 断言与 sh 是否在 PATH 无关：进程启动失败 → ERR_IO 返回而非崩溃。
 */
class SessionShellPoolTest {

    // 正斜杠路径（git bash sh 不识别 Windows 反斜杠）
    private val tmpDir = System.getProperty("java.io.tmpdir").replace('\\', '/')

    private val ctx = ExecutionContext(sessionId = "pool-test", agentName = "MengPaw", workDir = tmpDir)

    @Before
    fun setUp() {
        SessionShellPool.resetForTest()
    }

    @After
    fun tearDown() {
        SessionShellPool.resetForTest()
        SessionShellPool.commandTimeoutMs = 30_000L
    }

    @Test
    fun `output without trailing newline still parses sentinel`() = runBlocking {
        // P1 回归锚点: printf hello 无结尾换行 — 哨兵 printf 前置换行保证独立成行
        val r = SessionShellPool.execute("printf hello", ctx)
        assertTrue("无换行输出应成功: ${r.error}", r.success)
        assertEquals("输出应完整保留", "hello", r.output.trim())
        // 后续会话仍可复用
        val r2 = SessionShellPool.execute("echo world", ctx)
        assertTrue(r2.success)
        assertTrue(r2.output.contains("world"))
    }

    @Test
    fun `oversized output gets truncation marker`() = runBlocking {
        // 输出 120KB > 100KB 上限 → 截断标记（防 Agent 基于残缺输出下结论）
        val r = SessionShellPool.execute("head -c 120000 /dev/zero | tr '\\0' 'x'", ctx)
        assertTrue("截断命令应成功: ${r.error}", r.success)
        assertTrue("应含截断标记: ...${r.output.takeLast(60)}", r.output.contains("truncated at 100 KB"))
    }

    @Test
    fun `session reused across calls without recreating process`() = runBlocking {
        val r1 = SessionShellPool.execute("echo hello-1", ctx)
        val r2 = SessionShellPool.execute("echo hello-2", ctx)
        assertTrue("第一次应成功: ${r1.error}", r1.success)
        assertTrue("第二次应成功: ${r2.error}", r2.success)
        assertTrue("输出应正确: ${r1.output}", r1.output.contains("hello-1"))
        assertTrue("输出应正确: ${r2.output}", r2.output.contains("hello-2"))
        assertEquals("两次调用应复用同一会话进程（未重建）", 1, SessionShellPool.totalCreated)
    }

    @Test
    fun `cwd reset on next call after cd`() = runBlocking {
        // cd 到项目根（不同于 workDir）→ 下次调用自动初始化应重置 cwd。
        // 注意: git bash 的 /tmp 是 tmpDir 的 MSYS 映射（同一个目录），不能用 /tmp 作对比
        val otherDir = System.getProperty("user.dir").replace('\\', '/')
        val r1 = SessionShellPool.execute("cd $otherDir && pwd", ctx)
        assertTrue("cd 应成功: ${r1.error}", r1.success)
        val r2 = SessionShellPool.execute("pwd", ctx)
        assertTrue("第二次应成功: ${r2.error}", r2.success)
        assertNotEquals("cwd 应被重置回 workDir（不再是 $otherDir）: ${r2.output}", r1.output.trim(), r2.output.trim())
    }

    @Test
    fun `concurrent calls are isolated`() = runBlocking {
        val outputs = coroutineScope {
            (1..4).map { i ->
                async {
                    val r = SessionShellPool.execute("echo value-$i", ctx)
                    r.success to r.output
                }
            }.awaitAll()
        }
        outputs.forEachIndexed { i, (success, out) ->
            assertTrue("第 ${i + 1} 个应成功: $out", success)
            assertTrue("输出归属应正确: $out", out.contains("value-${i + 1}"))
        }
    }

    @Test
    fun `timed out command destroys session`() = runBlocking {
        // 2500ms: 足够覆盖新 sh 进程启动 (1500ms 在全量并行负载下仍有恢复命令
        // 启动抖动超时 — 0.35.2 发布全量实测; 仍 < sleep 3 保持超时语义)
        SessionShellPool.commandTimeoutMs = 2500
        val r = SessionShellPool.execute("sleep 3", ctx)
        assertFalse("sleep 3 应超时", r.success)
        assertEquals(ErrorCodes.ERR_TIMEOUT, r.errorCode)
        // 超时后池可继续工作（销毁的会话被替换）
        val after = SessionShellPool.execute("echo recovered", ctx)
        assertTrue("超时后应可继续执行: ${after.error}", after.success)
        assertTrue(after.output.contains("recovered"))
    }

    @Test
    fun `sandbox stays in executor not pool`() = runBlocking {
        // 沙箱检查在 DefaultCommandExecutor — 池本身只收已通过检查的命令
        val executor = DefaultCommandExecutor()
        val r = executor.execute("rm -rf /", ctx)
        assertFalse("黑名单命令应被拦截", r.success)
        assertEquals(ErrorCodes.ERR_PERMISSION_DENIED, r.errorCode)
        assertEquals("被拦截不应创建会话", 0, SessionShellPool.totalCreated)
    }

    @Test
    fun `unknown command returns shell error not crash`() = runBlocking {
        val executor = DefaultCommandExecutor()
        val r = executor.execute("nosuchcmd.xyz", ctx)
        assertFalse("未知命令应失败", r.success)
        // sh 存在 → exit 127/ERR_INTERNAL；sh 不存在 → ERR_IO。两种机器都稳定
        assertNotEquals("不应是 ERR_NOT_FOUND", ErrorCodes.ERR_NOT_FOUND, r.errorCode)
    }
}
