// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json

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
    fun `deepseek请求体回传assistant的reasoning_content`() {
        // v0.41.1 未发布: DeepSeek 思考模式官方要求多轮工具调用时 reasoning_content
        // 必须原样回传, 否则 API 400 — 仅 deepseek 端点 includeReasoning=true
        val body = buildRequestBody(
            model = "deepseek-v4-pro",
            config = AdaptiveLlmProvider.AdaptiveConfig(),
            messages = listOf(
                mapOf("role" to "system", "content" to "sys"),
                mapOf("role" to "user", "content" to "任务"),
                mapOf("role" to "assistant", "content" to "Action: search", "reasoning_content" to "先想一下")
            ),
            stream = true,
            includeReasoning = true
        )
        assertTrue("assistant 消息必须回传 reasoning_content", body.contains("\"reasoning_content\":\"先想一下\""))
        assertTrue("正文内容保留", body.contains("Action: search"))
    }

    @Test
    fun `非deepseek端点不回传reasoning_content`() {
        val body = buildRequestBody(
            model = "gpt-4.1",
            config = AdaptiveLlmProvider.AdaptiveConfig(),
            messages = listOf(
                mapOf("role" to "assistant", "content" to "Action: search", "reasoning_content" to "先想一下")
            ),
            includeReasoning = false
        )
        assertFalse("OpenAI 等端点不接受该字段, 不得外泄", body.contains("reasoning_content"))
    }

    @Test
    fun `deepseek端点_assistant无思维链时不带reasoning_content键`() {
        val body = buildRequestBody(
            model = "deepseek-v4-pro",
            config = AdaptiveLlmProvider.AdaptiveConfig(),
            messages = listOf(
                mapOf("role" to "assistant", "content" to "普通回复")
            ),
            includeReasoning = true
        )
        assertFalse("无思维链时不带空键", body.contains("reasoning_content"))
    }

    @Test
    fun `非法JSON_回退原始文本`() {
        val parsed = parseBody("not json at all")
        assertEquals("not json at all", parsed.content)
        assertNull(parsed.reasoning)
        assertNull(parsed.usage)
    }

    // ── 思考强度四档 (v0.46.2, 官方 思考模式 文档原文) ─────────────────────

    @Test
    fun `思考强度Max_High_Low_注入thinking与reasoning_effort`() {
        for (effort in listOf(ThinkingEffort.MAX, ThinkingEffort.HIGH, ThinkingEffort.LOW)) {
            val body = buildRequestBody(
                model = "deepseek-v4-flash",
                config = AdaptiveLlmProvider.AdaptiveConfig(),
                messages = listOf(mapOf("role" to "user", "content" to "hi")),
                stream = true,
                thinkingEffort = effort
            )
            assertTrue("$effort 应开启思考: $body", body.contains("\"thinking\":{\"type\":\"enabled\"}"))
            assertTrue("$effort 应带强度: $body", body.contains("\"reasoning_effort\":\"${effort.wire}\""))
        }
    }

    @Test
    fun `思考强度Off_只发disabled不带强度`() {
        val body = buildRequestBody(
            model = "deepseek-v4-flash",
            config = AdaptiveLlmProvider.AdaptiveConfig(),
            messages = listOf(mapOf("role" to "user", "content" to "hi")),
            thinkingEffort = ThinkingEffort.OFF
        )
        assertTrue("Off 档走官方 disabled: $body", body.contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertFalse("关闭思考时不得带 reasoning_effort: $body", body.contains("reasoning_effort"))
    }

    @Test
    fun `不传档位时请求体不含thinking字段_保持既有行为`() {
        val body = buildRequestBody(
            model = "deepseek-v4-flash",
            config = AdaptiveLlmProvider.AdaptiveConfig(),
            messages = listOf(mapOf("role" to "user", "content" to "hi"))
        )
        assertFalse(body.contains("thinking"))
        assertFalse(body.contains("reasoning_effort"))
    }

    @Test
    fun `思考档位仅DeepSeek端点注入_其它端点返回null`() {
        assertEquals(ThinkingEffort.OFF, effectiveThinkingEffort("deepseek", ThinkingEffort.OFF))
        assertNull("OpenAI 官方未记载该字段, 不得注入", effectiveThinkingEffort("openai", ThinkingEffort.HIGH))
        assertNull(effectiveThinkingEffort("kimi", ThinkingEffort.MAX))
        assertNull(effectiveThinkingEffort("glm", ThinkingEffort.LOW))
    }

    @Test
    fun `思考档位默认High_未知持久化值回退默认`() {
        assertEquals(ThinkingEffort.HIGH, ThinkingEffort.DEFAULT)
        assertEquals(ThinkingEffort.OFF, ThinkingEffort.fromStorage("off"))
        assertEquals(ThinkingEffort.MAX, ThinkingEffort.fromStorage("MAX"))
        assertEquals(ThinkingEffort.HIGH, ThinkingEffort.fromStorage(null))
        assertEquals(ThinkingEffort.HIGH, ThinkingEffort.fromStorage(""))
        assertEquals(ThinkingEffort.HIGH, ThinkingEffort.fromStorage("legacy-value"))
    }

    // ── v0.46.3 正文提取加固 (内容块数组 / 整包 JSON 兜底 / 形态日志不带内容) ──

    @Test
    fun `非流式message_content为内容块数组_正确提取正文`() {
        val parsed = parseBody(
            """
            {"choices":[{"message":{"content":[{"type":"text","text":"第一段"},{"type":"text","text":"第二段"}],
            "reasoning_content":"想一下"},"finish_reason":"stop"}]}
            """.trimIndent()
        )
        assertEquals("第一段第二段", parsed.content)
        assertEquals("想一下", parsed.reasoning)
    }

    @Test
    fun `usage为null或字段缺失_不得整包解析失败回退原文`() {
        // v0.46.3: DeepSeek V4.1 Flash 起响应可能带 "usage": null —
        // 旧实现 json["usage"]?.jsonObject 抛异常 → 整包回退原始报文当回答 (更严重的错)
        val nullUsage = parseBody("""{"choices":[{"message":{"content":"正文"}}],"usage":null}""")
        assertEquals("正文", nullUsage.content)
        assertNull(nullUsage.usage)

        val nullMessage = parseBody("""{"choices":[{"message":null,"delta":{"content":"增量正文"}}],"usage":null}""")
        assertEquals("增量正文", nullMessage.content)

        // 无正文时按既定契约回退原始报文 (maxFallbackLength=null 即全文; RemoteApi 传 500 截断) —
        // 这里只钉住"usage:null 不会让整包解析抛异常"这一修复点, 不改回退语义
        val emptyChoices = parseBody("""{"choices":[],"usage":null}""")
        assertEquals("""{"choices":[],"usage":null}""", emptyChoices.content)
        assertNull(emptyChoices.usage)
    }

    @Test
    fun `整包JSON兜底_合法取正文_垃圾返回null`() {
        assertEquals("整包正文", extractMessageContentOrNull("""{"choices":[{"message":{"content":"整包正文"}}]}"""))
        assertEquals("增量正文", extractMessageContentOrNull("""{"choices":[{"delta":{"content":"增量正文"}}]}"""))
        assertNull("非法 JSON 不得回退原文", extractMessageContentOrNull("<html>502</html>"))
        assertNull("无 choices 不得回退", extractMessageContentOrNull("""{"error":{"message":"boom"}}"""))
    }

    @Test
    fun `形态描述只含键名与类型_不含内容值`() {
        val el = Json.parseToJsonElement("""{"content":[{"type":"text","text":"机密内容"}],"role":"assistant"}""")
        val shape = shapeOf(el)
        assertTrue("应含类型与键名: $shape", shape.contains("object") && shape.contains("content") && shape.contains("role"))
        assertFalse("绝不泄露内容值: $shape", shape.contains("机密内容"))
        assertEquals("array(size=2)", shapeOf(Json.parseToJsonElement("""[1,2]""")))
        assertEquals("primitive", shapeOf(Json.parseToJsonElement("\"文本\"")))
        assertEquals("null", shapeOf(null))
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
