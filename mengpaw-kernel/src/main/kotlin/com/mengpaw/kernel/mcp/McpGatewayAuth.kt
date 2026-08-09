// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.mcp

/**
 * 本机 MCP 网关 (9881) Bearer token 认证 (v0.34.3 P1-6)。
 *
 * 9881 此前零认证 — 任何本机进程可 POST /mcp 调插件命令 (含 root.exec 若已装
 * root-plugin)。对齐 9880 浏览器桥 fail-closed: 无 token/错 token 一律 401。
 * token 由 McpGateway (plugin-framework) 启动时 ensureToken 生成并持久化
 * {BASE}/配置/mcp_gateway_token; 本机 MCP 客户端经 `self.mcp token` 获取。
 */
object McpGatewayAuth {

    @Volatile var authToken: String = ""

    /** 从 {BASE}/配置/mcp_gateway_token 读取或生成 (32 字节 SecureRandom hex)。 */
    fun ensureToken(): String {
        if (authToken.isNotEmpty()) return authToken
        val file = java.io.File(com.mengpaw.kernel.DataPaths.CONFIG, "mcp_gateway_token")
        val existing = try { if (file.exists()) file.readText().trim() else "" } catch (_: Exception) { "" }
        if (existing.length >= 16) {
            authToken = existing
            return authToken
        }
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        authToken = bytes.joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
        try {
            file.parentFile?.mkdirs()
            file.writeText(authToken)
        } catch (_: Exception) { /* 持久化失败: 仅本次运行生效 */ }
        return authToken
    }

    /** 鉴权判定 (纯函数可测): POST /mcp 需 Bearer token 匹配; /health 免认证。
     *  @return (状态行, 响应体) 表示应拒绝, null 表示放行。 */
    fun authRejection(method: String, path: String, authorization: String?, token: String): Pair<String, String>? {
        if (method == "GET" && path == "/health") return null
        if (method != "POST" || path != "/mcp") return null
        if (token.isBlank()) {
            return "401 Unauthorized" to """{"ok":false,"error":"MCP gateway not configured"}"""
        }
        val provided = authorization?.removePrefix("Bearer")?.trim().orEmpty()
        if (provided != token) {
            return "401 Unauthorized" to """{"ok":false,"error":"unauthorized: missing or invalid gateway token (self.mcp token)"}"""
        }
        return null
    }
}
