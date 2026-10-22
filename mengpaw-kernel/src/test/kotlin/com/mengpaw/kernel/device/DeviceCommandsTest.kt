// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.device

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `device.*` 命令测试 (P4, 设计 §9.1) — self / list / info / find / refresh。
 *
 * 采集器经 [DeviceCollectors] 注入假实现 (与 Android 同一条 SPI), 库存目录用临时目录,
 * 不碰用户真实数据。
 */
class DeviceCommandsTest {

    private lateinit var base: File
    private val collector = FakeCollector()
    private val ctx = ExecutionContext(sessionId = "test-session", agentName = "MengPaw")

    private class FakeCollector(var snapshot: DeviceSnapshot? = null) : DeviceCollector {
        var calls = 0
        override fun collect(): DeviceSnapshot? {
            calls++
            return snapshot
        }
    }

    @Before
    fun setUp() {
        base = Files.createTempDirectory("mengpaw-device-cmd-").toFile()
        DataPaths.initialize(base.absolutePath)
        DeviceInventoryStore.devicesDir().mkdirs()
        collector.snapshot = DeviceSnapshot(
            deviceName = "荣耀平板",
            formFactor = "TABLET",
            os = "Android 15 (API 35)",
            cpuCores = 8,
            ramMB = 8192,
            storageFreeMB = 41_200,
            screen = "2560x1600@320",
            sensors = listOf("accel", "gyro"),
            camera = listOf("rear", "front")
        )
        DeviceCollectors.collector = collector
        DeviceCollectors.agentFactsProvider = {
            listOf(
                AgentEntry(
                    name = "MengPaw", model = "deepseek-flash", contextTokens = 128_000,
                    vision = true, tools = true, plugins = listOf("browser")
                )
            )
        }
    }

    @After
    fun tearDown() {
        DeviceCollectors.reset()
        base.deleteRecursively()
    }

    private suspend fun run(cmd: String, vararg args: String): ExecutionResult {
        val handler = requireNotNull(DeviceCommands.commands[cmd]) { "未注册命令: $cmd" }
        return handler(args.toList(), ctx)
    }

    private fun writeForeign(inv: DeviceInventory) {
        File(DataPaths.TWIN_DEVICES, "${inv.deviceId}.md").writeText(DeviceInventory.serialize(inv))
    }

    // ── device.refresh ─────────────────────────────────────────────

    @Test
    fun `refresh 首次写入_再次无差异不写`() = runTest {
        val first = run("refresh")
        assertTrue(first.error, first.success)
        assertTrue("应提示已更新: ${first.output}", first.output.contains("已更新本机库存"))
        val localFile = File(DataPaths.TWIN_DEVICES, "${DeviceInventoryStore.localDeviceId()}.md")
        assertTrue("本机库存必须落盘: ${localFile.absolutePath}", localFile.isFile)
        val observed = DeviceInventory.parse(localFile.readText())!!.observedAt

        val second = run("refresh")
        assertTrue(second.success)
        assertTrue("无差异不写: ${second.output}", second.output.contains("无变化"))
        assertTrue("观测时间应保持: ${second.output}", second.output.contains(observed))
    }

    @Test
    fun `refresh 硬件变化后重写`() = runTest {
        assertTrue(run("refresh").success)
        val firstText = DeviceInventoryStore.readLocal()!!.cpuCores
        collector.snapshot = collector.snapshot!!.copy(cpuCores = 12)
        val again = run("refresh")
        assertTrue(again.success)
        assertTrue("应重新采集: ${again.output}", again.output.contains("已更新本机库存"))
        assertTrue("CPU 应更新", DeviceInventoryStore.readLocal()!!.cpuCores == 12)
        assertTrue("原值应为 8", firstText == 8)
    }

    @Test
    fun `采集器未注册时明确失败`() = runTest {
        DeviceCollectors.collector = null
        val result = run("refresh")
        assertFalse(result.success)
        assertTrue("应说明采集器未注册: ${result.error}", result.error.orEmpty().contains("采集器未注册"))
    }

    // ── device.self ────────────────────────────────────────────────

    @Test
    fun `self 显示本机行与寄存声明`() = runTest {
        assertTrue(run("refresh").success)
        val self = run("self")
        assertTrue(self.success)
        assertTrue("应含本机库存段", self.output.contains("## 本机库存"))
        assertTrue("应登记设备名", self.output.contains("荣耀平板"))
        assertTrue("应给寄存声明", self.output.contains("寄存声明"))
        assertTrue("应标注只写自己那一行", self.output.contains("你只登记自己那一行"))
        assertTrue("应展示模型能力", self.output.contains("deepseek-flash"))
    }

    @Test
    fun `self 未登记时提示 refresh`() = runTest {
        val self = run("self")
        assertTrue(self.success)
        assertTrue("应引导采集: ${self.output}", self.output.contains("device.refresh"))
    }

    // ── device.list ────────────────────────────────────────────────

    @Test
    fun `list 同时列出本机与对端`() = runTest {
        assertTrue(run("refresh").success)
        writeForeign(DeviceInventory(deviceId = "peer9999", deviceName = "台式机", formFactor = "DESKTOP", observedAt = "2026-10-20T00:00+08:00"))

        val list = run("list")
        assertTrue(list.success)
        assertTrue("应列出本机: ${list.output}", list.output.contains("荣耀平板"))
        assertTrue("应列出对端: ${list.output}", list.output.contains("台式机"))
        assertTrue("应计数 2 端: ${list.output}", list.output.contains("2 端"))
    }

    @Test
    fun `list 空库给出指引`() = runTest {
        val list = run("list")
        assertTrue(list.success)
        assertTrue("应提示采集: ${list.output}", list.output.contains("device.refresh"))
    }

    // ── device.find ────────────────────────────────────────────────

    @Test
    fun `find browser 只返回具备该能力的端`() = runTest {
        assertTrue(run("refresh").success)
        writeForeign(
            DeviceInventory(
                deviceId = "peer9999", deviceName = "手机", formFactor = "PHONE",
                observedAt = "2026-10-20T00:00+08:00",
                agents = listOf(AgentEntry(name = "MengPaw", model = "x", plugins = listOf("camera")))
            )
        )
        val found = run("find", "browser")
        assertTrue(found.success)
        assertTrue("应含本机: ${found.output}", found.output.contains("荣耀平板"))
        assertFalse("无浏览器的端不得出现: ${found.output}", found.output.contains("手机"))
    }

    @Test
    fun `find 无结果时给出可执行文案`() = runTest {
        assertTrue(run("refresh").success)
        val found = run("find", "量子计算")
        assertFalse(found.success)
        assertTrue("无结果文案: ${found.error}", found.error.orEmpty().contains("未找到具备「量子计算」能力的设备"))
        assertTrue("应引导刷新: ${found.error}", found.error.orEmpty().contains("device.refresh"))
    }

    // ── device.info ────────────────────────────────────────────────

    @Test
    fun `info 按名称取完整库存并标注可能过时`() = runTest {
        writeForeign(
            DeviceInventory(
                deviceId = "peer9999", deviceName = "旧平板", formFactor = "TABLET",
                cpuCores = 4, ramMB = 2048, storageFreeMB = 1024,
                observedAt = "2026-01-01T00:00+08:00", sensors = listOf("accel"), camera = listOf("rear")
            )
        )
        val info = run("info", "旧平板")
        assertTrue(info.success)
        assertTrue("应含形态", info.output.contains("TABLET"))
        assertTrue("应含传感器", info.output.contains("accel"))
        assertTrue("过期应标注: ${info.output}", info.output.contains("可能过时"))
        assertTrue("应给文件路径", info.output.contains("peer9999.md"))
    }

    @Test
    fun `info 未找到时明确失败`() = runTest {
        val info = run("info", "不存在的设备")
        assertFalse(info.success)
        assertTrue("应提示未找到: ${info.error}", info.error.orEmpty().contains("未找到设备"))
    }
}
