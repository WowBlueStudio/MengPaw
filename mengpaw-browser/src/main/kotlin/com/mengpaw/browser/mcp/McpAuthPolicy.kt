// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.mcp

/**
 * MCP 桥认证策略 (纯函数, 便于单元测试)。
 *
 * 两种模式:
 * - **默认 (安全模式)**: 仅同签名 Shell 经签名级 ContentProvider 拿到 token 后可调,
 *   无 token / 错 token 一律拒绝 (fail-closed, 九维审查 P0 修复定案)。
 * - **开放模式 (Playwright 式)**: 免认证放行 — 仅本机回环 127.0.0.1:9880 可达,
 *   本机任意进程可直接控制浏览器; 由用户在设置中显式开启 (默认关闭)。
 */
object McpAuthPolicy {

    /**
     * 判断 /mcp 请求是否授权。
     * @param openMode 开放模式开关 (true = 免认证放行)
     * @param expectedToken 服务端持有的 token (安全模式下空值恒拒绝, fail-closed)
     * @param providedHeader 请求 Authorization 头原文 (如 `Bearer abc`)
     */
    fun isAuthorized(openMode: Boolean, expectedToken: String, providedHeader: String): Boolean {
        if (openMode) return true
        val provided = providedHeader.removePrefix("Bearer").trim()
        return expectedToken.isNotBlank() && provided == expectedToken
    }
}
