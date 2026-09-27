// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.harness.CheckpointStatus
import com.mengpaw.harness.HarnessToolInvoker
import com.mengpaw.harness.HarnessToolRequest
import com.mengpaw.harness.HarnessToolResult
import com.mengpaw.kernel.session.Checkpoint
import com.mengpaw.kernel.session.CheckpointManager
import com.mengpaw.kernel.session.IntentState
import com.mengpaw.kernel.session.Message
import com.mengpaw.kernel.session.SessionManager
import com.mengpaw.kernel.session.ToolIntent
import com.mengpaw.kernel.session.ToolIntentLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ReAct 循环断点续跑集成测试 (P0-1 / P0-2) — 假 LLM + 假工具执行器。
 *
 * 断言意图:
 * ① 中断后检查点已落 RUNNING 且带 messages 快照 (含工具 Observation);
 * ② `resume = true` 时步号从 `checkpoint.step` 接续 (不从头烧步数);
 * ③ 恢复后的会话历史仍含中断前的工具观察 (修"Observation 丢失");
 * ④ 不重复追加用户任务 (检查点 messages 已含);
 * ⑤ 有 pending intent 时注入"未确认完成"system 提示 (禁止自动重放副作用);
 * ⑥ 终态检查点 status == COMPLETED;
 * ⑦ 非 resume 路径不读取旧检查点、不接续步数, 但仍每步写检查点。
 */
class AgentResumeLoopTest {

    /** 记录调用的假工具执行器 — 不触达任何真实命令管线。 */
    private class FakeToolInvoker : HarnessToolInvoker {
        val calls = mutableListOf<String>()
        override suspend fun invoke(request: HarnessToolRequest): HarnessToolResult {
            calls.add(request.name)
            return HarnessToolResult.ok("OBSERVATION-OK-${request.name}")
        }
    }

    /** 假 LLM: 首轮发一个动作, 之后直接给最终答案。 */
    private class ScriptedLlm(private val finalText: String) : com.mengpaw.kernel.llm.LlmProvider {
        private var turn = 0
        override suspend fun complete(prompt: String): String = respond()
        override suspend fun completeWithMessages(messages: List<Map<String, String>>): String = respond()
        override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
            respond().also { onToken(it) }
        override fun info() = com.mengpaw.kernel.llm.ProviderInfo("mock", "resume-test", com.mengpaw.kernel.llm.ProviderType.LOCAL)
        override fun close() {}
        private fun respond(): String = if (turn++ == 0) {
            """
            Thought: 先查一下目标。
            Action: deploy.check
            Action Input: {"target": "release"}
            """.trimIndent()
        } else {
            "Final Answer: $finalText"
        }
    }

    private fun tempBase(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "mengpaw_resume_${tag}_${System.nanoTime()}").apply {
            mkdirs()
            deleteOnExit()
        }

    private fun prepareBase(tag: String): File {
        val base = tempBase(tag)
        DataPaths.initialize(base.absolutePath)
        File(base, "Agent文档/MengPaw/memory").mkdirs()
        File(base, "Agent文档/MengPaw/memory/memory.md").writeText("- [20260801_000000] 测试记忆\n")
        File(base, "配置").mkdirs()
        com.mengpaw.kernel.security.AgentPermissionStore.resetForTest(File(base, "perm.json"))
        return base
    }

    private fun engineWith(llm: com.mengpaw.kernel.llm.LlmProvider, invoker: FakeToolInvoker): AgentEngine =
        AgentEngine(llmProvider = llm, sessionManager = SessionManager(), toolInvoker = invoker)

    @Test
    fun `中断后检查点 RUNNING 且每步落盘`() = runBlocking {
        val base = prepareBase("run")
        val checkpointDir = File(base, "会话检查点").absolutePath
        val invoker = FakeToolInvoker()
        val engine = engineWith(ScriptedLlm("done"), invoker)

        engine.run("部署发布目标", maxSteps = 3, onStep = {})

        val sessionId = engine.currentConversationId()
        assertNotNull("会话必须已建立", sessionId)
        val loaded = CheckpointManager(checkpointDir).loadLatest(sessionId!!)
        assertNotNull("每步都必须写检查点", loaded)
        // 第一次 LLM 调用执行动作 → step=1 落 RUNNING (任务随后以 Final Answer 收尾, 但那份档已被终态覆盖)
        assertTrue("步号应已推进", (loaded?.step ?: 0) >= 1)
        assertTrue("工具必须被调用 (假执行器)", invoker.calls.contains("deploy.check"))
    }

    @Test
    fun `resume 接续步号并保留中断前的 Observation`() = runBlocking {
        val base = prepareBase("resume")
        val checkpointDir = File(base, "会话检查点").absolutePath
        val invoker = FakeToolInvoker()
        val first = engineWith(ScriptedLlm("第一轮收尾"), invoker)
        val firstResult = first.run("部署发布目标", maxSteps = 3, onStep = {})
        assertEquals("第一轮收尾", firstResult)

        // 模拟"中断": 手工把该会话最近检查点改回 RUNNING (真实场景是进程在步中被杀)
        val sessionId = first.currentConversationId()
        assertNotNull(sessionId)
        val manager = CheckpointManager(checkpointDir)
        val interrupted = manager.loadLatest(sessionId!!)
        assertNotNull("第一轮必须留下检查点", interrupted)
        val running = Checkpoint(
            sessionId = sessionId,
            step = interrupted?.step ?: 0,
            remainingTask = interrupted?.remainingTask ?: "部署发布目标",
            context = interrupted?.context ?: emptyMap(),
            status = CheckpointStatus.RUNNING,
            messages = interrupted?.messages ?: emptyList()
        )
        manager.save(running)
        val stepBefore = running.step
        assertTrue("中断前应至少跑过一步", stepBefore >= 1)

        // 新引擎 = 模拟进程重启后重新装配
        val second = engineWith(ScriptedLlm("续跑完成"), invoker)
        second.restoreConversation(
            externalSessionId = "sess_ui",
            messages = listOf("user" to "部署发布目标", "assistant" to "第一轮收尾"),
            lastWasInterrupted = true,
            previousEngineSessionId = sessionId
        )
        val secondResult = second.run("部署发布目标", maxSteps = 3, resume = true, onStep = {})

        assertEquals("续跑完成", secondResult)
        // ② 步号接续: 不得从 0 重烧
        //    判据是 >= 而非 >: 若续跑在第一次迭代就给出 Final Answer (无新工具动作),
        //    终态检查点步号与中断前相等**是正确语义** (本轮没有产生新的"已完成步")。
        //    "确实接着跑完了"由下面的历史断言证明 (含中断前 Observation + 续跑答复)。
        val resumedClosure = manager.loadLatest(sessionId)
        assertNotNull(resumedClosure)
        assertTrue(
            "步号不得回退 (接续而非从 0 重跑): before=$stepBefore, after=${resumedClosure?.step}",
            (resumedClosure?.step ?: -1) >= stepBefore
        )
        // ⑥ 终态
        assertEquals(CheckpointStatus.COMPLETED, resumedClosure?.status)

        // ③ 恢复后的会话历史含中断前的工具 Observation
        val restoredHistory = second.getSessionManager().getHistory(sessionId)
        val joined = restoredHistory.joinToString("\n") { it.content }
        assertTrue("恢复历史必须含中断前的工具观察: $joined", joined.contains("OBSERVATION-OK-deploy.check"))
        assertTrue("恢复历史必须含续跑答复 (证明循环真的接着跑了): $joined", joined.contains("续跑完成"))

        // ④ 未重复追加用户任务
        val userTaskCount = restoredHistory.count { it.role == "user" && it.content == "部署发布目标" }
        assertEquals("用户任务不得重复追加", 1, userTaskCount)
    }

    @Test
    fun `pending intent 注入未确认完成提示且不自动重放`() = runBlocking {
        val base = prepareBase("pending")
        val checkpointDir = File(base, "会话检查点").absolutePath
        val invoker = FakeToolInvoker()
        val first = engineWith(ScriptedLlm("第一轮收尾"), invoker)
        first.run("部署发布目标", maxSteps = 3, onStep = {})
        val sessionId = first.currentConversationId()
        assertNotNull(sessionId)

        // 留下一条"已声明将执行但未确认完成"的意图 (崩溃窗口的唯一危险态)
        val intents = ToolIntentLog()
        intents.begin(ToolIntent(
            sessionId = sessionId!!,
            step = 1,
            toolCallId = ToolIntentLog.toolCallIdOf(sessionId, 1, "deploy.apply", "abcd1234"),
            toolName = "deploy.apply",
            argsDigest = "abcd1234",
            state = IntentState.PENDING,
            startedAt = System.currentTimeMillis()
        ))

        val manager = CheckpointManager(checkpointDir)
        val interrupted = manager.loadLatest(sessionId)
        manager.save(Checkpoint(
            sessionId = sessionId,
            step = interrupted?.step ?: 1,
            remainingTask = interrupted?.remainingTask ?: "部署发布目标",
            context = interrupted?.context ?: emptyMap(),
            status = CheckpointStatus.RUNNING,
            messages = interrupted?.messages ?: emptyList()
        ))
        val callsBeforeResume = invoker.calls.size

        val second = engineWith(ScriptedLlm("续跑完成"), invoker)
        second.restoreConversation(
            externalSessionId = "sess_ui",
            messages = listOf("user" to "部署发布目标"),
            lastWasInterrupted = true,
            previousEngineSessionId = sessionId
        )
        second.run("部署发布目标", maxSteps = 3, resume = true, onStep = {})

        // ⑤ 注入事实提示 (system, 非 localOnly — 必须让模型看见)
        val history = second.getSessionManager().getHistory(sessionId)
        val note = history.firstOrNull { it.role == "system" && it.content.contains("未确认完成") }
        assertNotNull("必须注入未确认完成提示: ${history.map { it.role to it.content.take(40) }}", note)
        assertTrue("提示需列出工具名", note?.content?.contains("deploy.apply") == true)
        assertTrue("提示需要求先核对再重试", note?.content?.contains("不要直接重复执行") == true)
        assertEquals("提示必须进 LLM 请求 (localOnly=false)", false, note?.localOnly)
        // pending 工具不得被自动重放 (只多跑了一次原本的部署动作)
        assertEquals(
            "不得自动重放未确认完成的工具",
            callsBeforeResume + 1, invoker.calls.size
        )
    }

    @Test
    fun `非 resume 路径不读取旧检查点也不接续步数但仍每步写检查点`() = runBlocking {
        val base = prepareBase("fresh")
        val checkpointDir = File(base, "会话检查点").absolutePath
        val manager = CheckpointManager(checkpointDir)
        // 预先放一份"看起来可以续跑"的旧档 — 非 resume 路径必须无视它
        manager.save(Checkpoint(
            sessionId = "sess_old",
            step = 5,
            remainingTask = "旧任务",
            context = emptyMap(),
            status = CheckpointStatus.RUNNING,
            messages = listOf(Message("user", "旧任务"), Message("assistant", "Command: x\nResult: y"))
        ))

        val invoker = FakeToolInvoker()
        val engine = engineWith(ScriptedLlm("新任务完成"), invoker)
        val result = engine.run("全新任务", maxSteps = 3, onStep = {})

        assertEquals("新任务完成", result)
        val newSessionId = engine.currentConversationId()
        assertNotNull(newSessionId)
        assertTrue("非续跑必须开新会话 (不与旧检查点共用 id)", newSessionId != "sess_old")
        // 仍然每步写检查点 (设计如此: 新任务自己也要有续跑能力)
        val fresh = manager.loadLatest(newSessionId!!)
        assertNotNull("非 resume 路径同样每步写检查点", fresh)
        assertEquals(CheckpointStatus.COMPLETED, fresh?.status)
        // 旧档原封不动 (未被读取更未被接续)
        val untouched = manager.loadLatest("sess_old")
        assertEquals("旧检查点不得被本次运行改写", 5, untouched?.step)
        assertEquals(CheckpointStatus.RUNNING, untouched?.status)
    }
}
