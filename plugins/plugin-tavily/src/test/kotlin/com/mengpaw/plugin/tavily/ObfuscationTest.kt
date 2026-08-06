// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.tavily

import org.junit.Assert.*
import org.junit.Test

/**
 * API key 混淆编解码单测 (P2 折中: XOR + "obf:" 前缀, 防目录浏览级泄露)。
 * XOR 常量非零 → 混淆串逐字节不同于明文, 明文不可能以整串出现;
 * 另验证无 4 字符明文窗口泄漏 (对固定测试 key 的确定性断言)。
 * getter 的 env/配置文件分支依赖 DataPaths.CONFIG 落盘, 不在本文件范围。
 */
class ObfuscationTest {

    private val plugin = TavilyPlugin()

    @Test
    fun `round trip restores ascii key`() {
        val key = "tvly-abc123DEF"
        assertEquals(key, plugin.deobfuscate(plugin.obfuscate(key)))
    }

    @Test
    fun `round trip restores unicode key`() {
        val key = "密钥-中文-🚀测试"
        assertEquals(key, plugin.deobfuscate(plugin.obfuscate(key)))
    }

    @Test
    fun `empty string round trips`() {
        assertEquals("", plugin.deobfuscate(plugin.obfuscate("")))
    }

    @Test
    fun `obfuscated output differs from plaintext`() {
        val key = "tvly-abc123DEF"
        assertNotEquals(key, plugin.obfuscate(key))
    }

    @Test
    fun `obfuscated output never contains the full plaintext key`() {
        val key = "tvly-9f8e7d6c5b4a3z2y1x0w"
        val encoded = plugin.obfuscate(key)
        assertFalse("混淆串不得含完整明文", encoded.contains(key))
    }

    @Test
    fun `obfuscated output contains no plaintext windows`() {
        val key = "tvly-9f8e7d6c5b4a3z2y1x0w"
        val encoded = plugin.obfuscate(key)
        for (i in 0..key.length - 4) {
            val window = key.substring(i, i + 4)
            assertFalse("明文窗口 '$window' 泄漏到混淆串", encoded.contains(window))
        }
    }

    @Test
    fun `obf prefix storage format round trips`() {
        // 模拟 setup 落盘格式: "obf:" + obfuscate(key), getter 侧 removePrefix 后反混淆
        val key = "tvly-stored-key-123"
        val stored = "obf:" + plugin.obfuscate(key)
        assertTrue(stored.startsWith("obf:"))
        assertEquals(key, plugin.deobfuscate(stored.removePrefix("obf:")))
    }

    @Test
    fun `obfuscation is deterministic`() {
        assertEquals(plugin.obfuscate("same-key"), plugin.obfuscate("same-key"))
    }
}
