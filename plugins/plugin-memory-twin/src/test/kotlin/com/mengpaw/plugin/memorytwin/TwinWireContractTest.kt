// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.acp.AcpMessage
import com.mengpaw.kernel.acp.AcpResult
import com.mengpaw.kernel.acp.AcpServer
import com.mengpaw.kernel.agent.AgentProfile
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 交付线协议契约测试 (P0 回归) — **发送端与接收端必须能对接**。
 *
 * 背景: 93a1ef6b 只统一了清单哈希形态, 没统一 `send` 载荷形态 —— 发送端写扁平字符串、
 * 接收端按 `{content, mtime}` 对象解析, 只要真有差异就抛异常; 68 个用例全是"单边各测各的",
 * 所以这种跨组件形态漂移可以全绿。本测试是唯一一条真正跨越两端的用例:
 * `buildManifestPayload` / `TwinAcpHandler` 产出 → `parseManifestResponse` 消费。
 */
class TwinWireContractTest {

    private lateinit var tempBase: File
    private lateinit var handler: TwinAcpHandler
    private lateinit var server: AcpServer

    @Before
    fun setUp() {
        tempBase = File(System.getProperty("java.io.tmpdir"), "mengpaw-wire-${System.nanoTime()}")
        DataPaths.initialize(tempBase.absolutePath)
        File(DataPaths.AGENTS, "agent/workspace").mkdirs()
        File(DataPaths.AGENTS, "agent/workspace/doc.md").writeText("文档内容 v1")
        val engine = TwinSyncEngine(serverSupplier = { null }, transportSupplier = { null },
            agentName = "agent", deviceId = "dev-1", deviceName = "测试机")
        handler = TwinAcpHandler(engine)
        server = AcpServer(AgentProfile(agentId = "agent", agentName = "agent"), sharedSecret = "test-secret")
        server.registerHandler(handler)
    }

    @After
    fun tearDown() {
        DataPaths.initialize("/sdcard/MengPaw")
        tempBase.deleteRecursively()
    }

    /** 用发送端生成清单, 喂给接收端 handler: 内容一致 → send 必须为空。 */
    @Test
    fun `发送端清单与接收端比对一致时不重发 (跨组件回归)`() = runTest {
        val payload = buildManifestPayload("agent")
        // 哈希形态必须是协议形态 (16 字符), 否则接收端比较必然失配
        val files = Json.parseToJsonElement(payload).jsonObject["files"]!!.jsonObject
        assertEquals(TwinWorkspace.MANIFEST_HASH_CHARS,
            files["workspace/doc.md"]!!.jsonObject["hash"]!!.jsonPrimitive.content.length)

        val result = handler.handle(AcpMessage.wsManifest("peer-1", "agent", payload), server) as AcpResult
        assertTrue(result.success)
        val resp = Json.parseToJsonElement(result.data).jsonObject
        assertTrue("内容一致不得重发: ${resp["send"]}", resp["send"]!!.jsonObject.isEmpty())
    }

    /** 真有差异: handler 产出的 send 必须能被接收端解析器原样还原 (含 mtime)。 */
    @Test
    fun `差异文件经 send 通道可被接收端解析还原 (P0 回归)`() = runTest {
        val peerManifest = buildJsonObject {
            put("files", buildJsonObject {
                put("workspace/doc.md", buildJsonObject {
                    put("hash", "0".repeat(TwinWorkspace.MANIFEST_HASH_CHARS))   // 与本地不同
                    put("mtime", 1L)
                })
            })
        }.toString()

        val result = handler.handle(AcpMessage.wsManifest("peer-1", "agent", peerManifest), server) as AcpResult
        assertTrue(result.success)

        // 关键: 用接收端真实解析器消费发送端真实产物
        val parsed = parseManifestResponse(result.data)
        assertEquals(1, parsed.send.size)
        assertEquals("workspace/doc.md", parsed.send[0].relPath)
        assertEquals("文档内容 v1", parsed.send[0].content)
        assertTrue("mtime 必须随内容下发", parsed.send[0].mtime > 0L)
    }

    /** 旧版对端发完整 64 字符哈希时也应判为一致 (混合版本兼容)。 */
    @Test
    fun `对端发完整 64 字符哈希时仍判一致`() = runTest {
        val fullHash = TwinWorkspace.fileHash(File(DataPaths.AGENTS, "agent/workspace/doc.md"))
        val peerManifest = buildJsonObject {
            put("files", buildJsonObject {
                put("workspace/doc.md", buildJsonObject {
                    put("hash", fullHash)
                    put("mtime", 1L)
                })
            })
        }.toString()

        val result = handler.handle(AcpMessage.wsManifest("peer-1", "agent", peerManifest), server) as AcpResult
        val resp = Json.parseToJsonElement(result.data).jsonObject
        assertTrue("64 字符形态不得被判为差异", resp["send"]!!.jsonObject.isEmpty())
    }

    /** 响应里的穿越路径在解析阶段被跳过 (不进入 apply)。 */
    @Test
    fun `响应中的穿越路径被解析器拒绝`() {
        val parsed = parseManifestResponse(
            """{"send":{"../evil.md":{"content":"x","mtime":1}},"request":["../evil2.md"]}"""
        )
        assertTrue(parsed.send.isEmpty())
        assertTrue(parsed.request.isEmpty())
    }

    /** 写盘入口二次消毒: 即使调用方漏了, 存储层也拒绝越界写入。 */
    @Test
    fun `写盘入口拒绝穿越路径且不在工作区外落文件`() {
        val r = TwinWorkspace.applyWorkspaceFile("agent", "../evil.md", "x", "peer", 0L)
        assertEquals("rejected", r)
        assertFalse(File(DataPaths.AGENTS, "evil.md").exists())
    }

    /** 目标存在但读不出来(是目录) → 明确失败, 不得拿空内容覆盖。 */
    @Test
    fun `目标不可读时返回 error 而不是静默覆盖`() {
        val target = File(DataPaths.AGENTS, "agent/weird.md")
        target.mkdirs()   // 同名目录 → readText 抛异常
        val r = TwinWorkspace.applyWorkspaceFile("agent", "weird.md", "", "peer", Long.MAX_VALUE)
        assertEquals("error", r)
        assertTrue("目录不得被文件覆盖", target.isDirectory)
    }

    /** 内容相同且本地更新 → 仍返回 same (早退在冲突判断之前)。 */
    @Test
    fun `内容相同且本地较新时返回 same 且不产生冲突备份`() {
        val target = File(DataPaths.AGENTS, "agent/workspace/doc.md")
        assertTrue(target.setLastModified(System.currentTimeMillis() + 120_000))
        val r = TwinWorkspace.applyWorkspaceFile("agent", "workspace/doc.md", "文档内容 v1", "peer", 0L)
        assertEquals("same", r)
        val conflicts = target.parentFile!!.listFiles()!!.filter { it.name.contains(".conflict.") }
        assertTrue("相同内容不得产生冲突备份", conflicts.isEmpty())
    }
}
