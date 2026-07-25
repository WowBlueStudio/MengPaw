// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

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
}
