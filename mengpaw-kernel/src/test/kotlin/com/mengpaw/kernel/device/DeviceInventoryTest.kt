// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.device

import com.mengpaw.kernel.DataPaths
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 设备库存模型测试 (P4, docs/memory-git-sync-design.md §7.2) —
 * 解析/序列化往返、坏 frontmatter 返回 null、未知键保留、单写者拒绝、agents 合并、时效。
 */
class DeviceInventoryTest {

    private lateinit var base: File

    @Before
    fun setUp() {
        base = Files.createTempDirectory("mengpaw-device-inv-").toFile()
        DataPaths.initialize(base.absolutePath)
        DeviceInventoryStore.devicesDir().mkdirs()
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun sample() = DeviceInventory(
        deviceId = "7f3a1234",
        legacyFingerprint = "92c1abcd",
        deviceName = "荣耀平板",
        formFactor = "TABLET",
        os = "Android 15 (API 35)",
        cpuCores = 8,
        ramMB = 8192,
        storageFreeMB = 41_200,
        screen = "2560x1600@320",
        sensors = listOf("accel", "gyro", "light"),
        camera = listOf("rear", "front"),
        observedAt = "2026-10-22T22:41+08:00",
        agents = listOf(
            AgentEntry(
                name = "MengPaw",
                model = "deepseek-flash",
                contextTokens = 128_000,
                vision = true,
                tools = true,
                plugins = listOf("browser", "office", "termux"),
                sources = mapOf("model" to "measured", "vision" to "declared")
            )
        ),
        body = "# 荣耀平板\n\n这是人读补充。"
    )

    // ── 解析 / 序列化 ───────────────────────────────────────────────

    @Test
    fun `序列化往返字段不丢`() {
        val text = DeviceInventory.serialize(sample())
        val parsed = DeviceInventory.parse(text)
        assertNotNull("序列化结果必须可解析:\n$text", parsed)
        assertEquals(sample(), parsed)
        assertTrue("首行必须是 frontmatter 分隔符", text.startsWith("---\n"))
        val agent = parsed!!.agents.single()
        assertEquals("deepseek-flash", agent.model)
        assertEquals(128_000, agent.contextTokens)
        assertEquals(true, agent.vision)
        assertEquals(mapOf("model" to "measured", "vision" to "declared"), agent.sources)
    }

    @Test
    fun `未知键保留且正文不丢`() {
        val text = """
            ---
            deviceId: abc123
            deviceName: 测试机
            cpuCores: 4
            futureKey: keep-me
            agents: []
            ---

            # 测试机

            手写补充。
        """.trimIndent()
        val parsed = DeviceInventory.parse(text) ?: error("应解析成功")
        assertEquals("keep-me", parsed.extra["futureKey"])
        assertEquals("abc123", parsed.deviceId)
        assertTrue("正文应保留: ${parsed.body}", parsed.body.contains("手写补充"))
        assertTrue("未知键应出现在序列化结果", DeviceInventory.serialize(parsed).contains("futureKey: keep-me"))
    }

    @Test
    fun `缺省键给默认值`() {
        val parsed = DeviceInventory.parse("---\ndeviceId: only-id\n---\n")
        assertNotNull(parsed)
        assertEquals(DeviceInventory.FORM_UNKNOWN, parsed!!.formFactor)
        assertEquals(DeviceInventory.STATUS_ACTIVE, parsed.status)
        assertEquals(DeviceInventory.SCHEMA_VERSION, parsed.schemaVersion)
        assertTrue(parsed.agents.isEmpty())
    }

    @Test
    fun `坏 frontmatter 一律返回 null 不抛`() {
        val cases = listOf(
            "没有 frontmatter 的纯文本",
            "---\ndeviceId: abc\n", // 未闭合
            "---\ndeviceId abc\n---\n", // 行内无冒号
            "---\ndeviceId: abc\ncpuCores: eight\n---\n", // 数值非数字
            "---\ndeviceId: abc\nagents:\n  - model: x\n---\n", // agents 项缺 name
            "---\ndeviceName: 无名\n---\n" // 缺 deviceId
        )
        cases.forEach { text ->
            assertNull("应返回 null: $text", DeviceInventory.parse(text))
        }
    }

    @Test
    fun `行内注释与人读样例格式可解析`() {
        val text = """
            ---
            deviceId: 7f3a-…            # 安装期 UUID
            deviceName: 荣耀平板
            formFactor: TABLET
            cpuCores: 8
            ramMB: 8192
            sensors: [accel, gyro, light]
            camera: [rear, front]
            observedAt: 2026-10-22T22:41+08:00
            schemaVersion: 1
            status: active
            agents:
              - name: MengPaw
                model: deepseek-flash
                contextTokens: 128000
                vision: true
                tools: true
                plugins: [browser, office, termux]
                sources: {model: measured, vision: declared}
            ---
        """.trimIndent()
        val parsed = DeviceInventory.parse(text) ?: error("设计文档样例必须可解析")
        assertEquals("7f3a-…", parsed.deviceId)
        assertEquals(listOf("accel", "gyro", "light"), parsed.sensors)
        assertEquals(listOf("rear", "front"), parsed.camera)
        assertEquals(listOf("browser", "office", "termux"), parsed.agents.single().plugins)
    }

    // ── 单写者 ─────────────────────────────────────────────────────

    @Test
    fun `只能写本机那一行_其他端只读`() {
        val own = sample()
        assertEquals(InventoryWrite.WRITTEN, DeviceInventoryStore.write(own, localId = own.deviceId))

        val foreign = own.copy(deviceId = "peer9999", deviceName = "对端")
        assertEquals(InventoryWrite.REJECTED_FOREIGN, DeviceInventoryStore.write(foreign, localId = own.deviceId))
        assertFalse(
            "对端行不得落盘: ${DataPaths.TWIN_DEVICES}",
            File(DataPaths.TWIN_DEVICES, "peer9999.md").exists()
        )
        // 自己的行第二次相同内容 → 无变化, 不重写
        assertEquals(InventoryWrite.UNCHANGED, DeviceInventoryStore.write(own, localId = own.deviceId))
    }

    @Test
    fun `路径穿越形态的 deviceId 被拒绝`() {
        assertNull(DeviceInventoryStore.fileNameOf("../evil"))
        assertNull(DeviceInventoryStore.fileNameOf("a/b"))
        assertNull(DeviceInventoryStore.fileNameOf(".."))
        assertNull(DeviceInventoryStore.fileNameOf("with.dot"))
        assertEquals("abc-1_x.md", DeviceInventoryStore.fileNameOf("abc-1_x"))
    }

    // ── agents 合并 ────────────────────────────────────────────────

    @Test
    fun `同 agent 取 observedAt 较新的一方`() {
        val older = AgentEntry(name = "MengPaw", model = "old-model", observedAt = "2026-10-20T10:00+08:00")
        val newer = AgentEntry(name = "MengPaw", model = "new-model", observedAt = "2026-10-22T10:00+08:00")
        val merged = DeviceInventoryStore.mergeAgents(listOf(older), listOf(newer))
        assertEquals("new-model", merged.single().model)

        val reverse = DeviceInventoryStore.mergeAgents(listOf(newer), listOf(older))
        assertEquals("旧值更旧时不得覆盖", "new-model", reverse.single().model)
    }

    @Test
    fun `不同 agent 全部保留且顺序稳定`() {
        val a = AgentEntry(name = "A", model = "m1", observedAt = "2026-10-20T10:00+08:00")
        val b = AgentEntry(name = "B", model = "m2", observedAt = "2026-10-22T10:00+08:00")
        val merged = DeviceInventoryStore.mergeAgents(listOf(a), listOf(b))
        assertEquals(listOf("A", "B"), merged.map { it.name })
    }

    // ── 时效 ───────────────────────────────────────────────────────

    @Test
    fun `时效计算与过期标注`() {
        val now = java.time.OffsetDateTime.parse("2026-10-23T00:00+08:00").toInstant().toEpochMilli()
        val fresh = sample().copy(observedAt = "2026-10-22T22:41+08:00")
        assertEquals(0L, DeviceInventoryStore.ageDays(fresh.observedAt, now) ?: -1L)
        assertTrue(DeviceInventoryStore.ageText(fresh, now).contains("小时前"))
        assertFalse(DeviceInventoryStore.isStale(fresh, now))

        val stale = sample().copy(observedAt = "2026-09-01T00:00+08:00")
        assertEquals(52L, DeviceInventoryStore.ageDays(stale.observedAt, now) ?: -1L)
        assertTrue("应标可能过时", DeviceInventoryStore.isStale(stale, now))
        assertTrue(DeviceInventoryStore.ageText(stale, now).contains("天前更新"))

        val unknown = sample().copy(observedAt = "")
        assertNull(DeviceInventoryStore.ageDays("", now))
        assertEquals("无时间记录", DeviceInventoryStore.ageText(unknown, now))
        assertFalse("缺时间不等于过期", DeviceInventoryStore.isStale(unknown, now))
    }

    @Test
    fun `能力关键词覆盖插件传感器相机形态`() {
        val caps = sample().capabilities()
        assertTrue(caps.contains("browser"))
        assertTrue(caps.contains("vision"))
        assertTrue(caps.contains("accel"))
        assertTrue(caps.contains("camera"))
        assertTrue(caps.contains("tablet"))
    }
}
