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
    fun `fewshot removed - format contract held by main prompt`() {
        val prompt = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.CHINESE, agentName = "MengPaw")
        assertFalse("FewShot 示例已从前缀移除", prompt.contains("## 示例（严格模仿格式）"))
        assertFalse("示例内容不得残留", prompt.contains("搜索能力原生内置"))
        assertTrue("格式契约由主提示词响应格式节承担", prompt.contains("Action: （命令名称）"))
        assertTrue("格式契约含 Thought 标记", prompt.contains("Thought:"))
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
    fun `parse multi-key json keeps all keys`() {
        // 多 key JSON 解析保留全部键 — AgentEngine 门卫以此为拦截依据 (值数 > 1 即参数错位风险)
        val input = """
            Action: plugin.install
            Action Input: {"force": true, "id": "tavily-plugin"}
        """.trimIndent()

        val result = engine.parse(input)
        assertEquals(1, result.actions.size)
        assertEquals(2, result.actions.first().parameters.size)
        assertEquals("true", result.actions.first().parameters["force"])
        assertEquals("tavily-plugin", result.actions.first().parameters["id"])
    }

    @Test
    fun `parse empty object input maps to empty params`() {
        // FIX(自检报告 P1-3): 显式 Action Input: {} → emptyMap(), 不再走 raw 兜底被门卫误拦
        val explicit = engine.parse("""
            Action: self.status
            Action Input: {}
        """.trimIndent())
        assertEquals(1, explicit.actions.size)
        assertTrue("显式 {} 应为空参数", explicit.actions.first().parameters.isEmpty())
        assertNull("显式 {} 不应被门卫误拦", explicit.actions.first().paramFormatError())

        // 省略 Action Input 行 → 同样空参数
        val omitted = engine.parse("""
            Action: self.status
        """.trimIndent())
        assertEquals(1, omitted.actions.size)
        assertTrue("省略 Action Input 应为空参数", omitted.actions.first().parameters.isEmpty())
        assertNull("省略 Action Input 不应被门卫误拦", omitted.actions.first().paramFormatError())
    }

    @Test
    fun `result discipline rules present in both prompts`() {
        val zh = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.CHINESE, agentName = "MengPaw")
        assertTrue("中文提示词应禁止自编结果", zh.contains("禁止自编结果"))
        assertTrue("中文提示词应要求 Error 时禁止声称成功", zh.contains("Result 含 Error 时禁止声称成功"))
        assertTrue("中文提示词应要求写操作后验证", zh.contains("写操作后必须用查询命令验证"))
        assertTrue("中文提示词应禁止 JSON 参数", zh.contains("禁止 JSON"))
        val en = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.ENGLISH, agentName = "MengPaw")
        assertTrue("英文提示词应禁止编造结果", en.contains("never fabricate results"))
        assertTrue("英文提示词应要求 Error 时不声称成功", en.contains("NEVER claim success"))
        assertTrue("英文提示词应要求写操作后验证", en.contains("verify with a query command"))
        assertTrue("英文提示词应禁止 JSON 参数", en.contains("JSON is NOT accepted"))
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
    fun `media delivery format guide present in both prompts`() {
        // 聊天内媒体交付格式指引 (v0.34.0): 缺失时 LLM 用自然语言描述路径,
        // 下行提取器猜不中格式 → 用户收不到文件 (与 XML 工具调用同类静默丢失)
        val zh = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.CHINESE, agentName = "MengPaw")
        assertTrue("中文提示词应含 markdown 图片格式", zh.contains("![描述](绝对路径)"))
        assertTrue("中文提示词应含已保存到独立行", zh.contains("已保存到 <绝对路径>"))
        assertTrue("中文提示词应要求路径真实存在", zh.contains("agent.ls 验证"))
        val en = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.ENGLISH, agentName = "MengPaw")
        assertTrue("英文提示词应含 markdown 图片格式", en.contains("![description](absolute path)"))
        assertTrue("英文提示词应含 Saved to 独立行", en.contains("Saved to <absolute path>"))
        assertTrue("英文提示词应要求路径真实存在", en.contains("must really exist"))
    }

    @Test
    fun `parse xml tool calls translates to actions`() {
        // 用户案例回归: Claude 原生 XML 工具调用语法 — 此前被 Rule 3 当最终答案吞掉
        // (工具从不执行, 用户只见原始 XML)。应转译为 ToolCall 走并行执行链路。
        val input = """我来重新审视一下框架。先看看工作区的实际结构和文档，再做全面审查。
<tool_calls><invoke name="agent.ls"><parameter name="path">.</parameter></invoke><invoke name="agent.docs"></invoke><invoke name="agent.memory"></invoke></tool_calls>"""
        val result = engine.parse(input)
        assertFalse("XML 工具调用不应被当最终答案", result.isFinal)
        assertEquals(3, result.actions.size)
        assertEquals("agent.ls", result.actions[0].name)
        assertEquals(".", result.actions[0].parameters["path"])
        assertEquals("agent.docs", result.actions[1].name)
        assertTrue("无参 invoke 应为空参数", result.actions[1].parameters.isEmpty())
        assertTrue("action 字段保留第一个", result.action?.name == "agent.ls")
        assertTrue("thought 应取 XML 之前的文本", result.thought.startsWith("我来重新审视一下框架"))
    }

    @Test
    fun `parse antml invoke variant`() {
        val input = """Thought: 需要列目录。<antml:invoke name="agent.ls"><antml:parameter name="path">docs</antml:parameter></antml:invoke>"""
        val result = engine.parse(input)
        assertFalse(result.isFinal)
        assertEquals(1, result.actions.size)
        assertEquals("agent.ls", result.actions.first().name)
        assertEquals("docs", result.actions.first().parameters["path"])
    }

    @Test
    fun `xml multi param still guarded by param format gate`() {
        // 转译不绕过安全门卫: 多字段 XML 参数仍触发 paramFormatError
        val input = """<invoke name="plugin.install"><parameter name="id">tavily-plugin</parameter><parameter name="force">true</parameter></invoke>"""
        val result = engine.parse(input)
        assertFalse(result.isFinal)
        assertNotNull("多字段 XML 参数应被门卫拦下", result.actions.first().paramFormatError())
    }

    @Test
    fun `react markers take precedence over xml syntax`() {
        // 有 Action 标记时按 ReAct 执行, XML 块忽略 (ReAct 为规范形态)
        val input = """Thought: 用 ReAct。
Action: self.status
Action Input: {}
<invoke name="agent.ls"><parameter name="path">.</parameter></invoke>"""
        val result = engine.parse(input)
        assertFalse(result.isFinal)
        assertEquals(1, result.actions.size)
        assertEquals("self.status", result.actions.first().name)
    }

    @Test
    fun `parse plain natural language stays final`() {
        // 纯自然语言 (无 ReAct 标记、无 XML 信封) 仍按 Rule 3 视为最终答案
        val result = engine.parse("这是普通回答，没有任何工具调用。")
        assertTrue(result.isFinal)
        assertEquals("这是普通回答，没有任何工具调用。", result.thought)
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
    fun `mutating memory subcommands are not exempt from loop detection`() {
        // P2 修复: 旧前缀匹配豁免全部 memory 命令 — 写子命令不得豁免
        repeat(4) { assertFalse(engine.detectLoop("agent.memory.keep 记住这个")) }
        assertTrue(engine.detectLoop("agent.memory.keep 记住这个")) // 5th triggers
        // agent.boost 前缀也不得连带豁免 agent.boost.delete
        repeat(4) { assertFalse(engine.detectLoop("agent.boost.delete")) }
        assertTrue(engine.detectLoop("agent.boost.delete"))
    }

    @Test
    fun `system prompt ports section is pointer not full table`() {
        // 分层注入 (自检报告 P0-1): 端口表不再常驻提示词, 改为 self.ports 按需取
        val prompt = engine.buildSystemPrompt()
        assertTrue("提示词应含网络端口章节", prompt.contains("## 网络端口"))
        assertTrue("提示词应指向 self.ports 按需取", prompt.contains("self.ports"))
        assertFalse("整张端口表不得常驻提示词", prompt.contains("### 本机监听"))
        assertFalse("外部服务默认端口表不得常驻", prompt.contains("### 外部服务默认端口"))
        // 占位符必须被替换, 不得泄漏到提示词
        assertFalse(prompt.contains("__PORTS_TABLE__"))
    }

    @Test
    fun `english system prompt ports section is pointer`() {
        val prompt = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.ENGLISH)
        assertTrue(prompt.contains("## Network Ports"))
        assertTrue(prompt.contains("self.ports"))
        assertFalse("英文端口表不得常驻提示词", prompt.contains("Locally listened"))
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
    fun `parity - prompt segments order is identity then main then docs`() {
        val prompt = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.CHINESE, agentName = "MengPaw")
        val identityIdx = prompt.indexOf("你是 **MengPaw**")
        val mainIdx = prompt.indexOf("你是檬爪 MengPaw")
        val docsIdx = prompt.indexOf("## 📋 Skills 双层池")
        assertTrue("identity 必须位于提示词开头", identityIdx == 0)
        assertTrue("主提示词必须在 identity 之后", mainIdx > identityIdx)
        assertTrue("docsBlock 必须在主提示词之后", docsIdx > mainIdx)
    }

    @Test
    fun `slash commands moved out - one-line pointer stays`() {
        val prompt = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.CHINESE, agentName = "MengPaw")
        assertFalse("斜杠命令清单已从前缀移除", prompt.contains("**/Mission**"))
        assertFalse("模式详情不得残留前缀", prompt.contains("RubricGate"))
        assertTrue("前缀保留一行指引", prompt.contains("agent.modes"))
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
            assertTrue("agents.md 存在时应注入操作手册段", withDoc.contains("你的操作手册（agents.md）"))
        } finally {
            doc.delete()
        }
        val withoutDoc = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.CHINESE, agentName = "MengPaw")
        assertFalse("agents.md 删除后不得注入空标题段", withoutDoc.contains("你的操作手册（agents.md）"))
        assertFalse("agents.md 删除后不得注入残留内容", withoutDoc.contains("测试内容"))
    }

    @Test
    fun `parity - ports placeholder never leaks and pointer is present`() {
        val prompt = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.CHINESE, agentName = "MengPaw")
        assertFalse("占位符不得泄漏到前缀", prompt.contains("__PORTS_TABLE__"))
        assertTrue("self.ports 指针必须存在", prompt.contains("self.ports"))
        assertFalse("端口具体值不得残留提示词", prompt.contains("9876"))
    }

    // ── P1-6 引导状态机: 身份未就绪提醒 ─────────────────────────────

    @Test
    fun `identity reminder injected when profile name is unfilled`() {
        // 模板格式 profile.md — 身份段名字行为空 (值命中占位符) → 提示词应持续注入提醒;
        // 用户资料段的第二个名字行已填也不得误判为"身份已就绪" (取首个名字行)。
        val dir = File(DataPaths.AGENTS, "MengPaw")
        dir.mkdirs()
        val doc = File(dir, "profile.md")
        doc.writeText(
            """
            |## 身份
            |
            |- **名字：** *（挑个你喜欢的）*
            |- **定位：** *（AI？机器人？）*
            |
            |## 用户资料
            |
            |- **名字：** 小明
            """.trimMargin()
        )
        engine.invalidateDocCache("MengPaw")
        try {
            val zh = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.CHINESE, agentName = "MengPaw")
            assertTrue("名字未填时应注入中文提醒", zh.contains("身份未就绪"))
            assertTrue("中文提醒应给出填写指引", zh.contains("名字: xxx"))
            assertTrue("中文提醒应指明填写工具", zh.contains("agent.write profile.md"))
            val en = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.ENGLISH, agentName = "MengPaw")
            assertTrue("名字未填时应注入英文提醒", en.contains("Identity not ready"))
            assertTrue("英文提醒应给出填写指引", en.contains("Name: xxx"))
        } finally {
            doc.delete()
            engine.invalidateDocCache("MengPaw")
        }
    }

    @Test
    fun `identity reminder disappears when profile name is filled`() {
        // toMarkdown 格式 profile.md — 名称行已填真实名字 → 提醒必须消失
        val dir = File(DataPaths.AGENTS, "MengPaw")
        dir.mkdirs()
        val doc = File(dir, "profile.md")
        doc.writeText(
            """
            |# 关系设定
            |
            |## 自身
            |- 名称: 小爪
            |- ID: agent-001
            |- 定位: 使魔
            """.trimMargin()
        )
        engine.invalidateDocCache("MengPaw")
        try {
            val zh = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.CHINESE, agentName = "MengPaw")
            assertFalse("名字已填时不得注入中文提醒", zh.contains("身份未就绪"))
            val en = engine.buildSystemPrompt(lang = PromptEngine.AgentLanguage.ENGLISH, agentName = "MengPaw")
            assertFalse("名字已填时不得注入英文提醒", en.contains("Identity not ready"))
        } finally {
            doc.delete()
            engine.invalidateDocCache("MengPaw")
        }
    }
}
