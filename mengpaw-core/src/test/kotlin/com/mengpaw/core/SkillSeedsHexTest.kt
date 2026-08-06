// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * SkillSeeds 的 SHA-256 hex 输出测试 (P2 修复: String.format 显式 Locale.ROOT)。
 *
 * 说明: ensure()/readManifest 等依赖 Context/assets/DataPaths 不可在 JVM 单测;
 * 私有函数 sha256(file) 是纯文件哈希逻辑 — 反射调用验证:
 *   - %02x 输出恒为小写十六进制 (阿拉伯语默认 Locale 下畸形输出的回归保险)
 *   - 长度恒为 64 (SHA-256 十六进制表示)
 *   - 已知向量哈希精确匹配 (hello / 空文件)
 */
class SkillSeedsHexTest {

    private val sha256Method = SkillSeeds::class.java
        .getDeclaredMethod("sha256", File::class.java)
        .apply { isAccessible = true }

    /** 写入临时文件并反射调用私有 sha256。 */
    private fun sha256Of(content: ByteArray): String? {
        val file = Files.createTempFile("seed-", ".md").toFile()
        try {
            file.writeBytes(content)
            return sha256Method.invoke(SkillSeeds, file) as String?
        } finally {
            file.delete()
        }
    }

    @Test
    fun 已知向量_hello() {
        // 公开已知 SHA-256("hello") — 全小写
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            sha256Of("hello".toByteArray())
        )
    }

    @Test
    fun 已知向量_空文件() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Of(ByteArray(0))
        )
    }

    @Test
    fun 输出恒为小写十六进制() {
        val bytes = ByteArray(256) { it.toByte() } // 0x00..0xFF 全字节
        val hex = sha256Of(bytes)!!
        assertEquals("SHA-256 hex 长度恒为 64", 64, hex.length)
        assertTrue(
            "输出必须全为小写十六进制 (Locale.ROOT 修复回归): $hex",
            hex.all { it in '0'..'9' || it in 'a'..'f' }
        )
        // 不得出现大写 (阿拉伯语 Locale 畸形输出的特征)
        assertTrue(hex.none { it in 'A'..'F' })
    }

    @Test
    fun 随机内容长度恒定() {
        repeat(5) { i ->
            val random = ByteArray(37 + i * 11) { (it * 31 % 251).toByte() }
            val hex = sha256Of(random)!!
            assertEquals(64, hex.length)
            assertTrue(hex.all { it in '0'..'9' || it in 'a'..'f' })
        }
    }

    @Test
    fun 相同内容哈希稳定() {
        val content = "同一内容".toByteArray()
        assertEquals(sha256Of(content), sha256Of(content))
    }
}
