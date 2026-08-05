// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.cli.ExecutionContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * agent.memory.read/search/stats/write 融合命令测试 (替代 plugin-memory 的 memory.*)。
 * 每测试独立临时目录 + 固定 agent 名 — @Before 重置 DataPaths 保证隔离。
 */
class AgentMemoryCommandsTest {

    private val executor = AgentExecutor(AgentDocManager())
    private val ctx = ExecutionContext(sessionId = "test-session", agentName = "mem-test")

    @Before
    fun setUp() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "mengpaw-mem-test-${System.currentTimeMillis()}")
        tmp.mkdirs()
        com.mengpaw.kernel.DataPaths.initialize(tmp.absolutePath)
    }

    private suspend fun run(cmd: String, vararg args: String) =
        executor.commands[cmd]!!(args.toList(), ctx)

    @Test
    fun `write creates entry with specified id title`() = runTest {
        val r = run("memory.write", "test-note-1", "这是测试内容")
        assertTrue(r.success)
        val ltm = AgentDocs.readLongTermMemory("mem-test")
        assertTrue("长期记忆应含指定 ID 标题", ltm.contains("## test-note-1"))
        assertTrue(ltm.contains("这是测试内容"))
    }

    @Test
    fun `write updates existing entry`() = runTest {
        run("memory.write", "test-note-2", "旧内容")
        val r2 = run("memory.write", "test-note-2", "新内容")
        assertTrue(r2.success)
        val ltm = AgentDocs.readLongTermMemory("mem-test")
        assertTrue(ltm.contains("新内容"))
        assertFalse("旧内容应被替换", ltm.contains("旧内容"))
    }

    @Test
    fun `read fetches entry by id`() = runTest {
        run("memory.write", "test-note-3", "可读取的内容")
        val r = run("memory.read", "test-note-3")
        assertTrue(r.success)
        assertTrue(r.output.contains("可读取的内容"))
    }

    @Test
    fun `read reports not found`() = runTest {
        val r = run("memory.read", "不存在-的-id")
        assertFalse(r.success)
    }

    @Test
    fun `search hits long-term memory`() = runTest {
        run("memory.write", "search-topic", "关键词: 量子纠缠")
        val r = run("memory.search", "量子")
        assertTrue(r.success)
        assertTrue(r.output.contains("量子纠缠"))
    }

    @Test
    fun `search miss returns no match`() = runTest {
        val r = run("memory.search", "绝对不存在的词汇xyz")
        assertTrue(r.success)
        assertTrue(r.output.contains("无匹配"))
    }

    @Test
    fun `stats reports long-term count`() = runTest {
        run("memory.write", "stats-note-a", "内容a")
        run("memory.write", "stats-note-b", "内容b")
        val r = run("memory.stats")
        assertTrue(r.success)
        assertTrue(r.output.contains("长期记忆: 2 条"))
    }

    @Test
    fun `read resolves workspace relative path and tolerates leading slash`() = runTest {
        // FIX(自检报告 P0-2): 相对路径以工作区为基准 + 前导 / 宽容解析
        val ws = File(com.mengpaw.kernel.DataPaths.AGENTS, "mem-test")
        ws.mkdirs()
        File(ws, "profile.md").writeText("# 测试档案")
        // 相对路径
        val r1 = run("read", "profile.md")
        assertTrue("相对路径应命中工作区文件: ${r1.output}", r1.success)
        assertTrue(r1.output.contains("测试档案"))
        // 前导 / 宽容 (Unix 习惯写法, Android 会被当根目录绝对路径)
        val r2 = run("read", "/profile.md")
        assertTrue("前导 / 应回退工作区解析: ${r2.output}", r2.success)
        assertTrue(r2.output.contains("测试档案"))
        // 错误信息输出解析后的真实路径
        val r3 = run("read", "不存在.md")
        assertFalse(r3.success)
        assertTrue("错误应输出解析后的真实路径: ${r3.error}", r3.error?.contains("解析为") == true)
    }
}
