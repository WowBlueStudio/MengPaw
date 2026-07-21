// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.net

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Network plugin — provides net.* CLI commands.
 * Requires Ktor HTTP client.
 *
 * SECURITY: URL validation prevents SSRF attacks by blocking:
 * - Non-HTTP schemes (file://, jar://, ftp://, etc.)
 * - Private/internal IP ranges (RFC 1918, localhost, cloud metadata)
 */
class NetPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "net-plugin",
        name = "网络请求",
        version = "1.0.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "HTTP 网络请求：curl, get, post",
        permissions = listOf("INTERNET"),
        minCoreVersion = "0.2.0",
        commands = listOf("net.curl", "net.get", "net.post")
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "curl" to ::curl,
        "get" to ::curl,    // alias
        "post" to ::post
    )

    private val client = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(10, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
                // SECURITY: Do not follow redirects automatically — SSRF via redirect
                followRedirects(false)
            }
        }
    }

    /** Validate URL for SSRF protection. Returns null if valid, or error message. */
    private suspend fun validateUrl(rawUrl: String): String? {
        val uri = try {
            val u = URI(rawUrl)
            // Require absolute URL
            if (!u.isAbsolute) return "Only absolute URLs are allowed"
            u
        } catch (e: Exception) {
            return "Invalid URL: ${e.message}"
        }

        // Block non-HTTP schemes (file://, jar://, ftp://, javascript:, etc.)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return "Blocked scheme '$scheme': only http/https are allowed"
        }

        // Resolve hostname and check against private IP ranges
        val host = uri.host ?: return "URL has no host"
        return try {
            // SECURITY + PERF: Offload DNS resolution to IO thread to avoid blocking
            val addr = withContext(Dispatchers.IO) { InetAddress.getByName(host) }
            if (isBlockedAddress(addr)) {
                "Blocked internal address: $host (${addr.hostAddress})"
            } else null
        } catch (e: Exception) {
            "Cannot resolve host: $host"
        }
    }

    /** Check if an IP address is in a blocked (private/internal) range. */
    private fun isBlockedAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress) return true        // 127.0.0.0/8, ::1
        if (addr.isLinkLocalAddress) return true        // 169.254.0.0/16
        if (addr.isSiteLocalAddress) return true        // 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
        if (addr.isAnyLocalAddress) return true         // 0.0.0.0
        // Block cloud metadata endpoints (AWS, GCP, Azure, etc.)
        val ip = addr.hostAddress ?: return false
        if (ip == "169.254.169.254") return true        // AWS / GCP metadata
        if (ip == "100.100.100.200") return true        // Alibaba Cloud metadata
        // Block IPv4-mapped IPv6 localhost
        if (ip == "::ffff:127.0.0.1") return true
        return false
    }

    private suspend fun curl(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: net curl <url>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val url = args[0]

        // SSRF validation
        val validationError = validateUrl(url)
        if (validationError != null) return ExecutionResult.fail(validationError, errorCode = ErrorCodes.ERR_INVALID_INPUT)

        return try {
            val response = client.get(url)
            val location = response.headers["Location"] ?: response.headers["location"]
            if (location != null && (response.status.value in 300..399)) {
                ExecutionResult.fail("Redirect to $location blocked. Use the destination URL directly.",
                    errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
            } else {
                val body = response.bodyAsText().take(10000)
                ExecutionResult.ok(body)
            }
        } catch (e: Exception) {
            ExecutionResult.fail("HTTP error: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    private suspend fun post(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: net post <url> <body>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val url = args[0]
        val body = args.drop(1).joinToString(" ")

        // SSRF validation
        val validationError = validateUrl(url)
        if (validationError != null) return ExecutionResult.fail(validationError, errorCode = ErrorCodes.ERR_INVALID_INPUT)

        return try {
            val response = client.post(url) { setBody(body) }
            val location = response.headers["Location"] ?: response.headers["location"]
            if (location != null && (response.status.value in 300..399)) {
                ExecutionResult.fail("Redirect to $location blocked. Use the destination URL directly.",
                    errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
            } else {
                ExecutionResult.ok(response.bodyAsText().take(10000))
            }
        } catch (e: Exception) {
            ExecutionResult.fail("HTTP error: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }
}
