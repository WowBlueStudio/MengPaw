// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.mcp

import com.mengpaw.kernel.plugin.PluginManager
import org.junit.Assert.*
import org.junit.Test

class McpTest {

    companion object {
        @org.junit.BeforeClass @JvmStatic
        fun setup() {
            val tmpDir = java.io.File(System.getProperty("java.io.tmpdir"), "mengpaw-test-${System.currentTimeMillis()}")
            tmpDir.mkdirs()
            com.mengpaw.kernel.DataPaths.initialize(tmpDir.absolutePath)
        }
    }

    private val pluginManager = PluginManager("0.12.12")
    private val mcpServer = McpServer(pluginManager)

    @Test fun `tools list returns valid JSON-RPC`() {
        val response = mcpServer.handleRequest("""{"jsonrpc":"2.0","method":"tools/list","id":1}""")
        assertTrue("Response should be valid JSON: $response", response.contains("jsonrpc"))
        assertTrue("Response should have id", response.contains("\"id\""))
        assertTrue("Response should have result", response.contains("result"))
    }

    @Test fun `resources list returns hardcoded resources`() {
        val response = mcpServer.handleRequest("""{"jsonrpc":"2.0","method":"resources/list","id":2}""")
        assertTrue("Should contain agents docs: $response", response.contains("Agents.md"))
        assertTrue("Should contain memory index: $response", response.contains("memory://index"))
    }

    @Test fun `prompts list returns templates`() {
        val response = mcpServer.handleRequest("""{"jsonrpc":"2.0","method":"prompts/list","id":3}""")
        assertTrue("Should contain react-agent: $response", response.contains("react-agent"))
    }

    @Test fun `get prompt injects arguments`() {
        val request = """{"jsonrpc":"2.0","method":"prompts/get","id":4,"params":{"name":"react-agent","arguments":{"task":"test task"}}}"""
        val response = mcpServer.handleRequest(request)
        assertTrue("Should inject task argument: $response", response.contains("test task"))
    }

    @Test fun `unknown method returns error`() {
        val response = mcpServer.handleRequest("""{"jsonrpc":"2.0","method":"unknown/method","id":5}""")
        assertTrue("Should return error: $response", response.contains("error"))
    }

    @Test fun `invalid JSON returns error`() {
        val response = mcpServer.handleRequest("not json")
        assertTrue("Should handle invalid JSON: $response", response.contains("error"))
    }

    @Test fun `McpClient presets are correctly configured`() {
        val presets = McpClient.PRESETS
        assertTrue("Should have openclaw preset", presets.containsKey("openclaw"))
        assertTrue("Should have qwenpaw preset", presets.containsKey("qwenpaw"))
        assertEquals("openclaw", presets["openclaw"]?.command)
        assertEquals("stdio", presets["openclaw"]?.transport)
    }
}
