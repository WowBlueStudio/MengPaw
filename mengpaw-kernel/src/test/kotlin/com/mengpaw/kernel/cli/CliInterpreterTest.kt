// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.cli

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CliInterpreterTest {

    private val interpreter = CliInterpreter()

    @Test
    fun `parse simple command`() {
        val result = interpreter.parse("fs.cat /path/to/file")
        assertEquals("fs.cat", result.command)
        assertEquals(listOf("/path/to/file"), result.args)
        assertTrue(result.flags.isEmpty())
    }

    @Test
    fun `parse command with flags`() {
        val result = interpreter.parse("fs.write /path/to/file --mode 644")
        assertEquals("fs.write", result.command)
        assertEquals(listOf("/path/to/file"), result.args)
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
    fun `parse command with short flags`() {
        val result = interpreter.parse("net.curl https://example.com -v")
        assertEquals("net.curl", result.command)
        assertEquals(listOf("https://example.com"), result.args)
        assertEquals(mapOf("v" to "true"), result.flags)
    }

    @Test
    fun `parse multiple args`() {
        val result = interpreter.parse("fs.cp source.txt dest.txt --force")
        assertEquals("fs.cp", result.command)
        assertEquals(listOf("source.txt", "dest.txt"), result.args)
        assertEquals(mapOf("force" to "true"), result.flags)
    }

    @Test
    fun `parse command with special characters`() {
        val result = interpreter.parse("fs.write /tmp/file --content \"hello@world!#test\"")
        assertEquals("fs.write", result.command)
        assertTrue(result.args.isNotEmpty())
    }

    @Test
    fun `parse long input`() {
        val longArg = "x".repeat(1000)
        val result = interpreter.parse("fs.cat $longArg")
        assertEquals("fs.cat", result.command)
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
        val result = interpreter.parse("fs.write path \\\"escaped\\\"")
        assertEquals("fs.write", result.command)
        assertTrue(result.args.isNotEmpty())
    }

    @Test
    fun `parse command with many args`() {
        val result = interpreter.parse("fs.write a b c d e f g h i j")
        assertEquals("fs.write", result.command)
        assertEquals(10, result.args.size)
    }
}
