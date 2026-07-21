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
    fun `no action returns null action treated as final`() {
        // FIX: Non-ReAct natural language responses (e.g. from DeepSeek-Chat)
        // are now treated as final answers instead of causing the loop to spin.
        val input = "Thought: just thinking"
        val result = engine.parse(input)
        assertNull(result.action)
        assertTrue(result.isFinal) // No Action + No Final Answer → treated as final
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
        assertFalse(engine.detectLoop("fs.cat /test"))
        assertFalse(engine.detectLoop("fs.cat /test"))
        assertTrue(engine.detectLoop("fs.cat /test")) // 3rd time
    }
}
