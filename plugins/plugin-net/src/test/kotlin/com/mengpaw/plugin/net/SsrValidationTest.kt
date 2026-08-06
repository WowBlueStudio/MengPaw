// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.net

import com.mengpaw.kernel.cli.ExecutionContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

/**
 * net.curl/net.post 的 SSRF 校验单测 (P0 安全面):
 * - 私有/内网/回环/云元数据地址黑名单 (isBlockedAddress)
 * - scheme 白名单 + 相对/畸形 URL 拒绝 (validateUrl)
 * - 命令层接线: 校验在真实网络请求之前执行 (curl/post 无需网络即可命中拦截)
 *
 * 注: 只测拦截路径; 放行路径用公网 IP 字面量 (getByName 对字面量不做 DNS)。
 */
class SsrValidationTest {

    private val plugin = NetPlugin()
    private val ctx = ExecutionContext(sessionId = "ssrf-test", agentName = "net-test")

    // ── isBlockedAddress 黑名单矩阵 ────────────────────────────────────

    @Test
    fun `isBlockedAddress blocks RFC1918 private ranges`() {
        assertTrue("10.x 应拦截", plugin.isBlockedAddress(InetAddress.getByName("10.0.0.1")))
        assertTrue("172.16-31.x 应拦截", plugin.isBlockedAddress(InetAddress.getByName("172.16.0.1")))
        assertTrue("192.168.x 应拦截", plugin.isBlockedAddress(InetAddress.getByName("192.168.1.1")))
    }

    @Test
    fun `isBlockedAddress blocks loopback link-local and any-local`() {
        assertTrue("127.0.0.1 应拦截", plugin.isBlockedAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue("0.0.0.0 应拦截", plugin.isBlockedAddress(InetAddress.getByName("0.0.0.0")))
        assertTrue("::1 应拦截", plugin.isBlockedAddress(InetAddress.getByName("::1")))
        assertTrue("IPv4 映射回环应拦截", plugin.isBlockedAddress(InetAddress.getByName("::ffff:127.0.0.1")))
        assertTrue("169.254.x 链路本地应拦截", plugin.isBlockedAddress(InetAddress.getByName("169.254.5.5")))
    }

    @Test
    fun `isBlockedAddress blocks cloud metadata endpoints`() {
        assertTrue("AWS/GCP 元数据应拦截", plugin.isBlockedAddress(InetAddress.getByName("169.254.169.254")))
        assertTrue("阿里云元数据应拦截", plugin.isBlockedAddress(InetAddress.getByName("100.100.100.200")))
    }

    @Test
    fun `isBlockedAddress allows public addresses`() {
        assertFalse("8.8.8.8 应放行", plugin.isBlockedAddress(InetAddress.getByName("8.8.8.8")))
        assertFalse("1.1.1.1 应放行", plugin.isBlockedAddress(InetAddress.getByName("1.1.1.1")))
    }

    // ── validateUrl 放行 ────────────────────────────────────────────────

    @Test
    fun `validateUrl accepts public http and https`() = runBlocking {
        assertNull("公网 http 应放行", plugin.validateUrl("http://8.8.8.8/"))
        assertNull("公网 https 应放行", plugin.validateUrl("https://1.1.1.1/path?q=1"))
    }

    // ── validateUrl 拦截 ────────────────────────────────────────────────

    @Test
    fun `validateUrl blocks private ip urls`() = runBlocking {
        assertTrue(plugin.validateUrl("http://10.0.0.1/")!!.contains("Blocked"))
        assertTrue(plugin.validateUrl("http://192.168.1.1/x")!!.contains("Blocked"))
        assertTrue(plugin.validateUrl("http://172.16.0.1/")!!.contains("Blocked"))
    }

    @Test
    fun `validateUrl blocks loopback and localhost`() = runBlocking {
        assertTrue(plugin.validateUrl("http://127.0.0.1:8080/")!!.contains("Blocked"))
        assertTrue(plugin.validateUrl("http://localhost/")!!.contains("Blocked"))
        assertNotNull("IPv6 回环字面量应被拒绝", plugin.validateUrl("http://[::1]/"))
        assertNotNull("IPv4 映射回环字面量应被拒绝", plugin.validateUrl("http://[::ffff:127.0.0.1]/"))
    }

    @Test
    fun `validateUrl blocks cloud metadata and link-local urls`() = runBlocking {
        assertTrue(plugin.validateUrl("http://169.254.169.254/latest/meta-data/")!!.contains("Blocked"))
        assertTrue(plugin.validateUrl("http://100.100.100.200/")!!.contains("Blocked"))
        assertTrue(plugin.validateUrl("http://169.254.10.10/")!!.contains("Blocked"))
    }

    @Test
    fun `validateUrl rejects non-http schemes`() = runBlocking {
        assertTrue(plugin.validateUrl("file:///etc/passwd")!!.contains("Blocked scheme"))
        assertTrue(plugin.validateUrl("ftp://1.1.1.1/")!!.contains("Blocked scheme"))
        assertTrue(plugin.validateUrl("javascript:alert(1)")!!.contains("Blocked scheme"))
    }

    @Test
    fun `validateUrl rejects relative and malformed urls`() = runBlocking {
        assertTrue(plugin.validateUrl("example.com/path")!!.contains("absolute"))
        assertTrue("空串是合法但相对的 URI — 按非绝对拒绝", plugin.validateUrl("")!!.contains("absolute"))
        // "http://" 依 JVM 实现可能解析失败 (Invalid URL) 或无主机 (no host) — 两种拒绝均可
        val bareHttp = plugin.validateUrl("http://")!!
        assertTrue(bareHttp.contains("Invalid") || bareHttp.contains("no host"))
    }

    // ── 命令层接线 (校验先于网络请求) ───────────────────────────────────

    @Test
    fun `curl blocks ssrf before any network request`() = runBlocking {
        val r = plugin.commands["curl"]!!(listOf("http://10.0.0.1/"), ctx)
        assertFalse("内网地址应在发请求前被拒", r.success)
        assertTrue((r.error ?: "").contains("Blocked internal address"))
    }

    @Test
    fun `curl blocks file scheme`() = runBlocking {
        val r = plugin.commands["curl"]!!(listOf("file:///etc/passwd"), ctx)
        assertFalse(r.success)
        assertTrue((r.error ?: "").contains("Blocked scheme"))
    }

    @Test
    fun `post blocks ssrf before any network request`() = runBlocking {
        val r = plugin.commands["post"]!!(listOf("http://192.168.1.1/api", "x=1"), ctx)
        assertFalse(r.success)
        assertTrue((r.error ?: "").contains("Blocked internal address"))
    }

    // ── net.proxy 纯字符串逻辑 ──────────────────────────────────────────

    @Test
    fun `proxy wraps github urls`() = runBlocking {
        val r = plugin.commands["proxy"]!!(listOf("https://github.com/a/b"), ctx)
        assertTrue(r.success)
        assertTrue(r.output.contains("https://ghproxy.com/https://github.com/a/b"))
    }

    @Test
    fun `proxy leaves non-github urls untouched`() = runBlocking {
        val r = plugin.commands["proxy"]!!(listOf("https://example.com/x"), ctx)
        assertTrue(r.success)
        assertTrue(r.output.contains("无需代理"))
    }
}
