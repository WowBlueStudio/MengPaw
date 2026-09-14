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

class CommandGateTest {

    private val mockLlm = MockLlmProvider()

    private val sessionManager = SessionManager()

    private val engine = AgentEngine(
        llmProvider = mockLlm,
        sessionManager = sessionManager
    )

    @Test
    fun `run executes multiple actions in one step`() = runBlocking {
        // 多 Action 并行执行: 一轮 LLM 输出 2 个 Action → 2 条 Observation → 模型总结
        var turn = 0
        val llm = object : LlmProvider {
            override suspend fun complete(prompt: String): String = respond()
            override suspend fun completeWithMessages(messages: List<Map<String, String>>): String = respond()
            override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
                respond().also { onToken(it) }
            override fun info() = ProviderInfo("mock", "multi-action", ProviderType.LOCAL)
            override fun close() {}
            fun respond(): String = when (turn++) {
                0 -> """
                    Thought: 查状态并查版本。
                    Action: self.status
                    Action Input: {}
                    Action: self.version
                    Action Input: {}
                """.trimIndent()
                else -> "Final Answer: 完成"
            }
        }
        val sm2 = SessionManager()
        val engine2 = AgentEngine(llmProvider = llm, sessionManager = sm2)
        val result = engine2.run("并行任务", maxSteps = 3)
        assertEquals("完成", result)
        // 两条 Command 观察都进入会话（合并为一条 assistant 消息，含 2 个 Command 块）
        val sessionId = engine2.currentConversationId()
        assertNotNull("会话应存在", sessionId)
        val commands = sm2.sessions.value.values.first().messages
            .sumOf { Regex("(?m)^Command:").findAll(it.content).count() }
        assertEquals("应产生 2 条 Command 观察", 2, commands)
    }

    @Test
    fun `json multi-key action input blocked by param format gate`() = runBlocking {
        // plugin.install 已是高危命令 (v0.34.1 HighRiskCommandGate) — 多键 JSON 无 reason
        // → REASON_REQUIRED 拒绝, 不得执行错位命令 (语义与 formatError 等价: 门卫拦截 + 引导重发)
        var turn = 0
        val llm = object : LlmProvider {
            override suspend fun complete(prompt: String): String = respond()
            override suspend fun completeWithMessages(messages: List<Map<String, String>>): String = respond()
            override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
                respond().also { onToken(it) }
            override fun info() = ProviderInfo("mock", "json-gate", ProviderType.LOCAL)
            override fun close() {}
            fun respond(): String = when (turn++) {
                0 -> """
                    Thought: 安装插件。
                    Action: plugin.install
                    Action Input: {"force": true, "id": "tavily-plugin"}
                """.trimIndent()
                else -> "Final Answer: 插件安装未成功, 参数格式错误。"
            }
        }
        val sm2 = SessionManager()
        val engine2 = AgentEngine(llmProvider = llm, sessionManager = sm2)
        val result = engine2.run("门卫测试", maxSteps = 3)
        assertEquals("插件安装未成功, 参数格式错误。", result)
        val history = sm2.getHistory(engine2.currentConversationId()!!)
        val obs = history.joinToString("\n") { it.content }
        assertTrue("Observation 应含 REASON_REQUIRED: $obs", obs.contains("REASON_REQUIRED"))
        assertTrue("Observation 应展示模型请求的命令", obs.contains("Command: plugin.install true tavily-plugin"))
        assertFalse("不得执行错位命令 (参数被吞): $obs", obs.contains("Plugin not found in marketplace: true"))
        assertFalse("不得执行错位命令 (未知命令): $obs", obs.contains("Unknown command: plugin.install"))
    }

    @Test
    fun `unparseable json action input blocked by param format gate`() = runBlocking {
        // JSON 解析失败 → raw 兜底 → 整个 JSON 串会被当参数 → 门卫同样拦截
        var turn = 0
        val llm = object : LlmProvider {
            override suspend fun complete(prompt: String): String = respond()
            override suspend fun completeWithMessages(messages: List<Map<String, String>>): String = respond()
            override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
                respond().also { onToken(it) }
            override fun info() = ProviderInfo("mock", "json-gate", ProviderType.LOCAL)
            override fun close() {}
            fun respond(): String = when (turn++) {
                0 -> """
                    Thought: 搜索插件。
                    Action: plugin.search
                    Action Input: {"query": "tavily", "force":}
                """.trimIndent()
                else -> "Final Answer: 插件搜索未成功, 参数格式有误。"
            }
        }
        val sm2 = SessionManager()
        val engine2 = AgentEngine(llmProvider = llm, sessionManager = sm2)
        val result = engine2.run("门卫测试", maxSteps = 3)
        assertEquals("插件搜索未成功, 参数格式有误。", result)
        val history = sm2.getHistory(engine2.currentConversationId()!!)
        val obs = history.joinToString("\n") { it.content }
        assertTrue("Observation 应含 PARAM_FORMAT_ERROR: $obs", obs.contains("PARAM_FORMAT_ERROR"))
        assertFalse("不得把整个 JSON 串当参数执行: $obs", obs.contains("搜索 \"{\"query"))
    }

    // ── 高危命令 reason 门禁 (v0.34.1, HighRiskCommandGate) ──

    @Test
    fun `high-risk command with reason passes gate and executes`() = runBlocking {
        // JSON 豁免通道: 高危命令带 reason → 模板展开执行, reason 不进入命令文本
        // v0.36.x 去重: agent.rm 已移除, 改用同属 MID 的 agent.memory.rm 验证 reason 门禁
        val tmp = System.getProperty("java.io.tmpdir") + "/mengpaw_gate_pass_" + System.nanoTime()
        com.mengpaw.kernel.DataPaths.initialize(tmp)
        val agentDir = java.io.File(tmp, "Agent文档/MengPaw")
        agentDir.mkdirs()
        com.mengpaw.kernel.security.AgentPermissionStore.resetForTest(java.io.File(tmp, "perm.json"))
        com.mengpaw.kernel.security.AgentPermissionStore.setLevel("MengPaw", com.mengpaw.kernel.security.AgentPermissionLevel.TRUSTED)
        var turn = 0
        val llm = object : LlmProvider {
            override suspend fun complete(prompt: String): String = respond()
            override suspend fun completeWithMessages(messages: List<Map<String, String>>): String = respond()
            override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
                respond().also { onToken(it) }
            override fun info() = ProviderInfo("mock", "high-risk-gate", ProviderType.LOCAL)
            override fun close() {}
            fun respond(): String = when (turn++) {
                0 -> """
                    Thought: 清理一条临时记忆。
                    Action: agent.memory.rm
                    Action Input: {"timestamp": "20260801_000000", "reason": "清理临时记忆"}
                """.trimIndent()
                else -> "Final Answer: 记忆删除未完成，目标条目不存在。"
            }
        }
        val sm2 = SessionManager()
        val engine2 = AgentEngine(llmProvider = llm, sessionManager = sm2)
        val result = engine2.run("门禁放行测试", maxSteps = 3)
        assertEquals("记忆删除未完成，目标条目不存在。", result)
        val obs = sm2.getHistory(engine2.currentConversationId()!!).joinToString("\n") { it.content }
        // reason 只应出现在模型原始输出 (Action Input, 传参必经之路), 绝不进入执行命令文本
        val commandLines = Regex("Command: agent\\.memory\\.rm[^\n]*").findAll(obs).map { it.value }.toList()
        assertTrue("命令应模板展开执行: $commandLines", commandLines.any { it == "Command: agent.memory.rm 20260801_000000" })
        assertFalse("reason 不得进入命令文本: $commandLines", commandLines.any { it.contains("清理临时记忆") })
        assertFalse("带 reason 不得拒绝: $obs", obs.contains("REASON_REQUIRED"))
    }

    @Test
    fun `high-risk command without reason blocked with REASON_REQUIRED`() = runBlocking {
        // 纯文本形态 (raw 兜底) 无 reason → 硬拒绝 + 引导示例, 不执行
        var turn = 0
        val llm = object : LlmProvider {
            override suspend fun complete(prompt: String): String = respond()
            override suspend fun completeWithMessages(messages: List<Map<String, String>>): String = respond()
            override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
                respond().also { onToken(it) }
            override fun info() = ProviderInfo("mock", "high-risk-gate", ProviderType.LOCAL)
            override fun close() {}
            fun respond(): String = when (turn++) {
                0 -> """
                    Thought: 删除一条临时记忆。
                    Action: agent.memory.rm
                    Action Input: 20260801_000000
                """.trimIndent()
                else -> "Final Answer: 删除未成功, 缺少 reason 参数。"
            }
        }
        val sm2 = SessionManager()
        val engine2 = AgentEngine(llmProvider = llm, sessionManager = sm2)
        val result = engine2.run("门禁拒绝测试", maxSteps = 3)
        assertEquals("删除未成功, 缺少 reason 参数。", result)
        val obs = sm2.getHistory(engine2.currentConversationId()!!).joinToString("\n") { it.content }
        assertTrue("应拒绝并含 REASON_REQUIRED: $obs", obs.contains("REASON_REQUIRED"))
        assertTrue("拒绝文本应含 JSON 示例引导: $obs", obs.contains("\"reason\""))
        assertFalse("不得执行删除: $obs", obs.contains("已删除"))
    }

    @Test
    fun `final answer probe marker is stripped before return`() = runBlocking {
        // P0-2 ③: Final Answer 末尾的 <!--mok--> 探针标记应在返回前剥离, 不污染 UI
        val llm = object : LlmProvider {
            override suspend fun complete(prompt: String): String = respond()
            override suspend fun completeWithMessages(messages: List<Map<String, String>>): String = respond()
            override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
                respond().also { onToken(it) }
            override fun info() = ProviderInfo("mock", "probe-strip", ProviderType.LOCAL)
            override fun close() {}
            fun respond(): String = "Final Answer: 完成\n<!--mok-->"
        }
        val sm2 = SessionManager()
        val engine2 = AgentEngine(llmProvider = llm, sessionManager = sm2)
        val result = engine2.run("探针剥离测试", maxSteps = 3)
        assertEquals("探针标记应剥离", "完成", result)
    }

    // ── 攻击提醒与拉黑闭环 (v0.34.1, ⑦) ──

}
