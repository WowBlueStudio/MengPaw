// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

// ── 设置页远程 HTTP 探测 — 拆自 SettingsViewModel.kt (2026-08-06, >400 行文件拆分批次4) ──
// 纯阻塞 IO 函数, 调用方负责切 IO 调度器; 语义与原 ViewModel 内联实现逐行对齐。

/**
 * 从 provider 的 GET /models 端点抓取模型列表。
 * 候选路径双尝试 (v1/models + models), 短超时 (3s/5s) 防 ANR; 空结果返回空列表。
 */
internal fun fetchModelsFromEndpoint(endpoint: String, apiKey: String): List<String> {
    val base = endpoint.substringBefore("/chat/completions")
        .substringBefore("/v1/chat")
        .substringBefore("/compatible-mode/v1")

    // Try both common paths
    val candidatePaths = listOf("$base/v1/models", "$base/models")
    var models: List<String> = emptyList()

    for (url in candidatePaths) {
        try {
            val client = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            client.connectTimeout = 3000; client.readTimeout = 5000
            client.setRequestProperty("Authorization", "Bearer $apiKey")
            val body = client.inputStream.bufferedReader().readText()
            client.disconnect()

            val parsed = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(body)
                .map { it.groupValues[1] }
                .filter { id ->
                    id.length < 80 && !id.contains(":") &&
                    !id.startsWith("dall-e") && !id.startsWith("whisper") &&
                    !id.startsWith("tts") && !id.contains("embedding") &&
                    !id.contains("moderation") && !id.contains("babbage") &&
                    !id.contains("davinci")
                }
                .toList()

            if (parsed.isNotEmpty()) {
                models = parsed
                break
            }
        } catch (_: Exception) { /* try next URL */ }
    }
    return models
}

/**
 * 探测 provider /models 端点连通性 — 返回 "OK" / "Err <code>" / 抛异常 (由调用方转 "Error")。
 */
internal fun testConnectionResult(endpoint: String, apiKey: String): String {
    val base = endpoint.substringBefore("/chat/completions").substringBefore("/v1/chat")
    val url = java.net.URL("$base/v1/models")
    val conn = url.openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = 10000; conn.readTimeout = 10000
    if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
    val code = conn.responseCode
    conn.disconnect()
    return if (code in 200..299) "OK" else "Err $code"
}
