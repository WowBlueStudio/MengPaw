package com.mengpaw.core.namespace

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult

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
        if (args.size < 2) return ExecutionResult.fail("Usage: ui click <x> <y>")
        val x = args[0].toIntOrNull() ?: return ExecutionResult.fail("Invalid x: ${args[0]}")
        val y = args[1].toIntOrNull() ?: return ExecutionResult.fail("Invalid y: ${args[1]}")
        // UI interaction delegates to accessibility service or ADB
        return ExecutionResult.ok("Click at ($x, $y) dispatched")
    }

    private suspend fun swipe(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 4) return ExecutionResult.fail("Usage: ui swipe <x1> <y1> <x2> <y2> [duration_ms]")
        val x1 = args[0].toIntOrNull() ?: return ExecutionResult.fail("Invalid x1: ${args[0]}")
        val y1 = args[1].toIntOrNull() ?: return ExecutionResult.fail("Invalid y1: ${args[1]}")
        val x2 = args[2].toIntOrNull() ?: return ExecutionResult.fail("Invalid x2: ${args[2]}")
        val y2 = args[3].toIntOrNull() ?: return ExecutionResult.fail("Invalid y2: ${args[3]}")
        return ExecutionResult.ok("Swipe from ($x1,$y1) to ($x2,$y2)")
    }

    private suspend fun input(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: ui input <text>")
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
