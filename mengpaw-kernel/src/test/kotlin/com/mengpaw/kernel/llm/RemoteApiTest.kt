// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * RemoteApi (fallback 链) 直测 — v0.40.4 P2 补测试: 构造器注入 MockEngine 客户端,
 * 验证流式思维链分流 / HTTP 错误 / 非流式 lastReasoning / 非法响应截断语义。
 * (此前因写死全局共享客户端无法注入, 该链路零直测。)
 */
class RemoteApiTest {

    @Test
    fun `流式_reasoning_content分流并累积lastReasoning`() = runTest {
        val engine = MockEngine {
            respond(
                """
                data: {"choices":[{"delta":{"reasoning_content":"先分析"}}]}

                data: {"choices":[{"delta":{"reasoning_content":"再作答"}}]}

                data: {"choices":[{"delta":{"content":"最终答案"}}]}

                data: [DONE]
                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream")
            )
        }
        val client = HttpClient(engine)
        try {
            val api = RemoteApi("http://localhost/v1/chat/completions", "k", client = client)
            val tokens = mutableListOf<String>()
            val reasoning = mutableListOf<String>()
            val content = api.completeStreamingWithMessages(
                listOf(mapOf("role" to "user", "content" to "hi")),
                onToken = { tokens.add(it) },
                onReasoning = { reasoning.add(it) }
            )
            assertEquals("最终答案", content)
            assertEquals(listOf("最终答案"), tokens)
            assertEquals("思维链不得混入正文通道", listOf("先分析", "再作答"), reasoning)
            assertEquals("先分析再作答", api.lastReasoning)
        } finally {
            client.close()
        }
    }

    @Test
    fun `流式HTTP错误_抛LlmApiException`() = runTest {
        val engine = MockEngine {
            respond("{\"error\":\"boom\"}", HttpStatusCode.InternalServerError)
        }
        val client = HttpClient(engine)
        try {
            val api = RemoteApi("http://localhost/v1/chat/completions", "k", client = client)
            var thrown: LlmApiException? = null
            try {
                api.completeStreamingWithMessages(
                    listOf(mapOf("role" to "user", "content" to "x")),
                    onToken = {}
                )
            } catch (e: LlmApiException) {
                thrown = e
            }
            assertNotNull("HTTP 错误必须抛 LlmApiException 触发重试链", thrown)
            assertEquals(500, thrown?.httpStatus)
        } finally {
            client.close()
        }
    }

    @Test
    fun `非流式_message_reasoning_content进lastReasoning`() = runTest {
        val engine = MockEngine {
            respond(
                """
                {"choices":[{"message":{"role":"assistant","content":"正文","reasoning_content":"思维链"}}],
                 "usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}
                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpClient(engine)
        try {
            val api = RemoteApi("http://localhost/v1/chat/completions", "k", client = client)
            val answer = api.completeWithMessages(listOf(mapOf("role" to "user", "content" to "hi")))
            assertEquals("正文", answer)
            assertEquals("思维链", api.lastReasoning)
            assertEquals(1, api.lastUsage?.promptTokens)
        } finally {
            client.close()
        }
    }

    @Test
    fun `非流式非法响应_截断回退500字符`() = runTest {
        val engine = MockEngine {
            respond(
                "x".repeat(1000),
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
        }
        val client = HttpClient(engine)
        try {
            val api = RemoteApi("http://localhost/v1/chat/completions", "k", client = client)
            val answer = api.completeWithMessages(listOf(mapOf("role" to "user", "content" to "hi")))
            assertEquals("非法响应必须截断回退, 防止错误体整段冒充回答", 500, answer.length)
            assertEquals(null, api.lastReasoning)
        } finally {
            client.close()
        }
    }
}
