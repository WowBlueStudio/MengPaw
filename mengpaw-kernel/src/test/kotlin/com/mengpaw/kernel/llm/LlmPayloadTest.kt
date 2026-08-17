// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * parseBody 非流式思维链分离提取 (v0.40.4) — 官方文档口径:
 * DeepSeek / Kimi / GLM / Qwen / 豆包 / xAI 均在 message.reasoning_content 返回思维链。
 */
class LlmPayloadTest {

    @Test
    fun `非流式_message_reasoning_content独立提取`() {
        val parsed = parseBody(
            """
            {
              "choices": [{
                "message": {"role": "assistant", "content": "正文", "reasoning_content": "思维链"}
              }],
              "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
            }
            """.trimIndent()
        )
        assertEquals("正文", parsed.content)
        assertEquals("思维链", parsed.reasoning)
        assertEquals(10, parsed.usage?.promptTokens)
        assertEquals(5, parsed.usage?.completionTokens)
        assertEquals(15, parsed.usage?.totalTokens)
    }

    @Test
    fun `非流式_无思维链时reasoning为null`() {
        val parsed = parseBody(
            """
            {"choices": [{"message": {"role": "assistant", "content": "只有正文"}}]}
            """.trimIndent()
        )
        assertEquals("只有正文", parsed.content)
        assertNull(parsed.reasoning)
        assertNull(parsed.usage)
    }

    @Test
    fun `非流式_思维链含Final Answer字样_不污染content`() {
        val parsed = parseBody(
            """
            {"choices": [{"message": {
              "role": "assistant",
              "content": "这是最终答案",
              "reasoning_content": "草稿方案\nFinal Answer: 先自查再定稿"
            }}]}
            """.trimIndent()
        )
        assertEquals("这是最终答案", parsed.content)
        assertEquals("草稿方案\nFinal Answer: 先自查再定稿", parsed.reasoning)
    }

    @Test
    fun `非法JSON_回退原始文本`() {
        val parsed = parseBody("not json at all")
        assertEquals("not json at all", parsed.content)
        assertNull(parsed.reasoning)
        assertNull(parsed.usage)
    }

    @Test
    fun `请求体不回显思维链_保持role和content线形`() {
        val body = buildRequestBody(
            model = "kimi-k3",
            config = AdaptiveLlmProvider.AdaptiveConfig(),
            messages = listOf(
                mapOf("role" to "system", "content" to "sys"),
                mapOf("role" to "assistant", "content" to "正文")
            )
        )
        // 用户定案仅响应侧解析: 思维链不回传 (Kimi 保留式思考官方要求回传, 属请求侧范围未实现;
        // DeepSeek 官方文档: 无工具调用时回传被忽略; Ollama /v1 回传 reasoning_content 曾致挂起)
        assertFalse("请求体不得出现 reasoning 键", body.contains("reasoning"))
        assertFalse("请求体不得出现 thinking 键", body.contains("thinking"))
        assertFalse("请求体不得出现 thought 键", body.contains("thought"))
    }

    @Test
    fun `MiniMax默认格式_think内联标签剥离进reasoning`() {
        // 官方原文 (platform.minimaxi.com): reasoning_split 为 false 时 thinking 保留在
        // content 字段的 <think>...</think> 标签内 — 响应侧剥离, 绝不混入正文
        val parsed = parseBody(
            """
            {"choices": [{"message": {
              "role": "assistant",
              "content": "<think>草稿方案</think>这是最终答案"
            }}]}
            """.trimIndent()
        )
        assertEquals("这是最终答案", parsed.content)
        assertEquals("草稿方案", parsed.reasoning)
    }

    @Test
    fun `MiniMax非流式_reasoning_details独立提取`() {
        // 官方工具使用&交错思维链文档: reasoning_split=true 时 thinking 经
        // reasoning_details 数组返回, 每项 {type, id, format, index, text}
        val parsed = parseBody(
            """
            {"choices": [{"message": {
              "role": "assistant",
              "content": "正文",
              "reasoning_details": [
                {"type": "reasoning.text", "id": "r1", "format": "MiniMax-response-v1", "index": 0, "text": "思考全文"}
              ]
            }}]}
            """.trimIndent()
        )
        assertEquals("正文", parsed.content)
        assertEquals("思考全文", parsed.reasoning)
    }

    @Test
    fun `双通道同现_独立字段优先_不拼接内联思考`() {
        val parsed = parseBody(
            """
            {"choices": [{"message": {
              "role": "assistant",
              "content": "<think>内联思考</think>正文",
              "reasoning_content": "独立思考"
            }}]}
            """.trimIndent()
        )
        assertEquals("正文", parsed.content)
        assertEquals("独立思考", parsed.reasoning)
    }
}
