// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.device.AgentEntry
import com.mengpaw.kernel.device.DeviceInventory
import com.mengpaw.kernel.device.DeviceInventoryStore
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.ProviderInfo
import com.mengpaw.kernel.llm.ProviderType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `twin.model` 读库存测试 (P4.5, 设计 §7.5) —
 * 本机库存 `agents[].model` 段存在时画像来自库存 (模型/上下文/视觉/工具/证据),
 * 缺失时回退 [ModelProfileResolver] 现场解析 (旧行为不变)。
 */
class TwinModelInventoryTest {

    private lateinit var base: File
    private lateinit var commands: TwinCapabilityCommands
    private val ctx = ExecutionContext(sessionId = "test-session", agentName = "MengPaw")
    private var originalProvider: LlmProvider? = null

    private class FakeProvider(private val model: String) : LlmProvider {
        override suspend fun complete(prompt: String): String = ""
        override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String = ""
        override fun info(): ProviderInfo = ProviderInfo("resolver-provider", model, ProviderType.REMOTE)
        override fun close() {}
    }

    @Before
    fun setUp() {
        base = Files.createTempDirectory("mengpaw-twin-model-").toFile()
        DataPaths.initialize(base.absolutePath)
        DeviceInventoryStore.devicesDir().mkdirs()
        ModelEvidenceStore.clearAll()
        originalProvider = MemoryTwinPlugin.llmProvider
        commands = TwinCapabilityCommands(TwinRuntimeState())
    }

    @After
    fun tearDown() {
        MemoryTwinPlugin.llmProvider = originalProvider
        ModelEvidenceStore.clearAll()
        DataPaths.initialize("/sdcard/MengPaw")
        base.deleteRecursively()
    }

    /** 写本机库存行 (deviceId 必须等于 [DeviceInventoryStore.localDeviceId] 才被 readLocal 读到)。 */
    private fun writeLocal(agent: AgentEntry) {
        val id = DeviceInventoryStore.localDeviceId()
        val inv = DeviceInventory(
            deviceId = id,
            deviceName = "测试设备",
            formFactor = "TABLET",
            observedAt = "2026-10-22T22:00+08:00",
            agents = listOf(agent)
        )
        File(DataPaths.TWIN_DEVICES, "$id.md").writeText(DeviceInventory.serialize(inv))
    }

    @Test
    fun `库存有模型段时画像来自库存`() = runBlocking {
        MemoryTwinPlugin.llmProvider = FakeProvider("resolver-model")
        writeLocal(
            AgentEntry(
                name = "MengPaw",
                model = "inventory-model",
                contextTokens = 200_000,
                vision = true,
                tools = false,
                sources = mapOf("context" to "measured", "vision" to "declared", "evidence" to "7")
            )
        )

        val r = commands.cmdModel(emptyList(), ctx)
        assertTrue(r.error, r.success)
        assertTrue("模型应来自库存: ${r.output}", r.output.contains("inventory-model"))
        assertFalse("不应回退 resolver 的模型: ${r.output}", r.output.contains("resolver-model"))
        assertTrue("上下文应来自库存: ${r.output}", r.output.contains("200K"))
        assertTrue("证据条数应来自库存: ${r.output}", r.output.contains("实测证据: 7 条"))
        assertTrue("视觉应来自库存: ${r.output}", r.output.contains("视觉: 支持"))
        assertTrue("工具判定应来自库存: ${r.output}", r.output.contains("工具调用: 不支持"))
    }

    @Test
    fun `无库存模型段时回退 resolver`() = runBlocking {
        MemoryTwinPlugin.llmProvider = FakeProvider("deepseek-flash")

        val r = commands.cmdModel(emptyList(), ctx)
        assertTrue(r.error, r.success)
        assertTrue("应回退 resolver 现场解析: ${r.output}", r.output.contains("deepseek-flash"))
        assertTrue("resolver 画像应含 provider: ${r.output}", r.output.contains("provider=resolver-provider"))
        assertTrue("无证据时仍显示 0 条: ${r.output}", r.output.contains("实测证据: 0 条"))
    }

    @Test
    fun `库存缺证据字段时仍读证据库`() = runBlocking {
        // 库存只存声明, 实测证据仍在 ModelEvidenceStore (设计 §7.5: 库存段 + 证据库)
        MemoryTwinPlugin.llmProvider = FakeProvider("deepseek-flash")
        ModelEvidenceStore.record("deepseek-flash", "resolver-provider", EvidenceFact.SUCCESS)
        writeLocal(AgentEntry(name = "MengPaw", model = "deepseek-flash", contextTokens = 128_000))

        val out = commands.cmdModel(emptyList(), ctx).output
        assertTrue("模型应来自库存: $out", out.contains("deepseek-flash"))
        assertTrue("证据条数应来自证据库: $out", out.contains("实测证据: 1 条"))
    }
}
