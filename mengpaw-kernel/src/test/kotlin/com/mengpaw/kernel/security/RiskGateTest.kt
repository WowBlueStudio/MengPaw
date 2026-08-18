// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.security

import com.mengpaw.kernel.llm.ToolCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** 安全分级系统测试 (v0.34.3): 分级表 / per-agent 权限 / 高危确认总线 / RiskGate 拦截。 */
class RiskGateTest {

    private val tmpDir = File(System.getProperty("java.io.tmpdir"), "mengpaw_riskgate_${System.nanoTime()}")

    @Before
    fun setup() {
        tmpDir.mkdirs()
        AgentPermissionStore.resetForTest(File(tmpDir, "permissions.json"))
        com.mengpaw.kernel.DataPaths.initialize(tmpDir.absolutePath)
    }

    @After
    fun teardown() {
        tmpDir.deleteRecursively()
    }

    // ── CommandRiskLevels 分级表 ──

    @Test
    fun `普通写文件命令为 LOW`() {
        assertEquals(RiskLevel.LOW, CommandRiskLevels.levelOf("agent.output"))
        assertEquals(RiskLevel.LOW, CommandRiskLevels.levelOf("agent.memory.record 记住"))
        assertEquals(RiskLevel.LOW, CommandRiskLevels.levelOf("agent.memory.keep 记住"))
        assertEquals(RiskLevel.LOW, CommandRiskLevels.levelOf("self.notify.message hi"))
    }

    @Test
    fun `删除修改类命令为 MID`() {
        assertEquals(RiskLevel.MID, CommandRiskLevels.levelOf("agent.memory.rm 2026-08-09 10:00"))
        assertEquals(RiskLevel.MID, CommandRiskLevels.levelOf("sys.screenshot"))
        assertEquals(RiskLevel.MID, CommandRiskLevels.levelOf("clipboard.copy hello"))
        assertEquals(RiskLevel.MID, CommandRiskLevels.levelOf("plugin.install x"))
    }

    @Test
    fun `清空卸载系统级为 HIGH`() {
        assertEquals(RiskLevel.HIGH, CommandRiskLevels.levelOf("clipboard.clear"))
        assertEquals(RiskLevel.HIGH, CommandRiskLevels.levelOf("sys.app.uninstall com.x"))
        assertEquals(RiskLevel.HIGH, CommandRiskLevels.levelOf("plugin.uninstall x"))
        assertEquals(RiskLevel.HIGH, CommandRiskLevels.levelOf("agent.memory.mid.delete 2026-08-09"))
        assertEquals(RiskLevel.HIGH, CommandRiskLevels.levelOf("root.exec ls"))
        assertEquals(RiskLevel.HIGH, CommandRiskLevels.levelOf("proc.exec ls"))
        assertEquals(RiskLevel.HIGH, CommandRiskLevels.levelOf("sys.camera.photo --confirm"))
    }

    @Test
    fun `无障碍命令分级_读屏中危_模拟操作高危`() {
        assertEquals(RiskLevel.LOW, CommandRiskLevels.levelOf("sys.accessibility.status"))
        assertEquals(RiskLevel.MID, CommandRiskLevels.levelOf("sys.accessibility.dump --max 100"))
        assertEquals(RiskLevel.HIGH, CommandRiskLevels.levelOf("sys.accessibility.click 100 200"))
        assertEquals(RiskLevel.HIGH, CommandRiskLevels.levelOf("sys.accessibility.click --text 确定"))
        assertEquals(RiskLevel.HIGH, CommandRiskLevels.levelOf("sys.accessibility.swipe 0 0 300 600"))
        assertEquals(RiskLevel.HIGH, CommandRiskLevels.levelOf("sys.accessibility.input hello"))
        assertEquals(RiskLevel.HIGH, CommandRiskLevels.levelOf("sys.accessibility.back"))
        assertEquals(RiskLevel.HIGH, CommandRiskLevels.levelOf("sys.accessibility.home"))
        assertEquals(RiskLevel.HIGH, CommandRiskLevels.levelOf("sys.accessibility.recents"))
    }

    @Test
    fun `未登记命令默认 LOW`() {
        // Linux 命令未登记风险表 → 默认 LOW, 实际安全由 CommandMonitor 规则承载
        assertEquals(RiskLevel.LOW, CommandRiskLevels.levelOf("cat profile.md"))
        assertEquals(RiskLevel.LOW, CommandRiskLevels.levelOf("stat x"))
    }

    // ── AgentPermissionStore ──

    @Test
    fun `权限等级默认标准并可持久化`() {
        assertEquals(AgentPermissionLevel.STANDARD, AgentPermissionStore.levelOf("agent-x"))
        assertTrue(AgentPermissionStore.setLevel("agent-x", AgentPermissionLevel.TRUSTED))
        assertEquals(AgentPermissionLevel.TRUSTED, AgentPermissionStore.levelOf("agent-x"))
        // 重新加载 (模拟重启)
        AgentPermissionStore.resetForTest(File(tmpDir, "permissions.json"))
        assertEquals(AgentPermissionLevel.TRUSTED, AgentPermissionStore.levelOf("agent-x"))
    }

    // ── UserConfirmBus ──

    @Test
    fun `无监听器高危请求默认拒绝`() = runBlocking {
        assertFalse(UserConfirmBus.request("root.exec ls", "测试", "高危"))
    }

    @Test
    fun `监听器拒绝则返回 false`() = runBlocking {
        val listener = UserConfirmBus.Listener { req ->
            UserConfirmBus.respond(req.id, false)
            true
        }
        UserConfirmBus.registerListener(listener)
        try {
            assertFalse(UserConfirmBus.request("root.exec ls", "测试", "高危"))
        } finally {
            UserConfirmBus.unregisterListener(listener)
        }
    }

    @Test
    fun `监听器允许则返回 true`() = runBlocking {
        val listener = UserConfirmBus.Listener { req ->
            UserConfirmBus.respond(req.id, true)
            true
        }
        UserConfirmBus.registerListener(listener)
        try {
            assertTrue(UserConfirmBus.request("root.exec ls", "测试", "高危"))
        } finally {
            UserConfirmBus.unregisterListener(listener)
        }
    }

    @Test
    fun `超时默认拒绝`() = runBlocking {
        val listener = UserConfirmBus.Listener { true } // 展示但不回传 → 等超时
        UserConfirmBus.registerListener(listener)
        try {
            assertFalse(UserConfirmBus.request("root.exec ls", "测试", "高危", timeoutMs = 200))
        } finally {
            UserConfirmBus.unregisterListener(listener)
        }
    }

    // ── RiskGate 集成 ──

    @Test
    fun `LOW 命令放行`() = runBlocking {
        val gate = HighRiskCommandGate.evaluate(ToolCall("agent.memory.record", mapOf("content" to "x")))
        assertNull(RiskGate.evaluate(gate, "agent-x", allowUserConfirm = true))
    }

    @Test
    fun `MID 命令标准权限拒绝信任权限放行`() = runBlocking {
        val gate = HighRiskCommandGate.evaluate(
            ToolCall("agent.memory.rm", mapOf("timestamp" to "2026-08-09", "reason" to "清理"))
        )
        assertNotNull("标准权限应拒绝中危", RiskGate.evaluate(gate, "agent-x", allowUserConfirm = true))
        AgentPermissionStore.setLevel("agent-x", AgentPermissionLevel.TRUSTED)
        assertNull("信任权限应放行中危", RiskGate.evaluate(gate, "agent-x", allowUserConfirm = true))
    }

    @Test
    fun `HIGH 命令 worker 环境直接拒绝`() = runBlocking {
        val gate = HighRiskCommandGate.evaluate(
            ToolCall("root.exec", mapOf("command" to "ls", "reason" to "测试"))
        )
        assertNotNull("worker 无弹窗应拒绝高危", RiskGate.evaluate(gate, "agent-x", allowUserConfirm = false))
    }

    @Test
    fun `HIGH 命令用户拒绝即阻挡`() = runBlocking {
        val gate = HighRiskCommandGate.evaluate(
            ToolCall("root.exec", mapOf("command" to "ls", "reason" to "测试"))
        )
        val listener = UserConfirmBus.Listener { req ->
            UserConfirmBus.respond(req.id, false)
            true
        }
        UserConfirmBus.registerListener(listener)
        try {
            assertNotNull("用户拒绝应阻挡", RiskGate.evaluate(gate, "agent-x", allowUserConfirm = true))
        } finally {
            UserConfirmBus.unregisterListener(listener)
        }
    }

    @Test
    fun `HIGH 命令用户允许则放行`() = runBlocking {
        val gate = HighRiskCommandGate.evaluate(
            ToolCall("clipboard.clear", mapOf("reason" to "清空测试"))
        )
        val listener = UserConfirmBus.Listener { req ->
            UserConfirmBus.respond(req.id, true)
            true
        }
        UserConfirmBus.registerListener(listener)
        try {
            assertNull("用户允许应放行", RiskGate.evaluate(gate, "agent-x", allowUserConfirm = true))
        } finally {
            UserConfirmBus.unregisterListener(listener)
        }
    }
}
