package com.mengpaw.core.namespace

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.cli.ErrorCodes

/**
 * Notification operations namespace.
 * Stub implementation — Android NotificationListenerService requires
 * Manifest declaration and user-enabled permission at runtime.
 */
object NotificationExecutor {
    val commands = mapOf(
        "list" to ::list,
        "dismiss" to ::dismiss
    )

    private suspend fun list(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(
            """
            (Notification list stub — requires NotificationListenerService on Android)
            No notifications available in stub mode.
            """.trimIndent()
        )
    }

    private suspend fun dismiss(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: notification dismiss [--all|<id>]",
            errorCode = ErrorCodes.ERR_INVALID_INPUT
        )
        val flag = args[0]
        return when (flag) {
            "--all" -> ExecutionResult.ok("Dismissed all notifications (stub)")
            else -> ExecutionResult.ok("Dismissed notification: $flag (stub)")
        }
    }
}
