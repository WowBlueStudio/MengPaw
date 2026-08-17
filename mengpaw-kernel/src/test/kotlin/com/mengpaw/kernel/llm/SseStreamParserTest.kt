// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * consumeSseStream 全厂商思维链分流直测 (v0.40.4, 此前零直测)。
 *
 * 夹具格式以各厂商官方 API 文档为唯一准则 (2026-08-17 核对):
 * - OpenAI 兼容 delta.reasoning_content: DeepSeek / Kimi / GLM / Qwen / 豆包 / xAI
 * - Ollama 新版 /v1 delta.reasoning: 官方文档未记载, 兼容兜底
 * - Anthropic content_block_delta thinking_delta/signature_delta/text_delta: platform.claude.com
 */
class SseStreamParserTest {

    private class StreamResult(
        val content: String,
        val tokens: List<String>,
        val reasoning: List<String>,
        val usages: List<TokenUsage>
    )

    /** 用 Ktor MockEngine 构造真实 HttpResponse, 走完整 bodyAsChannel 读取链路。 */
    private suspend fun runSse(body: String): StreamResult {
        val engine = MockEngine {
            respond(body, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val client = HttpClient(engine)
        try {
            val response: HttpResponse = client.get("http://localhost/v1/chat/completions")
            val tokens = mutableListOf<String>()
            val reasoning = mutableListOf<String>()
            val usages = mutableListOf<TokenUsage>()
            val content = consumeSseStream(
                response = response,
                onToken = { tokens.add(it) },
                requestStart = 0L,
                onUsage = { usages.add(it) },
                onReasoning = { reasoning.add(it) }
            )
            return StreamResult(content, tokens, reasoning, usages)
        } finally {
            client.close()
        }
    }

    @Test
    fun `DeepSeek官方格式_reasoning_content分流_不进content`() = runTest {
        val r = runSse(
            """
            data: {"id":"1","choices":[{"delta":{"reasoning_content":"先分析需求"}}]}

            data: {"choices":[{"delta":{"reasoning_content":"再规划步骤"}}]}

            data: {"choices":[{"delta":{"content":"最终答案"}}]}

            data: [DONE]
            """.trimIndent()
        )
        assertEquals("思维链不得进入返回正文", "最终答案", r.content)
        assertEquals(listOf("最终答案"), r.tokens)
        assertEquals(listOf("先分析需求", "再规划步骤"), r.reasoning)
    }

    @Test
    fun `Ollama新版v1兼容键_reasoning分流`() = runTest {
        val r = runSse(
            """
            data: {"choices":[{"delta":{"reasoning":"思考片段"}}]}

            data: {"choices":[{"delta":{"content":"答案"}}]}

            data: [DONE]
            """.trimIndent()
        )
        assertEquals("答案", r.content)
        assertEquals(listOf("思考片段"), r.reasoning)
    }

    @Test
    fun `兼容兜底键_thought与thinking分流`() = runTest {
        val r = runSse(
            """
            data: {"choices":[{"delta":{"thought":"思考A"}}]}

            data: {"choices":[{"delta":{"thinking":"思考B"}}]}

            data: {"choices":[{"delta":{"content":"正文"}}]}

            data: [DONE]
            """.trimIndent()
        )
        assertEquals("正文", r.content)
        assertEquals(listOf("思考A", "思考B"), r.reasoning)
    }

    @Test
    fun `Anthropic官方格式_thinking_delta进onReasoning_text_delta进onToken`() = runTest {
        val r = runSse(
            """
            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"思考一"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"思考二"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"text"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"正文"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

            data: [DONE]
            """.trimIndent()
        )
        assertEquals("正文", r.content)
        assertEquals(listOf("正文"), r.tokens)
        assertEquals("signature_delta 不得进入正文/思维链", listOf("思考一", "思考二"), r.reasoning)
    }

    @Test
    fun `仅思维链无正文_返回空串且onToken零调用`() = runTest {
        val r = runSse(
            """
            data: {"choices":[{"delta":{"reasoning_content":"只思考不回答"}}]}

            data: [DONE]
            """.trimIndent()
        )
        assertEquals("", r.content)
        assertTrue(r.tokens.isEmpty())
        assertEquals(listOf("只思考不回答"), r.reasoning)
    }

    @Test
    fun `思维链与正文交错到达_两通道互不污染`() = runTest {
        val r = runSse(
            """
            data: {"choices":[{"delta":{"reasoning_content":"R1"}}]}

            data: {"choices":[{"delta":{"content":"A1"}}]}

            data: {"choices":[{"delta":{"reasoning_content":"R2"}}]}

            data: {"choices":[{"delta":{"content":"A2"}}]}

            data: [DONE]
            """.trimIndent()
        )
        assertEquals("A1A2", r.content)
        assertEquals(listOf("A1", "A2"), r.tokens)
        assertEquals(listOf("R1", "R2"), r.reasoning)
    }

    @Test
    fun `行内usage回调与正文共存`() = runTest {
        val r = runSse(
            """
            data: {"choices":[{"delta":{"content":"正文"}}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15,"prompt_cache_hit_tokens":3,"prompt_cache_miss_tokens":7}}

            data: [DONE]
            """.trimIndent()
        )
        assertEquals("正文", r.content)
        assertEquals(1, r.usages.size)
        val u = r.usages[0]
        assertEquals(10, u.promptTokens)
        assertEquals(5, u.completionTokens)
        assertEquals(15, u.totalTokens)
        assertEquals(3, u.cacheHitTokens)
        assertEquals(7, u.cacheMissTokens)
    }

    @Test
    fun `畸形行跳过_不影响后续增量`() = runTest {
        val r = runSse(
            """
            data: {"choices":[{"delta":{"content":"A"}}]}

            data: not-json

            data: {"choices":[{"delta":{"content":"B"}}]}

            data: [DONE]
            """.trimIndent()
        )
        assertEquals("AB", r.content)
        assertEquals(listOf("A", "B"), r.tokens)
    }

    @Test
    fun `reasoning_content非字符串_整行content不丢`() = runTest {
        val r = runSse(
            """
            data: {"choices":[{"delta":{"reasoning_content":{"encrypted":"x"},"content":"正文"}}]}

            data: [DONE]
            """.trimIndent()
        )
        assertEquals("正文", r.content)
        assertTrue("非字符串思维链不得误进 onReasoning", r.reasoning.isEmpty())
    }

    @Test
    fun `MiniMax官方_reasoning_details累计全文_增量去重`() = runTest {
        // 官方 OpenAI SDK 流式示例 (platform.minimaxi.com): 每个 delta 的 reasoning_details
        // 数组项 text 为当前块累计全文, 示例按 len(reasoning_buffer) 取新增部分
        val r = runSse(
            """
            data: {"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.text","text":"先分析"}]}}]}

            data: {"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.text","text":"先分析再规划"}]}}]}

            data: {"choices":[{"delta":{"content":"最终答案"}}]}

            data: [DONE]
            """.trimIndent()
        )
        assertEquals("最终答案", r.content)
        assertEquals(listOf("先分析", "再规划"), r.reasoning)
    }

    @Test
    fun `MiniMax默认格式_think内联标签剥离_思维链走onReasoning`() = runTest {
        // 官方原文: reasoning_split 为 false 时 thinking 保留在 content 的 <think>...</think> 标签内
        val r = runSse(
            """
            data: {"choices":[{"delta":{"content":"<think>先分析需求"}}]}

            data: {"choices":[{"delta":{"content":"再规划步骤</think>最终答案"}}]}

            data: [DONE]
            """.trimIndent()
        )
        assertEquals("最终答案", r.content)
        assertEquals(listOf("最终答案"), r.tokens)
        assertEquals(listOf("先分析需求", "再规划步骤"), r.reasoning)
    }

    @Test
    fun `MiniMax默认格式_think标签跨chunk拆分_正文不污染`() = runTest {
        val r = runSse(
            """
            data: {"choices":[{"delta":{"content":"<think>思考"}}]}

            data: {"choices":[{"delta":{"content":"内容</th"}}]}

            data: {"choices":[{"delta":{"content":"ink>正文"}}]}

            data: [DONE]
            """.trimIndent()
        )
        assertEquals("正文", r.content)
        assertEquals(listOf("思考", "内容"), r.reasoning)
    }

    @Test
    fun `官方流式夹具_五家reasoning_content系全兼容`() = runTest {
        // Kimi 官方流式示例 (platform.kimi.com/docs/guide/use-thinking-models):
        // choice.delta.reasoning_content 为思考 / choice.delta.content 为回答
        val kimi = runSse(
            """
            data: {"choices":[{"delta":{"reasoning_content":"K-思考1"}}]}

            data: {"choices":[{"delta":{"content":"K-正文"}}]}

            data: {"choices":[{"delta":{"reasoning_content":"K-思考2"}}]}

            data: [DONE]
            """.trimIndent()
        )
        assertEquals("K-正文", kimi.content)
        assertEquals(listOf("K-思考1", "K-思考2"), kimi.reasoning)

        // GLM/Z.AI 官方迁移指南 (docs.z.ai/guides/overview/migrate-to-glm-new):
        // 流式须处理 delta.reasoning_content 与 delta.content
        val glm = runSse(
            """
            data: {"choices":[{"delta":{"reasoning_content":"G-思考"}}]}

            data: {"choices":[{"delta":{"content":"G-答案"}}]}

            data: [DONE]
            """.trimIndent()
        )
        assertEquals("G-答案", glm.content)
        assertEquals(listOf("G-思考"), glm.reasoning)

        // Qwen/DashScope 官方 (docs.qwencloud.com/developer-guides/text-generation/thinking):
        // 先 reasoning_content 后 content 两阶段流式
        val qwen = runSse(
            """
            data: {"choices":[{"delta":{"reasoning_content":"Q-思考"}}]}

            data: {"choices":[{"delta":{"content":"Q-回答"}}]}

            data: [DONE]
            """.trimIndent()
        )
        assertEquals("Q-回答", qwen.content)
        assertEquals(listOf("Q-思考"), qwen.reasoning)

        // xAI grok-4.6 官方 (docs.x.ai/developers/model-capabilities/text/reasoning):
        // 流式思考摘要经 chunk.reasoning_content 与正文一并返回
        val xai = runSse(
            """
            data: {"choices":[{"delta":{"reasoning_content":"X-摘要"}}]}

            data: {"choices":[{"delta":{"content":"X-正文"}}]}

            data: [DONE]
            """.trimIndent()
        )
        assertEquals("X-正文", xai.content)
        assertEquals(listOf("X-摘要"), xai.reasoning)

        // 火山/豆包 官方 (docs.volcengine.com/docs/6492/2165111):
        // reasoning_content 为思维链字段, 与 content 同级
        val volcano = runSse(
            """
            data: {"choices":[{"delta":{"reasoning_content":"V-推理"}}]}

            data: {"choices":[{"delta":{"content":"V-输出"}}]}

            data: [DONE]
            """.trimIndent()
        )
        assertEquals("V-输出", volcano.content)
        assertEquals(listOf("V-推理"), volcano.reasoning)
    }

    @Test
    fun `OpenAI官方chat_completions无思考字段_正文正常且onReasoning零调用`() = runTest {
        // OpenAI 官方 reasoning 指南 (developers.openai.com/api/docs/guides/reasoning):
        // "reasoning tokens are not visible via the API", chat/completions 流式仅 delta.content —
        // 正文不受影响, 思维链通道自然为空 (官方能力边界, 非缺陷)。
        val r = runSse(
            """
            data: {"choices":[{"delta":{"content":"官方正文"}}]}

            data: [DONE]
            """.trimIndent()
        )
        assertEquals("官方正文", r.content)
        assertEquals(listOf("官方正文"), r.tokens)
        assertTrue("官方不暴露思考时 onReasoning 应为零调用", r.reasoning.isEmpty())
    }
}
