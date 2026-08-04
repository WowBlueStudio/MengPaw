// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import com.mengpaw.kernel.DataPaths
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class PromptEngineTest {

    private val engine = PromptEngine()

    @Before
    fun initPaths() {
        DataPaths.initialize(System.getProperty("java.io.tmpdir") + "/mengpaw_prompt_test")
    }

    @Test
    fun `fewshot trimmed keeps action markers and drops removed steps`() {
        val prompt = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.CHINESE, agentName = "MengPaw")
        assertTrue("精简后仍含 Action 标记", prompt.contains("Action:"))
        assertTrue("示例 2 压缩后新文本存在（内置搜索）", prompt.contains("搜索能力原生内置"))
        assertFalse("示例 2 不再演示 plugin.install 安装流程", prompt.contains("查插件市场并安装 tavily-plugin"))
    }

    @Test
    fun `oversized agents doc is compacted with read link`() {
        // 写超长 agents.md → docsBlock 注入应被 compactDoc 截断 + 外链
        val dir = File(DataPaths.AGENTS, "MengPaw")
        dir.mkdirs()
        val doc = File(dir, "agents.md")
        doc.writeText("长文档内容".repeat(4000))  // ~20K 字符 > 12K 阈值
        try {
            val prompt = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.CHINESE, agentName = "MengPaw")
            assertTrue("超长文档应含截断外链标记", prompt.contains("文档过长"))
            assertTrue("外链应指向 agents.md", prompt.contains("agent.read"))
        } finally {
            doc.delete()
        }
    }

    @Test
    fun `same params hit system prompt cache`() {
        val p1 = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.CHINESE, agentName = "MengPaw")
        val p2 = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.CHINESE, agentName = "MengPaw")
        assertTrue("相同参数应命中缓存（同一实例）", p1 == p2)
    }

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

    // ── 固定前缀拼接契约 (parity) 锁定 ─────────────────────────────
    // 拼接顺序/分隔符任何改动会改变发送给 LLM 的前缀字节 → DeepSeek 前缀缓存整体失效。
    // 此组测试锁定契约: 改顺序/分隔符必须显式改测试。

    @Test
    fun `parity - prompt segments order is identity then main then fewshot then docs`() {
        val prompt = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.CHINESE, agentName = "MengPaw")
        val identityIdx = prompt.indexOf("你是 **MengPaw**")
        val mainIdx = prompt.indexOf("你是檬爪 MengPaw")
        val fewIdx = prompt.indexOf("## 示例（严格模仿格式）")
        val docsIdx = prompt.indexOf("## 📋 Skills 双层池")
        assertTrue("identity 必须位于提示词开头", identityIdx == 0)
        assertTrue("主提示词必须在 identity 之后", mainIdx > identityIdx)
        assertTrue("FewShot 必须在主提示词之后", fewIdx > mainIdx)
        assertTrue("docsBlock 必须在 FewShot 之后", docsIdx > fewIdx)
    }

    @Test
    fun `parity - deleted agents doc drops the agents section`() {
        // agents.md 删除后不得再注入固定标题段 (v0.29.0: 无条件注入改为条件注入)
        val dir = File(DataPaths.AGENTS, "MengPaw")
        dir.mkdirs()
        val doc = File(dir, "agents.md")
        doc.writeText("## 安全\n- 测试内容")
        try {
            val withDoc = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.CHINESE, agentName = "MengPaw")
            assertTrue("agents.md 存在时应注入操作手册段", withDoc.contains("你的操作手册（AGENTS.md）"))
        } finally {
            doc.delete()
        }
        val withoutDoc = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.CHINESE, agentName = "MengPaw")
        assertFalse("agents.md 删除后不得注入空标题段", withoutDoc.contains("你的操作手册（AGENTS.md）"))
        assertFalse("agents.md 删除后不得注入残留内容", withoutDoc.contains("测试内容"))
    }

    @Test
    fun `parity - ports placeholder never leaks and table is replaced`() {
        val prompt = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.CHINESE, agentName = "MengPaw")
        assertFalse("占位符不得泄漏到前缀", prompt.contains("__PORTS_TABLE__"))
        assertTrue("端口表替换产物必须存在", prompt.contains("9876"))
    }
}
