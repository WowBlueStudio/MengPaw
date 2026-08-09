// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.security

import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.CliInterpreter
import com.mengpaw.kernel.llm.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高危命令 reason 门禁测试 (P0 v0.34.1)。
 *
 * JSON 豁免通道: 高危命令必须带非空 reason, 模板驱动展开防键序污染;
 * 非高危命令维持 paramFormatError 原门卫 (行为零变化)。
 * 全部纯函数, 无触网无写盘。
 */
class HighRiskCommandGateTest {

    // ── 高危 + 纯文本形态 → REASON_REQUIRED ──

    @Test
    fun 高危纯文本参数被拒() {
        // 纯文本形态 = raw 兜底 (ReActParser: mapOf("raw" to 文本))
        val result = HighRiskCommandGate.evaluate(ToolCall("agent.rm", mapOf("raw" to "test.md")))
        assertEquals(ErrorCodes.REASON_REQUIRED, result.errorCode)
        assertNotNull("错误文本应含引导信息", result.error)
        assertTrue("错误文本应含 JSON 示例", result.error!!.contains("reason"))
        assertTrue("错误文本应含命令名", result.error!!.contains("agent.rm"))
    }

    @Test
    fun 高危reason空白被拒() {
        val result = HighRiskCommandGate.evaluate(
            ToolCall("agent.memory.edit", mapOf("timestamp" to "2026-08-09 10:00", "content" to "x", "reason" to "  "))
        )
        assertEquals(ErrorCodes.REASON_REQUIRED, result.errorCode)
    }

    @Test
    fun 单键JSON无reason被拒_补缝() {
        // 关键补缝: 单键 JSON (size==1, 无 raw) 原 paramFormatError 放行 — 高危命令必须 reason
        val result = HighRiskCommandGate.evaluate(ToolCall("agent.rm", mapOf("path" to "x")))
        assertEquals(ErrorCodes.REASON_REQUIRED, result.errorCode)
    }

    // ── 高危 + JSON 带 reason → 模板展开 ──

    @Test
    fun 双参数展开不含reason() {
        val result = HighRiskCommandGate.evaluate(
            ToolCall("agent.memory.edit", mapOf("timestamp" to "2026-08-09 10:00", "content" to "hello", "reason" to "用户要求备份"))
        )
        assertNull(result.error)
        assertEquals("agent.memory.edit \"2026-08-09 10:00\" hello", result.commandLine)
        assertTrue("reason 不得进入命令文本", !result.commandLine.contains("用户要求备份"))
    }

    @Test
    fun 缺键报PARAM_FORMAT_ERROR并列出期望键() {
        val result = HighRiskCommandGate.evaluate(
            ToolCall("agent.memory.edit", mapOf("timestamp" to "2026-08-09 10:00", "reason" to "备份"))
        )
        assertEquals(ErrorCodes.PARAM_FORMAT_ERROR, result.errorCode)
        assertTrue("错误文本应列出缺失键", result.error!!.contains("content"))
        assertTrue("错误文本应含示例", result.error!!.contains("reason"))
    }

    @Test
    fun flag参数展开() {
        val result = HighRiskCommandGate.evaluate(
            ToolCall("agent.rm", mapOf("path" to "x", "force" to "true", "reason" to "清理临时文件"))
        )
        assertNull(result.error)
        assertEquals("agent.rm x --force", result.commandLine)
    }

    // ── P4 修复 (2026-08-08 自检): 多行 content 换行保留 ──

    @Test
    fun 多行content经引号保护保留换行() {
        // JSON 通道: ReActParser 已把 \n 解析为真实换行 → 展开时须引号保护, 否则被 CLI 分词切散
        val result = HighRiskCommandGate.evaluate(
            ToolCall("agent.memory.edit", mapOf(
                "timestamp" to "2026-08-09 10:00",
                "content" to "第一行\n第二行\n```\ncode\n```",
                "reason" to "写多行文档"
            ))
        )
        assertNull(result.error)
        assertTrue("content 应带引号保护", result.commandLine.contains("\"第一行\n第二行\n```\ncode\n```\""))

        // 端到端: 展开后的命令行经 CliInterpreter 解析, 参数完整还原 (换行不丢)
        val parsed = CliInterpreter().parse(result.commandLine)
        assertEquals(listOf("2026-08-09 10:00", "第一行\n第二行\n```\ncode\n```"), parsed.args)
        assertTrue("reason 不得进入命令行", !result.commandLine.contains("写多行文档"))
    }

    @Test
    fun 含空格引号反斜杠的content转义还原() {
        val result = HighRiskCommandGate.evaluate(
            ToolCall("agent.memory.edit", mapOf(
                "timestamp" to "2026-08-09 10:00",
                "content" to "say \"hi\" \\ done and more",
                "reason" to "转义测试"
            ))
        )
        assertNull(result.error)
        val parsed = CliInterpreter().parse(result.commandLine)
        assertEquals(listOf("2026-08-09 10:00", "say \"hi\" \\ done and more"), parsed.args)
    }

    @Test
    fun 普通参数不加引号保持既有行为() {
        val result = HighRiskCommandGate.evaluate(
            ToolCall("agent.memory.edit", mapOf("timestamp" to "2026-08-09 10:00", "content" to "hello", "reason" to "备份"))
        )
        assertEquals("agent.memory.edit \"2026-08-09 10:00\" hello", result.commandLine)
    }

    @Test
    fun flag值非true不拼接() {
        val result = HighRiskCommandGate.evaluate(
            ToolCall("agent.rm", mapOf("path" to "x", "force" to "false", "reason" to "预览"))
        )
        assertEquals("agent.rm x", result.commandLine)
    }

    @Test
    fun 零参高危带reason展开为裸命令() {
        val result = HighRiskCommandGate.evaluate(ToolCall("clipboard.paste", mapOf("reason" to "读取剪贴板")))
        assertNull(result.error)
        assertEquals("clipboard.paste", result.commandLine)
    }

    @Test
    fun XML具名参数同构豁免() {
        // XML 工具调用转译后 parameters 是与 JSON 同构的具名 Map — 同一通道
        val result = HighRiskCommandGate.evaluate(
            ToolCall("agent.memory.edit", mapOf("timestamp" to "2026-08-09 10:00", "content" to "正文", "reason" to "用户要求"))
        )
        assertNull(result.error)
        assertEquals("agent.memory.edit \"2026-08-09 10:00\" 正文", result.commandLine)
    }

    // ── 非高危: 原门卫行为零变化 ──

    @Test
    fun 非高危多键JSON维持paramFormatError() {
        val result = HighRiskCommandGate.evaluate(
            ToolCall("agent.search", mapOf("query" to "a", "limit" to "5"))
        )
        assertEquals(ErrorCodes.PARAM_FORMAT_ERROR, result.errorCode)
        assertTrue(result.error!!.contains("agent.search"))
    }

    @Test
    fun 非高危纯文本照常放行() {
        val result = HighRiskCommandGate.evaluate(ToolCall("agent.search", mapOf("raw" to "天气")))
        assertNull(result.error)
        assertEquals("agent.search 天气", result.commandLine)
    }

    @Test
    fun 非高危单键JSON照常放行() {
        // 原 paramFormatError 对 size==1 无 raw 的 JSON 放行 — 保持原行为
        val result = HighRiskCommandGate.evaluate(ToolCall("agent.search", mapOf("query" to "天气")))
        assertNull(result.error)
        assertEquals("agent.search 天气", result.commandLine)
    }

    @Test
    fun 高危表覆盖关键命令() {
        // v0.34.3 分级化: HIGH_RISK 表 = 中危/高危命令 (reason 门禁);
        // 普通 (LOW) 命令移出, 由 CommandRiskLevels 分级承载
        listOf(
            "agent.rm", "fs.mv",
            "proc.exec", "proc.system", "plugin.install", "plugin.uninstall",
            "plugin.enable", "plugin.disable",
            "clipboard.copy", "clipboard.paste", "clipboard.clear",
            "skill.enable", "skill.disable",
            "agent.memory.rm", "agent.memory.edit", "agent.memory.mid.delete", "agent.memory.mid.rm",
            "agent.memory.mid.edit",
            "agent.memory.project.delete", "agent.memory.project.rm", "agent.memory.project.edit",
            "root.exec", "root.shell", "root.fs.write", "root.system.setprop",
            "root.system.hosts", "root.backup.restore",
            "root.apps.uninstall", "root.apps.freeze", "root.apps.unfreeze"
        ).forEach { name ->
            assertTrue("高危表应包含 $name", HighRiskCommandGate.HIGH_RISK.containsKey(name))
        }
        // LOW 命令 (v0.34.3 分级: 新建/写入/普通表达) 移出 reason 表
        listOf(
            "agent.write", "agent.mkdir", "fs.cp",
            "self.notify.message", "self.notify.banner",
            "agent.memory.keep", "agent.memory.write", "agent.memory.record", "agent.memory.project.save"
        ).forEach { name ->
            assertTrue("LOW 命令不应在 reason 表: $name", !HighRiskCommandGate.HIGH_RISK.containsKey(name))
            assertTrue("LOW 命令分级应为普通: $name", CommandRiskLevels.levelOf(name) == RiskLevel.LOW)
        }
        // 只读命令不在高危表
        assertTrue("agent.read 不应在高危表", !HighRiskCommandGate.HIGH_RISK.containsKey("agent.read"))
        assertTrue("agent.output (只读列表) 不应在高危表", !HighRiskCommandGate.HIGH_RISK.containsKey("agent.output"))
    }
}
