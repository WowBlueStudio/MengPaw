// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import org.junit.Assert.*
import org.junit.Test

/**
 * 梦境提供者 SPI 测试 — 注册/覆盖/回退。
 */
class DreamProviderTest {

    private class FakeProvider : DreamProvider {
        override val providerName = "fake"
        override suspend fun buildContext(agentName: String, scroll: ScrollContextManager?): String? = "fake-context"
        override suspend fun refine(agentName: String, llmProvider: com.mengpaw.kernel.llm.LlmProvider, scroll: ScrollContextManager?): String? = "fake-insight"
        override fun organize(agentName: String): DreamResult = DreamResult(1, 1, "fake")
        override fun stats(): String = "fake-stats"
        override fun history(limit: Int): String = "fake-history"
    }

    @Test
    fun `default provider is kernel DreamEngine`() {
        assertEquals("未注册时回退内核默认", DreamEngine, DreamProviderRegistry.active())
    }

    @Test
    fun `registered provider wins`() {
        DreamProviderRegistry.register(FakeProvider())
        assertEquals("注册后应生效", "fake", DreamProviderRegistry.active().providerName)
    }

    @Test
    fun `unregister falls back to default`() {
        DreamProviderRegistry.register(FakeProvider())
        DreamProviderRegistry.unregister("fake")
        assertEquals("注销后回退内核默认", DreamEngine, DreamProviderRegistry.active())
    }

    @Test
    fun `later registration overrides earlier`() {
        DreamProviderRegistry.register(FakeProvider())
        val second = object : DreamProvider by FakeProvider() { override val providerName = "fake-2" }
        DreamProviderRegistry.register(second)
        assertEquals("后注册者胜 (第三方可覆盖内置默认)", "fake-2", DreamProviderRegistry.active().providerName)
        DreamProviderRegistry.unregister("fake-2")
    }
}
