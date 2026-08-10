// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import org.junit.Assert.assertEquals
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
}
