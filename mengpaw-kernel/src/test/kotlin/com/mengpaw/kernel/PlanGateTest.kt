// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.llm.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * plan 模式的高危命令门禁 (P0 补测)。
 *
 * 背景: `PlanModeExecutor` 原实现直接 `name + values` 拼命令行, 绕过 `HighRiskCommandGate`
 * —— plan 模式下 `memory.restore` / `root.*` 这类命令既不校验 reason, JSON 参数还会被
 * 当位置参数硬拼。现在统一走 [gatePlanAction], 本测试锁住"与 ReAct 同规则"。
 */
class PlanGateTest {

    @Test
    fun `门禁表内命令缺 reason 时被拒`() {
        val r = gatePlanAction(ToolCall("memory.restore", mapOf("path" to "soul.md")))
        assertEquals("必须报 REASON_REQUIRED", ErrorCodes.REASON_REQUIRED, r.errorCode)
        assertTrue("错误信息应提示补 reason: ${r.error}", r.error.orEmpty().contains("reason"))
    }

    @Test
    fun `带 reason 时按模板展开 且 reason 不进命令行`() {
        val r = gatePlanAction(
            ToolCall("memory.restore", mapOf("path" to "soul.md", "snapshot" to "abc12345", "reason" to "回滚到旧版"))
        )
        assertNull("带 reason 不应被拒: ${r.error}", r.error)
        assertTrue("路径必须保留: ${r.commandLine}", r.commandLine.contains("soul.md"))
        assertTrue("可选快照号必须保留: ${r.commandLine}", r.commandLine.contains("abc12345"))
        assertFalse("reason 是意图声明, 不得进入命令文本: ${r.commandLine}", r.commandLine.contains("回滚到旧版"))
        assertEquals("回滚到旧版", r.reason)
    }

    @Test
    fun `非门禁表命令行为不变`() {
        val r = gatePlanAction(ToolCall("agent.memory.record", mapOf("content" to "x")))
        assertNull(r.error)
        assertTrue("普通命令照常展开: ${r.commandLine}", r.commandLine.startsWith("agent.memory.record"))
    }

    @Test
    fun `root 类高危同样受控`() {
        val r = gatePlanAction(ToolCall("root.exec", mapOf("command" to "rm -rf /")))
        assertEquals(ErrorCodes.REASON_REQUIRED, r.errorCode)
    }
}
