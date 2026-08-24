// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReAct 解析器回归测试 (v0.37.3) — 退化输出拦截 + XML `<action>` 标签转译。
 *
 * 背景 BUG: 模型输出一长串 `<Action><Action>…` (非标准 XML, 无 name), 原解析器
 * 所有规则不命中 → Rule 3 当纯文本最终答案返回, 用户看到垃圾; 且 `<Action name="cmd">`
 * 合理 XML 形态也不被转译 (Rule 2b 只支持 invoke/antml:invoke)。
 */
class ReActParserTest {

    private val parser = ReActParser()

    @Test
    fun `重复Action标签判定为退化输出`() {
        val out = "<Action><Action><Action><Action><Action><Action><Action>"
        assertTrue("连续重复 XML 标签必须判定退化", parser.isDegenerateOutput(out))
    }

    @Test
    fun `单一token流判定为退化输出`() {
        assertTrue(parser.isDegenerateOutput("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        assertTrue(parser.isDegenerateOutput("重复重复重复重复重复重复重复重复重复重复重复重复重复重复重复重复重复重复重复重复重复重复重复重复重复重复重复"))
    }

    @Test
    fun `正常最终答案不判定退化`() {
        assertFalse(parser.isDegenerateOutput("进化记录显示最近 3 条失败模式: 1. 路径参数拼接错误 2. 超时未重试 3. 幻觉汇报。建议关注第 1 条。"))
    }

    @Test
    fun `正常XML工具调用不判定退化`() {
        val xml = "<action name=\"evolution.audit\"><parameter name=\"q\">近3条</parameter></action>"
        assertFalse("带 name 的合法 XML 工具调用不是退化输出", parser.isDegenerateOutput(xml))
    }

    @Test
    fun `action标签转译为ToolCall`() {
        val parsed = parser.parse(
            """<action name="evolution.audit"><parameter name="q">近3条</parameter></action>"""
        )
        assertFalse("XML action 应被解析为工具调用而非最终答案", parsed.isFinal)
        assertEquals("evolution.audit", parsed.action?.name)
        assertEquals("近3条", parsed.action?.parameters?.get("q"))
    }

    // ── P1-1: JSON 数组工具调用 ──────────────────────────────────────

    @Test
    fun `json array of tool calls is translated`() {
        val parsed = parser.parse(
            """[{"command":"fs.cat","input":{"path":"/a"}},{"name":"agent.ls","parameters":{"path":"."}}]"""
        )
        assertFalse("JSON 数组工具调用不应被当最终答案", parsed.isFinal)
        assertEquals(2, parsed.actions.size)
        assertEquals("fs.cat", parsed.actions[0].name)
        assertEquals("/a", parsed.actions[0].parameters["path"])
        assertEquals("agent.ls", parsed.actions[1].name)
        assertEquals(".", parsed.actions[1].parameters["path"])
    }

    @Test
    fun `json array inside code fence is translated`() {
        val parsed = parser.parse(
            """先执行以下命令：
```json
[{"command":"self.status"},{"command":"agent.ls","input":{"path":"docs"}}]
```"""
        )
        assertFalse("代码块内 JSON 数组应转译", parsed.isFinal)
        assertEquals(2, parsed.actions.size)
        assertEquals("self.status", parsed.actions[0].name)
        assertTrue("无输入命令应为空参数", parsed.actions[0].parameters.isEmpty())
        assertEquals("docs", parsed.actions[1].parameters["path"])
    }

    @Test
    fun `file listing with only name keys stays final`() {
        // 数据答案 [{name:...}] 无命令/输入键 — 不得误判为工具调用
        val parsed = parser.parse("""结果是: [{"name":"a.txt","size":100},{"name":"b.txt","size":200}]""")
        assertTrue("仅含 name 的文件清单应保持最终答案", parsed.isFinal)
        assertTrue(parsed.actions.isEmpty())
    }

    @Test
    fun `json object with command and string input maps to raw`() {
        val parsed = parser.parse("""[{"command":"agent.memory","input":"记住这个"}]""")
        assertFalse(parsed.isFinal)
        assertEquals("agent.memory", parsed.actions.first().name)
        assertEquals("记住这个", parsed.actions.first().parameters["raw"])
    }
}
