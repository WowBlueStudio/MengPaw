// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.concise

import com.mengpaw.kernel.plugin.PluginManager
import kotlinx.coroutines.runBlocking
import org.junit.After
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
    fun `active 时中文提示词删除强要求句并追加反 Markdown 约束`() {
        val out = ConciseMiddleware.onSystemPrompt(zhPrompt, "MengPaw")
        assertFalse("强要求句应被删除", out.contains("必须输出完整的 Thought → Action → Action Input 序列"))
        assertTrue("Action 标记说明应保留", out.contains("Action:"))
        assertTrue("反 Markdown 约束应追加", out.contains("回复默认用简洁纯文本"))
    }

    @Test
    fun `active 时英文提示词删除强要求句并追加反 Markdown 约束`() {
        val out = ConciseMiddleware.onSystemPrompt(enPrompt, "MengPaw")
        assertFalse("英文强要求句应被删除", out.contains("MUST output the complete"))
        assertTrue("Final Answer 标记应保留", out.contains("Final Answer:"))
        assertTrue("英文反 Markdown 约束应追加", out.contains("plain text by default"))
    }

    @Test
    fun `变换幂等 — 二次调用不重复追加`() {
        val once = ConciseMiddleware.onSystemPrompt(zhPrompt, "MengPaw")
        val twice = ConciseMiddleware.onSystemPrompt(once, "MengPaw")
        assertTrue("二次调用应保持原文", once == twice)
    }

    @Test
    fun `停用后原样返回`() {
        runBlocking { PluginManager.globalInstance.deactivate(ConcisePlugin.PLUGIN_ID) }
        val out = ConciseMiddleware.onSystemPrompt(zhPrompt, "MengPaw")
        assertTrue("停用时应原样返回", out == zhPrompt)
        assertTrue("强要求句仍在", out.contains("必须输出完整的 Thought → Action → Action Input 序列"))
    }
}
