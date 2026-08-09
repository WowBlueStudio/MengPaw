// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 命令参数污染测试 (v0.34.3) — Agent 把描述文本 ("等待结果"/"看看") 拼进路径参数尾部,
 * joinToString 还原后路径含空格 → 解析失败且 Agent 原样复制重试循环复现。
 * 修复: 路径解析失败附污染提示 (含疑似多余文本 + 纯净重发指引); 写类命令前置拒绝防错误落盘。
 */
class AgentFilePollutionTest {

    @Before
    fun initPaths() {
        DataPaths.initialize(System.getProperty("java.io.tmpdir") + "/mengpaw_pollution_test")
        File(DataPaths.BASE).deleteRecursively()
    }

    private fun executor(): Pair<AgentExecutor, ExecutionContext> {
        val ex = AgentExecutor(AgentDocManager(agentId = "TestAgent"))
        return ex to ExecutionContext(sessionId = "test", agentName = "TestAgent")
    }

    @Test
    fun `ls with trailing description text reports pollution`() = runTest {
        // 用户实证: "agent.ls / 等待结果" — 等待结果被并入路径参数
        val (ex, ctx) = executor()
        val r = ex.commands["ls"]!!.invoke(listOf("/", "等待结果"), ctx)
        assertFalse("污染参数应失败", r.success)
        assertTrue("错误应含参数污染提示: ${r.error}", r.error?.contains("参数污染") == true)
        assertTrue("应指出多余文本: ${r.error}", r.error?.contains("等待结果") == true)
        assertTrue("应给纯净重发指引: ${r.error}", r.error?.contains("agent.ls /") == true)
    }

    @Test
    fun `read with pollution fails with hint`() = runTest {
        val (ex, ctx) = executor()
        val r = ex.commands["read"]!!.invoke(listOf("notes.md", "看看"), ctx)
        assertFalse(r.success)
        assertTrue("应含污染提示: ${r.error}", r.error?.contains("参数污染") == true)
    }

    @Test
    fun `mkdir with pollution rejected before creating`() = runTest {
        val (ex, ctx) = executor()
        val r = ex.commands["mkdir"]!!.invoke(listOf("/", "等待结果"), ctx)
        assertFalse(r.success)
        assertTrue("应前置拒绝污染: ${r.error}", r.error?.contains("参数污染") == true)
        // 不得在工作区错误创建目录
        assertFalse("污染路径不得创建目录", File(File(DataPaths.AGENTS, "TestAgent"), "等待结果").exists())
    }

    @Test
    fun `rm with pollution rejected before deleting`() = runTest {
        val (ex, ctx) = executor()
        val r = ex.commands["rm"]!!.invoke(listOf("/", "等待结果"), ctx)
        assertFalse(r.success)
        assertTrue("rm 应前置拒绝污染: ${r.error}", r.error?.contains("参数污染") == true)
    }

    @Test
    fun `clean path unaffected`() = runTest {
        val (ex, ctx) = executor()
        // /data 是系统挂载点, 解析后不存在 → 普通"路径不存在", 无污染误报
        val r = ex.commands["ls"]!!.invoke(listOf("/data"), ctx)
        assertFalse(r.success)
        assertFalse("正常路径不应误报污染: ${r.error}", r.error?.contains("参数污染") == true)
    }
}
