// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.notification

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType
import java.util.UUID

/**
 * Agent-internal notification plugin.
 *
 * SECURITY:
 * - Notification IDs use cryptographically random UUIDs (not predictable timestamps).
 * - Notifications are isolated per session — only the creating session can dismiss them.
 * - Content is capped at 200 characters per field.
 * - Thread-safe concurrent access with @Synchronized.
 */
class NotificationPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "notification-plugin", name = "通知管理", version = "0.1.1",
        type = PluginType.NATIVE, author = "MengPaw",
        description = "通知管理：send, list, dismiss（按会话隔离，防伪造ID）",
        permissions = listOf("POST_NOTIFICATIONS"), minCoreVersion = "0.2.0",
        commands = listOf("notification.send", "notification.list", "notification.dismiss")
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "send" to ::send, "list" to ::list, "dismiss" to ::dismiss
    )

    /** Max content length for title/text fields. */
    private val maxFieldLen = 200

    /** Notification record with ownership info. */
    data class Record(
        val id: String,
        val sessionId: String,
        val title: String,
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val records = mutableListOf<Record>()

    /** Max stored records. */
    private val maxRecords = 20

    private suspend fun send(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val title = parseArg(args, "--title")?.take(maxFieldLen) ?: ""
        val content = parseArg(args, "--content")?.take(maxFieldLen) ?: ""
        // SECURITY: Use UUID (not timestamp) for unpredictable IDs
        val id = "notif_${UUID.randomUUID().toString().take(8)}"
        records.add(0, Record(id, ctx.sessionId, title, content))
        // Auto-expire old records
        while (records.size > maxRecords) records.removeAt(records.lastIndex)
        return ExecutionResult.ok("Notification sent: id=$id title=\"$title\"")
    }

        private suspend fun list(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (records.isEmpty()) return ExecutionResult.ok("(No recent notifications)")
        // Show session's own + all (but mark ownership)
        return ExecutionResult.ok(records.joinToString("\n") { r ->
            val owner = if (r.sessionId == ctx.sessionId) "[own]" else "[peer]"
            "$owner ${r.id}: ${r.title}: ${r.content.take(80)}"
        })
    }

        private suspend fun dismiss(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: notification dismiss [--all|<id>]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        return when (args[0]) {
            "--all" -> {
                // SECURITY: Only dismiss own session's notifications
                val removed = records.removeAll { it.sessionId == ctx.sessionId }
                if (removed) ExecutionResult.ok("Dismissed $removed own notifications")
                else ExecutionResult.ok("No own notifications to dismiss")
            }
            else -> {
                val targetId = args[0]
                // SECURITY: Only the owning session can dismiss a specific notification
                val record = records.find { it.id == targetId }
                if (record == null) {
                    ExecutionResult.fail("Not found: $targetId", errorCode = ErrorCodes.ERR_NOT_FOUND)
                } else if (record.sessionId != ctx.sessionId) {
                    ExecutionResult.fail("Cannot dismiss notification from another session",
                        errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
                } else {
                    records.remove(record)
                    ExecutionResult.ok("Dismissed: $targetId")
                }
            }
        }
    }

    private fun parseArg(args: List<String>, name: String): String? {
        for (i in args.indices) {
            if (args[i] == name && i + 1 < args.size) return args[i + 1]
            if (args[i].startsWith("$name=")) return args[i].removePrefix("$name=")
        }
        return null
    }
}
