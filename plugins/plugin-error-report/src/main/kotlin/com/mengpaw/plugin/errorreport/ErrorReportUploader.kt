// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.errorreport

import com.mengpaw.kernel.error.ErrorCollector
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 错误上报执行器 — 从 ErrorReportPlugin 拆分 (职责: 上传调度与执行)。
 *
 * 依赖通过构造参数注入 (参照 SessionCompressor 模式): token/endpoint/
 * WiFi 门禁/上传中标志均由插件侧闭包提供, 行为与拆分前完全一致。
 */
internal class ErrorReportUploader(
    private val scope: CoroutineScope,
    private val client: HttpClient,
    private val tokenProvider: () -> String,
    private val endpointProvider: () -> String,
    private val wifiOnlyProvider: () -> Boolean,
    private val wifiConnectedProvider: () -> Boolean,
    private val uploadingProvider: () -> Boolean,
    private val setUploading: (Boolean) -> Unit
) {
    private var uploadJob: Job? = null

    /** 取消延时的待上传任务 (onUninstall 时调用, 语义同原 uploadJob?.cancel())。 */
    fun cancelScheduledUpload() {
        uploadJob?.cancel()
    }

    fun scheduleUpload() {
        if (wifiOnlyProvider() && !wifiConnectedProvider()) return
        uploadJob?.cancel()
        uploadJob = scope.launch {
            delay(30_000) // 30s debounce — wait for network to stabilize
            doUpload()
        }
    }

    suspend fun doUpload(): Result<Pair<Int, Int>> {
        if (uploadingProvider() || tokenProvider().isEmpty()) return Result.failure(RuntimeException("Not configured"))
        setUploading(true)
        var uploaded = 0
        var failed = 0

        try {
            val pending = ErrorCollector.pendingUploads()
            if (pending.isEmpty()) {
                setUploading(false)
                return Result.success(0 to 0)
            }

            // Batch upload in groups of 10
            val batch = pending.take(10)
            val body = buildString {
                appendLine("## MengPaw 错误报告")
                appendLine()
                appendLine("> 自动上报 · ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
                appendLine()
                batch.forEach { e ->
                    appendLine("### ${e.id} — ${e.source}")
                    appendLine("- **类型**: ${e.type.name}")
                    appendLine("- **时间**: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.timestamp))}")
                    appendLine("- **Agent**: ${e.agentName ?: "N/A"}")
                    appendLine("- **消息**: ${e.message}")
                    if (e.stackTrace.isNotBlank()) {
                        appendLine("- **堆栈**:")
                        appendLine("```")
                        appendLine(e.stackTrace.take(1500))
                        appendLine("```")
                    }
                    if (e.metadata.isNotEmpty()) {
                        appendLine("- **元数据**: ${e.metadata.map { "${it.key}=${it.value}" }.joinToString(", ")}")
                    }
                    appendLine()
                    appendLine("---")
                    appendLine()
                }
            }

            val response = client.post(endpointProvider()) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer ${tokenProvider()}")
                setBody("""{"title":"错误报告 ${batch.first().id}..${batch.last().id}","body":${buildJsonString(body)}}""")
            }

            if (response.status.isSuccess()) {
                uploaded = batch.size
                ErrorCollector.markReported(batch.map { it.id })
            } else {
                failed = batch.size
            }
        } catch (e: Exception) {
            failed = ErrorCollector.pendingUploads().size.coerceAtMost(10)
        } finally {
            setUploading(false)
        }

        return Result.success(uploaded to failed)
    }

    private fun buildJsonString(text: String): String {
        return "\"" + text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""
    }
}
