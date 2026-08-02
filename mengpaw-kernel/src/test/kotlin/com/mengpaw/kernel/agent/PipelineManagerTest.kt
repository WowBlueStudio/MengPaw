// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.PipelineManager
import org.junit.Assert.assertEquals
import org.junit.Test

/** 折叠模型档位规则单测。 */
class PipelineManagerTest {

    @Test
    fun `default models use nine-zero threshold`() {
        assertEquals(0.90, PipelineManager.compactRatioFor("deepseek-chat"), 0.001)
        assertEquals(0.90, PipelineManager.compactRatioFor("gpt-5.4"), 0.001)
        assertEquals(0.90, PipelineManager.compactRatioFor("glm-5.2"), 0.001)
    }

    @Test
    fun `conservative models fall back to eight-zero`() {
        assertEquals(0.80, PipelineManager.compactRatioFor("qwen-flash-mini"), 0.001)
        assertEquals(0.80, PipelineManager.compactRatioFor("gpt-5.4-nano"), 0.001)
        assertEquals(0.80, PipelineManager.compactRatioFor("llama-7b"), 0.001)
        assertEquals(0.80, PipelineManager.compactRatioFor("llama-13b"), 0.001)
    }

    @Test
    fun `substring lookalikes are not misclassified`() {
        // 词边界匹配 — "mini" 不误伤 minimax / litemode
        assertEquals(0.90, PipelineManager.compactRatioFor("minimax"), 0.001)
        assertEquals(0.90, PipelineManager.compactRatioFor("litemode"), 0.001)
        assertEquals(0.90, PipelineManager.compactRatioFor("qwen-minimax-plus"), 0.001)
    }
}
