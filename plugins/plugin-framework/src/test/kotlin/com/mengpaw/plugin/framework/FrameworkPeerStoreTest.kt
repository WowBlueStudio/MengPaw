// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.framework

import com.mengpaw.kernel.ports.Ports
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * FrameworkPeerStore 纯逻辑单测: 指纹计算 (Locale.ROOT hex, P2 修复)、
 * peer JSON 序列化往返、框架类型表一致性。不触磁盘 (loadAll/save 依赖
 * DataPaths.CONFIG, 留待集成层)。
 */
class FrameworkPeerStoreTest {

    // ── 指纹计算 (P2: Locale.ROOT %02x) ────────────────────────────────

    @Test
    fun `computeFingerprint is deterministic lowercase hex`() {
        val a = FrameworkPeerStore.computeFingerprint("MengPaw-1", "device-abc")
        val b = FrameworkPeerStore.computeFingerprint("MengPaw-1", "device-abc")
        assertEquals("同输入必须同指纹", a, b)
        assertEquals("8 字节 SHA-256 前缀 = 16 hex 字符", 16, a.length)
        assertTrue("必须全小写 hex", a.matches(Regex("[0-9a-f]{16}")))
    }

    @Test
    fun `computeFingerprint differs for different inputs`() {
        assertNotEquals(
            FrameworkPeerStore.computeFingerprint("A", "x"),
            FrameworkPeerStore.computeFingerprint("A", "y")
        )
        assertNotEquals(
            FrameworkPeerStore.computeFingerprint("A", "x"),
            FrameworkPeerStore.computeFingerprint("B", "x")
        )
    }

    // ── peer JSON 往返 ──────────────────────────────────────────────────

    @Test
    fun `peer json round trip preserves all fields`() {
        val peer = FrameworkPeerStore.FrameworkPeer(
            fingerprint = "fp-1",
            name = "客厅机",
            version = "0.30.0",
            frameworkName = "MengPaw",
            address = "192.168.1.5",
            port = 12345,
            capabilities = listOf("mcp", "acp"),
            agents = listOf("alice", "bob"),
            lastSeen = 123456789L,
            trusted = true,
            remark = "备注文本",
            frameworkType = "claude-code"
        )
        val restored = FrameworkPeerStore.FrameworkPeer.fromJson(peer.toJson())
        assertEquals("往返后字段必须一致", peer, restored)
    }

    @Test
    fun `peer fromJson fills defaults for missing fields`() {
        val p = FrameworkPeerStore.FrameworkPeer.fromJson(JSONObject())
        assertEquals("", p.fingerprint)
        assertEquals("MengPaw", p.frameworkName)
        assertEquals(Ports.ACP, p.port)
        assertTrue(p.capabilities.isEmpty())
        assertTrue(p.agents.isEmpty())
        assertFalse(p.trusted)
        assertEquals("mengpaw", p.frameworkType)
    }

    @Test
    fun `peer toJson emits obfuscation-free plain fields`() {
        val json = FrameworkPeerStore.FrameworkPeer(
            fingerprint = "fp", name = "n", version = "v",
            address = "addr", port = 1, lastSeen = 2L
        ).toJson()
        assertEquals("fp", json.getString("fingerprint"))
        assertEquals("n", json.getString("name"))
        assertEquals(1, json.getInt("port"))
        assertEquals(2L, json.getLong("lastSeen"))
    }

    // ── 框架类型表一致性 ────────────────────────────────────────────────

    @Test
    fun `every framework type has a protocol label`() {
        FrameworkPeerStore.FRAMEWORK_TYPES.keys.forEach { type ->
            assertTrue("类型 '$type' 缺协议标签", type in FrameworkPeerStore.PROTOCOL_LABELS)
        }
    }

    @Test
    fun `mengpaw maps to ACP port`() {
        assertEquals(Ports.ACP, FrameworkPeerStore.FRAMEWORK_TYPES["mengpaw"])
    }
}
