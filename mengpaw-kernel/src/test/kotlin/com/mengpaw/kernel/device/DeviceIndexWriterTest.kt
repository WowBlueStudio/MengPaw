// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.device

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.llm.PromptEngine
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 设备索引测试 (P4, docs/memory-git-sync-design.md §7.6 / §8) —
 * 一行一设备、排序 (本机优先/退役垫底)、时效文案、提示词注入随索引 mtime 失效。
 */
class DeviceIndexWriterTest {

    private lateinit var base: File
    private val agent = "device-index-agent"
    private val localId: String get() = DeviceInventoryStore.localDeviceId()

    @Before
    fun setUp() {
        base = Files.createTempDirectory("mengpaw-device-index-").toFile()
        DataPaths.initialize(base.absolutePath)
        DeviceInventoryStore.devicesDir().mkdirs()
        File(DataPaths.AGENTS, agent).mkdirs()
    }

    @After
    fun tearDown() {
        DeviceCollectors.reset()
        base.deleteRecursively()
    }

    private fun inventory(
        id: String,
        name: String,
        observedAt: String,
        status: String = DeviceInventory.STATUS_ACTIVE,
        plugins: List<String> = emptyList(),
        model: String = ""
    ) = DeviceInventory(
        deviceId = id,
        deviceName = name,
        formFactor = "TABLET",
        cpuCores = 8,
        ramMB = 8192,
        observedAt = observedAt,
        status = status,
        agents = listOf(AgentEntry(name = "MengPaw", model = model, plugins = plugins, vision = true))
    )

    private fun writeForeign(inv: DeviceInventory) {
        File(DataPaths.TWIN_DEVICES, "${inv.deviceId}.md").writeText(DeviceInventory.serialize(inv))
    }

    @Test
    fun `一行一设备_本机优先_退役垫底`() {
        val now = java.time.OffsetDateTime.parse("2026-10-23T00:00+08:00").toInstant().toEpochMilli()
        val text = DeviceIndexWriter.render(
            listOf(
                inventory("bbbb2222", "台式机", "2026-10-20T00:00+08:00"),
                inventory("cccc3333", "旧平板", "2026-08-01T00:00+08:00", status = DeviceInventory.STATUS_RETIRED),
                inventory(localId, "荣耀平板", "2026-10-22T22:41+08:00")
            ),
            localId = localId,
            now = now
        )
        val first = text.lineSequence().first()
        assertTrue("首行必须声明自动生成: $first", first.contains("自动生成"))
        val rows = text.lines().filter { it.startsWith("- ") }
        assertTrue("每设备一行, 实际 ${rows.size}: $rows", rows.size == 3)
        assertTrue("本机排第一: $rows", rows[0].contains("荣耀平板") && rows[0].contains("本机"))
        assertTrue("退役行垫底: $rows", rows.last().contains("旧平板") && rows.last().contains("已退役"))
        assertFalse("非本机行不得带本机标记: $rows", rows[1].contains("本机"))
    }

    @Test
    fun `时效文案与模型能力进索引`() {
        val now = java.time.OffsetDateTime.parse("2026-10-23T00:00+08:00").toInstant().toEpochMilli()
        val text = DeviceIndexWriter.render(
            listOf(inventory(localId, "荣耀平板", "2026-10-22T22:41+08:00", plugins = listOf("browser"), model = "deepseek-flash")),
            localId = localId,
            now = now
        )
        assertTrue("应有小时级时效: $text", text.contains("小时前更新"))
        assertTrue("应含模型: $text", text.contains("deepseek-flash"))
        assertTrue("应含关键能力: $text", text.contains("browser"))
        val days = DeviceIndexWriter.render(
            listOf(inventory(localId, "荣耀平板", "2026-09-01T00:00+08:00")),
            localId = localId, now = now
        )
        assertTrue("应有天级时效: $days", days.contains("天前更新"))
    }

    @Test
    fun `重建落盘且无变化不重写`() {
        DeviceInventoryStore.write(inventory(localId, "荣耀平板", "2026-10-22T22:41+08:00"), localId)
        val file = DeviceIndexWriter.indexFile()
        assertTrue("写入库存后应自动生成索引: ${file.absolutePath}", file.isFile)
        val first = DeviceIndexWriter.read()
        val mtime = file.lastModified()
        DeviceIndexWriter.rebuild()
        assertTrue("内容应存在", first.isNotBlank())
        assertTrue("无变化不得重写 (mtime 不变)", file.lastModified() <= mtime + 1000L)
        assertTrue(DeviceIndexWriter.read().contains("荣耀平板"))
    }

    @Test
    fun `库存为空时索引给出登记提示`() {
        val text = DeviceIndexWriter.render(emptyList(), localId = localId)
        assertTrue("空库提示: $text", text.contains("device.refresh"))
    }

    // ── 提示词注入 (P4, 设计 §8.1 / §8.2) ─────────────────────────

    @Test
    fun `提示词注入设备段并随索引变化重建`() {
        DeviceInventoryStore.write(
            inventory(localId, "荣耀平板", "2026-10-22T22:41+08:00", plugins = listOf("browser"), model = "deepseek-flash"),
            localId
        )
        writeForeign(inventory("bbbb2222", "台式机", "2026-10-20T00:00+08:00", plugins = listOf("office")))
        DeviceIndexWriter.rebuild()

        val engine = PromptEngine()
        val before = engine.buildSystemPrompt(agentName = agent)
        assertTrue("应注入设备段: $before", before.contains("## 你的设备与能力"))
        assertTrue("应登记本机那一行: $before", before.contains("你寄存于"))
        assertTrue("其余端应出现: $before", before.contains("其余 1 端"))
        assertTrue("应有单写者约束: $before", before.contains("你只登记自己那一行"))
        assertTrue("应有冲突裁决约束: $before", before.contains("冲突标记"))

        Thread.sleep(10) // 保证 index.md mtime 变化
        writeForeign(inventory("cccc3333", "手机", "2026-10-22T23:00+08:00", plugins = listOf("camera")))
        assertNotNull(DeviceIndexWriter.rebuild())

        val after = engine.buildSystemPrompt(agentName = agent)
        assertTrue("索引变化后必须重建提示词: $after", after.contains("手机"))
        assertTrue(after.contains("其余 2 端"))
    }

    @Test
    fun `无库存文件时不注入设备段`() {
        val prompt = PromptEngine().buildSystemPrompt(agentName = agent)
        assertFalse("无库存不应占 token: $prompt", prompt.contains("## 你的设备与能力"))
    }
}
