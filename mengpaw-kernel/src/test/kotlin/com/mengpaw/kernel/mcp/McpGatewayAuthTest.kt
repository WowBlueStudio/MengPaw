// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.mcp

import com.mengpaw.kernel.DataPaths
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/** 9881 MCP 网关认证测试 (v0.34.3 P1-6): 无 token/错 token 401, 正确放行, health 免认证。 */
class McpGatewayAuthTest {

    private val tmp = File(System.getProperty("java.io.tmpdir"), "mengpaw_mcpauth_${System.nanoTime()}")

    @Before
    fun setup() {
        tmp.mkdirs()
        DataPaths.initialize(tmp.absolutePath)
        McpGatewayAuth.authToken = ""
    }

    @After
    fun teardown() {
        tmp.deleteRecursively()
        McpGatewayAuth.authToken = ""
    }

    @Test
    fun `missing token rejected`() {
        val r = McpGatewayAuth.authRejection("POST", "/mcp", null, "gateway-token")
        assertNotNull(r)
        assertEquals("401 Unauthorized", r!!.first)
    }

    @Test
    fun `wrong token rejected`() {
        val r = McpGatewayAuth.authRejection("POST", "/mcp", "Bearer wrong", "gateway-token")
        assertNotNull(r)
        assertEquals("401 Unauthorized", r!!.first)
    }

    @Test
    fun `correct token allowed`() {
        assertNull(McpGatewayAuth.authRejection("POST", "/mcp", "Bearer gateway-token", "gateway-token"))
    }

    @Test
    fun `health exempt from auth`() {
        assertNull(McpGatewayAuth.authRejection("GET", "/health", null, ""))
    }

    @Test
    fun `ensure token persists across restart`() {
        val t1 = McpGatewayAuth.ensureToken()
        assertNotNull(t1)
        McpGatewayAuth.authToken = "" // 模拟进程重启
        val t2 = McpGatewayAuth.ensureToken()
        assertEquals("token 应从持久化文件恢复", t1, t2)
    }
}
