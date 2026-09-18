// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.cli

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CliInterpreterTest {

    private val interpreter = CliInterpreter()

    @Test
    fun `parse simple command`() {
        val result = interpreter.parse("net.curl https://example.com")
        assertEquals("net.curl", result.command)
        assertEquals(listOf("https://example.com"), result.args)
        assertTrue(result.flags.isEmpty())
    }

    @Test
    fun `parse command with flags`() {
        val result = interpreter.parse("net.curl https://example.com --mode 644")
        assertEquals("net.curl", result.command)
        assertEquals(listOf("https://example.com"), result.args)
        assertEquals(mapOf("mode" to "644"), result.flags)
    }

    @Test
    fun `parse command with quoted args`() {
        val result = interpreter.parse("ui.input \"Hello World\"")
        assertEquals("ui.input", result.command)
        assertEquals(listOf("Hello World"), result.args)
    }

    @Test
    fun `parse empty input`() {
        val result = interpreter.parse("")
        assertEquals("", result.command)
        assertTrue(result.args.isEmpty())
    }

    @Test
    fun `single dash option stays in args in order`() {
        // P0 回归: 单横线选项是 Linux 原生语义 (-n/-c/-i), 必须原样、原位留在 args。
        // 此前被当作"带值 flag"归入 flags 并吞掉下一个 token — Pipeline 还原时只认双横线,
        // 结果 grep -n 关键词 文件 变成 args=[文件] + flags={n=关键词},
        // 实际执行参数 [文件, --n, 关键词] (选项错位 + 值被吞)。
        val result = interpreter.parse("grep -n 关键词 文件.md")
        assertEquals("grep", result.command)
        assertEquals(listOf("-n", "关键词", "文件.md"), result.args)
        assertTrue("单横线选项不得进 flags", result.flags.isEmpty())
    }

    @Test
    fun `wc -c keeps file argument after option`() {
        val result = interpreter.parse("wc -c article.md")
        assertEquals(listOf("-c", "article.md"), result.args)
        assertTrue(result.flags.isEmpty())
    }

    @Test
    fun `double dash flag still parsed as flag`() {
        // 双横线保持框架 flag 语义 (Pipeline 会原样还原为 --key value)
        val result = interpreter.parse("net.curl https://example.com --mode 644")
        assertEquals(listOf("https://example.com"), result.args)
        assertEquals(mapOf("mode" to "644"), result.flags)
    }

    @Test
    fun `double dash flag without value parsed as true`() {
        val result = interpreter.parse("plugin.verify --all")
        assertTrue(result.args.isEmpty())
        assertEquals(mapOf("all" to "true"), result.flags)
    }

    @Test
    fun `parse multiple args`() {
        val result = interpreter.parse("net.curl https://example.com --timeout 10")
        assertEquals("net.curl", result.command)
        assertEquals(listOf("https://example.com"), result.args)
        assertEquals(mapOf("timeout" to "10"), result.flags)
    }

    @Test
    fun `parse command with special characters`() {
        val result = interpreter.parse("net.post https://example.com --content \"hello@world!#test\"")
        assertEquals("net.post", result.command)
        assertTrue(result.args.isNotEmpty())
    }

    @Test
    fun `parse long input`() {
        val longArg = "x".repeat(1000)
        val result = interpreter.parse("net.curl $longArg")
        assertEquals("net.curl", result.command)
        assertEquals(1, result.args.size)
        assertEquals(longArg, result.args[0])
    }

    @Test
    fun `parse whitespace-only input`() {
        val result = interpreter.parse("   \t  \n  ")
        assertEquals("", result.command)
    }

    @Test
    fun `parse backslash escaped quotes`() {
        val result = interpreter.parse("net.curl path \\\"escaped\\\"")
        assertEquals("net.curl", result.command)
        assertTrue(result.args.isNotEmpty())
    }

    @Test
    fun `parse command with many args`() {
        val result = interpreter.parse("net.curl a b c d e f g h i j")
        assertEquals("net.curl", result.command)
        assertEquals(10, result.args.size)
    }
}
