// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨平台能力卡采集回归测试 (v0.36 平台化) —
 * 纯 JVM 基础采集: 任何跑 kernel 的端开箱可用 (桌面三端直接注册)。
 */
class FleetCapabilityCollectorTest {

    @Test
    fun 基础采集返回合理能力卡() {
        val card = FleetCapabilityCollector.collect(
            frameworkName = "MengPaw (Test)",
            environment = "TestOS x64",
            deviceName = "test-host"
        )
        assertEquals("MengPaw (Test)", card.frameworkName)
        assertEquals("TestOS x64", card.environment)
        assertTrue("CPU 核数应 > 0", card.cpuCores > 0)
        assertTrue("内存应 > 0", card.ramMB > 0)
    }

    @Test
    fun 附加工具合并去重() {
        val card = FleetCapabilityCollector.collect(extraDevTools = listOf("android-sdk", "android-sdk"))
        assertTrue(card.devTools.contains("android-sdk"))
        assertEquals("去重后只保留一份", 1, card.devTools.count { it == "android-sdk" })
    }

    @Test
    fun 能力卡JSON可解析() {
        val json = FleetCapabilityCollector.collectJson()
        val restored = FleetCapability.fromJson(json)
        assertTrue("collectJson 应可解析", restored != null)
        assertEquals("mengpaw", restored?.frameworkType)
    }
}
