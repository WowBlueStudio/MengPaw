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
