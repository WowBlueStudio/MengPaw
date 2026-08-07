// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.security

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 攻击来源黑名单测试 (P0 v0.34.1)。
 *
 * 持久化范式: blocklist.json 原子写 + 懒加载; 匹配语义: 精确 + 域名后缀 / 路径前缀;
 * extractSource: net.* → host, 其余 → 首参。
 */
class SourceBlocklistTest {

    private lateinit var tempFile: File

    @Before
    fun setUp() {
        tempFile = File.createTempFile("blocklist-test", ".json")
        tempFile.deleteOnExit()
        SourceBlocklist.resetForTest(tempFile)
    }

    @After
    fun tearDown() {
        tempFile.delete()
    }

    // ── 基本语义 ──

    @Test
    fun block与isBlocked与list() {
        assertTrue(SourceBlocklist.block("evil.com"))
        assertTrue(SourceBlocklist.isBlocked("evil.com"))
        assertEquals(listOf("evil.com"), SourceBlocklist.list())
        assertTrue("拉黑幂等", SourceBlocklist.block("evil.com"))
        assertEquals(listOf("evil.com"), SourceBlocklist.list())
    }

    @Test
    fun unblock解除() {
        SourceBlocklist.block("evil.com")
        assertTrue(SourceBlocklist.unblock("evil.com"))
        assertFalse(SourceBlocklist.isBlocked("evil.com"))
        assertTrue("不存在条目 unblock 幂等成功", SourceBlocklist.unblock("nothing"))
    }

    @Test
    fun 空来源拒绝() {
        assertFalse(SourceBlocklist.block("  "))
        assertFalse(SourceBlocklist.isBlocked(""))
    }

    // ── 匹配语义: 域名后缀 / 路径前缀 ──

    @Test
    fun 域名后缀匹配() {
        SourceBlocklist.block("evil.com")
        assertTrue("子域应命中", SourceBlocklist.isBlocked("sub.evil.com"))
        assertTrue("深层子域应命中", SourceBlocklist.isBlocked("a.b.evil.com"))
        assertFalse("形似域名不误伤", SourceBlocklist.isBlocked("evil.com.evil.org"))
        assertFalse("无关域名不命中", SourceBlocklist.isBlocked("evil.org"))
    }

    @Test
    fun 路径前缀匹配() {
        SourceBlocklist.block("/a/b")
        assertTrue("子路径应命中", SourceBlocklist.isBlocked("/a/b/c.txt"))
        assertTrue("精确路径命中", SourceBlocklist.isBlocked("/a/b"))
        assertFalse("同级不误伤", SourceBlocklist.isBlocked("/a/c"))
    }

    // ── 持久化 ──

    @Test
    fun 持久化后新实例恢复() {
        SourceBlocklist.block("evil.com")
        SourceBlocklist.block("/bad.md")
        // 模拟进程重启: 重指同一文件 + 重置内存态
        SourceBlocklist.resetForTest(tempFile)
        assertTrue("重载后仍命中", SourceBlocklist.isBlocked("evil.com"))
        assertTrue("重载后路径仍命中", SourceBlocklist.isBlocked("/bad.md"))
        assertEquals(2, SourceBlocklist.list().size)
    }

    @Test
    fun 损坏文件静默保持内存态() {
        tempFile.writeText("not-json{{{{")
        SourceBlocklist.resetForTest(tempFile)
        assertFalse("损坏文件不崩溃不命中", SourceBlocklist.isBlocked("evil.com"))
        // 内存态仍可继续工作
        assertTrue(SourceBlocklist.block("evil.com"))
        assertTrue(SourceBlocklist.isBlocked("evil.com"))
    }

    @Test
    fun 持久化内容为JSON数组() {
        SourceBlocklist.block("evil.com")
        SourceBlocklist.block("/a b.md") // 含特殊字符路径
        val text = tempFile.readText()
        assertTrue(text.startsWith("["))
        assertTrue(text.contains("evil.com"))
        assertTrue(text.contains("/a b.md"))
    }

    // ── extractSource ──

    @Test
    fun net命令提取host() {
        assertEquals("evil.com", SourceBlocklist.extractSource("net.curl https://evil.com/a?x=1"))
        assertEquals("example.org", SourceBlocklist.extractSource("net.get http://example.org"))
        assertEquals("example.com", SourceBlocklist.extractSource("net.curl example.com/page"))
    }

    @Test
    fun 非法URL返回null() {
        assertNull(SourceBlocklist.extractSource("net.curl 不是网址"))
    }

    @Test
    fun 非net命令取首参() {
        assertEquals("/a/b.md", SourceBlocklist.extractSource("agent.read /a/b.md"))
        assertEquals("x", SourceBlocklist.extractSource("agent.rm x --force"))
        assertEquals("测试文件.md", SourceBlocklist.extractSource("agent.write 测试文件.md 内容"))
    }

    @Test
    fun 零参或无参返回null() {
        assertNull(SourceBlocklist.extractSource("clipboard.paste"))
        assertNull(SourceBlocklist.extractSource(""))
        assertNull(SourceBlocklist.extractSource("  "))
    }
}
