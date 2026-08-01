// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.mcp

import com.mengpaw.kernel.plugin.PluginManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/**
 * MCP (Model Context Protocol) Server — 将 MengPaw 插件命令暴露为 MCP Tools。
 *
 * 原语:
 * - Tools: 插件命令自动映射
 * - Resources: Agent 文档 + 记忆索引
 * - Prompts: ReAct 模板 + 插件搜索模板
 *
 * 扩展点:
 * - McpToolProvider / McpResourceProvider — 插件实现这些接口来注册自定义能力
 */
class McpServer(private val pluginManager: PluginManager) {
    private val toolProviders = mutableListOf<McpToolProvider>()
    private val resourceProviders = mutableListOf<McpResourceProvider>()

    fun registerToolProvider(p: McpToolProvider) { toolProviders.add(p) }
    fun registerResourceProvider(p: McpResourceProvider) { resourceProviders.add(p) }

    fun listTools(): List<McpTool> {
        val tools = mutableListOf<McpTool>()
        pluginManager.getActivePlugins().forEach { plugin ->
            val ns = plugin.metadata.id.removeSuffix("-plugin").removeSuffix("-ext")
            plugin.commands.keys.forEach { cmd ->
                tools.add(McpTool("$ns.$cmd", "${plugin.metadata.name}: $cmd",
                    mapOf("type" to "object", "properties" to emptyMap<String, Any>())))
            }
        }
        toolProviders.forEach { tools.addAll(it.getTools()) }
        return tools
    }

    fun listResources(): List<McpResource> {
        val res = mutableListOf(
            McpResource("agents://docs/Agents.md", "Agent 安全规则", "模型安全行为约束", "text/markdown"),
            McpResource("agents://docs/Soul.md", "Agent 灵魂设定", "执行风格与模式", "text/markdown"),
            McpResource("agents://docs/CLI.md", "CLI 命令参考", "框架与插件命令", "text/markdown"),
            McpResource("memory://index", "记忆索引", "被动查询记忆索引", "application/json")
        )
        resourceProviders.forEach { res.addAll(it.getResources()) }
        return res
    }

    fun listPrompts(): List<McpPrompt> = listOf(
        McpPrompt("react-agent", "ReAct Agent 系统提示模板", listOf(McpPromptArgument("task", "用户任务描述"))),
        McpPrompt("plugin-search", "帮助 Agent 搜索合适的插件", listOf(McpPromptArgument("requirement", "能力需求描述")))
    )

    fun getPrompt(name: String, args: Map<String, String>): String = when (name) {
        "react-agent" -> "你是 ReAct Agent。使用 Thought→Action→Final Answer 格式执行: ${args["task"]}"
        else -> ""
    }

    /** Handle JSON-RPC MCP request. */
    fun handleRequest(jsonRpc: String): String {
        return try {
            val req = Json.parseToJsonElement(jsonRpc).jsonObject
            val method = req["method"]?.jsonPrimitive?.content ?: return err(null, "Missing method")
            val id = req["id"]?.jsonPrimitive?.content
            when (method) {
                "tools/list" -> ok(id, toolsListJson())
                "tools/call" -> runBlocking { callTool(id, req["params"]?.jsonObject ?: JsonObject(emptyMap())) }
                "resources/list" -> ok(id, resourcesListJson())
                "prompts/list" -> ok(id, promptsListJson())
                "prompts/get" -> {
                    val p = req["params"]?.jsonObject
                    val a = p?.get("arguments")?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                    ok(id, promptGetJson(getPrompt(p?.get("name")?.jsonPrimitive?.content ?: "", a)))
                }
                else -> err(id, "Unknown method: $method")
            }
        } catch (e: Exception) { err(null, "Parse error: ${e.message}") }
    }

    /**
     * tools/call — 执行插件命令或 provider 工具。
     * 工具名 `ns.cmd` 匹配 active 插件的命令; 否则尝试 McpToolProvider.callTool。
     * 插件命令直接调 CommandHandler (与 AgentEngine 同款执行语义, 不走进程级 DefaultCommandExecutor)。
     */
    private suspend fun callTool(id: String?, params: JsonObject): String {
        val name = params["name"]?.jsonPrimitive?.content ?: return err(id, "Missing tool name")
        val arguments = params["arguments"]?.jsonObject
            ?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()

        // 1. 插件命令: ns.cmd
        val dot = name.indexOf('.')
        if (dot > 0) {
            val ns = name.substring(0, dot)
            val cmd = name.substring(dot + 1)
            val plugin = pluginManager.getActivePlugins().find { p ->
                p.metadata.id.removeSuffix("-plugin").removeSuffix("-ext") == ns
            }
            val handler = plugin?.commands?.get(cmd)
            if (handler != null) {
                val ctx = com.mengpaw.kernel.cli.ExecutionContext(sessionId = "mcp", userId = "mcp")
                val result = handler(arguments.values.toList(), ctx)
                val text = if (result.success) result.output else result.error ?: "Unknown error"
                return ok(id, """{"content":[{"type":"text","text":${JsonPrimitive(text).toString()}}],"isError":${!result.success}}""")
            }
        }

        // 2. Provider 工具
        for (p in toolProviders) {
            val tool = p.getTools().find { it.name == name } ?: continue
            return p.callTool(name, arguments).fold(
                onSuccess = { ok(id, """{"content":[{"type":"text","text":${JsonPrimitive(it).toString()}}]}""") },
                onFailure = { err(id, it.message ?: "Tool call failed") }
            )
        }
        return err(id, "Tool not found: $name")
    }

    private fun ok(id: String?, result: String) = """{"jsonrpc":"2.0","id":$id,"result":$result}"""
    private fun err(id: String?, msg: String) = """{"jsonrpc":"2.0","id":${id ?: "null"},"error":{"code":-32600,"message":"$msg"}}"""

    private fun toolsListJson(): String = buildJsonArray {
        listTools().forEach { t -> addJsonObject { put("name", t.name); put("description", t.description); putJsonObject("inputSchema") { put("type", "object"); putJsonObject("properties") {} } } }
    }.toString()

    private fun resourcesListJson(): String = buildJsonArray {
        listResources().forEach { r -> addJsonObject { put("uri", r.uri); put("name", r.name); put("description", r.description); put("mimeType", r.mimeType) } }
    }.toString()

    private fun promptsListJson(): String = buildJsonArray {
        listPrompts().forEach { p -> addJsonObject { put("name", p.name); put("description", p.description); putJsonArray("arguments") { p.arguments.forEach { a -> addJsonObject { put("name", a.name); put("description", a.description); put("required", a.required) } } } } }
    }.toString()

    private fun promptGetJson(text: String): String = buildJsonObject {
        putJsonArray("messages") { addJsonObject { put("role", "user"); putJsonObject("content") { put("type", "text"); put("text", text) } } }
    }.toString()
}

data class McpTool(val name: String, val description: String, val inputSchema: Map<String, Any>)
data class McpResource(val uri: String, val name: String, val description: String = "", val mimeType: String = "text/plain")
data class McpPrompt(val name: String, val description: String = "", val arguments: List<McpPromptArgument> = emptyList())
data class McpPromptArgument(val name: String, val description: String = "", val required: Boolean = false)

interface McpToolProvider {
    fun getTools(): List<McpTool>

    /** 执行工具 (tools/call 支持)。默认不可调用 — provider 按需覆写。 */
    fun callTool(name: String, arguments: Map<String, String>): Result<String> =
        Result.failure(IllegalStateException("Tool $name is not callable"))
}
interface McpResourceProvider {
    fun getResources(): List<McpResource>
}
