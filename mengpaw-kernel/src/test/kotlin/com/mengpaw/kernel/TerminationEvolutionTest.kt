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

class TerminationEvolutionTest {

    private val mockLlm = MockLlmProvider()

    private val sessionManager = SessionManager()

    private val engine = AgentEngine(
        llmProvider = mockLlm,
        sessionManager = sessionManager
    )

    @Test
    fun `empty LLM response is retried once then succeeds`() = runBlocking {
        // v0.28.7: DeepSeek 偶发空流 (S-DONE len=0) → 自动重试一次, 不写空白 assistant 消息
        mockLlm.responseQueue.add("")
        mockLlm.responseQueue.add("Final Answer: Retried successfully.")
        val result = engine.run("Empty retry test", maxSteps = 3)
        assertEquals("Retried successfully.", result)
        // 历史中无空白 assistant 消息 (否则完整性 latch 锁死后续轮次)
        val sessionId = engine.currentConversationId()
        val history = sessionManager.getHistory(sessionId!!)
        assertFalse("不应有空白 assistant 消息", history.any { it.role == "assistant" && it.content.isBlank() })
        assertTrue("完整性检查应通过", sessionManager.checkSessionIntegrity(sessionId))
    }

    @Test
    fun `persistently empty LLM response yields error not blank message`() = runBlocking {
        // 两次空响应 → 明确报错 (非空白), 不入库空白 assistant 消息
        mockLlm.responseQueue.add("")
        mockLlm.responseQueue.add("")
        val result = engine.run("Empty error test", maxSteps = 3)
        assertTrue("应返回空响应错误: $result", result.contains("空响应") || result.contains("empty response"))
        val sessionId = engine.currentConversationId()
        val history = sessionManager.getHistory(sessionId!!)
        assertFalse("不应有空白 assistant 消息", history.any { it.role == "assistant" && it.content.isBlank() })
        assertTrue("完整性检查应通过", sessionManager.checkSessionIntegrity(sessionId))
    }

    @Test
    fun `run handles max steps`() = runBlocking {
        // LLM never gives final answer, just keeps acting
        mockLlm.nextResponse = """
            Thought: Let me check something.
            Action: self.status
            Action Input: {}
        """.trimIndent()

        val result = engine.run("Infinite task", maxSteps = 2)
        assertTrue(result.contains("已达到最大步数") || result.contains("Max steps"))
    }

    // ── v0.44 静默分支进化: 幻觉即时门禁已移除 (幻觉偶发, 由分支会话沉淀) ──

    @Test
    fun `hallucinated final answer passes through since gate removed`() = runBlocking {
        // 失败 → 声称成功 (幻觉) → v0.44 门禁移除 → 答案直接放行, 不注入"内部反馈" (幻觉率统计仍保留)
        val tmp = System.getProperty("java.io.tmpdir") + "/mengpaw_gate_removed_" + System.nanoTime()
        com.mengpaw.kernel.DataPaths.initialize(tmp)
        val agentDir = java.io.File(tmp, "Agent文档/MengPaw")
        agentDir.mkdirs()
        val receivedByLlm = mutableListOf<List<Map<String, String>>>()
        var turn = 0
        val llm = object : LlmProvider {
            override suspend fun complete(prompt: String): String = respond()
            override suspend fun completeWithMessages(messages: List<Map<String, String>>): String {
                receivedByLlm.add(messages)
                return respond()
            }
            override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
                respond().also { onToken(it) }
            override fun info() = ProviderInfo("mock", "no-gate", ProviderType.LOCAL)
            override fun close() {}
            fun respond(): String = when (turn++) {
                0 -> """
                    Thought: 读取文件。
                    Action: cat
                    Action Input: missing.md
                """.trimIndent()
                else -> "Final Answer: 文件已成功读取, 内容完整。"
            }
        }
        val sm2 = SessionManager()
        val engine2 = AgentEngine(llmProvider = llm, sessionManager = sm2)
        val result = engine2.run("静默门禁测试", maxSteps = 5)
        assertTrue("幻觉答案现直接放行: $result", result.contains("已成功读取"))
        val injected = receivedByLlm.any { msgs ->
            msgs.any { it["role"] == "system" && it["content"].orEmpty().contains("内部反馈") }
        }
        assertFalse("不应再注入内部反馈 (门禁移除)", injected)
    }

    @Test
    fun `failure mitigated by successful retry is not gated`() = runBlocking {
        // 第一轮缺 reason 被门禁拒绝, 第二轮同一命令行补 reason 成功 → 失败已弥补
        // → 最终回答无需复述历史失败, 门禁放行 (同参数才豁免; 换参数 = 不同操作, 不豁免)
        // v0.34.3: agent.memory.mid.rm 为 MID — TRUSTED 权限避免分级拦截干扰;
        // 参数无空格无 flag, 无 reason 与带 reason 两种展开形态的 commandLine 一致,
        // 保证"同命令重试成功"豁免键匹配 (agent.rm 的 force flag 形态不一致会破坏豁免)
        val tmp = System.getProperty("java.io.tmpdir") + "/mengpaw_gate_mitigated_" + System.nanoTime()
        com.mengpaw.kernel.DataPaths.initialize(tmp)
        val agentDir = java.io.File(tmp, "Agent文档/MengPaw")
        agentDir.mkdirs()
        val midFile = java.io.File(agentDir, "memory/memory_2026-08-09.md")
        midFile.parentFile.mkdirs()
        midFile.writeText("\n## 14:30:15\n\n测试条目\n")  // mid 条目格式: ## HH:mm:ss
        com.mengpaw.kernel.security.AgentPermissionStore.resetForTest(java.io.File(tmp, "perm.json"))
        com.mengpaw.kernel.security.AgentPermissionStore.setLevel("MengPaw", com.mengpaw.kernel.security.AgentPermissionLevel.TRUSTED)
        val receivedByLlm = mutableListOf<List<Map<String, String>>>()
        var turn = 0
        val llm = object : LlmProvider {
            override suspend fun complete(prompt: String): String = respond()
            override suspend fun completeWithMessages(messages: List<Map<String, String>>): String {
                receivedByLlm.add(messages)
                return respond()
            }
            override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
                respond().also { onToken(it) }
            override fun info() = ProviderInfo("mock", "mitigated-gate", ProviderType.LOCAL)
            override fun close() {}
            fun respond(): String = when (turn++) {
                0 -> """
                    Thought: 清理中期记忆条目。
                    Action: agent.memory.mid.rm
                    Action Input: {"date": "2026-08-09", "timestamp": "14:30:15"}
                """.trimIndent()
                1 -> """
                    Thought: 需要补 reason 重试。
                    Action: agent.memory.mid.rm
                    Action Input: {"date": "2026-08-09", "timestamp": "14:30:15", "reason": "清理临时条目"}
                """.trimIndent()
                else -> "Final Answer: 清理完成。"
            }
        }
        val sm2 = SessionManager()
        val engine2 = AgentEngine(llmProvider = llm, sessionManager = sm2)
        val result = engine2.run("弥补豁免测试", maxSteps = 5)
        assertEquals("失败已被成功弥补, 门禁应放行: $result", "清理完成。", result)
        assertEquals("不应触发门禁拒绝 (LLM 仅 3 轮: 失败/重试/收尾)", 3, receivedByLlm.size)
    }

    @Test
    fun `stubborn hallucinated final answer passes through since gate removed`() = runBlocking {
        // v0.44: 幻觉即时门禁移除 — 顽固幻觉答案不再被拒绝到步数上限, 直接放行; 由静默分支进化沉淀。
        val tmp = System.getProperty("java.io.tmpdir") + "/mengpaw_gate_stubborn_" + System.nanoTime()
        com.mengpaw.kernel.DataPaths.initialize(tmp)
        val agentDir = java.io.File(tmp, "Agent文档/MengPaw")
        agentDir.mkdirs()
        val receivedByLlm = mutableListOf<List<Map<String, String>>>()
        var turn = 0
        val llm = object : LlmProvider {
            override suspend fun complete(prompt: String): String = respond()
            override suspend fun completeWithMessages(messages: List<Map<String, String>>): String {
                receivedByLlm.add(messages)
                return respond()
            }
            override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
                respond().also { onToken(it) }
            override fun info() = ProviderInfo("mock", "no-gate-stubborn", ProviderType.LOCAL)
            override fun close() {}
            fun respond(): String = when (turn++) {
                0 -> """
                    Thought: 读取文件。
                    Action: cat
                    Action Input: missing.md
                """.trimIndent()
                else -> "Final Answer: 文件已成功读取, 内容完整。"
            }
        }
        val sm2 = SessionManager()
        val engine2 = AgentEngine(llmProvider = llm, sessionManager = sm2)
        val result = engine2.run("顽固幻觉测试", maxSteps = 3)
        assertTrue("幻觉答案现在直接放行: $result", result.contains("已成功读取"))
        // 1 次 Action + 1 次 Final Answer = 2 轮 (不再拒绝烧到步数上限)
        assertEquals("幻觉答案应一轮放行 (LLM 共 2 轮)", 2, receivedByLlm.size)
    }

    @Test
    fun `retry loop injects stop directive on third same-error failure`() = runBlocking {
        // 同命令同错误码失败 3 次 (回合内空转) → 第 3 次 Observation 注入停指令 (对齐 QwenPaw),
        // Agent 收到后转向如实汇报 → 门禁放行; 指令只注入一次不刷屏
        val tmp = System.getProperty("java.io.tmpdir") + "/mengpaw_gate_retryloop_" + System.nanoTime()
        com.mengpaw.kernel.DataPaths.initialize(tmp)
        val agentDir = java.io.File(tmp, "Agent文档/MengPaw")
        agentDir.mkdirs()
        val receivedByLlm = mutableListOf<List<Map<String, String>>>()
        var turn = 0
        val llm = object : LlmProvider {
            override suspend fun complete(prompt: String): String = respond()
            override suspend fun completeWithMessages(messages: List<Map<String, String>>): String {
                receivedByLlm.add(messages)
                return respond()
            }
            override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
                respond().also { onToken(it) }
            override fun info() = ProviderInfo("mock", "retry-loop", ProviderType.LOCAL)
            override fun close() {}
            fun respond(): String = when (turn++) {
                // 前 3 轮同一命令同一错误反复失败 (空转)
                0, 1, 2 -> """
                    Thought: 读取文件。
                    Action: cat
                    Action Input: missing.md
                """.trimIndent()
                // 收到停指令后转向: 不再重试, 如实汇报
                else -> "Final Answer: 无法读取 missing.md, 文件不存在, 任务无法完成。"
            }
        }
        val sm2 = SessionManager()
        val engine2 = AgentEngine(llmProvider = llm, sessionManager = sm2)
        val result = engine2.run("重试循环测试", maxSteps = 6)
        assertEquals("应转向如实汇报: $result", "无法读取 missing.md, 文件不存在, 任务无法完成。", result)
        val history = sm2.getHistory(engine2.currentConversationId()!!).joinToString("\n") { it.content }
        assertTrue("第 3 次失败应注入停指令: $history", history.contains("重试循环"))
        assertTrue("停指令应要求停止重试: $history", history.contains("停止重试"))
        assertTrue("停指令应只出现一次 (防刷屏)", Regex("检测到重试循环").findAll(history).count() == 1)
        assertEquals("应共 4 轮 LLM 调用 (3 次失败 + 1 次收尾)", 4, receivedByLlm.size)
    }

    @Test
    fun `max steps termination records clipped context into evolution archive`() = runBlocking {
        // 步数上限截断 (无 Final Answer) → 进化模块介入: 剪取会话上下文片段写入失败模式库
        com.mengpaw.kernel.evolution.EvolutionStore.resetFailuresForTest()
        val tmp = System.getProperty("java.io.tmpdir") + "/mengpaw_term_e2e_" + System.nanoTime()
        com.mengpaw.kernel.DataPaths.initialize(tmp)
        val agentDir = java.io.File(tmp, "Agent文档/MengPaw")
        agentDir.mkdirs()
        var turn = 0
        val llm = object : LlmProvider {
            override suspend fun complete(prompt: String): String = respond()
            override suspend fun completeWithMessages(messages: List<Map<String, String>>): String = respond()
            override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
                respond().also { onToken(it) }
            override fun info() = ProviderInfo("mock", "term-e2e", ProviderType.LOCAL)
            override fun close() {}
            fun respond(): String = when (turn++) {
                0 -> """
                    Thought: 查系统状态。
                    Action: self.status
                    Action Input: {}
                """.trimIndent()
                // 一直行动不给 Final Answer → 步数上限截断
                else -> """
                    Thought: 再查一次状态。
                    Action: self.status
                    Action Input: {}
                """.trimIndent()
            }
        }
        val sm2 = SessionManager()
        val engine2 = AgentEngine(llmProvider = llm, sessionManager = sm2)
        val result = engine2.run("步数上限测试", maxSteps = 2)
        assertTrue("应由步数上限终止: $result", result.contains("最大步数") || result.contains("Max steps"))
        val failures = java.io.File(com.mengpaw.kernel.DataPaths.evolutionFailuresFile("MengPaw"))
        assertTrue("失败档案应落盘", failures.exists())
        val text = failures.readText()
        assertTrue("应记录截断原因: $text", text.contains("[截断: max_steps]"))
        assertTrue("应剪取上下文片段 (最近 Action): $text", text.contains("self.status"))
    }

    @Test
    fun `empty response termination records into evolution archive`() = runBlocking {
        // 模型层失败 (连续空响应) → 进化介入: 记录 empty_response 截断
        com.mengpaw.kernel.evolution.EvolutionStore.resetFailuresForTest()
        val tmp = System.getProperty("java.io.tmpdir") + "/mengpaw_empty_evo_" + System.nanoTime()
        com.mengpaw.kernel.DataPaths.initialize(tmp)
        val agentDir = java.io.File(tmp, "Agent文档/MengPaw")
        agentDir.mkdirs()
        val llm = object : LlmProvider {
            override suspend fun complete(prompt: String): String = ""
            override suspend fun completeWithMessages(messages: List<Map<String, String>>): String = ""
            override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String = ""
            override fun info() = ProviderInfo("mock", "empty-evo", ProviderType.LOCAL)
            override fun close() {}
        }
        val sm2 = SessionManager()
        val engine2 = AgentEngine(llmProvider = llm, sessionManager = sm2)
        val result = engine2.run("空响应进化测试", maxSteps = 3)
        assertTrue("应返回空响应错误: $result", result.contains("空响应") || result.contains("empty response"))
        val failures = java.io.File(com.mengpaw.kernel.DataPaths.evolutionFailuresFile("MengPaw"))
        assertTrue("失败档案应落盘", failures.exists())
        assertTrue("应记录空响应截断: ${failures.readText()}", failures.readText().contains("[截断: empty_response]"))
    }

    @Test
    fun `incomplete action termination records into evolution archive`() = runBlocking {
        // 只思考不行动 (连续 needsContinue) = 完成度低 → 进化介入: 记录 incomplete_action
        com.mengpaw.kernel.evolution.EvolutionStore.resetFailuresForTest()
        val tmp = System.getProperty("java.io.tmpdir") + "/mengpaw_incomplete_evo_" + System.nanoTime()
        com.mengpaw.kernel.DataPaths.initialize(tmp)
        val agentDir = java.io.File(tmp, "Agent文档/MengPaw")
        agentDir.mkdirs()
        val llm = object : LlmProvider {
            override suspend fun complete(prompt: String): String = "Thought: 我在思考如何完成任务。"
            override suspend fun completeWithMessages(messages: List<Map<String, String>>): String =
                "Thought: 我在思考如何完成任务。"
            override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String {
                val r = "Thought: 我在思考如何完成任务。"
                r.forEach { onToken(it.toString()) }
                return r
            }
            override fun info() = ProviderInfo("mock", "incomplete-evo", ProviderType.LOCAL)
            override fun close() {}
        }
        val sm2 = SessionManager()
        val engine2 = AgentEngine(llmProvider = llm, sessionManager = sm2)
        val result = engine2.run("不行动测试", maxSteps = 3)
        assertTrue("应强制收尾: $result", result.contains("最大步数") || result.contains("Max steps"))
        val failures = java.io.File(com.mengpaw.kernel.DataPaths.evolutionFailuresFile("MengPaw"))
        assertTrue("失败档案应落盘", failures.exists())
        val text = failures.readText()
        assertTrue("应记录不行动截断: $text", text.contains("[截断: incomplete_action]"))
        assertTrue("应剪取思考上下文: $text", text.contains("思考"))
    }

    // ── Mock LLM Provider ────────────────────────────────────────────────

}
