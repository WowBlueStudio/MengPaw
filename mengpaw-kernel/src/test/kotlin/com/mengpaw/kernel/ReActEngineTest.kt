// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.cli.*
import com.mengpaw.kernel.llm.*
import com.mengpaw.kernel.session.SessionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReActEngineTest {

    private val mockLlm = MockLlmProvider()

    private val sessionManager = SessionManager()

    private val engine = AgentEngine(
        llmProvider = mockLlm,
        sessionManager = sessionManager
    )

    @Test
    fun `agent state transits correctly`() {
        assertEquals("Idle", AgentState.Idle.toString())
        val running = AgentState.Running("test task", 1, 10)
        assertEquals("test task", running.task)
        assertEquals(1, running.step)
        assertEquals(10, running.maxSteps)
        val finished = AgentState.Finished("done")
        assertEquals("done", finished.result)
        val error = AgentState.Error("oops")
        assertEquals("oops", error.message)
    }

    @Test
    fun `initial agent state is idle`() {
        assertEquals(AgentState.Idle, engine.state.value)
    }

    @Test
    fun `run sets state through running to finished`() = runBlocking {
        // LLM returns Final Answer immediately
        mockLlm.nextResponse = """
            Thought: Task is complete.
            Final Answer: All done successfully.
        """.trimIndent()

        val result = engine.run("Simple task", maxSteps = 3)
        assertEquals("All done successfully.", result)
        assertTrue(engine.state.value is AgentState.Finished)
    }

    @Test
    fun `注入的确认门被工具门禁调用并按其结果放行`() = runBlocking {
        val tmp = System.getProperty("java.io.tmpdir") + "/mengpaw_confirm_gate_" + System.nanoTime()
        DataPaths.initialize(tmp)
        java.io.File(tmp, "Agent文档/MengPaw/memory").mkdirs()
        val memoryFile = java.io.File(tmp, "Agent文档/MengPaw/memory/memory.md")
        memoryFile.writeText("- [20260801_000000] 临时记忆条目\n")
        com.mengpaw.kernel.security.AgentPermissionStore.resetForTest(java.io.File(tmp, "perm.json"))
        com.mengpaw.kernel.security.AgentPermissionStore.setLevel(
            "MengPaw", com.mengpaw.kernel.security.AgentPermissionLevel.TRUSTED)

        var asked = 0
        val gate = object : com.mengpaw.kernel.harness.HarnessConfirmGate {
            override suspend fun request(
                command: String, reason: String?, riskLabel: String, timeoutMs: Long
            ): com.mengpaw.kernel.harness.ConfirmDecision {
                asked++
                return com.mengpaw.kernel.harness.ConfirmDecision.ALLOWED
            }
        }
        val env = com.mengpaw.kernel.harness.HarnessEnv(
            fileSystem = com.mengpaw.kernel.harness.JvmHarnessFileSystem,
            paths = com.mengpaw.kernel.harness.BaseDirPathResolver(tmp),
            confirmGate = gate
        )

        var turn = 0
        val llm = object : LlmProvider {
            override suspend fun complete(prompt: String): String = respond()
            override suspend fun completeWithMessages(messages: List<Map<String, String>>): String = respond()
            override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
                respond().also { onToken(it) }
            override fun info() = ProviderInfo("mock", "confirm-gate", ProviderType.LOCAL)
            override fun close() {}
            fun respond(): String = when (turn++) {
                0 -> """
                    Thought: 需要清空剪贴板 (HIGH 级, 必经确认门)。
                    Action: clipboard.clear
                    Action Input: {"reason": "清空剪贴板以免敏感内容泄露"}
                """.trimIndent()
                else -> "Final Answer: 清理完成。"
            }
        }
        val engine = AgentEngine(llmProvider = llm, sessionManager = SessionManager(), harnessEnv = env)
        val result = engine.run("确认门接线测试", maxSteps = 3)

        assertEquals("清理完成。", result)
        assertTrue("注入的确认门必须被调用 (高危工具门禁未接线)", asked >= 1)
        val history = engine.getSessionManager().getHistory(engine.currentConversationId()!!).joinToString("\n") { it.content }
        assertFalse("ALLOWED 不应产生拒绝文案: $history", history.contains("用户拒绝了高危操作"))
    }

    @Test
    fun `注入的确认门拒绝时工具不执行`() = runBlocking {
        val tmp = System.getProperty("java.io.tmpdir") + "/mengpaw_confirm_deny_" + System.nanoTime()
        DataPaths.initialize(tmp)
        java.io.File(tmp, "Agent文档/MengPaw/memory").mkdirs()
        val memoryFile = java.io.File(tmp, "Agent文档/MengPaw/memory/memory.md")
        memoryFile.writeText("- [20260801_000000] 临时记忆条目\n")
        com.mengpaw.kernel.security.AgentPermissionStore.resetForTest(java.io.File(tmp, "perm.json"))
        com.mengpaw.kernel.security.AgentPermissionStore.setLevel(
            "MengPaw", com.mengpaw.kernel.security.AgentPermissionLevel.TRUSTED)

        // 计数式拒绝门 — 既验证"被调用"(接线生效), 又保持 fail-closed 语义
        var asked = 0
        val countingDeny = object : com.mengpaw.kernel.harness.HarnessConfirmGate {
            override suspend fun request(
                command: String, reason: String?, riskLabel: String, timeoutMs: Long
            ): com.mengpaw.kernel.harness.ConfirmDecision {
                asked++
                return com.mengpaw.kernel.harness.DenyAllConfirmGate.request(command, reason, riskLabel, timeoutMs)
            }
        }
        val env = com.mengpaw.kernel.harness.HarnessEnv(
            fileSystem = com.mengpaw.kernel.harness.JvmHarnessFileSystem,
            paths = com.mengpaw.kernel.harness.BaseDirPathResolver(tmp),
            // 无人可问 → 安全默认必须不放行
            confirmGate = countingDeny
        )

        var turn = 0
        val llm = object : LlmProvider {
            override suspend fun complete(prompt: String): String = respond()
            override suspend fun completeWithMessages(messages: List<Map<String, String>>): String = respond()
            override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
                respond().also { onToken(it) }
            override fun info() = ProviderInfo("mock", "confirm-deny", ProviderType.LOCAL)
            override fun close() {}
            fun respond(): String = when (turn++) {
                0 -> """
                    Thought: 需要清空剪贴板 (HIGH 级, 必经确认门)。
                    Action: clipboard.clear
                    Action Input: {"reason": "清空剪贴板以免敏感内容泄露"}
                """.trimIndent()
                else -> "Final Answer: 未能清理。"
            }
        }
        val engine = AgentEngine(llmProvider = llm, sessionManager = SessionManager(), harnessEnv = env)
        val result = engine.run("确认门拒绝测试", maxSteps = 3)

        assertEquals("未能清理。", result)
        val history = engine.getSessionManager().getHistory(engine.currentConversationId()!!).joinToString("\n") { it.content }
        assertTrue("NO_LISTENER 必须按拒绝处理: $history", history.contains("用户拒绝了高危操作"))
        assertTrue("确认门必须被调用 (接线生效)", asked >= 1)
    }

}
