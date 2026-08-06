// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.hermes

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.acp.AcpMessage
import com.mengpaw.kernel.acp.AcpMessageType
import com.mengpaw.kernel.acp.AcpResult
import com.mengpaw.kernel.acp.AcpServer
import com.mengpaw.kernel.agent.AgentProfile
import com.mengpaw.kernel.security.PromptFirewall
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * TribeAcpHandler 消息分发与信任门测试 (插件零测试补齐)。
 *
 * TribeAcpHandler 无任何 Android 依赖, 可在 JVM 直接构造:
 * - 分发逻辑: DELEGATE/RESULT/SHARE_MEMORY/HEARTBEAT/未知类型/停服标志
 * - 信任门: 经 AcpServer.handleMessage 走内核 PromptFirewall 门禁,
 *   未配对 peer 的消息被拦截, 配对后放行。
 */
class TribeAcpHandlerTest {

    private lateinit var tempBase: File
    private lateinit var board: TribeKanbanBoard
    private lateinit var server: AcpServer
    private lateinit var handler: TribeAcpHandler

    @Before
    fun setUp() {
        tempBase = File(System.getProperty("java.io.tmpdir"), "mengpaw-hermes-acp-${System.nanoTime()}")
        DataPaths.initialize(tempBase.absolutePath)
        board = TribeKanbanBoard()
        handler = TribeAcpHandler(localAgentName = "agent-b", kanbanBoard = board,
            delegateEngine = null, heartbeatMonitor = null)
        server = AcpServer(AgentProfile(agentId = "agent-b", agentName = "agent-b"), sharedSecret = "test-secret")
        server.registerHandler(handler)
    }

    @After
    fun tearDown() {
        DataPaths.initialize("/sdcard/MengPaw")
        tempBase.deleteRecursively()
    }

    private fun delegatePayload(title: String) = buildJsonObject {
        put("taskId", "t-123")
        put("title", title)
        put("priority", "P1")
        put("fromAgent", "agent-a")
    }.toString()

    // ── 分发: DELEGATE ─────────────────────────────────────────

    @Test
    fun `DELEGATE 结构化 JSON 创建看板任务并转 ASSIGNED (P0 修复回归)`() = runTest {
        val msg = AcpMessage("agent-a", "agent-b", AcpMessageType.DELEGATE.name, delegatePayload("委派任务"))
        val result = handler.handle(msg, server) as AcpResult

        assertTrue(result.success)
        assertEquals("delegate_queued", result.message)
        val taskId = result.data
        assertTrue(taskId.isNotBlank())

        // 看板任务存在且已 ASSIGNED — 否则后续 RESULT 落 COMPLETED 必抛 (P0 死锁修复)
        val task = board.get(taskId)
        assertNotNull(task)
        assertEquals(TaskStatus.ASSIGNED, task!!.status)
        assertEquals("agent-b", task.toAgent)
        assertEquals(DelegateMode.ACP, task.delegateMode)
    }

    @Test
    fun `DELEGATE 非 JSON 载荷返回 null 让内核 handler 兜底`() = runTest {
        val msg = AcpMessage("agent-a", "agent-b", AcpMessageType.DELEGATE.name, "纯文本任务")
        assertNull(handler.handle(msg, server))
    }

    @Test
    fun `DELEGATE 缺 title 返回 null`() = runTest {
        val msg = AcpMessage("agent-a", "agent-b", AcpMessageType.DELEGATE.name,
            """{"taskId":"t-1","description":"没标题"}""")
        assertNull(handler.handle(msg, server))
    }

    // ── 分发: RESULT ───────────────────────────────────────────

    @Test
    fun `RESULT COMPLETED 更新看板任务为终态并记录结果`() = runTest {
        val created = board.create(TribeTask(title = "任务", fromAgent = "agent-a", toAgent = "agent-b"))
        board.transition(created.id, TaskStatus.ASSIGNED)

        val payload = buildJsonObject {
            put("taskId", created.id)
            put("result", "任务产出")
            put("status", "COMPLETED")
        }.toString()
        val result = handler.handle(
            AcpMessage("agent-a", "agent-b", AcpMessageType.RESULT.name, payload), server) as AcpResult

        assertTrue(result.success)
        assertEquals(TaskStatus.COMPLETED, board.get(created.id)!!.status)
        assertEquals("任务产出", board.get(created.id)!!.result)
    }

    @Test
    fun `RESULT 未知状态返回失败结果`() = runTest {
        val created = board.create(TribeTask(title = "任务", fromAgent = "agent-a", toAgent = "agent-b"))
        val payload = """{"taskId":"${created.id}","status":"WAT"}"""
        val result = handler.handle(
            AcpMessage("agent-a", "agent-b", AcpMessageType.RESULT.name, payload), server) as AcpResult
        assertFalse(result.success)
        assertEquals("unknown_status", result.message)
    }

    // ── 分发: SHARE_MEMORY / HEARTBEAT / 其它 ───────────────────

    @Test
    fun `SHARE_MEMORY 写入团队记忆目录`() = runTest {
        val result = handler.handle(
            AcpMessage("agent-a", "agent-b", AcpMessageType.SHARE_MEMORY.name, "共享内容"), server) as AcpResult
        assertTrue(result.success)
        val memosDir = File(DataPaths.TEAM_MEMOS)
        assertTrue(memosDir.listFiles()!!.any { it.name.startsWith("memo_acp_") && it.readText().contains("共享内容") })
    }

    @Test
    fun `HEARTBEAT 返回 alive`() = runTest {
        val result = handler.handle(
            AcpMessage("agent-a", "*", AcpMessageType.HEARTBEAT.name, ttl = 1), server) as AcpResult
        assertTrue(result.success)
        assertEquals("alive", result.message)
    }

    @Test
    fun `未知消息类型返回 null`() = runTest {
        val msg = AcpMessage("agent-a", "agent-b", AcpMessageType.DISCOVER.name, "")
        assertNull(handler.handle(msg, server))
    }

    @Test
    fun `服务停止后 handler 拒绝处理 (P1 修复回归)`() = runTest {
        handler.active = false
        assertNull(handler.handle(
            AcpMessage("agent-a", "agent-b", AcpMessageType.DELEGATE.name, delegatePayload("停服任务")), server))
        // 恢复标志, 避免影响后续测试
        handler.active = true
    }

    // ── 信任门: 经 AcpServer 全链路 ─────────────────────────────

    @Test
    fun `未配对 peer 的 DELEGATE 被内核防火墙拦截`() = runTest {
        val peerId = "guest-${System.nanoTime()}"  // 每测试唯一, 避免信任标记串扰
        val raw = Json.encodeToString(AcpMessage.serializer(),
            AcpMessage(peerId, "agent-b", AcpMessageType.DELEGATE.name, delegatePayload("越权任务")))

        val result = server.handleMessage(raw)
        assertFalse(result.success)
        assertTrue(result.message.contains("Firewall blocked") || result.message.contains("GUEST"))
        // 看板未被污染
        assertTrue(board.list().isEmpty())
    }

    @Test
    fun `配对后 peer 的 DELEGATE 放行并入库`() = runTest {
        val peerId = "paired-${System.nanoTime()}"
        PromptFirewall.trust(peerId, "fp")
        try {
            val raw = Json.encodeToString(AcpMessage.serializer(),
                AcpMessage(peerId, "agent-b", AcpMessageType.DELEGATE.name, delegatePayload("受信任委派")))

            val result = server.handleMessage(raw)
            assertTrue("受信任 peer 应放行: ${result.message}", result.success)
            assertEquals("delegate_queued", result.message)
            assertEquals(1, board.list().size)
        } finally {
            PromptFirewall.untrust(peerId)
        }
    }

    @Test
    fun `未配对 peer 的只读命令按访客白名单放行`() = runTest {
        val peerId = "guest-read-${System.nanoTime()}"
        // agent.audit 在 GUEST_ALLOWED — 经内核 check 应放行 (kernel 未注册其它 DELEGATE 消费者时仍返回 ack)
        val raw = Json.encodeToString(AcpMessage.serializer(),
            AcpMessage(peerId, "agent-b", AcpMessageType.DELEGATE.name, "agent.audit 今天"))
        val result = server.handleMessage(raw)
        assertTrue("访客只读命令应放行: ${result.message}", result.success)
    }
}
