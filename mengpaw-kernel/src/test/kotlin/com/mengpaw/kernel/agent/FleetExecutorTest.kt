// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.cli.ExecutionContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * FleetExecutor 命令回归测试 (v0.36 平台化) —
 * fleet 命名空间常驻内核, 平台能力经 FleetPlatform 注入: 用 fake provider 验证
 * 成员总览/信任门禁/任务状态/能力卡/文件错误路径。
 */
class FleetExecutorTest {

    private val base = File(System.getProperty("java.io.tmpdir"), "fleet_exec_test-${System.nanoTime()}")
    private val ctx = ExecutionContext(sessionId = "test-session", agentName = "MengPaw")

    private fun member(trusted: Boolean = true) = FleetMember(
        name = "坦克机", fingerprint = "mengpaw|aa:bb:cc:dd:ee:ff",
        // 测试环境不可达地址 — sendDirect 快速拒绝, 不依赖真实局域网
        frameworkType = "mengpaw", address = "127.0.0.1", port = 1,
        trusted = trusted, lastSeen = System.currentTimeMillis())

    @Before
    fun init() {
        com.mengpaw.kernel.DataPaths.initialize(base.absolutePath)
        FleetRuntimeStore.clear()
        FleetCapability.cache.clear()
        FleetPlatform.membersProvider = { listOf(member()) }
        FleetPlatform.localIpv4Provider = { "192.168.2.9" }
        FleetPlatform.localPeerIdProvider = { "mengpaw-abc-def" }
        FleetPlatform.capabilityProvider = {
            FleetCapability("MengPaw (Android)", "mengpaw", "0.35.5", "Android 16",
                "test-device", 8, 4096, 51200).toJson()
        }
    }

    @After
    fun cleanup() {
        FleetPlatform.membersProvider = null
        FleetPlatform.localIpv4Provider = null
        FleetPlatform.localPeerIdProvider = null
        FleetPlatform.capabilityProvider = null
        FleetRuntimeStore.clear()
        FleetCapability.cache.clear()
        try { base.deleteRecursively() } catch (_: Exception) {}
    }

    @Test
    fun 舰队成员总览() {
        val r = runBlocking { FleetExecutor.commands["peers"]!!.invoke(emptyList(), ctx) }
        assertTrue(r.success)
        assertTrue(r.output.contains("坦克机"))
        assertTrue(r.output.contains("mengpaw"))
    }

    @Test
    fun 委派信任门禁拒绝未信任节点() {
        FleetPlatform.membersProvider = { listOf(member(trusted = false)) }
        val r = runBlocking { FleetExecutor.commands["delegate"]!!.invoke(listOf("坦克机", "执行测试"), ctx) }
        assertTrue(r.success == false)
        assertTrue(r.error.orEmpty().contains("未信任"))
    }

    @Test
    fun 委派发送失败保留SENT记录() {
        // 对端不可达 (无真实监听) — sendDirect 返回 false, 但任务状态已落盘 SENT
        val r = runBlocking { FleetExecutor.commands["delegate"]!!.invoke(listOf("坦克机", "开发 APK"), ctx) }
        assertTrue(r.success == false)
        assertTrue(r.error.orEmpty().contains("委派发送失败"))
        assertEquals("SENT", FleetRuntimeStore.list().first().status)
    }

    @Test
    fun 任务状态展示委派记录() {
        FleetRuntimeStore.startTask("fleet-x1", "执行测试", "坦克机", "mengpaw-abc-def")
        val r = runBlocking { FleetExecutor.commands["status"]!!.invoke(emptyList(), ctx) }
        assertTrue(r.success)
        assertTrue(r.output.contains("fleet-x1"))
        assertTrue(r.output.contains("执行测试"))
    }

    @Test
    fun 发送不存在的文件报错() {
        val r = runBlocking { FleetExecutor.commands["send"]!!.invoke(listOf("坦克机", "C:/no/such.apk"), ctx) }
        assertTrue(r.success == false)
        assertTrue(r.error.orEmpty().contains("文件不存在"))
    }

    @Test
    fun 本机能力卡展示() {
        val r = runBlocking { FleetExecutor.commands["capability"]!!.invoke(emptyList(), ctx) }
        assertTrue(r.success)
        assertTrue(r.output.contains("MengPaw (Android)"))
        assertTrue(r.output.contains("8 核"))
    }

    @Test
    fun 能力扫描写Notes() {
        // 对端不可达 — 0 份上报, 但 Notes 文件仍生成 (含空提示)
        val r = runBlocking { FleetExecutor.commands["scan"]!!.invoke(emptyList(), ctx) }
        assertTrue(r.success)
        val notes = File(com.mengpaw.kernel.DataPaths.AGENTS, "MengPaw/Notes/fleet_capabilities.md")
        assertTrue("Notes 应生成", notes.exists())
        assertTrue(notes.readText().contains("fleet.scan"))
    }
}
