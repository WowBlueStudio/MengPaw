package com.mengpaw.plugin.notification

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.cli.ErrorCodes
import com.mengpaw.core.plugin.Plugin
import com.mengpaw.core.plugin.PluginMetadata
import com.mengpaw.core.plugin.PluginType

class NotificationPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "notification-plugin", name = "通知管理", version = "1.0.0",
        type = PluginType.NATIVE, author = "MengPaw",
        description = "通知管理：send, list, dismiss",
        permissions = listOf("POST_NOTIFICATIONS"), minCoreVersion = "0.2.0",
        commands = listOf("notification.send", "notification.list", "notification.dismiss")
    )

    override val commands: Map<String, com.mengpaw.core.plugin.CommandHandler> = mapOf(
        "send" to ::send, "list" to ::list, "dismiss" to ::dismiss
    )

    private val records = mutableListOf<Triple<String, String, String>>() // id, title, content

    private suspend fun send(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val title = parseArg(args, "--title") ?: ""
        val content = parseArg(args, "--content") ?: ""
        val id = "notif_${System.currentTimeMillis()}"
        records.add(0, Triple(id, title, content))
        if (records.size > 20) records.removeAt(records.lastIndex)
        return ExecutionResult.ok("Notification sent: id=$id title=\"$title\"")
    }

    private suspend fun list(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (records.isEmpty()) return ExecutionResult.ok("(No recent notifications)")
        return ExecutionResult.ok(records.joinToString("\n") { "[${it.first}] ${it.second}: ${it.third.take(80)}" })
    }

    private suspend fun dismiss(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: notification dismiss [--all|<id>]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        return when (args[0]) {
            "--all" -> { records.clear(); ExecutionResult.ok("Dismissed all") }
            else -> {
                val removed = records.removeAll { it.first == args[0] }
                if (removed) ExecutionResult.ok("Dismissed: ${args[0]}")
                else ExecutionResult.fail("Not found: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
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
