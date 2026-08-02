// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.concise

import com.mengpaw.kernel.AgentEngine
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.ProviderInfo
import com.mengpaw.kernel.llm.ProviderType
import com.mengpaw.kernel.plugin.PluginManager
import com.mengpaw.kernel.session.SessionManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 言简意赅 上下文测试 — mission 子 Agent 链路（拆解→worker→verifier→合成）
 * 在 middleware 变换后的提示词下运行，确保不干扰生成、不破坏解析。
 *
 * 断言均通过 calls 记录（每次发给 LLM 的完整 prompt）+ 按序回放响应。
 */
class ConciseMissionTest {

    /** 按序返回响应（超出循环用最后一个），记录每次 prompt。 */
    private class ScriptedLlmProvider(
        private val responses: List<String>
    ) : LlmProvider {
        val calls = CopyOnWriteArrayList<String>()
        private val idx = AtomicInteger(0)

        override suspend fun complete(prompt: String): String {
            calls.add(prompt)
            return responses[idx.getAndIncrement().coerceAtMost(responses.lastIndex)]
        }

        override suspend fun completeWithMessages(messages: List<Map<String, String>>): String =
            complete(messages.joinToString("\n") { "${it["role"]}:${it["content"]}" })

        override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
            complete(prompt).also { onToken(it) }

        override fun info(): ProviderInfo = ProviderInfo("mock", "concise", ProviderType.LOCAL)
        override fun close() {}
    }

    @Before
    fun setUp() {
        runBlocking {
            val pm = PluginManager.globalInstance
            if (pm.get(ConcisePlugin.PLUGIN_ID) == null) {
                pm.install(ConcisePlugin())
            }
            pm.activate(ConcisePlugin.PLUGIN_ID)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            try { PluginManager.globalInstance.uninstall(ConcisePlugin.PLUGIN_ID) } catch (_: Exception) {}
        }
    }

    private fun engineWith(provider: LlmProvider): AgentEngine = AgentEngine(
        llmProvider = provider,
        middleware = ConciseMiddleware,
        sessionManager = SessionManager()
    ).also {
        // 触发 refreshSystemPrompt() — middleware 变换只在该路径生效
        // （构造时 LlmRequestBuilder 直接用原始 buildSystemPrompt，不经 middleware）
        it.setAgentIdentity("MengPaw", null, "mock")
    }

    private fun runMission(engine: AgentEngine, task: String) = runBlocking {
        engine.runWithMission(task = task, onStep = {})
    }

    private val SINGLE_DECOMPOSE = "[{\"id\":\"t1\",\"desc\":\"子任务A\",\"criteria\":\"完成A\"}]"

    // ── 用例 ─────────────────────────────────────────────────────────

    @Test
    fun `worker 纯自然语言无标记输出被当最终答案`() {
        val provider = ScriptedLlmProvider(listOf(
            SINGLE_DECOMPOSE,                       // 拆解
            "设备正常，共 3 个文件",                   // worker 纯文本（无 Thought/Action 标记）
            "VERDICT: PASS\nANALYSIS: 达标",        // verifier
            "综合报告：设备正常，共 3 个文件"           // 合成
        ))
        val report = runMission(engineWith(provider), "检查设备状态")
        assertTrue("报告应含合成文本: $report", report.contains("综合报告：设备正常，共 3 个文件"))
        // worker 只被调 1 次 — 纯文本被当最终答案，未误判为 needsContinue 循环
        // （合成调用也含子任务描述，需排除 "Synthesize"）
        assertEquals(1, provider.calls.count { it.contains("子任务A") && !it.contains("Synthesize") })
        // middleware 变换确实生效（发给 worker 的完整消息含反 Markdown 约束）
        assertTrue(provider.calls.any { it.contains("回复默认用简洁纯文本") })
    }

    @Test
    fun `worker 带 Markdown 输出原样进入合成不剥离`() {
        val workerOutput = "### 结果\n**重要**：完成 2 项"
        val provider = ScriptedLlmProvider(listOf(
            SINGLE_DECOMPOSE,
            workerOutput,
            "VERDICT: PASS\nANALYSIS: 达标",
            "综合报告：完成"
        ))
        val report = runMission(engineWith(provider), "批量处理")
        assertTrue("报告应含合成文本: $report", report.contains("综合报告：完成"))
        // worker 原文（含 Markdown）完整进入合成输入 — 只降生成概率，不剥内容
        assertTrue("Markdown 内容应原样进入合成: ${provider.calls.last()}",
            provider.calls.last().contains("### 结果") && provider.calls.last().contains("**重要**"))
    }

    @Test
    fun `纯文本输出不触发 verifier 重试`() {
        val provider = ScriptedLlmProvider(listOf(
            SINGLE_DECOMPOSE,
            "设备正常",
            "VERDICT: PASS\nANALYSIS: 达标",
            "综合报告：完成"
        ))
        runMission(engineWith(provider), "检查状态")
        assertEquals("verifier 只应被调 1 次（PASS 不重试）", 1, provider.calls.count { it.contains("VERDICT:") })
    }

    @Test
    fun `拆解 JSON 正常解析为 2 子任务`() {
        val provider = ScriptedLlmProvider(listOf(
            "[{\"id\":\"t1\",\"desc\":\"子任务A\",\"criteria\":\"完成A\"}," +
                "{\"id\":\"t2\",\"desc\":\"子任务B\",\"criteria\":\"完成B\"}]",
            "A完成", "VERDICT: PASS\nANALYSIS: ok",
            "B完成", "VERDICT: PASS\nANALYSIS: ok",
            "综合报告：完成"
        ))
        val report = runMission(engineWith(provider), "两个子任务")
        assertTrue("报告应含合成文本: $report", report.contains("综合报告：完成"))
        assertTrue("两个子任务都应执行", provider.calls.any { it.contains("子任务A") } && provider.calls.any { it.contains("子任务B") })
    }

    @Test
    fun `拆解带 json 代码块仍成功`() {
        val provider = ScriptedLlmProvider(listOf(
            "```json\n$SINGLE_DECOMPOSE\n```",
            "完成",
            "VERDICT: PASS\nANALYSIS: ok",
            "综合报告：完成"
        ))
        val report = runMission(engineWith(provider), "带代码块拆解")
        assertTrue("报告应含合成文本: $report", report.contains("综合报告：完成"))
    }
}
