// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import org.junit.Assert.*
import org.junit.Test

class PromptEngineTest {

    private val engine = PromptEngine()

    @Test
    fun `parse standard react`() {
        val input = """
            Thought: I need to check the file.
            Action: fs.cat
            Action Input: {"path": "/test.txt"}
        """.trimIndent()

        val result = engine.parse(input)
        assertFalse(result.isFinal)
        assertEquals("I need to check the file.", result.thought)
        assertNotNull(result.action)
        assertEquals("fs.cat", result.action?.name)
        assertEquals("/test.txt", result.action?.parameters?.get("path"))
        // 单 Action 向后兼容: actions 列表 = [单 Action]
        assertEquals(1, result.actions.size)
        assertEquals("fs.cat", result.actions.first().name)
    }

    @Test
    fun `parse multiple actions`() {
        val input = """
            Thought: 查状态并列出文件。
            Action: self.status
            Action Input: {}
            Action: agent.ls
            Action Input: {"path": "."}
        """.trimIndent()

        val result = engine.parse(input)
        assertFalse(result.isFinal)
        assertEquals(2, result.actions.size)
        assertEquals("self.status", result.actions[0].name)
        assertEquals("agent.ls", result.actions[1].name)
        assertEquals(".", result.actions[1].parameters["path"])
        // action 字段保留 = 第一个（向后兼容）
        assertEquals("self.status", result.action?.name)
    }

    @Test
    fun `parse multiple actions with trailing final answer executes actions`() {
        // 多 Action + 末尾 Final Answer 属于并行执行形态 — 不吞为最终答案
        val input = """
            Action: self.status
            Action Input: {}
            Action: agent.ls
            Action Input: {"path": "."}
            Final Answer: 完成
        """.trimIndent()

        val result = engine.parse(input)
        assertFalse("多 Action 形态不应被当最终答案", result.isFinal)
        assertEquals(2, result.actions.size)
    }

    @Test
    fun `parse single action with trailing final answer keeps final`() {
        // 单 Action + Final Answer 保持原行为（取答案，不执行 Action）
        val input = """
            Action: self.status
            Action Input: {}
            Final Answer: 设备正常
        """.trimIndent()

        val result = engine.parse(input)
        assertTrue("单 Action + Final Answer 应保持最终答案语义", result.isFinal)
        assertEquals("设备正常", result.thought)
    }

    @Test
    fun `parse multiple actions with json tolerance`() {
        // 第二个 Action Input 非法 JSON → raw 回退（容错）
        val input = """
            Action: self.status
            Action Input: {}
            Action: agent.ls
            Action Input: 直接文本参数
        """.trimIndent()

        val result = engine.parse(input)
        assertEquals(2, result.actions.size)
        assertEquals("直接文本参数", result.actions[1].parameters["raw"])
    }

    @Test
    fun `parse final answer`() {
        val input = """
            Thought: Task is complete.
            Final Answer: The file contains "Hello World"
        """.trimIndent()

        val result = engine.parse(input)
        assertTrue(result.isFinal)
        assertEquals("The file contains \"Hello World\"", result.thought)
        assertNull(result.action)
    }

    @Test
    fun `parse with chinese colon`() {
        val input = "Thought：检查文件\nAction：fs.cat\nAction Input：{}"
        val result = engine.parse(input)
        assertFalse(result.isFinal)
        assertEquals("检查文件", result.thought)
        assertEquals("fs.cat", result.action?.name)
    }

    @Test
    fun `parse lowercase thought`() {
        val input = "thought: analyzing\nAction: self.status\nAction Input: {}"
        val result = engine.parse(input)
        assertEquals("analyzing", result.thought)
        assertEquals("self.status", result.action?.name)
    }

    @Test
    fun `thought without action triggers needsContinue not final`() {
        // v0.15.0: Thought-only no longer treated as final answer.
        // Instead triggers needsContinue → AgentEngine injects continue prompt.
        val input = "Thought: just thinking"
        val result = engine.parse(input)
        assertNull(result.action)
        assertFalse("Thought-only must NOT be final", result.isFinal)
        assertTrue("Thought-only must trigger continue prompt", result.needsContinue)
    }

    @Test
    fun `natural language response without markers treated as final answer`() {
        // DeepSeek-Chat / non-reasoning models may respond in plain text
        val input = "你好！我是AI助手，有什么可以帮助你的吗？"
        val result = engine.parse(input)
        assertNull(result.action)
        assertTrue(result.isFinal)
        assertEquals(input, result.thought)
    }

    @Test
    fun `detect command loop`() {
        // 1st–4th: false (threshold is 5)
        assertFalse(engine.detectLoop("fs.cat /test"))
        assertFalse(engine.detectLoop("fs.cat /test"))
        assertFalse(engine.detectLoop("fs.cat /test"))
        assertFalse(engine.detectLoop("fs.cat /test"))
        assertTrue(engine.detectLoop("fs.cat /test")) // 5th triggers
    }

    @Test
    fun `safe commands never trigger loop detection`() {
        repeat(10) { assertFalse(engine.detectLoop("agent.docs")) }
        repeat(10) { assertFalse(engine.detectLoop("agent.memory test")) }
        repeat(10) { assertFalse(engine.detectLoop("self.version")) }
    }

    @Test
    fun `system prompt contains network ports section with all ports`() {
        val prompt = engine.buildSystemPrompt()
        assertTrue("提示词应含网络端口章节", prompt.contains("## 网络端口"))
        assertTrue("提示词应含本机监听表", prompt.contains("本机监听"))
        assertTrue("提示词应含外部服务默认端口表", prompt.contains("外部服务默认端口"))
        com.mengpaw.kernel.ports.Ports.ALL.forEach {
            assertTrue("提示词缺少端口 ${it.port}", prompt.contains("${it.port}"))
        }
        // 占位符必须被替换, 不得泄漏到提示词
        assertFalse(prompt.contains("__PORTS_TABLE__"))
    }

    @Test
    fun `english system prompt contains network ports section`() {
        val prompt = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.ENGLISH)
        assertTrue(prompt.contains("## Network Ports"))
        assertTrue(prompt.contains("Locally listened"))
        assertFalse(prompt.contains("__PORTS_TABLE__"))
    }

    @Test
    fun `zh prompt mentions browser extract pipeline and search commands`() {
        val prompt = engine.buildSystemPrompt()
        assertTrue("提示词应教 Agent 处理 browser_extract_* 任务", prompt.contains("browser_extract_*.md"))
        assertTrue("提示词应说明 browser_return_* 是交换文件", prompt.contains("browser_return_*.md"))
        assertTrue("提示词应列 search 管道命令", prompt.contains("search.clean"))
    }

    @Test
    fun `en prompt mentions browser extract pipeline and search commands`() {
        val prompt = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.ENGLISH)
        assertTrue("英文提示词应教 Agent 处理 browser_extract_* 任务", prompt.contains("browser_extract_*.md"))
        assertTrue("英文提示词应说明 browser_return_* 是交换文件", prompt.contains("browser_return_*.md"))
        assertTrue("英文提示词应列 search 管道命令", prompt.contains("search.clean"))
    }
}
