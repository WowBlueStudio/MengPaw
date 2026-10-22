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
import com.mengpaw.kernel.memory.MemoryLedger
import com.mengpaw.kernel.memory.SnapshotStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
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
 * 账本同步并集 (P3, docs/memory-git-sync-design.md §6.1) —
 * `ledger-<agent>` 命名空间走 v0.3 四消息, `entries.jsonl` 按 entryId 并集;
 * 同一天双设备各一条 → 合并后两条都在。
 */
class TwinSyncLedgerTest {

    private lateinit var tempBase: File
    private lateinit var transport: FakeTransport
    private lateinit var engine: TwinSyncEngine
    private lateinit var server: AcpServer
    private val agent = "agent"
    private val ledgerNs = SyncV3Messages.ledgerNamespace("agent")
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
        tempBase = File(System.getProperty("java.io.tmpdir"), "mengpaw-ledger-sync-${System.nanoTime()}")
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

    private fun nsOf(message: AcpMessage): String? = try {
        Json.parseToJsonElement(message.payload).jsonObject["ns"]?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }

    @Test
    fun `同一天双设备账本并集后两条都在`() = runTest {
        // 本机条目
        val localEntry = MemoryLedger.newEntry(
            agentName = agent,
            track = MemoryLedger.TRACK_MID,
            file = midRel,
            block = "## 22:40:00\n\n平板写的",
            device = "dev-local",
            session = "sess-a",
            wall = "2026-10-22T22:40:00+08:00"
        )
        assertNull(MemoryLedger.append(localEntry).error)
        val localText = MemoryLedger.file(agent).readText()

        // 对端条目 (账本 = 本机行 + 对端行, 模拟对端也见过同一条)
        val remoteEntry = MemoryLedger.newEntry(
            agentName = agent,
            track = MemoryLedger.TRACK_MID,
            file = midRel,
            block = "## 22:41:00\n\n台式机写的",
            device = "dev-remote",
            session = "sess-b",
            wall = "2026-10-22T22:41:00+08:00"
        )
        val remoteText = localText.trimEnd() + "\n" +
            Json.encodeToString(MemoryLedger.Entry.serializer(), remoteEntry) + "\n"
        val remoteHead = SnapshotStore.commit(
            ledgerNs,
            mapOf("entries.jsonl" to SnapshotStore.writeBlob(remoteText.toByteArray(Charsets.UTF_8))),
            "远端账本", agent, "dev-remote"
        )

        engine.addManualPeer("10.0.0.2", name = "peer-b").capabilityCard = """{"protocolVersion":"0.3"}"""
        transport.responder = { message ->
            when (message.type) {
                AcpMessageType.SYNC_HELLO.name -> {
                    if (nsOf(message) == ledgerNs) {
                        AcpResult(true, "diff", SyncV3Messages.ackPayload(
                            false, listOf(remoteHead), emptyList(), emptyList()
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
        assertTrue("账本命名空间也必须发 HELLO", transport.sent.any {
            it.type == AcpMessageType.SYNC_HELLO.name && nsOf(it) == ledgerNs
        })
        val merged = MemoryLedger.entries(agent)
        assertEquals("并集后两条都在", 2, merged.size)
        assertTrue(merged.any { it.device == "dev-local" })
        assertTrue(merged.any { it.device == "dev-remote" })
        // 顺序稳定: 按 (wall, entryId) 升序
        assertEquals(listOf(localEntry.entryId, remoteEntry.entryId), merged.map { it.entryId })
        assertEquals("落盘顺序与内存一致", merged.map { it.entryId }, MemoryLedger.read(agent).entries.map { it.entryId })

        val index = File(DataPaths.AGENTS, "$agent/memory/index.md")
        assertNotNull("账本合并后应重建记忆索引", index.takeIf { it.exists() })
        assertTrue("并集不得丢条目", localText.contains(localEntry.entryId))
        assertTrue(
            "对端条目应已进入本机账本",
            merged.any { it.entryId == remoteEntry.entryId && it.device == "dev-remote" }
        )
    }

    @Test
    fun `账本命名空间按 agent 隔离`() {
        assertTrue(SyncV3Messages.isAllowedNamespace(ledgerNs, agent))
        assertTrue(SyncV3Messages.isAllowedNamespace(SyncV3Messages.workspaceNamespace(agent), agent))
        assertTrue(SyncV3Messages.isAllowedNamespace(SyncV3Messages.NS_DEVICES, agent))
        assertFalse(SyncV3Messages.isAllowedNamespace("ledger-other", agent))
        assertEquals("ledger-$agent", ledgerNs)
    }
}
