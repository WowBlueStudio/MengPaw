// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 预置供应商表与显示顺序 (v0.41.0): MiniMax 登记 + 除自建/自定义外按英文名首字母排序。
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
}
