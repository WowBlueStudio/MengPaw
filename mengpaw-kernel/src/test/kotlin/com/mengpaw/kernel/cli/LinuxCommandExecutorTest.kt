// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.cli

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.security.CommandMonitor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Linux 命令通道 — 回退判断、点分保护、无参保护、BLOCK/CONFIRM、真实 shell 兜底。 */
class LinuxCommandExecutorTest {

    private val ctx = ExecutionContext(
        sessionId = "linux-test",
        agentName = "agent",
        workDir = System.getProperty("java.io.tmpdir")
    )

    @Before
    fun setUp() {
        DataPaths.initialize(System.getProperty("java.io.tmpdir") + "/mengpaw_linux_test")
        CommandMonitor.resetForTest()
        SessionShellPool.resetForTest()
    }

    private fun exec(cmd: String, confirm: Boolean = true) =
        runBlocking { LinuxCommandExecutor.execute(cmd, ctx, confirm) }

    @Test
    fun `dotted unknown command does not fall to shell`() {
        val r = exec("agent.rea")
        assertEquals(ErrorCodes.ERR_NOT_FOUND, r.errorCode)
        assertTrue("应附检索引导: ${r.error}", r.error?.contains("self.search") == true)
    }

    @Test
    fun `no-arg stdin command rejected before shell`() {
        assertEquals(ErrorCodes.ERR_PERMISSION_DENIED, exec("grep").errorCode)
    }

    @Test
    fun `block rule rejects before shell`() {
        assertEquals(ErrorCodes.ERR_PERMISSION_DENIED, exec("rm -rf /").errorCode)
    }

    @Test
    fun `confirm rule rejected without user consent in worker`() {
        assertEquals(ErrorCodes.ERR_PERMISSION_DENIED, exec("rm old.log", confirm = false).errorCode)
    }

    @Test
    fun `unknown command falls back to sandbox shell`() {
        // sh 存在 → exit 127/ERR_INTERNAL; sh 不存在 → ERR_IO — 均不是 ERR_NOT_FOUND
        val r = exec("nosuchcmd_zzz")
        assertNotEquals(ErrorCodes.ERR_NOT_FOUND, r.errorCode)
    }

    @Test
    fun `safe command passes monitor`() {
        // 放行路径: 不弹窗、不过滤; 执行结果取决于环境 sh 是否可用, 但绝不应被安全策略拒
        val r = exec("echo hello")
        assertNotEquals(ErrorCodes.ERR_PERMISSION_DENIED, r.errorCode)
    }

    @Test
    fun `sh -c payload goes through same monitor`() {
        val r = exec("sh -c \"rm -rf /\"")
        assertEquals(ErrorCodes.ERR_PERMISSION_DENIED, r.errorCode)
    }
}
