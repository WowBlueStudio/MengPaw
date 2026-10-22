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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 协议 v0.3 纯 pull 驱动测试 (P2) — heads 相同 0 传输 / 不同只拉缺失对象 /
 * dev 命名空间同套消息 / 双栈判定 / 删除传播 (含 `.deleted` 备份)。
 */
class TwinSyncV3Test {

    private lateinit var tempBase: File
    private lateinit var transport: FakeTransport
    private lateinit var engine: TwinSyncEngine
    private lateinit var server: AcpServer
    private val agent = "agent"
    private val ns = "ws-$agent"

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
        tempBase = File(System.getProperty("java.io.tmpdir"), "mengpaw-v3-${System.nanoTime()}")
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

    // ── 工具 ───────────────────────────────────────────────────────

    private fun workspace(): File = File(DataPaths.AGENTS, agent)

    private fun writeWorkspace(relPath: String, content: String): File =
        File(workspace(), relPath).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    private fun addPeer(card: String = """{"protocolVersion":"0.3"}"""): TwinPeerInfo {
        val peer = engine.addManualPeer("10.0.0.2", name = "peer-b")
        peer.capabilityCard = card
        return peer
    }

    private fun commitSnapshot(namespace: String, device: String, files: Map<String, String>): Snapshot {
        val ids = files.mapValues { SnapshotStore.writeBlob(it.value.toByteArray(Charsets.UTF_8)) }
        val id = SnapshotStore.commit(namespace, ids, "test", agent, device)
        return requireNotNull(SnapshotStore.snapshot(namespace, id))
    }

    private fun objectFile(id: String): File =
        File(File(DataPaths.TWIN_STORE, "objects"), id.take(2)).resolve(id.drop(2))

    private fun typesSent(): List<String> = transport.sent.map { it.type }

    private fun nsOf(message: AcpMessage): String? = try {
        Json.parseToJsonElement(message.payload).jsonObject["ns"]?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }

    // ── heads 相同 → 0 传输 ────────────────────────────────────────

    @Test
    fun `heads 相同只走一轮 HELLO 不请求任何对象`() = runTest {
        writeWorkspace("soul.md", "本机内容\n")
        val local = SnapshotStore.commit(ns, mapOf("soul.md" to SnapshotStore.writeBlob("本机内容\n".toByteArray())), "本地", agent, "dev-local")
        SnapshotStore.setLocalRef(ns, local)
        addPeer()
        // ACK 的 heads 用真实本机 heads (ws 与 dev 各自读取)
        transport.responder = { message ->
            when (message.type) {
                AcpMessageType.SYNC_HELLO.name ->
                    AcpResult(true, "same", SyncV3Messages.ackPayload(true, SnapshotStore.heads(nsOf(message).orEmpty()), emptyList(), emptyList()))
                else -> null
            }
        }

        val result = engine.syncWithPeer("peer-b")

        assertEquals("头相同 → 0 文件", 0, result.filesReceived)
        assertNull(result.error)
        assertTrue("只应有 HELLO: ${typesSent()}", typesSent().all { it == AcpMessageType.SYNC_HELLO.name })
        assertEquals("ws + dev 各一轮 HELLO", 2, transport.sent.count { it.type == AcpMessageType.SYNC_HELLO.name })
        assertTrue("不得请求 TREE_WALK", AcpMessageType.TREE_WALK.name !in typesSent())
        assertTrue("不得请求 OBJ_GET", AcpMessageType.OBJ_GET.name !in typesSent())
    }

    // ── heads 不同 → 只拉缺失对象 ──────────────────────────────────

    @Test
    fun `heads 不同时只请求缺失 tree 与缺失 blob 并完成合并`() = runTest {
        // 本机: soul.md 与 base 一致 (h1 是 local ref)
        writeWorkspace("soul.md", "旧内容\n")
        writeWorkspace("same.md", "未变内容\n")
        val base = SnapshotStore.commit(
            ns,
            mapOf(
                "soul.md" to SnapshotStore.writeBlob("旧内容\n".toByteArray()),
                "same.md" to SnapshotStore.writeBlob("未变内容\n".toByteArray())
            ),
            "base", agent, "dev-local"
        )
        SnapshotStore.setLocalRef(ns, base)

        // 对端: soul.md 改动 + 新增 new.md (对象在共享库中, 之后手工删除模拟"本机没有")
        val remote = commitSnapshot(
            "ws-remote", "dev-remote",
            mapOf("soul.md" to "对端内容\n", "same.md" to "未变内容\n", "new.md" to "新增文件\n")
        )
        val remoteTree = requireNotNull(SnapshotStore.readTree(remote.tree))
        val remoteRaw = requireNotNull(SnapshotStore.exportObject(remote.id))
        val missingSoulBlob = requireNotNull(remoteTree["soul.md"])
        val missingNewBlob = requireNotNull(remoteTree["new.md"])
        val presentBlob = requireNotNull(remoteTree["same.md"])
        // 模拟本机没有对端快照/tree/两个 blob
        assertTrue(objectFile(remote.id).delete())
        assertTrue(objectFile(remote.tree).delete())
        assertTrue(objectFile(missingSoulBlob).delete())
        assertTrue(objectFile(missingNewBlob).delete())

        addPeer()
        val requestedBlobs = mutableListOf<List<String>>()
        transport.responder = { message ->
            when (message.type) {
                AcpMessageType.SYNC_HELLO.name -> {
                    if (nsOf(message) == ns) {
                        AcpResult(true, "diff", SyncV3Messages.ackPayload(
                            same = false,
                            heads = listOf(remote.id),
                            missingTrees = listOf(remote.id),
                            missingBlobs = listOf(missingSoulBlob, presentBlob, missingNewBlob)
                        ))
                    } else {
                        AcpResult(true, "same", SyncV3Messages.ackPayload(true, emptyList(), emptyList(), emptyList()))
                    }
                }
                AcpMessageType.TREE_WALK.name -> AcpResult(true, "tree_walk",
                    SyncV3Messages.treeWalkResponsePayload(
                        SyncV3Messages.SnapshotMeta(
                            id = remote.id, tree = remote.tree, parents = emptyList(),
                            lamport = remote.lamport, wall = remote.wall,
                            device = remote.device, author = remote.author, message = remote.message
                        ),
                        remote.tree,
                        remoteTree,
                        remoteRaw
                    ))
                AcpMessageType.OBJ_GET.name -> {
                    val blobsElement = Json.parseToJsonElement(message.payload).jsonObject["blobs"]
                    val ids = (blobsElement as? kotlinx.serialization.json.JsonArray)
                        ?.map { it.jsonPrimitive.content }
                        ?: emptyList()
                    requestedBlobs.add(ids)
                    val bytes = mapOf(
                        missingSoulBlob to "对端内容\n".toByteArray(Charsets.UTF_8),
                        missingNewBlob to "新增文件\n".toByteArray(Charsets.UTF_8)
                    ).filterKeys { it in ids }
                    AcpResult(true, "obj_get", SyncV3Messages.objGetResponsePayload(bytes))
                }
                else -> null
            }
        }

        val result = engine.syncWithPeer("peer-b")

        assertNull(result.error)
        assertEquals("只请求缺失 blob (已存在的不请求)", 1, requestedBlobs.size)
        val requested = requestedBlobs.first().toSet()
        assertEquals(setOf(missingSoulBlob, missingNewBlob), requested)
        assertFalse("已在本地库的 blob 不得请求", presentBlob in requested)

        // 合并结果落盘
        assertEquals("对端内容\n", File(workspace(), "soul.md").readText())
        assertEquals("新增文件\n", File(workspace(), "new.md").readText())
        // 内容与对端树一致 → 收敛 heads: **并集**, 本地 head 不得被丢弃
        // (单纯采纳对端 head 会让本地提交变成不可达对象, GC 一跑历史就没了)
        val headsAfter = SnapshotStore.heads(ns)
        assertTrue("应保留对端 head", headsAfter.contains(remote.id))
        assertTrue("本地 head 不得被丢弃", headsAfter.contains(base))
        assertEquals("local ref 必须指向某个现存 head", 1, headsAfter.count { it == SnapshotStore.localRef(ns) })
    }

    // ── dev 命名空间 ───────────────────────────────────────────────

    @Test
    fun `dev 命名空间走同一套消息并同步设备库存`() = runTest {
        File(DataPaths.TWIN_DEVICES).mkdirs()
        File(DataPaths.TWIN_DEVICES, "dev-local.md").writeText("本机库存\n")
        val remote = commitSnapshot("dev-remote", "dev-remote", mapOf("dev-remote.md" to "对端库存\n"))

        addPeer()
        transport.responder = { message ->
            when (message.type) {
                AcpMessageType.SYNC_HELLO.name -> {
                    if (nsOf(message) == SyncV3Messages.NS_DEVICES) {
                        AcpResult(true, "diff", SyncV3Messages.ackPayload(
                            false, listOf(remote.id), listOf(remote.id), emptyList()
                        ))
                    } else {
                        AcpResult(true, "same", SyncV3Messages.ackPayload(true, emptyList(), emptyList(), emptyList()))
                    }
                }
                else -> null   // 对象都在共享库 → 无需 TREE_WALK/OBJ_GET
            }
        }

        val result = engine.syncWithPeer("peer-b")

        assertNull(result.error)
        assertTrue("dev 也要发 HELLO", transport.sent.any {
            it.type == AcpMessageType.SYNC_HELLO.name && nsOf(it) == SyncV3Messages.NS_DEVICES
        })
        assertEquals("对端库存\n", File(DataPaths.TWIN_DEVICES, "dev-remote.md").readText())
        assertEquals("本机库存\n", File(DataPaths.TWIN_DEVICES, "dev-local.md").readText())
        assertNotNull("dev 命名空间应产生快照", SnapshotStore.heads(SyncV3Messages.NS_DEVICES).firstOrNull())
    }

    // ── 删除传播 ───────────────────────────────────────────────────

    @Test
    fun `A 删除文件后 B 同步删除并留 deleted 备份`() = runTest {
        writeWorkspace("soul.md", "保留内容\n")
        writeWorkspace("gone.md", "将被删除\n")
        val base = SnapshotStore.commit(
            ns,
            mapOf(
                "soul.md" to SnapshotStore.writeBlob("保留内容\n".toByteArray()),
                "gone.md" to SnapshotStore.writeBlob("将被删除\n".toByteArray())
            ),
            "base", agent, "dev-local"
        )
        SnapshotStore.setLocalRef(ns, base)
        // 对端只保留 soul.md
        val remote = commitSnapshot("ws-remote-2", "dev-remote", mapOf("soul.md" to "保留内容\n"))

        addPeer()
        transport.responder = { message ->
            when (message.type) {
                AcpMessageType.SYNC_HELLO.name -> {
                    if (nsOf(message) == ns) {
                        AcpResult(true, "diff", SyncV3Messages.ackPayload(
                            false, listOf(remote.id), listOf(remote.id), emptyList()
                        ))
                    } else {
                        AcpResult(true, "same", SyncV3Messages.ackPayload(true, emptyList(), emptyList(), emptyList()))
                    }
                }
                else -> null
            }
        }

        val result = engine.syncWithPeer("peer-b")

        assertNull(result.error)
        assertFalse("对端已删 → 本机必须删除", File(workspace(), "gone.md").exists())
        val backup = File(workspace(), "gone.md.deleted.peer-b")
        assertTrue("必须留 .deleted 备份", backup.exists())
        assertEquals("将被删除\n", backup.readText())
        assertTrue("soul.md 保留", File(workspace(), "soul.md").exists())
        val inbox = File(workspace(), "inbox")
        val notice = inbox.listFiles()?.firstOrNull { it.name.startsWith("merge_notice_") }
        assertNotNull("删除传播必须写 inbox 提示", notice)
        assertTrue("提示里应出现被删文件", notice?.readText()?.contains("gone.md") == true)
    }

    // ── 双栈判定 ───────────────────────────────────────────────────

    @Test
    fun `双栈判定 0_2 走旧路径 0_3 走新路径`() = runTest {
        assertFalse(TwinSyncV3.supportsV3(null))
        assertFalse(TwinSyncV3.supportsV3(""))
        assertFalse(TwinSyncV3.supportsV3("not-json"))
        assertFalse(TwinSyncV3.supportsV3("""{"protocolVersion":"0.2"}"""))
        assertFalse("0.2.9 低于 0.3 (逐段数值比较)", TwinSyncV3.supportsV3("""{"protocolVersion":"0.2.9"}"""))
        assertTrue(TwinSyncV3.supportsV3("""{"protocolVersion":"0.3"}"""))
        assertTrue(TwinSyncV3.supportsV3("""{"protocolVersion":"0.3.0"}"""))
        assertTrue(TwinSyncV3.supportsV3("""{"protocolVersion":"0.4"}"""))
        assertTrue(TwinSyncV3.supportsV3("""{"protocolVersion":"1.0"}"""))

        // 0.2 对端: 必须走 WS_MANIFEST (旧路径), 不得出现 SYNC_HELLO
        addPeer("""{"protocolVersion":"0.2"}""")
        transport.responder = { AcpResult(false, "legacy-path") }
        val v2 = engine.syncWithPeer("peer-b")
        assertNotNull(v2.error)
        assertEquals("0.2 必须先走 WS_MANIFEST", AcpMessageType.WS_MANIFEST.name, typesSent().first())
        assertFalse("0.2 不得发 SYNC_HELLO", AcpMessageType.SYNC_HELLO.name in typesSent())

        // 0.3 对端: 走 SYNC_HELLO
        transport.sent.clear()
        engine.addManualPeer("10.0.0.2", name = "peer-b").capabilityCard = """{"protocolVersion":"0.3"}"""
        transport.responder = { message ->
            if (message.type == AcpMessageType.SYNC_HELLO.name) {
                AcpResult(true, "same", SyncV3Messages.ackPayload(true, emptyList(), emptyList(), emptyList()))
            } else {
                AcpResult(false, "unexpected")
            }
        }
        val v3 = engine.syncWithPeer("peer-b")
        assertNull(v3.error)
        assertEquals("0.3 首条消息应为 SYNC_HELLO", AcpMessageType.SYNC_HELLO.name, typesSent().first())
    }
}
