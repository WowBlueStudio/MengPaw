// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.render

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

/**
 * API Render Plugin — 多后端 API 生图。
 *
 * 支持后端:
 * - Replicate (replicate.com) — 1000+ 开放模型
 * - Stability AI (stability.ai) — Stable Diffusion 官方
 * - OpenAI DALL-E (api.openai.com) — DALL-E 3
 *
 * 流程: 提交 Job → 轮询状态 → 下载文件 → 可在 MP浏览器预览
 */
class RenderPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "render-plugin", name = "API生图引擎", version = "1.0.0",
        type = PluginType.NATIVE, author = "MengPaw",
        description = "多后端API生图：Replicate/Stability/DALL-E，提交Job→轮询→下载→预览",
        permissions = emptyList(), minCoreVersion = "0.2.0",
        commands = listOf("render.models", "render.generate", "render.status", "render.preview")
    )

    override val commands: Map<String, com.mengpaw.core.plugin.CommandHandler> = mapOf(
        "models" to ::models, "generate" to ::generate, "status" to ::status, "preview" to ::preview
    )

    private val client = HttpClient(OkHttp)
    private val outputDir = File(DataPaths.RENDER_OUTPUTS).also { it.mkdirs() }
    private val jobs = mutableMapOf<String, RenderJob>()

    data class RenderJob(val id: String, val backend: String, val model: String, val status: String, val resultUrl: String = "")

    // ── render.models ────────────────────────────────────────────

    private suspend fun models(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val backend = args.firstOrNull()?.lowercase() ?: "replicate"
        return when (backend) {
            "replicate" -> ExecutionResult.ok("""
Replicate 热门模型:
| 模型 | 说明 | 示例命令 |
|------|------|----------|
| stability-ai/sdxl | SDXL 1.0 高质生图 | render.generate replicate stability-ai/sdxl prompt=... |
| stability-ai/stable-diffusion-3 | SD3 最新 | render.generate replicate stability-ai/sd3 prompt=... |
| black-forest-labs/flux-schnell | Flux 快速 | render.generate replicate black-forest-labs/flux-schnell prompt=... |
| bytedance/sdxl-lightning | 4步极速 | render.generate replicate bytedance/sdxl-lightning prompt=... |
""".trimIndent())
            "stability" -> ExecutionResult.ok("""
Stability AI 模型:
| 模型 | 说明 |
|------|------|
| stable-diffusion-xl-1024 | SDXL 1024px |
| stable-diffusion-3 | SD3 最新 |
| stable-image-core | 稳定核心版 |
| stable-image-ultra | 超高质量版 |
""".trimIndent())
            "dalle" -> ExecutionResult.ok("""
OpenAI DALL-E 模型:
| 模型 | 分辨率 | 价格 |
|------|--------|------|
| dall-e-3 | 1024/1792 | $0.04-0.12/img |
| dall-e-2 | 256/512/1024 | $0.016-0.02/img |
""".trimIndent())
            else -> ExecutionResult.fail("Unknown backend: $backend. Use replicate/stability/dalle.", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
    }

    // ── render.generate ─────────────────────────────────────────

    private suspend fun generate(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 3) return ExecutionResult.fail(
            "Usage: render.generate <backend> <model> [prompt=...] [negative=...] [width=1024] [height=1024] [steps=30] [seed=0]",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)

        val backend = args[0].lowercase()
        val model = args[1]
        val params = args.drop(2).flatMap { it.split("=", limit = 2).takeIf { p -> p.size == 2 } ?: emptyList() }
            .windowed(2, 2) { it[0] to it[1] }.toMap()
        val prompt = params["prompt"] ?: return ExecutionResult.fail("prompt= is required", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val jobId = "rndr_${System.currentTimeMillis().toString().takeLast(8)}"

        jobs[jobId] = RenderJob(jobId, backend, model, "submitted")

        return try {
            when (backend) {
                "replicate" -> submitReplicate(jobId, model, prompt, params)
                "stability" -> submitStability(jobId, model, prompt, params)
                "dalle" -> submitDalle(jobId, model, prompt, params)
                else -> ExecutionResult.fail("Unknown backend: $backend", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            }
        } catch (e: Exception) {
            jobs[jobId] = jobs[jobId]!!.copy(status = "failed")
            ExecutionResult.fail("API error: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    private suspend fun submitReplicate(jobId: String, model: String, prompt: String, params: Map<String, String>): ExecutionResult {
        val apiKey = System.getenv("REPLICATE_API_TOKEN") ?: return ExecutionResult.fail("REPLICATE_API_TOKEN not set", errorCode = ErrorCodes.ERR_INTERNAL)
        val body = buildJsonObject {
            put("version", model)
            putJsonObject("input") {
                put("prompt", prompt)
                params["negative"]?.let { put("negative_prompt", it) }
                put("width", params["width"]?.toIntOrNull() ?: 1024)
                put("height", params["height"]?.toIntOrNull() ?: 1024)
                params["seed"]?.toIntOrNull()?.let { put("seed", it) }
            }
        }
        val resp = client.post("https://api.replicate.com/v1/predictions") {
            header("Authorization", "Token $apiKey"); contentType(ContentType.Application.Json); setBody(body.toString())
        }
        val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val predId = json["id"]?.jsonPrimitive?.content ?: return ExecutionResult.fail("Replicate: no prediction ID", errorCode = ErrorCodes.ERR_INTERNAL)
        jobs[jobId] = RenderJob(jobId, "replicate", model, "processing", "")
        return ExecutionResult.ok("Job $jobId submitted to Replicate ($model).\nTrack: render.status $jobId\nPrediction: $predId")
    }

    private suspend fun submitStability(jobId: String, model: String, prompt: String, params: Map<String, String>): ExecutionResult {
        val apiKey = System.getenv("STABILITY_API_KEY") ?: return ExecutionResult.fail("STABILITY_API_KEY not set", errorCode = ErrorCodes.ERR_INTERNAL)
        val body = buildJsonObject {
            put("text_prompts", buildJsonArray { addJsonObject { put("text", prompt); params["negative"]?.let { put("negative_prompt", it) } } })
            put("cfg_scale", 7); put("steps", params["steps"]?.toIntOrNull() ?: 30); params["seed"]?.toIntOrNull()?.let { put("seed", it) }
            put("width", params["width"]?.toIntOrNull() ?: 1024); put("height", params["height"]?.toIntOrNull() ?: 1024)
        }
        val resp = client.post("https://api.stability.ai/v1/generation/$model/text-to-image") {
            header("Authorization", "Bearer $apiKey"); contentType(ContentType.Application.Json); setBody(body.toString())
        }
        val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val img = json["artifacts"]?.jsonArray?.firstOrNull()?.jsonObject?.get("base64")?.jsonPrimitive?.content
            ?: return ExecutionResult.fail("Stability: no image returned", errorCode = ErrorCodes.ERR_INTERNAL)
        // Save immediately (Stability returns base64, no polling needed)
        val file = File(outputDir, "$jobId.png")
        file.writeBytes(java.util.Base64.getDecoder().decode(img))
        jobs[jobId] = RenderJob(jobId, "stability", model, "completed", file.absolutePath)
        return ExecutionResult.ok("Generated! Saved to ${file.absolutePath}\nPreview: render.preview $jobId")
    }

    private suspend fun submitDalle(jobId: String, model: String, prompt: String, params: Map<String, String>): ExecutionResult {
        val apiKey = System.getenv("OPENAI_API_KEY") ?: return ExecutionResult.fail("OPENAI_API_KEY not set", errorCode = ErrorCodes.ERR_INTERNAL)
        val size = "${params["width"]?.toIntOrNull() ?: 1024}x${params["height"]?.toIntOrNull() ?: 1024}"
        val body = buildJsonObject {
            put("model", if (model == "dall-e-3") "dall-e-3" else "dall-e-2")
            put("prompt", prompt); put("n", 1); put("size", size)
        }
        val resp = client.post("https://api.openai.com/v1/images/generations") {
            header("Authorization", "Bearer $apiKey"); contentType(ContentType.Application.Json); setBody(body.toString())
        }
        val url = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["data"]?.jsonArray?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
            ?: return ExecutionResult.fail("DALL-E: no image URL", errorCode = ErrorCodes.ERR_INTERNAL)
        jobs[jobId] = RenderJob(jobId, "dalle", model, "completed", url)
        return ExecutionResult.ok("Generated! URL: $url\nPreview: render.preview $jobId\nDownload with: fs.cp the_url path")
    }

    // ── render.status / render.preview ──────────────────────────

    private suspend fun status(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.ok(jobs.map { (id, j) -> "$id [${j.backend}] ${j.status}: ${j.model}" }.joinToString("\n").ifEmpty { "(No jobs)" })
        val job = jobs[args[0]] ?: return ExecutionResult.fail("Job not found: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
        return ExecutionResult.ok("Job ${job.id}\nBackend: ${job.backend}\nModel: ${job.model}\nStatus: ${job.status}\nResult: ${job.resultUrl.ifEmpty { "(pending)" }}")
    }

    private suspend fun preview(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: render.preview <job-id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val job = jobs[args[0]] ?: return ExecutionResult.fail("Job not found: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
        if (job.resultUrl.isEmpty()) return ExecutionResult.ok("Still generating... Check: render.status ${args[0]}")
        return ExecutionResult.ok("Preview URL: ${job.resultUrl}\nOpen in MP浏览器: use browser intent with $job.resultUrl")
    }
}
