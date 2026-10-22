// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.device.AgentEntry
import com.mengpaw.kernel.device.DeviceInventory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FleetCapability 回归测试 (v0.36) — 能力卡序列化往返 + Notes 清单生成。
 */
class FleetCapabilityTest {

    private fun card() = FleetCapability(
        frameworkName = "MengPaw (Android)",
        frameworkType = "mengpaw",
        version = "0.35.5",
        environment = "Android 16 (API 36)",
        deviceName = "vivo PD2361",
        cpuCores = 8,
        ramMB = 4096,
        diskFreeMB = 51200,
        devTools = listOf("termux")
    )

    @Test
    fun 能力卡序列化往返保真() {
        val restored = FleetCapability.fromJson(card().toJson())
        assertEquals("MengPaw (Android)", restored?.frameworkName)
        assertEquals("mengpaw", restored?.frameworkType)
        assertEquals(8, restored?.cpuCores)
        assertEquals(51200L, restored?.diskFreeMB)
        assertEquals(listOf("termux"), restored?.devTools)
    }

    @Test
    fun Notes清单包含框架与环境信息() {
        val notes = FleetCapability.formatNotes(mapOf("mengpaw-abc" to card().toJson()))
        assertTrue(notes.contains("## MengPaw (Android) (mengpaw)"))
        assertTrue(notes.contains("环境: Android 16 (API 36)"))
        assertTrue(notes.contains("8 核"))
        assertTrue(notes.contains("开发环境: termux"))
    }

    @Test
    fun 空清单提示扫描() {
        assertTrue(FleetCapability.formatNotes(emptyMap()).contains("fleet.scan"))
    }

    private fun inventory(
        id: String, name: String, form: String, cpu: Int, ramMB: Long,
        model: String, ctx: Int, plugins: List<String> = emptyList()
    ) = DeviceInventory(
        deviceId = id,
        deviceName = name,
        formFactor = form,
        cpuCores = cpu,
        ramMB = ramMB,
        storageFreeMB = 41_200,
        observedAt = "2026-10-22T22:00+08:00",
        agents = listOf(
            AgentEntry(name = "MengPaw", model = model, contextTokens = ctx, plugins = plugins)
        )
    )

    @Test
    fun 库存优先渲染_每设备一节含模型与CPU() {
        val rows = listOf(
            inventory("tab-1", "荣耀平板", "TABLET", 8, 8192, "deepseek-flash", 128_000, listOf("browser")),
            inventory("pc-1", "台式机", "DESKTOP", 16, 32768, "gpt-5.4", 200_000, listOf("office"))
        )
        // 内存卡也非空 — 有库存时必须忽略它 (证明库存优先)
        val notes = FleetCapability.formatNotes(mapOf("peer-x" to card().toJson()), rows)

        assertTrue("应含平板一节: $notes", notes.contains("## 荣耀平板 (TABLET)"))
        assertTrue("应含台式机一节: $notes", notes.contains("## 台式机 (DESKTOP)"))
        assertFalse("库存优先时不得渲染内存卡", notes.contains("MengPaw (Android)"))
        assertTrue("应含模型字段: $notes", notes.contains("模型: deepseek-flash"))
        assertTrue("应含 CPU 字段: $notes", notes.contains("CPU 8 核"))
        assertTrue("应含内存字段: $notes", notes.contains("内存 8G"))
        assertTrue("应含关键能力: $notes", notes.contains("关键能力: browser"))
        assertTrue("应含更新时间: $notes", notes.contains("更新时间:"))
    }

    @Test
    fun 库存为空回退内存能力卡() {
        val notes = FleetCapability.formatNotes(mapOf("mengpaw-abc" to card().toJson()), emptyList())
        assertTrue("回退旧行为: $notes", notes.contains("## MengPaw (Android) (mengpaw)"))
        assertTrue("回退旧行为: $notes", notes.contains("开发环境: termux"))
        assertFalse("未走库存渲染", notes.contains("来源: 设备库存"))
    }
}
