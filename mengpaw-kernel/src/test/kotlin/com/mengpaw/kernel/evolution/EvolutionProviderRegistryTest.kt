// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.evolution

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * EvolutionProviderRegistry 单元测试 — 默认回退 / 覆盖 / 注销回退 (插件 SPI 语义)。
 * 注意: 注册表是全局 object, 测试结束必须注销自定义提供者, 避免污染后续测试。
 */
class EvolutionProviderRegistryTest {

    /** 自定义提供者: 覆盖 audit 命令 + 自定义引导, 其余委托默认。 */
    private class CustomProvider : EvolutionProvider {
        override val providerName: String = "TestCustomProvider"
        override suspend fun executeCommand(command: String, args: List<String>, ctx: ExecutionContext): ExecutionResult? =
            if (command == "audit") ExecutionResult.ok("自定义绩效: 0 条")
            else EvolutionEngine.executeCommand(command, args, ctx)

        override fun recordFailure(agentName: String?, command: String, errorCode: String, message: String, source: String) {
            EvolutionEngine.recordFailure(agentName, command, errorCode, message, source)
        }
        override fun recordCorrection(agentName: String?, correction: String, contextSnippet: String, task: String) {
            EvolutionEngine.recordCorrection(agentName, correction, contextSnippet, task)
        }
        override fun buildFragment(agentName: String?, command: String, message: String): String? =
            "【自定义引导】$message"
        override fun buildSessionBrief(agentName: String?): String? = null
    }

    private fun ensureDataPaths() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "mengpaw-evo-registry-${System.currentTimeMillis()}")
        tmp.mkdirs()
        com.mengpaw.kernel.DataPaths.initialize(tmp.absolutePath)
    }

    @Test
    fun `no registration falls back to kernel default`() {
        ensureDataPaths()
        assertEquals("无注册时回退内核默认", EvolutionEngine.providerName, EvolutionProviderRegistry.active().providerName)
    }

    @Test
    fun `custom provider overrides default and unregister falls back`() = runBlocking {
        ensureDataPaths()
        val custom = CustomProvider()
        try {
            EvolutionProviderRegistry.register(custom)
            assertEquals("后注册者胜", custom.providerName, EvolutionProviderRegistry.active().providerName)

            val result = EvolutionProviderRegistry.active().executeCommand(
                "audit", emptyList(), ExecutionContext(agentName = "evo-reg-1", sessionId = "s1")
            )
            assertNotNull(result)
            assertTrue("自定义 audit 应生效: $result", result!!.output.contains("自定义绩效"))

            val guide = EvolutionProviderRegistry.active().buildFragment("evo-reg-1", "fs.cat", "boom")
            assertEquals("自定义引导应生效", "【自定义引导】boom", guide)

            // 未覆盖命令回退默认 (委托 EvolutionEngine)
            val reactions = EvolutionProviderRegistry.active().executeCommand(
                "reactions", emptyList(), ExecutionContext(agentName = "evo-reg-1", sessionId = "s1")
            )
            assertNotNull("未覆盖命令应回退默认", reactions)
        } finally {
            EvolutionProviderRegistry.unregister(custom.providerName)
        }
        assertEquals("注销后回退内核默认", EvolutionEngine.providerName, EvolutionProviderRegistry.active().providerName)
    }

    @Test
    fun `unregister of unknown provider is no-op`() {
        ensureDataPaths()
        EvolutionProviderRegistry.unregister("不存在的提供者")
        assertEquals("注销未知提供者不影响默认", EvolutionEngine.providerName, EvolutionProviderRegistry.active().providerName)
    }
}
