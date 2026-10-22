// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.acp.AcpMessage
import com.mengpaw.kernel.acp.AcpMessageType
import com.mengpaw.kernel.acp.AcpResult
import com.mengpaw.kernel.acp.AcpServer
import com.mengpaw.kernel.acp.AcpTransport
import com.mengpaw.kernel.acp.SyncV3Messages
import com.mengpaw.kernel.agent.AgentProfile
import com.mengpaw.kernel.memory.Snapshot
import com.mengpaw.kernel.memory.SnapshotStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 第三端首次同步的删除安全性 (P0 回归, 终审补) — base 必须**按对端**隔离。
 *
 * 缺陷: v0.3 三方合并的 base 一直取命名空间级的 `refs/<ns>/local`（"上次与本机任一对端同步的头"）。
 * 于是与本机**从未同步过**的第三端首发时, 对端树里没有的文件被判定为"对端删除" →
 * `removeWorkspaceFile` 把本机真实记忆文件搬成 `.deleted.<对端>`，且下一轮同步把删除传给其他端。
 *
 * 本用例复现真实时序: 本机已与 peer-b 同步过 (local ref = 共同快照), 随后首次与 peer-c 同步,
 * peer-c 的树里只有 soul.md（新设备没拿到中期记忆分片）→ 分片**不得**被删除。
 */
class TwinThirdPeerMergeTest {

    private lateinit var tempBase: File
    private lateinit var transport: FakeTransport
    private lateinit var engine: TwinSyncEngine
    private lateinit var server: AcpServer
    private val agent = "agent"
    private val ns = "ws-$agent"
    private val soulRel = "soul.md"
    private val midRel = "memory/memory_2026-10-22.md"

    private class FakeTransport : AcpTransport {
        val sent = mutableListOf<AcpMessage>()
        var responder: ((AcpMessage) -> AcpResult?)? = null

        override suspend fun send(message: AcpMessage): Boolean {
            sent.add(message)
            return true
        }

        override suspend fun sendForResult(message: AcpMessage, toPeerId: String, timeoutMs: Long): AcpResult? {
            sent.add(message)
            return responder?.invoke(message)
        }

        override suspend fun receive(): AcpMessage? = null
        override fun isConnected(): Boolean = true
        override fun close() {}
    }

    @Before
    fun setUp() {
        tempBase = File(System.getProperty("java.io.tmpdir"), "mengpaw-third-peer-${System.nanoTime()}")
        DataPaths.initialize(tempBase.absolutePath)
        File(DataPaths.AGENTS, agent).mkdirs()
        transport = FakeTransport()
        server = AcpServer(AgentProfile(agentId = agent, agentName = agent), sharedSecret = "test")
        engine = TwinSyncEngine(
            serverSupplier = { server },
            transportSupplier = { transport },
            agentName = agent, deviceId = "dev-local", deviceName = "本机"
        )
    }

    @After
    fun tearDown() {
        DataPaths.initialize("/sdcard/MengPaw")
        tempBase.deleteRecursively()
    }

    private fun workspace(): File = File(DataPaths.AGENTS, agent)

    private fun writeWorkspace(relPath: String, content: String): File =
        File(workspace(), relPath).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    private fun commitSnapshot(namespace: String, device: String, files: Map<String, String>): Snapshot {
        val ids = files.mapValues { SnapshotStore.writeBlob(it.value.toByteArray(Charsets.UTF_8)) }
        val id = SnapshotStore.commit(namespace, ids, "test", agent, device)
        return requireNotNull(SnapshotStore.snapshot(namespace, id))
    }

    private fun nsOf(message: AcpMessage): String? = try {
        Json.parseToJsonElement(message.payload).jsonObject["ns"]?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }

    /** peer-c: 只有 soul.md 与对端一致, 中期分片它没有。 */
    private fun respondAsThirdPeer(remote: Snapshot): (AcpMessage) -> AcpResult? = { message ->
        when (message.type) {
            AcpMessageType.SYNC_HELLO.name -> {
                if (nsOf(message) == ns) {
                    AcpResult(
                        true, "diff",
                        SyncV3Messages.ackPayload(false, listOf(remote.id), listOf(remote.id), emptyList())
                    )
                } else {
                    AcpResult(true, "same", SyncV3Messages.ackPayload(true, emptyList(), emptyList(), emptyList()))
                }
            }
            // 对象在同一测试库内 → 无需 TREE_WALK/OBJ_GET
            else -> null
        }
    }

    @Test
    fun `与第三端首次同步不得删除本机已有文件`() = runTest {
        writeWorkspace(soulRel, "本机灵魂\n")
        writeWorkspace(midRel, "## 22:40:00\n\n本机记忆\n")

        // ① 本机已与 peer-b 同步过: 共同快照 = base (实现仍读命名空间级 local ref)
        val syncedWithB = commitSnapshot(
            ns, "dev-local",
            mapOf(soulRel to "本机灵魂\n", midRel to "## 22:40:00\n\n本机记忆\n")
        )
        SnapshotStore.setLocalRef(ns, syncedWithB.id)

        // ② 第三端 peer-c 的树里只有 soul.md (新设备没拿到中期分片)
        val remoteC = commitSnapshot("ws-c", "dev-c", mapOf(soulRel to "本机灵魂\n"))

        val peerC = engine.addManualPeer("10.0.0.3", name = "peer-c")
        peerC.capabilityCard = """{"protocolVersion":"0.3"}"""
        transport.responder = respondAsThirdPeer(remoteC)

        val result = engine.syncWithPeer("peer-c")

        assertNull(result.error)
        assertTrue(
            "对端没有的文件不得被当成'对端删除' — 本机中期记忆分片必须还在",
            File(workspace(), midRel).isFile
        )
        assertTrue(
            "不得留下 .deleted 搬迁痕迹 (删除传播只应发生在对端确实删过之后)",
            workspace().walkTopDown().none { it.isFile && it.name.contains(".deleted.peer-c") }
        )
        // ③ base 只对参与过的对端成立
        assertNull("peer-b 从未与之同步过 → 不得凭空有基准", SnapshotStore.baseRef(ns, "peer-b"))
        assertTrue("首轮完成后应为 peer-c 记录独立基准", SnapshotStore.baseRef(ns, "peer-c") != null)

        // ④ 重复同步必须幂等: 不再删文件, 也不再每轮造新提交 (互刷)
        transport.sent.clear()
        val again = engine.syncWithPeer("peer-c")
        assertNull(again.error)
        assertTrue("第二轮仍不得删文件", File(workspace(), midRel).isFile)
        val afterSecond = SnapshotStore.history(ns, 50).size
        val third = engine.syncWithPeer("peer-c")
        assertNull(third.error)
        assertEquals(
            "同一基准下重复同步不得新增快照 (否则每小时互刷几十个合并提交)",
            afterSecond,
            SnapshotStore.history(ns, 50).size
        )
        assertTrue(
            "最多一个合并提交: ${SnapshotStore.history(ns, 50).map { it.message }}",
            SnapshotStore.history(ns, 50).count { it.message == "合并 peer-c" } <= 1
        )
    }

    @Test
    fun `已建立基准的对端仍按三方合并处理删除`() = runTest {
        writeWorkspace(soulRel, "本机灵魂\n")
        writeWorkspace(midRel, "## 22:40:00\n\n本机记忆\n")
        val base = commitSnapshot(
            ns, "dev-local",
            mapOf(soulRel to "本机灵魂\n", midRel to "## 22:40:00\n\n本机记忆\n")
        )
        // peer-b 确实同步过 (与该对端有基准) → 它删掉的分片必须传播删除
        SnapshotStore.setBaseRef(ns, "peer-b", base.id)
        SnapshotStore.setLocalRef(ns, base.id)
        val remoteB = commitSnapshot("ws-b", "dev-b", mapOf(soulRel to "本机灵魂\n"))

        val peerB = engine.addManualPeer("10.0.0.2", name = "peer-b")
        peerB.capabilityCard = """{"protocolVersion":"0.3"}"""
        transport.responder = { message ->
            when (message.type) {
                AcpMessageType.SYNC_HELLO.name -> {
                    if (nsOf(message) == ns) {
                        AcpResult(
                            true, "diff",
                            SyncV3Messages.ackPayload(false, listOf(remoteB.id), listOf(remoteB.id), emptyList())
                        )
                    } else {
                        AcpResult(true, "same", SyncV3Messages.ackPayload(true, emptyList(), emptyList(), emptyList()))
                    }
                }
                else -> null
            }
        }

        val result = engine.syncWithPeer("peer-b")

        assertNull(result.error)
        assertTrue(
            "与已同步过的对端: 对端删除必须传播 (删到 .deleted 备份)",
            workspace().walkTopDown().any { it.isFile && it.name.contains(".deleted.") }
        )
        assertFalse("原路径应已删除", File(workspace(), midRel).exists())
    }
}
