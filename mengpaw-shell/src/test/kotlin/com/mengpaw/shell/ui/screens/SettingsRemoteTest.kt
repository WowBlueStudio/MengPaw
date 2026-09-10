// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型列表探测 URL 派生 (v0.46.2 修复): 按各厂商官方文档的模型列表路径派生 —
 * 去掉 `/chat/completions` 后接 `/models`, 且不得凭空补出 `.../v1/v1/models`。
 * 覆盖用户 2026-09-10 反馈的「刷新模型列表拿不到正确结果」两类路径缺陷。
 */
class SettingsRemoteTest {

    @Test
    fun `DeepSeek走官方GET_models_先不打v1`() {
        // 官方 api-docs.deepseek.com: base_url(OpenAI) = https://api.deepseek.com, GET /models
        val urls = modelsProbeUrls("https://api.deepseek.com/chat/completions")
        assertEquals("https://api.deepseek.com/models", urls.first())
        assertEquals(listOf("https://api.deepseek.com/models", "https://api.deepseek.com/v1/models"), urls)
    }

    @Test
    fun `已带v1的端点不再补v1_不产生重复版本段`() {
        for (endpoint in listOf(
            "https://api.openai.com/v1/chat/completions",
            "https://api.moonshot.cn/v1/chat/completions",
            "https://api.x.ai/v1/chat/completions",
            "https://api.minimaxi.com/v1/chat/completions",
            "https://api.openmodel.ai/v1/chat/completions",
            "http://192.168.1.100:9880/v1/chat/completions"
        )) {
            val urls = modelsProbeUrls(endpoint)
            assertEquals(1, urls.size)
            assertTrue("应派生 /v1/models: $urls", urls.first().endsWith("/v1/models"))
            assertFalse("不得出现 /v1/v1: $urls", urls.any { it.contains("/v1/v1/") })
        }
    }

    @Test
    fun `DashScope与GLM保留各自版本段路径`() {
        // 修复前: DashScope 被裁掉 /compatible-mode/v1 → 两个候选都不存在, 刷新恒空
        assertEquals(
            listOf("https://dashscope.aliyuncs.com/compatible-mode/v1/models"),
            modelsProbeUrls("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
        )
        assertEquals(
            listOf("https://open.bigmodel.cn/api/paas/v4/models"),
            modelsProbeUrls("https://open.bigmodel.cn/api/paas/v4/chat/completions")
        )
        assertEquals(
            listOf("https://ark.cn-beijing.volces.com/api/v3/models"),
            modelsProbeUrls("https://ark.cn-beijing.volces.com/api/v3/chat/completions")
        )
    }

    @Test
    fun `空端点与尾斜杠容错`() {
        assertTrue("空端点不得发出请求", modelsProbeUrls("").isEmpty())
        assertTrue("纯空白不得发出请求", modelsProbeUrls("   ").isEmpty())
        assertEquals(
            listOf("https://api.deepseek.com/models", "https://api.deepseek.com/v1/models"),
            modelsProbeUrls("https://api.deepseek.com/chat/completions/")
        )
    }
}
