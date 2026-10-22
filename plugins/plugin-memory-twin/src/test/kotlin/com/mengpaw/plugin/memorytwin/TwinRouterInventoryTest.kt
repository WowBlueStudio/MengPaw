// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.device.AgentEntry
import com.mengpaw.kernel.device.DeviceInventory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 库存路由测试 (P4, 设计 §7.5/§7.6) — 能力卡来源改为库存后:
 * 1. **离线设备也参与评估** (不再只认本次会话上报过能力卡的节点);
 * 2. **过期 (>30 天) 仍参与**, 但结论标注"可能过时";
 * 3. 库存缺运行时字段 (电量/网络) 不得误扣分。
 */
class TwinRouterInventoryTest {

    private lateinit var base: File

    @Before
    fun setUp() {
        base = Files.createTempDirectory("mengpaw-router-inv-").toFile()
        DataPaths.initialize(base.absolutePath)
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun inventory(
        id: String,
        name: String,
        formFactor: String,
        observedAt: String,
        plugins: List<String> = emptyList(),
        model: String = "test-model",
        cmCores: Int = 8
    ) = DeviceInventory(
        deviceId = id,
        deviceName = name,
        formFactor = formFactor,
        cpuCores = cmCores,
        ramMB = 8192,
        observedAt = observedAt,
        agents = listOf(AgentEntry(name = "MengPaw", model = model, plugins = plugins))
    )

    private val now = java.time.OffsetDateTime.parse("2026-10-23T00:00+08:00").toInstant().toEpochMilli()

    @Test
    fun `库存路由让离线端参与并按能力选出最优端`() {
        val devices = listOf(
            inventory("tablet-1", "荣耀平板", "TABLET", "2026-10-22T22:00+08:00", plugins = listOf("browser")),
            inventory("pc-1", "台式机", "DESKTOP", "2026-10-22T22:00+08:00", plugins = listOf("office"))
        )
        val analysis = TwinRouter.routeFromInventory("打开浏览器搜索这个网页", devices, now)
        assertEquals("两端都应参与评估", 2, analysis.recommendations.size)
        assertEquals("tablet-1", analysis.bestPeerId)
        val best = analysis.recommendations.first()
        assertTrue("优势应写浏览器可用: ${best.strengths}", best.strengths.any { it.contains("浏览器") })
        assertTrue("不足应写无浏览器: ${analysis.recommendations.last().weaknesses}",
            analysis.recommendations.last().weaknesses.any { it.contains("无浏览器") })
    }

    @Test
    fun `过期库存仍参与但标注可能过时`() {
        val old = inventory("old-1", "旧手机", "PHONE", "2026-01-01T00:00+08:00", plugins = listOf("browser"))
        val analysis = TwinRouter.routeFromInventory("识别这张图片里的文字", listOf(old), now)
        assertEquals("过期设备不得被排除", 1, analysis.recommendations.size)
        val rec = analysis.recommendations.single()
        assertEquals("应标注过期", "可能过时", rec.staleness)
        assertTrue("不足应提示可能过时: ${rec.weaknesses}", rec.weaknesses.any { it.contains("可能过时") })
        assertTrue("总结应提示时效风险: ${analysis.summary}", analysis.summary.contains("可能过时"))
    }

    @Test
    fun `库存缺运行时字段不误扣分`() {
        val fresh = inventory("tab-2", "新平板", "TABLET", "2026-10-22T22:00+08:00")
        val rec = TwinRouter.routeFromInventory("随便聊聊", listOf(fresh), now).recommendations.single()
        assertTrue("基线 50 + 平板加分, 不应被电量/网络扣分 (实际 ${rec.score})", rec.score >= 50)
        assertTrue("不得把未知电量当低电: ${rec.weaknesses}", rec.weaknesses.none { it.contains("电量") })
    }
}
