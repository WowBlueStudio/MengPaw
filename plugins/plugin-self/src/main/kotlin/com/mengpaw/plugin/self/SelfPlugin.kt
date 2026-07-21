// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.self

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType

/**
 * Self-introspection plugin — provides self.* CLI commands.
 * Pure JVM, zero dependencies beyond core.
 */
class SelfPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "self-plugin",
        name = "自省",
        version = "0.1.1",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "Agent 自省：status, config, stats, version",
        minCoreVersion = "0.2.0",
        commands = listOf("self.status", "self.config", "self.stats", "self.version")
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "status" to ::status,
        "config" to ::config,
        "stats" to ::stats,
        "version" to ::version
    )

    private var maxSteps = 50

    private suspend fun status(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val uptime = System.currentTimeMillis() - startTime
        return ExecutionResult.ok("""
            Session: ${ctx.sessionId}
            User: ${ctx.userId}
            WorkDir: ${ctx.workDir}
            Uptime: ${uptime / 1000}s
            Memory: ${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)}MB / ${Runtime.getRuntime().maxMemory() / (1024 * 1024)}MB
        """.trimIndent())
    }

    private suspend fun config(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) {
            return ExecutionResult.ok("maxSteps=$maxSteps\ncoreVersion=0.2.0")
        }
        val kv = args.joinToString(" ").split("=", limit = 2)
        if (kv.size == 2 && kv[0] == "maxSteps") {
            maxSteps = kv[1].toIntOrNull() ?: maxSteps
            return ExecutionResult.ok("maxSteps set to $maxSteps")
        }
        return ExecutionResult.fail("Usage: self config [key=value]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
    }

    private suspend fun stats(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val rt = Runtime.getRuntime()
        return ExecutionResult.ok("""
            Memory: ${(rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)}MB / ${rt.maxMemory() / (1024 * 1024)}MB
            Processors: ${rt.availableProcessors()}
            Threads: ${Thread.activeCount()}
        """.trimIndent())
    }

    private suspend fun version(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok("MengPaw v${com.mengpaw.kernel.AgentEngine.CORE_VERSION} (microkernel)")
    }

    companion object {
        private val startTime = System.currentTimeMillis()
    }
}
