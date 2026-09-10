// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

// ── 设置页远程 HTTP 探测 — 拆自 SettingsViewModel.kt (2026-08-06, >400 行文件拆分批次4) ──
// 纯阻塞 IO 函数, 调用方负责切 IO 调度器; 语义与原 ViewModel 内联实现逐行对齐。

/**
 * 由对话端点派生「模型列表」候选 URL (v0.46.2 修复)。
 *
 * 修复前的派生有两条路径级缺陷 (用户 2026-09-10 反馈: 刷新模型列表拿到异常结果):
 * 1. 先试 `$base/v1/models` — base 已含 /v1 时 (OpenAI/Kimi/Grok/MiniMax/自建) 实际发出
 *    `.../v1/v1/models` 死请求, 每次刷新白烧一次往返;
 * 2. base 只按 `/chat/completions`、`/v1/chat`、`/compatible-mode/v1` 三条字面量裁剪 —
 *    DashScope 端点裁掉 `/compatible-mode/v1` 后剩裸域名, 两个候选都不存在 → 刷新恒空。
 *
 * 现按「厂商文档的模型列表路径 = 去掉 /chat/completions 后的端点 + /models」为唯一准则:
 * DeepSeek 官方 `GET /models` (base https://api.deepseek.com) → `.../models` ✓;
 * OpenAI `.../v1/models` ✓; DashScope `.../compatible-mode/v1/models` ✓; GLM `.../paas/v4/models` ✓。
 * 仅在 base 未带版本段时补一次 `/v1/models` 兜底 (部分自建网关只实现 /v1)。
 */
internal fun modelsProbeUrls(endpoint: String): List<String> {
    val trimmed = endpoint.trim().trimEnd('/')
    if (trimmed.isBlank()) return emptyList()
    val base = trimmed.substringBefore("/chat/completions")
        .substringBefore("/completions")
        .substringBefore("/v1/chat")
    if (base.isBlank()) return emptyList()
    val urls = mutableListOf("$base/models")
    // 已带版本段的端点不再追加 /v1 (避免 .../v1/v1/models)
    val hasVersionSegment = base.endsWith("/v1") || base.contains("/v1/") || base.contains("/api/v3") ||
        base.contains("/api/paas/")
    if (!hasVersionSegment) urls += "$base/v1/models"
    return urls
}

/**
 * 从 provider 的 GET /models 端点抓取模型列表。
 * 候选路径按厂商文档顺序尝试, 短超时 (3s/5s) 防 ANR; 空结果返回空列表。
 */
internal fun fetchModelsFromEndpoint(endpoint: String, apiKey: String): List<String> {
    // Try documented paths in order
    val candidatePaths = modelsProbeUrls(endpoint)
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
 * 走与模型列表抓取同一条派生 (v0.46.2: 此前恒拼 `$base/v1/models`, DashScope 等端点恒 Err)。
 */
internal fun testConnectionResult(endpoint: String, apiKey: String): String {
    val url = java.net.URL(modelsProbeUrls(endpoint).firstOrNull() ?: return "N/A")
    val conn = url.openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = 10000; conn.readTimeout = 10000
    if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
    val code = conn.responseCode
    conn.disconnect()
    return if (code in 200..299) "OK" else "Err $code"
}
