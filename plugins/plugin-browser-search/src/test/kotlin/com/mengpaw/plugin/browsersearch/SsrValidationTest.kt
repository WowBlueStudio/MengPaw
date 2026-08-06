// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.browsersearch

import com.mengpaw.kernel.cli.ExecutionContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

/**
 * browser-search 的 SSRF 校验单测 (P0 安全面, 与 plugin-net 同模式独立实现)。
 * 命令层拦截已由 BrowserSearchPluginTest 覆盖 (md + 回环/非 http scheme),
 * 本文件直连 validateUrl/isBlockedAddress 补全黑名单矩阵与放行路径。
 */
class SsrValidationTest {

    private val plugin = BrowserSearchPlugin()
    private val ctx = ExecutionContext(sessionId = "ssrf-test", agentName = "search-test")

    // ── isBlockedAddress 黑名单矩阵 ────────────────────────────────────

    @Test
    fun `isBlockedAddress blocks private loopback and metadata ranges`() {
        assertTrue(plugin.isBlockedAddress(InetAddress.getByName("10.0.0.1")))
        assertTrue(plugin.isBlockedAddress(InetAddress.getByName("192.168.1.1")))
        assertTrue(plugin.isBlockedAddress(InetAddress.getByName("172.16.0.1")))
        assertTrue(plugin.isBlockedAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(plugin.isBlockedAddress(InetAddress.getByName("0.0.0.0")))
        assertTrue(plugin.isBlockedAddress(InetAddress.getByName("::1")))
        assertTrue(plugin.isBlockedAddress(InetAddress.getByName("::ffff:127.0.0.1")))
        assertTrue(plugin.isBlockedAddress(InetAddress.getByName("169.254.169.254")))
        assertTrue(plugin.isBlockedAddress(InetAddress.getByName("100.100.100.200")))
    }

    @Test
    fun `isBlockedAddress allows public addresses`() {
        assertFalse(plugin.isBlockedAddress(InetAddress.getByName("8.8.8.8")))
        assertFalse(plugin.isBlockedAddress(InetAddress.getByName("1.1.1.1")))
    }

    // ── validateUrl 放行 ────────────────────────────────────────────────

    @Test
    fun `validateUrl accepts public http and https`() = runBlocking {
        assertNull(plugin.validateUrl("http://8.8.8.8/"))
        assertNull(plugin.validateUrl("https://1.1.1.1/path?q=1"))
    }

    // ── validateUrl 拦截 ────────────────────────────────────────────────

    @Test
    fun `validateUrl blocks private ip urls`() = runBlocking {
        assertTrue(plugin.validateUrl("http://10.0.0.1/")!!.contains("Blocked"))
        assertTrue(plugin.validateUrl("http://192.168.1.1/x")!!.contains("Blocked"))
        assertTrue(plugin.validateUrl("http://172.16.0.1/")!!.contains("Blocked"))
    }

    @Test
    fun `validateUrl blocks loopback localhost and metadata`() = runBlocking {
        assertTrue(plugin.validateUrl("http://127.0.0.1:8080/")!!.contains("Blocked"))
        assertTrue(plugin.validateUrl("http://localhost/")!!.contains("Blocked"))
        assertTrue(plugin.validateUrl("http://169.254.169.254/latest/meta-data/")!!.contains("Blocked"))
        assertTrue(plugin.validateUrl("http://100.100.100.200/")!!.contains("Blocked"))
        assertNotNull("IPv6 回环字面量应被拒绝", plugin.validateUrl("http://[::1]/"))
    }

    @Test
    fun `validateUrl rejects non-http schemes`() = runBlocking {
        assertTrue(plugin.validateUrl("file:///etc/passwd")!!.contains("Blocked scheme"))
        assertTrue(plugin.validateUrl("ftp://1.1.1.1/")!!.contains("Blocked scheme"))
    }

    @Test
    fun `validateUrl rejects relative and malformed urls`() = runBlocking {
        assertTrue(plugin.validateUrl("example.com/path")!!.contains("absolute"))
        assertTrue("空串是合法但相对的 URI — 按非绝对拒绝", plugin.validateUrl("")!!.contains("absolute"))
        // "http://" 依 JVM 实现可能解析失败 (Invalid URL) 或无主机 (no host) — 两种拒绝均可
        val bareHttp = plugin.validateUrl("http://")!!
        assertTrue(bareHttp.contains("Invalid") || bareHttp.contains("no host"))
    }

    // ── 引擎检测 (经 extract 命令间接验证) ──────────────────────────────

    @Test
    fun `extract command reports detected engine`() = runBlocking {
        val r = plugin.commands["extract"]!!(listOf("bing"), ctx)
        assertTrue(r.success)
        assertTrue(r.output.contains("bing"))
    }

    @Test
    fun `extract defaults to google for unknown or empty engine`() = runBlocking {
        val r = plugin.commands["extract"]!!(emptyList(), ctx)
        assertTrue(r.success)
        assertTrue(r.output.contains("google"))
        val r2 = plugin.commands["extract"]!!(listOf("unknown-engine"), ctx)
        assertTrue(r2.output.contains("google"))
    }
}
