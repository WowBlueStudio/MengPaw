// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.browserpush

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.acp.AcpMessage
import com.mengpaw.kernel.acp.AcpMessageType
import com.mengpaw.kernel.acp.AcpServer
import com.mengpaw.kernel.acp.AcpTransport
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginContext
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType
import java.io.File

/**
 * Cross-device browser page push plugin.
 *
 * Allows an Agent on device A to push a web page URL to device B's Agent.
 * Requires B device authorization (TRUSTED = auto-accept, GUEST = inbox approval).
 *
 * ## Privacy
 * Only the URL and page title are transmitted. No browsing history or cookies.
 */
class BrowserPushPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "browser-push-plugin",
        name = "跨设备推送",
        version = "0.1.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "Agent 跨设备推送网页。需对端授权。基于 ACP 协议。",
        permissions = emptyList(),
        minCoreVersion = "0.2.3",
        commands = listOf("browser.push", "browser.push.pending", "browser.push.accept", "browser.push.reject")
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "push" to ::push,
        "push.pending" to ::pending,
        "push.accept" to ::accept,
        "push.reject" to ::reject,
    )

    private var acpServer: AcpServer? = null
    private var acpTransport: AcpTransport? = null

    override suspend fun onInstall(ctx: PluginContext) {
        // ACP integration is handled by the shell service
        ctx.log("跨设备推送插件已激活。TRUSTED 设备自动接受，GUEST 设备需手动授权。")
    }

    // ── browser.push ────────────────────────────────────────────────────

    private suspend fun push(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: browser.push <url> [--to <agent-id>] [--title <title>]",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)

        val url = args[0]
        if (!url.startsWith("http")) return ExecutionResult.fail("URL must start with http/https", errorCode = ErrorCodes.ERR_INVALID_INPUT)

        // Parse optional flags
        val to = parseArg(args, "--to")
        val title = parseArg(args, "--title") ?: url

        val server = acpServer
        val transport = acpTransport
        if (server == null || transport == null) {
            return ExecutionResult.fail("ACP 未启动。请先执行 self.acp start", errorCode = ErrorCodes.ERR_INTERNAL)
        }

        return try {
            if (to != null) {
                // Direct push to specific peer
                val msg = AcpMessage.browserPush(ctx.sessionId, to, url, title)
                transport.send(msg)
                ExecutionResult.ok("网页已推送到 $to: $title\n$url")
            } else {
                // Broadcast to all known peers
                val peers = server.getPeers()
                if (peers.isEmpty()) return ExecutionResult.fail("未发现在线设备。请确保双方在同一 WiFi 且 ACP 已启动。", errorCode = ErrorCodes.ERR_NOT_FOUND)
                peers.forEach { peer ->
                    val msg = AcpMessage.browserPush(ctx.sessionId, peer.agentId, url, title)
                    try { transport.send(msg) } catch (_: Exception) { }
                }
                ExecutionResult.ok("网页已推送到 ${peers.size} 个设备: $title\n$url")
            }
        } catch (e: Exception) {
            ErrorCollector.report(e, "BrowserPush.push")
            ExecutionResult.fail("推送失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    // ── browser.push.pending ────────────────────────────────────────────

    private suspend fun pending(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val inbox = File(DataPaths.AGENT_INBOX)
        val pushes = inbox.listFiles()?.filter { it.name.startsWith("push_") }?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (pushes.isEmpty()) return ExecutionResult.ok("(无待处理的推送请求)")
        val output = buildString {
            appendLine("## 待处理的推送 (${pushes.size})")
            appendLine()
            pushes.forEach { f ->
                val lines = try { f.readText().lines() } catch (_: Exception) { emptyList() }
                val from = lines.find { it.startsWith("- 来自:") }?.removePrefix("- 来自: ")?.trim() ?: "?"
                val url = lines.find { it.startsWith("- URL:") }?.removePrefix("- URL: ")?.trim() ?: "?"
                appendLine("### ${f.nameWithoutExtension}")
                appendLine("- 来自: $from  |  URL: $url")
                appendLine()
            }
        }
        return ExecutionResult.ok(output)
    }

    // ── browser.push.accept ─────────────────────────────────────────────

    private suspend fun accept(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.push.accept <push-id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val pushFile = File(DataPaths.AGENT_INBOX, "${args[0]}.md")
        if (!pushFile.exists()) return ExecutionResult.fail("推送请求不存在: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)

        val lines = try { pushFile.readText().lines() } catch (_: Exception) { emptyList() }
        val url = lines.find { it.startsWith("- URL:") }?.removePrefix("- URL: ")?.trim() ?: ""
        val from = lines.find { it.startsWith("- 来自:") }?.removePrefix("- 来自: ")?.trim() ?: ""

        // Open in browser
        pushFile.delete()

        if (url.isBlank()) return ExecutionResult.fail("推送请求无效: 无 URL", errorCode = ErrorCodes.ERR_INVALID_INPUT)

        // Notify back
        try {
            acpTransport?.send(AcpMessage.browserPushResponse(ctx.sessionId, from, accepted = true))
        } catch (_: Exception) { }

        return ExecutionResult.ok("已接受推送，正在打开: $url\n使用 browser.open 打开此 URL。")
    }

    // ── browser.push.reject ─────────────────────────────────────────────

    private suspend fun reject(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.push.reject <push-id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val pushFile = File(DataPaths.AGENT_INBOX, "${args[0]}.md")
        if (!pushFile.exists()) return ExecutionResult.fail("推送请求不存在: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)

        val lines = try { pushFile.readText().lines() } catch (_: Exception) { emptyList() }
        val from = lines.find { it.startsWith("- 来自:") }?.removePrefix("- 来自: ")?.trim() ?: ""
        pushFile.delete()

        try {
            acpTransport?.send(AcpMessage.browserPushResponse(ctx.sessionId, from, accepted = false, reason = "rejected"))
        } catch (_: Exception) { }

        return ExecutionResult.ok("已拒绝推送请求。")
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun parseArg(args: List<String>, flag: String): String? {
        val idx = args.indexOf(flag)
        return if (idx >= 0 && idx + 1 < args.size) args[idx + 1] else null
    }
}
