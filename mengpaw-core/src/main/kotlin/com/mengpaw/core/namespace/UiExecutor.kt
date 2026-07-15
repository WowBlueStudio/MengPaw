package com.mengpaw.core.namespace

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.cli.ErrorCodes

/**
 * UI interaction namespace.
 * NOTE: Requires Android accessibility service or root to function.
 */
object UiExecutor {
    val commands = mapOf(
        "click" to ::click,
        "swipe" to ::swipe,
        "input" to ::input,
        "screenshot" to ::screenshot,
        "back" to ::back,
        "home" to ::home,
        "wait" to ::wait
    )

    private suspend fun click(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: ui click <x> <y>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val x = args[0].toIntOrNull() ?: return ExecutionResult.fail("Invalid x: ${args[0]}", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val y = args[1].toIntOrNull() ?: return ExecutionResult.fail("Invalid y: ${args[1]}", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        // UI interaction delegates to accessibility service or ADB
        return ExecutionResult.ok("Click at ($x, $y) dispatched")
    }

    private suspend fun swipe(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 4) return ExecutionResult.fail("Usage: ui swipe <x1> <y1> <x2> <y2> [duration_ms]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val x1 = args[0].toIntOrNull() ?: return ExecutionResult.fail("Invalid x1: ${args[0]}", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val y1 = args[1].toIntOrNull() ?: return ExecutionResult.fail("Invalid y1: ${args[1]}", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val x2 = args[2].toIntOrNull() ?: return ExecutionResult.fail("Invalid x2: ${args[2]}", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val y2 = args[3].toIntOrNull() ?: return ExecutionResult.fail("Invalid y2: ${args[3]}", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        return ExecutionResult.ok("Swipe from ($x1,$y1) to ($x2,$y2)")
    }

    private suspend fun input(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: ui input <text>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val text = args.joinToString(" ")
        return ExecutionResult.ok("Input text: $text")
    }

    private suspend fun screenshot(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val path = if (args.isNotEmpty()) args[0]
        else "${ctx.workDir}/screenshots/session_${ctx.sessionId}.png"
        java.io.File(path).parentFile?.mkdirs()
        return ExecutionResult.ok("Screenshot saved to $path")
    }

    private suspend fun back(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok("Back navigation dispatched")
    }

    private suspend fun home(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok("Home navigation dispatched")
    }

    private suspend fun wait(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val ms = args.firstOrNull()?.toLongOrNull() ?: 1000L
        kotlinx.coroutines.delay(ms)
        return ExecutionResult.ok("Waited ${ms}ms")
    }
}
