// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.framework

import org.junit.Assert.*
import org.junit.Test

/**
 * MCP 网关请求体上限与路由判定单测 (P2 修复: MAX_MCP_BODY_BYTES=4MB)。
 * 纯函数直测 parseContentLength / routeRejection — 不启真实 ServerSocket,
 * 不触内核 McpServer (受理分支由 handle() 在运行时转交)。
 */
class McpGatewayTest {

    private val MAX = McpGateway.MAX_MCP_BODY_BYTES

    // ── Content-Length 解析 ─────────────────────────────────────────────

    @Test
    fun `parseContentLength parses valid values`() {
        assertEquals(123, McpGateway.parseContentLength("Content-Length: 123"))
        assertEquals(0, McpGateway.parseContentLength("Content-Length: 0"))
        assertEquals(42, McpGateway.parseContentLength("content-length: 42"))
        assertEquals(7, McpGateway.parseContentLength("Content-Length:  7"))
    }

    @Test
    fun `parseContentLength rejects invalid missing and negative`() {
        assertNull("非数字应拒绝", McpGateway.parseContentLength("Content-Length: abc"))
        assertNull("小数应拒绝", McpGateway.parseContentLength("Content-Length: 12.5"))
        assertNull("负数应拒绝", McpGateway.parseContentLength("Content-Length: -5"))
        assertNull("缺失应拒绝", McpGateway.parseContentLength(null))
        assertNull("其他头应拒绝", McpGateway.parseContentLength("Host: example.com"))
    }

    // ── 路由判定: 受理 ──────────────────────────────────────────────────

    @Test
    fun `route accepts mcp requests within size limit`() {
        assertNull("空请求体应受理", McpGateway.routeRejection("POST", "/mcp", 0))
        assertNull("恰好 4MB 应受理", McpGateway.routeRejection("POST", "/mcp", MAX))
    }

    // ── 路由判定: 413 拒绝 ──────────────────────────────────────────────

    @Test
    fun `route rejects oversized body with 413`() {
        val (status, body) = McpGateway.routeRejection("POST", "/mcp", MAX + 1)!!
        assertEquals("413 Payload Too Large", status)
        assertTrue("响应体应含上限提示", body.contains("limit"))
    }

    @Test
    fun `route rejects invalid or missing content-length with 413`() {
        assertEquals("413 Payload Too Large", McpGateway.routeRejection("POST", "/mcp", null)!!.first)
        assertEquals("413 Payload Too Large", McpGateway.routeRejection("POST", "/mcp", -1)!!.first)
    }

    // ── 路由判定: 其他路径 ──────────────────────────────────────────────

    @Test
    fun `route serves health check`() {
        val (status, body) = McpGateway.routeRejection("GET", "/health", null)!!
        assertEquals("200 OK", status)
        assertTrue(body.contains("\"ok\":true"))
    }

    @Test
    fun `route 404 for unknown paths and methods`() {
        assertEquals("404 Not Found", McpGateway.routeRejection("GET", "/mcp", null)!!.first)
        assertEquals("404 Not Found", McpGateway.routeRejection("POST", "/other", 10)!!.first)
        assertEquals("404 Not Found", McpGateway.routeRejection("DELETE", "/mcp", 10)!!.first)
        assertEquals("404 Not Found", McpGateway.routeRejection("GET", "/", null)!!.first)
    }
}
