// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.mcp

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginContext
import com.mengpaw.kernel.plugin.PluginManager
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginUiButton
import kotlinx.coroutines.runBlocking
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

    // ── tools/call (v0.22.1 新增) ──────────────────────────────────────

    private fun testPlugin(): Plugin = object : Plugin {
        override val metadata = PluginMetadata(
            id = "echo-plugin", name = "echo", version = "1.0.0",
            author = "test", minCoreVersion = "0.2.0"
        )
        private val hiHandler: com.mengpaw.kernel.plugin.CommandHandler = { args, _ ->
            ExecutionResult.ok("hello ${args.firstOrNull() ?: "world"}")
        }
        override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
            "hi" to hiHandler
        )
        override val uiButtons: List<PluginUiButton> = emptyList()
        override suspend fun onInstall(context: PluginContext) {}
        override suspend fun onUninstall() {}
        override suspend fun onUpgrade(newVersion: String) {}
    }

    @Test fun `tools call executes plugin command`() = runBlocking {
        val pm = PluginManager("0.20.0")
        assertTrue(pm.install(testPlugin()).isSuccess)
        assertTrue(pm.activate("echo-plugin").isSuccess)
        val server = McpServer(pm)
        val response = server.handleRequest(
            """{"jsonrpc":"2.0","method":"tools/call","params":{"name":"echo.hi","arguments":{"name":"mcp"}},"id":9}"""
        )
        assertTrue("应执行插件命令: $response", response.contains("hello mcp"))
        assertFalse("不应报错: $response", response.contains("error"))
    }

    @Test fun `tools call unknown tool returns error`() = runBlocking {
        val pm = PluginManager("0.20.0")
        assertTrue(pm.install(testPlugin()).isSuccess)
        assertTrue(pm.activate("echo-plugin").isSuccess)
        val server = McpServer(pm)
        val response = server.handleRequest(
            """{"jsonrpc":"2.0","method":"tools/call","params":{"name":"echo.nonexistent"},"id":10}"""
        )
        assertTrue("未知工具应报错: $response", response.contains("error"))
    }

    @Test fun `tools call provider tool delegates to callTool`() = runBlocking {
        val pm = PluginManager("0.20.0")
        val server = McpServer(pm)
        server.registerToolProvider(object : McpToolProvider {
            override fun getTools(): List<McpTool> =
                listOf(McpTool("test_tool", "test", emptyMap()))
            override fun callTool(name: String, arguments: Map<String, String>): Result<String> =
                Result.success("called with ${arguments["k"]}")
        })
        val response = server.handleRequest(
            """{"jsonrpc":"2.0","method":"tools/call","params":{"name":"test_tool","arguments":{"k":"v"}},"id":11}"""
        )
        assertTrue("provider 工具应委托 callTool: $response", response.contains("called with v"))
    }
}
