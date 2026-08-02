// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.ProviderInfo
import com.mengpaw.kernel.llm.ProviderType
import com.mengpaw.kernel.session.SessionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * "! 前缀命令" 测试 — 绕过 Agent 直接执行（Pipeline 优先, 受控 shell fallback）。
 *
 * 用 runBlocking（真实时间）而非 runTest: fallback 用例真实 spawn 进程,
 * runTest 虚拟时间会与 DefaultCommandExecutor 的 withTimeout(30s) 冲突。
 *
 * 断言均与 sh 是否在 PATH 无关: 未知命令两种机器都 != ERR_NOT_FOUND
 * (sh 存在 → exit 127/ERR_INTERNAL; sh 不存在 → ERR_IO)。
 */
class BangCommandExecutionTest {

    /** executeCommand 不调 LLM — 空实现即可。 */
    private class FakeProvider : LlmProvider {
        override suspend fun complete(prompt: String): String = "mock"
        override suspend fun completeWithMessages(messages: List<Map<String, String>>): String = "mock"
        override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
            "mock".also { onToken(it) }
        override fun info(): ProviderInfo = ProviderInfo("mock", "bang", ProviderType.LOCAL)
        override fun close() {}
    }

    @Before
    fun init() {
        DataPaths.initialize(System.getProperty("java.io.tmpdir") + "/mengpaw_bang_test")
    }

    private fun engine() = AgentEngine(llmProvider = FakeProvider(), sessionManager = SessionManager())

    @Test
    fun `known command runs through pipeline`() = runBlocking {
        val r = engine().executeCommand("self.status")
        assertTrue("pipeline 路径应成功: ${r.error}", r.success)
        assertTrue("self.status 应输出会话信息: ${r.output}", r.output.contains("Session:"))
    }

    @Test
    fun `unknown command falls back to shell sandbox`() = runBlocking {
        val r = engine().executeCommand("nosuchcmd.xyz")
        assertFalse("未知命令应失败: ${r.output}", r.success)
        assertNotEquals("fallback 生效, 不应是 ERR_NOT_FOUND", ErrorCodes.ERR_NOT_FOUND, r.errorCode)
    }

    @Test
    fun `blocked prefix rejected by sandbox before shell spawn`() = runBlocking {
        val r = engine().executeCommand("rm -rf /")
        assertEquals(ErrorCodes.ERR_PERMISSION_DENIED, r.errorCode)
    }

    @Test
    fun `empty command rejected`() = runBlocking {
        assertEquals(ErrorCodes.ERR_INVALID_INPUT, engine().executeCommand("   ").errorCode)
    }

    @Test
    fun `listCommands 非空且含 self 命令与描述`() {
        val cmds = engine().listCommands()
        assertTrue("补全列表不应为空", cmds.isNotEmpty())
        val st = cmds.firstOrNull { it.name == "self.status" }
        assertNotNull("应包含 self.status", st)
        assertTrue("self.status 应有功能描述: ${st!!.description}", st.description.isNotBlank())
    }

    @Test
    fun `notify 命令经 self 前缀兜底拿到描述`() {
        val cmds = engine().listCommands()
        val notify = cmds.firstOrNull { it.name == "self.notify.message" }
        assertNotNull("应包含 self.notify.message", notify)
        assertTrue("notify.message 描述不应为空 (BuiltinCommandIndex 缺 self. 前缀需兜底): ${notify!!.description}",
            notify.description.isNotBlank())
    }
}
