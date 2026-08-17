// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 桥认证策略单测 (开放模式 Playwright 式改造, 2026-08-17)。
 *
 * 覆盖: 安全模式 fail-closed (无 token/错 token 拒绝, 正确 token 放行) /
 * 开放模式免认证放行 (无 token、错 token 均可) / expected 空值恒拒绝。
 */
class McpAuthPolicyTest {

    @Test
    fun 安全模式无Token拒绝() {
        assertFalse(McpAuthPolicy.isAuthorized(openMode = false, expectedToken = "abc123", providedHeader = ""))
    }

    @Test
    fun 安全模式错误Token拒绝() {
        assertFalse(McpAuthPolicy.isAuthorized(openMode = false, expectedToken = "abc123", providedHeader = "Bearer wrong"))
    }

    @Test
    fun 安全模式正确Token放行() {
        assertTrue(McpAuthPolicy.isAuthorized(openMode = false, expectedToken = "abc123", providedHeader = "Bearer abc123"))
    }

    @Test
    fun 安全模式Bearer前缀容错() {
        assertTrue(McpAuthPolicy.isAuthorized(openMode = false, expectedToken = "abc123", providedHeader = "Bearer  abc123 "))
    }

    @Test
    fun 安全模式Expected为空恒拒绝() {
        // fail-closed: 未建立安全通道时即使请求带 token 也拒绝
        assertFalse(McpAuthPolicy.isAuthorized(openMode = false, expectedToken = "", providedHeader = "Bearer whatever"))
    }

    @Test
    fun 开放模式无Token放行() {
        assertTrue(McpAuthPolicy.isAuthorized(openMode = true, expectedToken = "abc123", providedHeader = ""))
    }

    @Test
    fun 开放模式错误Token放行() {
        // Playwright 式: 本机回环 + 用户显式开启, 免认证
        assertTrue(McpAuthPolicy.isAuthorized(openMode = true, expectedToken = "abc123", providedHeader = "Bearer wrong"))
    }
}
