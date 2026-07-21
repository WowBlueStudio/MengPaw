// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.cli

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CommandRegistryTest {

    private val registry = CommandRegistry()

    @Test
    fun `register and find command`() {
        var executed = false
        registry.register("test.hello") { args, ctx ->
            executed = true
            ExecutionResult.ok("hello")
        }

        val executor = registry.find("test.hello")
        assertNotNull(executor)
    }

    @Test
    fun `register namespace`() {
        registry.registerNamespace("test", mapOf(
            "a" to { _, _ -> ExecutionResult.ok("a") },
            "b" to { _, _ -> ExecutionResult.ok("b") }
        ))

        assertNotNull(registry.find("test.a"))
        assertNotNull(registry.find("test.b"))
        assertNull(registry.find("test.c"))
    }

    @Test
    fun `list commands`() {
        registry.register("fs.cat") { _, _ -> ExecutionResult.ok("") }
        registry.register("fs.ls") { _, _ -> ExecutionResult.ok("") }
        registry.register("ui.click") { _, _ -> ExecutionResult.ok("") }

        val all = registry.list()
        assertEquals(3, all.size)

        val fs = registry.list("fs")
        assertEquals(2, fs.size)
        assertTrue(fs.all { it.startsWith("fs.") })
    }

    @Test
    fun `list namespaces`() {
        registry.register("a.x") { _, _ -> ExecutionResult.ok("") }
        registry.register("b.y") { _, _ -> ExecutionResult.ok("") }

        val namespaces = registry.namespaces()
        assertEquals(setOf("a", "b"), namespaces)
    }
}
