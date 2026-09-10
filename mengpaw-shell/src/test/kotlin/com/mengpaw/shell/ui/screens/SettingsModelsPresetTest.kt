// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预置供应商表与显示顺序 (v0.41.0+): MiniMax 登记 + 除自建/自定义外按英文名首字母排序；
 * 2026-08-17 官方核对后 QWEN 旗舰更新为 qwen3.8-max。
 */
class SettingsModelsPresetTest {

    @Test
    fun `预置供应商按英文名首字母排序_自建与自定义除外`() {
        val order = LlmProviderPreset.presetChipOrder()
        assertEquals(
            listOf(
                // QWEN 预置的显示名即 DashScope, 因此无独立的 "Qwen" 条目
                "DashScope", "DeepSeek", "GLM (Zhipu)", "Grok (xAI)", "Kimi (Moonshot)",
                "MiniMax", "OpenAI", "OpenModel", "Volcano Engine (Doubao)"
            ),
            order.map { it.enLabel }
        )
        assertFalse("自建不得出现在字母序区", order.contains(LlmProviderPreset.SELF_HOSTED))
        assertFalse("自定义不得出现在字母序区", order.contains(LlmProviderPreset.CUSTOM))
    }

    @Test
    fun `DEEPSEEK预置_仅保留规范id_停用id全部移除`() {
        // 官方 api-docs.deepseek.com: base_url(OpenAI) = https://api.deepseek.com,
        // 官方 curl 为 POST https://api.deepseek.com/chat/completions — 无 /v1、无多余段
        val preset = LlmProviderPreset.DEEPSEEK
        assertEquals("https://api.deepseek.com/chat/completions", preset.endpoint)
        assertEquals("deepseek-flash", preset.defaultModel)
        assertEquals(listOf("deepseek-flash"), preset.models.map { it.name })
        // 官方 图像理解 指南: 「deepseek-flash 模型支持在文本之外输入图片」— V4.1 Flash 原生多模态,
        // 承接原 vision-exp 的图像理解入口; type 必须精确全等 (AgentProviderModelPanel 图标判定)
        assertEquals("多模态", preset.models.single().type)
        // 2026-09-10 官方 更新日志/模型 & 价格: V4 Flash 与 V4 Flash Vision Exp 已下线,
        // V4 Pro 于 09-14 12:00 起全部路由到 V4.1 Flash — 三个停用 id 不得再出现在预置表
        listOf("deepseek-v4-flash", "deepseek-v4-flash-vision-exp", "deepseek-v4-pro").forEach {
            assertFalse("$it 已下线或即将下线", preset.models.any { m -> m.name == it })
        }
    }

    @Test
    fun `存量配置归一_DeepSeek停用id改写为deepseek-flash_第三方托管不改写`() {
        val official = LlmProviderPreset.DEEPSEEK.endpoint
        listOf("deepseek-v4-flash", "deepseek-v4-flash-vision-exp", "deepseek-v4-pro").forEach {
            assertEquals("停用 id 应归一为规范 id", "deepseek-flash", normalizeRetiredModelId(official, it))
        }
        assertEquals("规范 id 原样保留", "deepseek-flash", normalizeRetiredModelId(official, "deepseek-flash"))
        assertEquals("自定义模型名不动", "my-finetune", normalizeRetiredModelId(official, "my-finetune"))
        // 火山方舟等第三方平台存在同名托管条目, 属平台自有命名 — 官方下线公告不覆盖, 改写会直接失效
        assertEquals(
            "第三方托管同名条目不得改写",
            "deepseek-v4-pro",
            normalizeRetiredModelId("https://ark.cn-beijing.volces.com/api/v3/chat/completions", "deepseek-v4-pro")
        )
        // 刷新模型列表: 官方过渡期 GET /models 仍返回 deepseek-v4-pro → 必须剔除, 不得让用户选中
        assertEquals(
            listOf("deepseek-flash"),
            filterRetiredModelIds(official, listOf("deepseek-flash", "deepseek-v4-pro"))
        )
        assertEquals(
            "第三方端点的同名条目照常展示",
            listOf("deepseek-v4-pro"),
            filterRetiredModelIds("https://ark.cn-beijing.volces.com/api/v3/chat/completions", listOf("deepseek-v4-pro"))
        )
    }

    @Test
    fun `供应商预置_思考强度档位默认High且仅DeepSeek可切换`() {
        val ds = SavedProvider(
            preset = LlmProviderPreset.DEEPSEEK, apiKey = "sk-x",
            endpoint = LlmProviderPreset.DEEPSEEK.endpoint, model = "deepseek-flash"
        )
        assertEquals("默认为官方默认档 High", com.mengpaw.kernel.llm.ThinkingEffort.HIGH, ds.thinkingEffort)
        assertTrue("DeepSeek 支持思考强度档位", ds.supportsThinkingEffort)

        val openai = SavedProvider(
            preset = LlmProviderPreset.OPENAI, apiKey = "sk-x",
            endpoint = LlmProviderPreset.OPENAI.endpoint, model = "gpt-5.6"
        )
        assertFalse("非 DeepSeek 端点不展示档位", openai.supportsThinkingEffort)
    }

    @Test
    fun `MiniMax预置_官方模型清单与端点`() {
        val preset = LlmProviderPreset.MINIMAX
        assertEquals("https://api.minimaxi.com/v1/chat/completions", preset.endpoint)
        assertEquals("MiniMax-M3", preset.defaultModel)
        assertEquals(
            listOf("MiniMax-M3", "MiniMax-M2.7", "MiniMax-M2.7-highspeed", "MiniMax-M2.5"),
            preset.models.take(4).map { it.name }
        )
    }

    @Test
    fun `QWEN预置_最新旗舰与默认型号`() {
        val preset = LlmProviderPreset.QWEN
        assertEquals("qwen3.8-max", preset.defaultModel)
        assertEquals("qwen3.8-max", preset.models.first().name)
        assertFalse("旗舰不得重复出现", preset.models.count { it.name == "qwen3.8-max" } > 1)
    }

    @Test
    fun `OPENAI与GROK预置_最新旗舰且不含官方退役型号`() {
        val openai = LlmProviderPreset.OPENAI
        assertEquals("gpt-5.6", openai.defaultModel)
        assertEquals("gpt-5.6", openai.models.first().name)
        assertFalse("o4-mini 官方已 Deprecated", openai.models.any { it.name == "o4-mini" })

        val grok = LlmProviderPreset.GROK
        assertEquals("grok-4.6", grok.defaultModel)
        assertEquals("grok-4.6", grok.models.first().name)
        assertFalse(
            "grok-4.1-fast-non-reasoning 官方 2026-05-15 已退役",
            grok.models.any { it.name == "grok-4.1-fast-non-reasoning" }
        )
    }

    @Test
    fun `VOLCANO预置_托管模型更新为官方当前型号`() {
        val preset = LlmProviderPreset.VOLCANO
        assertEquals("doubao-seed-2.0-pro", preset.defaultModel)
        val names = preset.models.map { it.name }
        assertTrue("应含 deepseek-v4-flash", "deepseek-v4-flash" in names)
        assertTrue("应含 deepseek-v4-pro", "deepseek-v4-pro" in names)
        assertTrue("应含 glm-5.3", "glm-5.3" in names)
        assertTrue("应含 doubao-seed-2.1-turbo", "doubao-seed-2.1-turbo" in names)
        assertFalse("deepseek-v3-2 已过时", "deepseek-v3-2" in names)
        assertFalse("glm-4.7 已过时", "glm-4.7" in names)
    }
}
