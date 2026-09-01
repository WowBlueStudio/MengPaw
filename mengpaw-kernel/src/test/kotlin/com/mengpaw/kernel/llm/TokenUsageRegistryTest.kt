// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TokenUsageRegistry 统一用量记录回调测试 (v0.46.1 根治多模式统计缺失)。 */
class TokenUsageRegistryTest {

    @Test
    fun record_invokesInjectedRecorder_withModelAndUsage() {
        var capturedModel: String? = null
        var captured: TokenUsage? = null
        TokenUsageRegistry.recorder = { m, u -> capturedModel = m; captured = u }
        try {
            TokenUsageRegistry.record("deepseek-chat", TokenUsage(10, 20, 30, 5, 25))
            assertEquals("deepseek-chat", capturedModel)
            assertEquals(30, captured?.totalTokens)
            assertEquals(10, captured?.promptTokens)
            assertEquals(20, captured?.completionTokens)
            assertEquals(5, captured?.cacheHitTokens)
        } finally {
            TokenUsageRegistry.recorder = null
        }
    }

    @Test
    fun record_withoutRecorder_isNoOp() {
        TokenUsageRegistry.recorder = null
        // 未注入 recorder 时调用不抛异常 (空操作)
        TokenUsageRegistry.record("m", TokenUsage(1, 2, 3, 0, 3))
        assertNull(TokenUsageRegistry.recorder)
    }
}
