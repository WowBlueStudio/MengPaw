// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.concise

import com.mengpaw.kernel.plugin.PluginManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 言简意赅 middleware 变换测试 — 强要求句删除 / 反 Markdown 追加 / 幂等 / 开关。
 */
class ConciseMiddlewareTest {

    private val zhPrompt = """
        ## 响应格式（必须遵守）
        Thought: （思考）
        Action: （命令名称）
        Action Input: （参数）
        ...或...
        Final Answer: （最终答案）

        使用中文思考和输出。

        **关键**：每一步必须输出完整的 Thought → Action → Action Input 序列。不要只输出 Thought 就停止。只有在任务真正完成时才输出 Final Answer。
    """.trimIndent()

    private val enPrompt = """
        ## Response Format (must follow)
        Thought: (your reasoning)
        Action: (command name)
        Action Input: (parameters)
        ...or...
        Final Answer: (your final response)

        Think and respond in English.

        **Critical**: Every step MUST output the complete Thought → Action → Action Input sequence. Never stop after just a Thought. Only output Final Answer when the task is truly complete.
    """.trimIndent()

    @Before
    fun setUp() {
        runBlocking {
            val pm = PluginManager.globalInstance
            if (pm.get(ConcisePlugin.PLUGIN_ID) == null) {
                pm.install(ConcisePlugin())
            }
            pm.activate(ConcisePlugin.PLUGIN_ID)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            try {
                PluginManager.globalInstance.uninstall(ConcisePlugin.PLUGIN_ID)
            } catch (_: Exception) {}
        }
    }

    @Test
    fun `active 时中文提示词前缀注入简洁引导且强要求句保留`() {
        val out = ConciseMiddleware.onSystemPrompt(zhPrompt, "MengPaw")
        assertTrue("前缀应注入简洁引导", out.startsWith("回答保持简洁"))
        assertTrue("强要求句应保留（不删，保住流式分步）", out.contains("必须输出完整的 Thought → Action → Action Input 序列"))
        assertTrue("Action 标记说明应保留", out.contains("Action:"))
        assertFalse("不应追加反 Markdown 约束", out.contains("回复默认用简洁纯文本"))
    }

    @Test
    fun `active 时英文提示词前缀注入简洁引导且强要求句保留`() {
        val out = ConciseMiddleware.onSystemPrompt(enPrompt, "MengPaw")
        assertTrue("前缀应注入英文简洁引导", out.startsWith("Keep replies concise"))
        assertTrue("英文强要求句应保留", out.contains("MUST output the complete"))
        assertTrue("Final Answer 标记应保留", out.contains("Final Answer:"))
        assertFalse("不应追加英文反 Markdown 约束", out.contains("plain text by default"))
    }

    @Test
    fun `变换幂等 — 二次调用不重复注入`() {
        val once = ConciseMiddleware.onSystemPrompt(zhPrompt, "MengPaw")
        val twice = ConciseMiddleware.onSystemPrompt(once, "MengPaw")
        assertTrue("二次调用应保持原文", once == twice)
        assertEquals(1, twice.split("回答保持简洁").size - 1)
    }

    @Test
    fun `停用后原样返回`() {
        runBlocking { PluginManager.globalInstance.deactivate(ConcisePlugin.PLUGIN_ID) }
        val out = ConciseMiddleware.onSystemPrompt(zhPrompt, "MengPaw")
        assertTrue("停用时应原样返回", out == zhPrompt)
        assertTrue("强要求句仍在", out.contains("必须输出完整的 Thought → Action → Action Input 序列"))
    }
}
