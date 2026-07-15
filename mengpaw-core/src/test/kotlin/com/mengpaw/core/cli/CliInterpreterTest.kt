package com.mengpaw.core.cli

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
}
