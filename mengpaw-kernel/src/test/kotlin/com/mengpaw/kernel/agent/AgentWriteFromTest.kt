// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ErrorCodes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * P2-11(自检报告): agent.write 多行内容 — --from <源文件> 批量导入 + 引用规则提示。
 */
class AgentWriteFromTest {

    @Before
    fun initPaths() {
        DataPaths.initialize(System.getProperty("java.io.tmpdir") + "/mengpaw_agentwrite_test")
        File(DataPaths.BASE).deleteRecursively()
    }

    private fun executor(): Pair<AgentExecutor, ExecutionContext> {
        val ex = AgentExecutor(AgentDocManager(agentId = "TestAgent"))
        return ex to ExecutionContext(sessionId = "test", agentName = "TestAgent")
    }

    private fun wsFile(agent: String, relative: String): File {
        val f = File(File(DataPaths.AGENTS, agent), relative)
        f.parentFile.mkdirs()
        return f
    }

    @Test
    fun `write with from imports multi-line file content`() = runTest {
        val (ex, ctx) = executor()
        wsFile("TestAgent", "draft.md").writeText("第一行\n第二行 long content\n```\ncode block\n```")

        // 参数形态: agent.write <目标路径> --from <源文件> (flags 平铺后同现于 args)
        val r = ex.commands["write"]!!.invoke(listOf("notes.md", "--from", "draft.md"), ctx)
        assertTrue("--from 导入应成功: ${r.error}", r.success)
        // P0 (2026-08-08): 成功结果应回传内容预览 + 行数, Agent 声称成功须基于真实落盘内容
        assertTrue("结果应含行数", r.output!!.contains("行)"))
        assertTrue("结果应含内容预览", r.output!!.contains("内容预览") && r.output!!.contains("第一行"))
        // P0 强化 (2026-08-08): 校验锚点 — 声称成功须引用真实内容片段
        assertTrue("结果应含校验锚点", r.output!!.contains("[校验锚点]"))
        assertTrue("锚点应含内容开头", r.output!!.contains("第一行"))
        // P0 实质化 (2026-08-08): 框架自动读回验证 — 成功断言由框架完成
        assertTrue("结果应含读回验证", r.output!!.contains("读回验证: 内容一致 ✓"))

        val out = wsFile("TestAgent", "notes.md")
        assertTrue(out.exists())
        assertEquals("多行内容原样写入", "第一行\n第二行 long content\n```\ncode block\n```", out.readText())
    }

    @Test
    fun `write from missing source fails with not found`() = runTest {
        val (ex, ctx) = executor()
        val r = ex.commands["write"]!!.invoke(listOf("a.md", "--from", "不存在.md"), ctx)
        assertFalse(r.success)
        assertEquals(ErrorCodes.ERR_NOT_FOUND, r.errorCode)
        assertFalse(wsFile("TestAgent", "a.md").exists())
    }

    @Test
    fun `write from directory source fails`() = runTest {
        val (ex, ctx) = executor()
        wsFile("TestAgent", "dir/x.txt").writeText("x")
        val r = ex.commands["write"]!!.invoke(listOf("a.md", "--from", "dir"), ctx)
        assertFalse(r.success)
        assertTrue(r.error!!.contains("目录"))
    }

    @Test
    fun `write without from keeps positional content`() = runTest {
        val (ex, ctx) = executor()
        val r = ex.commands["write"]!!.invoke(listOf("b.md", "你好", "世界"), ctx)
        assertTrue(r.success)
        assertEquals("你好 世界", wsFile("TestAgent", "b.md").readText())
    }

    @Test
    fun `write with quoted content survives tokenize`() = runTest {
        val (ex, ctx) = executor()
        // 引用规则: 内容含空格用引号包裹 (CliInterpreter tokenize 保留引号内空格)
        val r = ex.commands["write"]!!.invoke(listOf("c.md", "Hello World 你好"), ctx)
        assertTrue(r.success)
        assertEquals("Hello World 你好", wsFile("TestAgent", "c.md").readText())
    }

    @Test
    fun `write missing content hints quoting and from rules`() = runTest {
        val (ex, ctx) = executor()
        val r = ex.commands["write"]!!.invoke(listOf("d.md"), ctx)
        assertFalse(r.success)
        assertEquals(ErrorCodes.ERR_INVALID_INPUT, r.errorCode)
        // P2-11: 错误信息须带引用规则与 --from 提示, 模型据此收敛
        assertTrue("应提示引号规则", r.error!!.contains("引号"))
        assertTrue("应提示 --from 导入", r.error!!.contains("--from"))
    }
}
