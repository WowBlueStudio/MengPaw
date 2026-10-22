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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * D 类库存的"单写者"必须落到**同步路径** (审计 P1-1, 设计 §7.2)。
 *
 * 合并器 D 类在"两侧都改过"时按 (lamport, deviceId) 取较新一侧 —— 对端只要自报更高
 * lamport 就能在快照里塞 `<本机deviceId>.md` 覆盖本机库存行。修复后: 落盘层按
 * **文件行归属**判定权限, 对端不得改写/删除本机行, 第三方行仍正常中继落盘。
 */
class TwinDeviceSingleWriterTest {

    private lateinit var tempBase: File
    private lateinit var transport: FakeTransport
    private lateinit var engine: TwinSyncEngine
    private lateinit var server: AcpServer
    private val agent = "agent"
    private val ns = SyncV3Messages.NS_DEVICES

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
        tempBase = File(System.getProperty("java.io.tmpdir"), "mengpaw-dev-sw-${System.nanoTime()}")
        DataPaths.initialize(tempBase.absolutePath)
        File(DataPaths.AGENTS, agent).mkdirs()
        File(DataPaths.TWIN_DEVICES).mkdirs()
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

    private fun inventory(name: String): File = File(DataPaths.TWIN_DEVICES, name)

    private fun addPeer() {
        engine.addManualPeer("10.0.0.2", name = "peer-b").capabilityCard = """{"protocolVersion":"0.3"}"""
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

    /** 只让 `dev` 命名空间与对端存在差异; `ws`/`ledger` 视为头相同 (0 传输)。 */
    private fun respondOnlyDev(remoteId: String) {
        transport.responder = { message ->
            when (message.type) {
                AcpMessageType.SYNC_HELLO.name ->
                    if (nsOf(message) == ns) {
                        AcpResult(
                            true, "diff",
                            SyncV3Messages.ackPayload(false, listOf(remoteId), listOf(remoteId), emptyList())
                        )
                    } else {
                        AcpResult(true, "same", SyncV3Messages.ackPayload(true, emptyList(), emptyList(), emptyList()))
                    }
                else -> null
            }
        }
    }

    private fun inboxNotes(): List<File> =
        File(DataPaths.AGENTS, "$agent/inbox").listFiles()
            ?.filter { it.name.startsWith("merge_notice_") }
            ?: emptyList()

    // ── ① 对端不得改写本机行 (lamport 更高也不行) ──────────────────

    @Test
    fun `dev 同步拒绝对端改写本机库存行`() = runTest {
        inventory("dev-local.md").writeText("本机库存 v2\n")
        // 与 peer-b 上次一致的行 (v1) = 共同基准; 当前本机已改成 v2
        val base = commitSnapshot(ns, "dev-local", mapOf("dev-local.md" to "本机库存 v1\n"))
        SnapshotStore.setBaseRef(ns, "peer-b", base.id)
        // 对端快照里塞"本机行"伪造内容, 且后提交 → lamport 更高
        val remote = commitSnapshot(ns, "dev-remote", mapOf("dev-local.md" to "对端伪造的库存\n"))
        assertTrue("对端 lamport 必须高于基准: ${remote.lamport} vs ${base.lamport}", remote.lamport > base.lamport)

        addPeer()
        respondOnlyDev(remote.id)

        val result = engine.syncWithPeer("peer-b")

        assertNull(result.error)
        assertEquals("本机行不得被对端覆盖", "本机库存 v2\n", inventory("dev-local.md").readText())
        val notes = inboxNotes()
        assertTrue("必须记录被拒的覆盖 (inbox): $notes", notes.isNotEmpty())
        assertTrue(
            "提示应点名被拒的行: ${notes.map { it.name }}",
            notes.any { it.readText().contains("dev-local.md") && it.readText().contains("单写者行") }
        )
    }

    // ── ② 第三方行照常落盘 (不误杀) ───────────────────────────────

    @Test
    fun `dev 同步正常落盘第三方库存行`() = runTest {
        inventory("dev-local.md").writeText("本机库存\n")
        val remote = commitSnapshot(ns, "dev-remote", mapOf("dev-third.md" to "第三方库存\n"))

        addPeer()
        respondOnlyDev(remote.id)

        val result = engine.syncWithPeer("peer-b")

        assertNull(result.error)
        assertEquals("第三方行必须正常落盘", "第三方库存\n", inventory("dev-third.md").readText())
        assertEquals("本机行不得被误动", "本机库存\n", inventory("dev-local.md").readText())
        assertEquals("第三方行计入接收数", 1, result.filesReceived)
    }

    // ── ③ 对端不得删除本机行 ──────────────────────────────────────

    @Test
    fun `dev 同步拒绝对端删除本机库存行`() = runTest {
        inventory("dev-local.md").writeText("本机库存 v1\n")
        // 基准里含本机行 → 对端本轮缺失才算"删除主张", 而不是"从未同步过"
        val base = commitSnapshot(ns, "dev-local", mapOf("dev-local.md" to "本机库存 v1\n"))
        SnapshotStore.setBaseRef(ns, "peer-b", base.id)
        val remote = commitSnapshot(ns, "dev-remote", mapOf("dev-remote.md" to "对端库存\n"))

        addPeer()
        respondOnlyDev(remote.id)

        val result = engine.syncWithPeer("peer-b")

        assertNull(result.error)
        assertEquals("本机行不得被对端删除", "本机库存 v1\n", inventory("dev-local.md").readText())
        val leftovers = File(DataPaths.TWIN_DEVICES).listFiles()
            ?.filter { it.name.startsWith("dev-local.md.deleted.") }
            ?: emptyList()
        assertTrue("不得产生 .deleted 备份 (删除未生效): $leftovers", leftovers.isEmpty())
        assertEquals("对端行正常落盘", "对端库存\n", inventory("dev-remote.md").readText())
    }
}
