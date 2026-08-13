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
}
