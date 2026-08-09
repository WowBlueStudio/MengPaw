// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.framework

import com.mengpaw.kernel.ports.Ports
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * FrameworkPeerStore 纯逻辑单测: 绑定标识 (框架类型|设备标识, v0.34.3)、
 * peer JSON 序列化往返、框架类型表一致性。不触磁盘 (loadAll/save 依赖
 * DataPaths.CONFIG, 留待集成层)。
 */
class FrameworkPeerStoreTest {

    // ── 绑定标识 (v0.34.3: 框架类型|设备标识, 不再哈希) ─────────────────

    @Test
    fun `computeFingerprint is frameworkType deviceId concatenation`() {
        assertEquals("mengpaw|aa:bb:cc:dd:ee:ff",
            FrameworkPeerStore.computeFingerprint("mengpaw", "aa:bb:cc:dd:ee:ff"))
        assertEquals("claude-code|192.168.1.5:9881",
            FrameworkPeerStore.computeFingerprint("claude-code", "192.168.1.5:9881"))
        // 同机多框架: 类型不同标识不同 (用户设计: 笔记本多框架不冲突)
        assertNotEquals(
            FrameworkPeerStore.computeFingerprint("claude-code", "aa:bb:cc:dd:ee:ff"),
            FrameworkPeerStore.computeFingerprint("codex", "aa:bb:cc:dd:ee:ff")
        )
    }

    @Test
    fun `shortCode derives from deviceId tail`() {
        assertEquals("dde-eff",
            FrameworkPeerStore.shortCodeOf("mengpaw|aa:bb:cc:dd:ee:ff"))
        assertEquals("681-123",
            FrameworkPeerStore.shortCodeOf("claude-code|192.168.1.123"))
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
