// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.comfy

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.cli.ErrorCodes
import com.mengpaw.core.plugin.Plugin
import com.mengpaw.core.plugin.PluginMetadata
import com.mengpaw.core.plugin.PluginType
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import com.mengpaw.core.DataPaths
import java.io.File

class ComfyPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "comfy-plugin", name = "ComfyUI工作流", version = "1.0.0",
        type = PluginType.NATIVE, author = "MengPaw",
        description = "ComfyUI工作流搭建：节点编排/JSON导出/API执行/MP浏览器预览",
        permissions = emptyList(), minCoreVersion = "0.2.0",
        commands = listOf("comfy.nodes", "comfy.workflow", "comfy.run", "comfy.preview", "comfy.export")
    )

    override val commands: Map<String, com.mengpaw.core.plugin.CommandHandler> = mapOf(
        "nodes" to { a, c -> this@ComfyPlugin.nodes(a, c) },
        "workflow" to { a, c -> this@ComfyPlugin.workflow(a, c) },
        "run" to { a, c -> this@ComfyPlugin.run(a, c) },
        "preview" to { a, c -> this@ComfyPlugin.preview(a, c) },
        "export" to { a, c -> this@ComfyPlugin.exportWf(a, c) }
    )

    private val client = HttpClient(OkHttp)
    private val wfDir = File(DataPaths.COMFY_WORKFLOWS).also { it.mkdirs() }
    private val outDir = File(DataPaths.COMFY_OUTPUTS).also { it.mkdirs() }

    private val NODE_CATALOG = mapOf(
        "loaders" to listOf("CheckpointLoaderSimple" to "加载底模", "LoadImage" to "加载图片", "VAELoader" to "加载VAE"),
        "conditioning" to listOf("CLIPTextEncode" to "正向提示词", "CLIPTextEncodeNegative" to "反向提示词"),
        "sampling" to listOf("KSampler" to "采样器", "KSamplerAdvanced" to "高级采样器"),
        "latent" to listOf("EmptyLatentImage" to "空潜空间", "VAEDecode" to "VAE解码", "VAEEncode" to "VAE编码"),
        "image" to listOf("SaveImage" to "保存图片", "PreviewImage" to "预览", "ImageUpscale" to "超分"),
        "advanced" to listOf("ControlNetLoader" to "加载ControlNet", "ControlNetApply" to "应用ControlNet", "IPAdapterApply" to "IPAdapter")
    )

    private suspend fun nodes(a: List<String>, c: ExecutionContext): ExecutionResult {
        val cat = a.firstOrNull()
        if (cat != null) {
            val nodes = NODE_CATALOG[cat] ?: return ExecutionResult.fail("Unknown: $cat", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            return ExecutionResult.ok("## $cat\n\n${nodes.joinToString("\n") { "### ${it.first}\n${it.second}\n\n> 参数设置前查询社区公开信息 (civitai.com)" }}")
        }
        return ExecutionResult.ok("## 节点分类\n${NODE_CATALOG.map { "| ${it.key} | ${it.value.size} |" }.joinToString("\n") { "| $it" }}\n\n查询: comfy.nodes <category>")
    }

    private suspend fun workflow(a: List<String>, c: ExecutionContext): ExecutionResult {
        if (a.isEmpty()) return ExecutionResult.fail("Usage: comfy.workflow create|add|connect|show|list", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        when (a[0]) {
            "create" -> {
                if (a.size < 2) return ExecutionResult.fail("Usage: comfy.workflow create <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
                val f = File(wfDir, "${a[1]}.json")
                if (f.exists()) return ExecutionResult.ok("Already exists: ${a[1]}")
                f.writeText("""{"nodes":[],"links":[],"name":"${a[1]}"}""")
                return ExecutionResult.ok("Created: ${a[1]}")
            }
            "add" -> {
                if (a.size < 4) return ExecutionResult.fail("Usage: comfy.workflow add <wf> <nodeId> <type> [k=v...]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
                val wf = loadWf(a[1]) ?: return ExecutionResult.fail("Not found: ${a[1]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
                val params = a.drop(4).flatMap { it.split("=", limit = 2).takeIf { p -> p.size == 2 } ?: emptyList() }.windowed(2, 2) { it[0] to it[1] }.toMap()
                val json = buildJsonObject { putJsonObject(a[2]) { put("class_type", a[3]); putJsonObject("inputs") { params.forEach { (k, v) -> put(k, JsonPrimitive(v)) } } } }
                wf["nodes"] = (wf["nodes"] as MutableList<JsonElement>).apply { add(json) }
                saveWf(a[1], wf)
                return ExecutionResult.ok("Added ${a[2]} (${a[3]}) to ${a[1]}")
            }
            "connect" -> {
                if (a.size < 4) return ExecutionResult.fail("Usage: comfy.workflow connect <wf> <from:slot> <to:slot>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
                val wf = loadWf(a[1]) ?: return ExecutionResult.fail("Not found: ${a[1]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
                val from = a[2].split(":"); val to = a[3].split(":")
                val link = buildJsonArray { add(JsonPrimitive(0)); add(JsonPrimitive(0)); add(JsonPrimitive(from[0])); add(JsonPrimitive(from.getOrElse(1) { "0" }.toIntOrNull() ?: 0)); add(JsonPrimitive(to[0])); add(JsonPrimitive(to.getOrElse(1) { "0" }.toIntOrNull() ?: 0)) }
                wf["links"] = (wf["links"] as MutableList<JsonElement>).apply { add(link) }
                saveWf(a[1], wf)
                return ExecutionResult.ok("Connected ${a[2]} -> ${a[3]}")
            }
            "show" -> {
                if (a.size < 2) return ExecutionResult.fail("Usage: comfy.workflow show <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
                val txt = File(wfDir, "${a[1]}.json").let { if (it.exists()) it.readText().take(3000) else return ExecutionResult.fail("Not found: ${a[1]}", errorCode = ErrorCodes.ERR_NOT_FOUND) }
                return ExecutionResult.ok("Workflow: ${a[1]}\n$txt")
            }
            "list" -> {
                val files = wfDir.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.name } ?: emptyList()
                return if (files.isEmpty()) ExecutionResult.ok("(No workflows)") else ExecutionResult.ok(files.joinToString("\n") { "• ${it.nameWithoutExtension} (${it.length() / 1024}KB)" })
            }
        }
        return ExecutionResult.fail("Unknown: ${a[0]}", errorCode = ErrorCodes.ERR_INVALID_INPUT)
    }

    private suspend fun run(a: List<String>, c: ExecutionContext): ExecutionResult {
        if (a.size < 2) return ExecutionResult.fail("Usage: comfy.run <wf> [api-url=http://localhost:8188]\n⚠️ 执行前确认参数已参考社区公开信息设置", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val url = a.find { it.startsWith("api-url=") }?.removePrefix("api-url=") ?: "http://localhost:8188"
        val f = File(wfDir, "${a[0]}.json")
        if (!f.exists()) return ExecutionResult.fail("Not found: ${a[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
        return try {
            val body = buildJsonObject { put("prompt", Json.parseToJsonElement(f.readText())) }
            val resp = client.post("$url/prompt") { contentType(ContentType.Application.Json); setBody(body.toString()) }
            val id = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["prompt_id"]?.jsonPrimitive?.content ?: return ExecutionResult.fail("No prompt_id", errorCode = ErrorCodes.ERR_INTERNAL)
            ExecutionResult.ok("Submitted. Prompt: $id")
        } catch (e: Exception) { ExecutionResult.fail("ComfyUI not reachable: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    private suspend fun preview(a: List<String>, c: ExecutionContext): ExecutionResult = ExecutionResult.ok("Preview in MP Browser at ComfyUI output URL.")
    private suspend fun exportWf(a: List<String>, c: ExecutionContext): ExecutionResult {
        if (a.size < 2) return ExecutionResult.fail("Usage: comfy.export <wf> json", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val f = File(wfDir, "${a[0]}.json")
        if (!f.exists()) return ExecutionResult.fail("Not found: ${a[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
        val dst = File(outDir, "${a[0]}.json"); f.copyTo(dst, overwrite = true)
        return ExecutionResult.ok("Exported: ${dst.absolutePath}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadWf(name: String): MutableMap<String, Any>? {
        val f = File(wfDir, "$name.json")
        if (!f.exists()) return null
        return try { (Json.parseToJsonElement(f.readText()) as? JsonObject)?.toMap()?.mapValues { it.value.toMutableValue() }?.toMutableMap() } catch (e: Exception) { null }
    }

    private fun saveWf(name: String, wf: MutableMap<String, Any>) {
        val json = buildJsonObject { wf.forEach { (k, v) -> put(k, v.toJsonElement()) } }
        File(wfDir, "$name.json").writeText(json.toString())
    }

    private fun Any.toMutableValue(): Any = when (this) {
        is JsonObject -> this.toMap().mapValues { it.value.toMutableValue() }.toMutableMap()
        is JsonArray -> this.map { it.toMutableValue() }.toMutableList()
        else -> this
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any.toJsonElement(): JsonElement = when (this) {
        is Map<*, *> -> buildJsonObject { (this as Map<String, Any>).forEach { (k, v) -> put(k, v.toJsonElement()) } }
        is List<*> -> buildJsonArray { (this@toJsonElement as List<*>).forEach { add((it as Any).toJsonElement()) } }
        is String -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }
}
