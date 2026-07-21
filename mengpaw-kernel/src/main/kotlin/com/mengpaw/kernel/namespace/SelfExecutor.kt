// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.namespace

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.error.ErrorCollector

/**
 * Self-introspection namespace - allows Agent to query its own state.
 * Uses command dispatch maps instead of nested when-blocks for clarity.
 */

/** Shared ACP server instance — accessible from CLI and AgentEngine. */
object AcpHolder {
    val server = com.mengpaw.kernel.acp.AcpServer(com.mengpaw.kernel.agent.AgentProfile())
    var transport: com.mengpaw.kernel.acp.AcpHttpTransport? = null
}

object SelfExecutor {
    /** Set by AgentEngine during buildPipeline so self.tools can enumerate available commands. */
    @Volatile var commandRegistry: com.mengpaw.kernel.cli.CommandRegistry? = null

    val commands = mapOf(
        "status" to ::status,
        "config" to ::config,
        "stats" to ::stats,
        "version" to ::version,
        "avatar" to ::avatar,
        "theme" to ::theme,
        "mcp" to ::mcp,
        "trigger" to ::triggerCmd,
        "acp" to ::acpCmd,
        "tools" to ::toolsCmd,
        "time" to ::timeCmd,
        "notify.message" to ::notifyMessage,
        "notify.banner" to ::notifyBanner
    )

    // ── Top-level commands ─────────────────────────────────────────

    private suspend fun status(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(
            "Session: ${ctx.sessionId}\n" +
            "User: ${ctx.userId}\n" +
            "WorkDir: ${ctx.workDir}"
        )
    }

    private suspend fun config(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) {
            return ExecutionResult.ok(
                "maxSteps = 50\n" +
                "timeoutMs = 300000\n" +
                "screenshotEnabled = true\n" +
                "browserPoolSize = 4"
            )
        }
        return ExecutionResult.ok("Config: ${args.joinToString(" ")}")
    }

    private suspend fun stats(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val totalMb = runtime.totalMemory() / (1024 * 1024)
        return ExecutionResult.ok(
            "Memory: ${usedMb}MB / ${totalMb}MB\n" +
            "Processors: ${runtime.availableProcessors()}\n" +
            "Threads: ${Thread.activeCount()}"
        )
    }

    private suspend fun version(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok("檬爪 v0.6.0 (kernel: ${com.mengpaw.kernel.AgentEngine.CORE_VERSION})")
    }

    // ── ACP subcommands ────────────────────────────────────────────

    /** ACP device-to-device communication. Usage: self.acp [start|stop|peers|discover|delegate|share|pair|...] */
    private suspend fun acpCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return acpUsage()
        val sub = args[0]
        return ACP_SUBCOMMANDS[sub]?.invoke(args, ctx)
            ?: acpUsage()
    }

    private val ACP_SUBCOMMANDS: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "start" to { _, _ ->
            val transport = com.mengpaw.kernel.acp.AcpHttpTransport(AcpHolder.server)
            AcpHolder.transport = transport
            AcpHolder.server.registerTransport(transport)
            transport.startListener()
            ExecutionResult.ok("ACP 已启动，端口 9876。其他设备可通过 self.acp discover 发现本设备。")
        },
        "stop" to { _, _ ->
            AcpHolder.transport?.close()
            AcpHolder.transport = null
            ExecutionResult.ok("ACP 已停止。")
        },
        "peers" to { _, _ ->
            val peers = AcpHolder.server.getPeers()
            if (peers.isEmpty()) ExecutionResult.ok("(无已连接设备)\n\n发现设备: self.acp discover")
            else ExecutionResult.ok(peers.joinToString("\n") { "• ${it.agentName} (${it.agentId}) @ ${it.address}:${it.port}" })
        },
        "discover" to { _, _ ->
            val peers = AcpHolder.server.discover()
            if (peers.isEmpty()) ExecutionResult.ok("(未发现其他设备)\n\n确保两台设备在同一 WiFi，且都已执行 self.acp start。")
            else ExecutionResult.ok("发现 ${peers.size} 个设备:\n" + peers.joinToString("\n") { "• ${it.agentName} (${it.agentId})" })
        },
        "delegate" to { a, _ ->
            if (a.size < 3) ExecutionResult.fail("Usage: self.acp delegate <peer-id> <task>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            else {
                val result = AcpHolder.server.delegate(a[1], a.drop(2).joinToString(" "))
                ExecutionResult.ok(if (result.success) "任务已委派。" else "委派失败: ${result.message}")
            }
        },
        "share" to { a, _ ->
            if (a.size < 3) ExecutionResult.fail("Usage: self.acp share memory|skill <peer-id> <id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            else {
                val type = a[1]; val peerId = a[2]; val id = a.getOrNull(3) ?: ""
                val result = if (type == "memory") AcpHolder.server.shareMemory(peerId, id)
                else AcpHolder.server.shareSkill(peerId, id)
                ExecutionResult.ok(if (result.success) "已共享。" else "共享失败: ${result.message}")
            }
        },
        "pair" to { a, _ ->
            if (a.size < 3) ExecutionResult.fail("Usage: self.acp pair <device-id> <peer-fingerprint>\n获取对方指纹: 让对方执行 self.acp fingerprint", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            else {
                val myFp = com.mengpaw.kernel.acp.AcpCrypto.myFingerprint()
                val peerFp = a[2]
                com.mengpaw.kernel.acp.AcpCrypto.deriveKey(myFp, peerFp, a[1])
                com.mengpaw.kernel.security.PromptFirewall.trust(a[1], peerFp)
                ExecutionResult.ok("已配对设备: ${a[1]}\n加密: AES-256-CBC (密钥已派生)\n该设备现在拥有完整访问权限。")
            }
        },
        "fingerprint" to { _, _ ->
            ExecutionResult.ok("本设备指纹: ${com.mengpaw.kernel.acp.AcpCrypto.myFingerprint()}\n将此指纹提供给配对方。")
        },
        "untrust" to { a, _ ->
            if (a.size < 2) ExecutionResult.fail("Usage: self.acp untrust <device-id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            else { com.mengpaw.kernel.security.PromptFirewall.untrust(a[1]); ExecutionResult.ok("已解除配对: ${a[1]}") }
        },
        "trusted" to { _, _ ->
            val list = com.mengpaw.kernel.security.PromptFirewall.listTrusted()
            if (list.isEmpty()) ExecutionResult.ok("(无已配对设备)\n\n配对: self.acp pair <device-id>")
            else ExecutionResult.ok("已配对设备:\n" + list.joinToString("\n") { "• $it" })
        },
        "firewall" to { _, _ ->
            ExecutionResult.ok(com.mengpaw.kernel.security.PromptFirewall.guestPolicySummary())
        }
    )

    private fun acpUsage() = ExecutionResult.fail(
        "Usage: self.acp start|stop|peers|discover|delegate|share|pair|fingerprint|untrust|trusted|firewall",
        errorCode = ErrorCodes.ERR_INVALID_INPUT
    )

    // ── Trigger subcommands ────────────────────────────────────────

    /** Trigger management. Usage: self.trigger [add|list|remove|topics|cron-wake] */
    private suspend fun triggerCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return triggerUsage()
        val sub = args[0]
        return TRIGGER_SUBCOMMANDS[sub]?.invoke(args, ctx)
            ?: triggerUsage()
    }

    private val TRIGGER_SUBCOMMANDS: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "add" to { a, _ ->
            if (a.size < 5) ExecutionResult.fail("Usage: self.trigger add <cron|lifetime> <id> <expr> <action>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            else {
                val engine = com.mengpaw.kernel.trigger.TriggerEngine
                val type = a[1]; val id = a[2]; val expr = a[3]; val action = a.drop(4).joinToString(" ")
                val ok = when (type) {
                    "cron" -> { engine.addCron(id, expr, action); engine.registerCronAlarm(null); true }
                    "lifetime" -> { engine.addLifetime(id, expr, action); true }
                    else -> false
                }
                if (ok) ExecutionResult.ok("Trigger $id added.")
                else ExecutionResult.fail("Type must be 'cron' or 'lifetime'", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            }
        },
        "list" to { _, _ ->
            val triggers = com.mengpaw.kernel.trigger.TriggerEngine.list()
            if (triggers.isEmpty()) ExecutionResult.ok("(No triggers)\n\n示例:\nself.trigger add cron morning-report 0 9 * * * 生成昨日摘要\nself.trigger add lifetime chat 10:00-20:00 随机聊天")
            else ExecutionResult.ok(triggers.joinToString("\n") { "${if (it.enabled) "✅" else "⛔"} ${it.id} [${it.type}] ${it.config} → ${it.action}" })
        },
        "remove" to { a, _ ->
            com.mengpaw.kernel.trigger.TriggerEngine.remove(a.getOrElse(1) { "" })
            ExecutionResult.ok("Removed.")
        },
        "topics" to { _, _ ->
            ExecutionResult.ok("## 真人感话题\n\n${com.mengpaw.kernel.trigger.TriggerEngine.LIFETIME_TOPICS.joinToString("\n") { "- $it" }}")
        },
        "cron-wake" to { _, _ ->
            com.mengpaw.kernel.trigger.TriggerEngine.registerCronAlarm(null)
            ExecutionResult.ok("Cron alarm re-registered.")
        }
    )

    private fun triggerUsage() = ExecutionResult.fail(
        "Usage: self.trigger add|list|remove|topics|cron-wake",
        errorCode = ErrorCodes.ERR_INVALID_INPUT
    )

    // ── MCP subcommands ────────────────────────────────────────────

    /** MCP connection management. Usage: self.mcp [connect|disconnect|status|call] */
    private suspend fun mcp(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.ok(com.mengpaw.kernel.mcp.McpClient.statusReport())
        val sub = args[0]
        return MCP_SUBCOMMANDS[sub]?.invoke(args, ctx)
            ?: ExecutionResult.fail("Usage: self.mcp connect|disconnect|status|call", errorCode = ErrorCodes.ERR_INVALID_INPUT)
    }

    private val MCP_SUBCOMMANDS: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "connect" to { a, _ ->
            if (a.size < 2) ExecutionResult.fail("Usage: self.mcp connect <id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            else {
                val cfg = com.mengpaw.kernel.mcp.McpClient.PRESETS[a[1]]
                if (cfg == null) ExecutionResult.fail("Unknown preset: ${a[1]}. Available: ${com.mengpaw.kernel.mcp.McpClient.PRESETS.keys}", errorCode = ErrorCodes.ERR_NOT_FOUND)
                else { com.mengpaw.kernel.mcp.McpClient.connect(a[1], cfg); ExecutionResult.ok("Connected to ${cfg.name}.") }
            }
        },
        "disconnect" to { a, _ ->
            if (a.size < 2) ExecutionResult.fail("Usage: self.mcp disconnect <id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            else { com.mengpaw.kernel.mcp.McpClient.disconnect(a[1]); ExecutionResult.ok("Disconnected: ${a[1]}") }
        },
        "status" to { _, _ -> ExecutionResult.ok(com.mengpaw.kernel.mcp.McpClient.statusReport()) },
        "call" to { a, _ ->
            if (a.size < 4) ExecutionResult.fail("Usage: self.mcp call <connection-id> <tool-name> <args-json>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            else {
                val conn = com.mengpaw.kernel.mcp.McpClient.listConnections().find { it.id == a[1] }
                if (conn == null) ExecutionResult.fail("Not connected: ${a[1]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
                else {
                    val toolArgs: Map<String, String> = try {
                        val json = kotlinx.serialization.json.Json.parseToJsonElement(a.drop(3).joinToString(" "))
                        (json as? kotlinx.serialization.json.JsonObject)?.mapValues {
                            (it.value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: it.value.toString()
                        } ?: emptyMap()
                    } catch (e: Exception) { emptyMap() }
                    ExecutionResult.ok(conn.callTool(a[2], toolArgs))
                }
            }
        }
    )

    // ── Notify (Agent→User push) ─────────────────────────────────

    /** Push a normal message into the chat. Usage: notify.message <text> */
    private suspend fun notifyMessage(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: notify.message <text>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val text = args.joinToString(" ")
        NotifyBus.message(text)
        return ExecutionResult.ok("已推送消息")
    }

    /** Push a banner overlay. Usage: notify.banner <text> [--level info|success|warn|error] */
    private suspend fun notifyBanner(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: notify.banner <text> [--level info|success|warn|error]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val levelIdx = args.indexOf("--level")
        val level = if (levelIdx >= 0 && levelIdx + 1 < args.size) {
            try { NotifyBus.NotifyLevel.valueOf(args[levelIdx + 1].uppercase()) } catch (_: Exception) { NotifyBus.NotifyLevel.INFO }
        } else NotifyBus.NotifyLevel.INFO
        val text = args.filterIndexed { i, _ -> i != levelIdx && i != levelIdx + 1 }.joinToString(" ")
        NotifyBus.banner(text, level)
        return ExecutionResult.ok("已推送横幅")
    }

    // ── Time ─────────────────────────────────────────────────────

    /** Get current time/date. Usage: self.time [format|timezone] */
    private suspend fun timeCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val now = java.time.ZonedDateTime.now()
        val fmt = java.time.format.DateTimeFormatter.ofPattern(
            when (args.firstOrNull()) {
                "iso" -> "yyyy-MM-dd'T'HH:mm:ssXXX"
                "date" -> "yyyy-MM-dd"
                "time" -> "HH:mm:ss"
                "week" -> "eeee"
                "timestamp" -> now.toInstant().toEpochMilli().toString()
                else -> "yyyy-MM-dd HH:mm:ss z"
            }
        )
        return ExecutionResult.ok(buildString {
            appendLine("现在时间: ${now.format(fmt)}")
            appendLine("时区: ${java.util.TimeZone.getDefault().id}")
            appendLine("星期: ${now.dayOfWeek}")
            appendLine("Unix 时间戳: ${now.toInstant().toEpochMilli()}")
            appendLine()
            appendLine("用法: self.time [iso|date|time|week|timestamp]")
        })
    }

    // ── Local Tools ────────────────────────────────────────────────

    /** List all available local tools/commands the Agent can invoke. Usage: self.tools [namespace] */
    private suspend fun toolsCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val registry = commandRegistry
        if (registry == null) return ExecutionResult.fail("Command registry not yet initialized", errorCode = ErrorCodes.ERR_INTERNAL)
        val ns = args.firstOrNull()
        val all = registry.list(ns).sorted()
        if (all.isEmpty()) return ExecutionResult.ok("(No commands available)")
        val byNs = all.groupBy { it.substringBefore(".") }
        return ExecutionResult.ok(buildString {
            appendLine("Available tools (${all.size} commands, ${byNs.size} namespaces):")
            byNs.forEach { (namespace, cmds) ->
                appendLine("\n## $namespace (${cmds.size})")
                cmds.forEach { appendLine("  • $it") }
            }
            if (ns == null) appendLine("\nTip: self.tools <namespace> to filter.")
        })
    }

    // ── Avatar ─────────────────────────────────────────────────────

    /** Set Agent avatar. Usage: self.avatar <image-path> */
    private suspend fun avatar(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: self.avatar <image-path>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val src = java.io.File(args[0])
        // FIX A12: Block path traversal — only allow paths within agent workspace
        val canonical: java.io.File = try { src.canonicalFile } catch (_: Exception) { return ExecutionResult.fail("Invalid path", errorCode = ErrorCodes.ERR_INVALID_INPUT) }
        if (!canonical.absolutePath.startsWith(com.mengpaw.kernel.DataPaths.AGENTS) &&
            !canonical.absolutePath.startsWith(com.mengpaw.kernel.DataPaths.BASE) &&
            !canonical.absolutePath.startsWith("/sdcard/Pictures") &&
            !canonical.absolutePath.startsWith("/sdcard/DCIM") &&
            !canonical.absolutePath.startsWith("/storage/emulated/0/Pictures") &&
            !canonical.absolutePath.startsWith("/storage/emulated/0/DCIM")) {
            return ExecutionResult.fail("Path not allowed: outside agent workspace and known media directories", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }
        if (!canonical.exists()) return ExecutionResult.fail("Not found: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
        val dst = java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, "avatar.png")
        dst.parentFile?.mkdirs()
        canonical.copyTo(dst, overwrite = true)
        return ExecutionResult.ok("Avatar updated.")
    }

    // ── Theme ───────────────────────────────────────────────────────

    /** Set Agent theme colors. Usage: self.theme primary=#165DFF surface=#FFFFFF */
    private suspend fun theme(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val themeFile = java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, "theme.md")
        val current = AgentTheme.fromFile(themeFile)

        if (args.isEmpty()) return ExecutionResult.ok(current.toMarkdown())

        val map = args.flatMap { it.split("=", limit = 2).takeIf { p -> p.size == 2 } ?: emptyList() }
            .windowed(2, 2) { it[0] to it[1] }.toMap()

        val updated = current.copy(
            primary = parseHex(map["primary"]) ?: current.primary,
            surface = parseHex(map["surface"]) ?: current.surface,
            darkPrimary = parseHex(map["darkPrimary"]) ?: current.darkPrimary,
            darkSurface = parseHex(map["darkSurface"]) ?: current.darkSurface,
            containerLight = parseHex(map["containerLight"]) ?: current.containerLight,
            containerDark = parseHex(map["containerDark"]) ?: current.containerDark,
        )
        themeFile.parentFile?.mkdirs()
        return try {
            themeFile.writeText(updated.toMarkdown())
            ExecutionResult.ok("Theme updated:\n${updated.toMarkdown()}")
        } catch (e: Exception) {
            ErrorCollector.report(e, "SelfExecutor.theme")
            ExecutionResult.fail("Write error: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
    }

    private fun parseHex(s: String?): Long? = s?.removePrefix("#")?.toLongOrNull(16)?.let { 0xFF000000 or it }
}

data class AgentTheme(
    val primary: Long = 0xFF165DFF,
    val surface: Long = 0xFFFFFFFF,
    val darkPrimary: Long = 0xFF6AA1FF,
    val darkSurface: Long = 0xFF272E3B,
    val containerLight: Long = 0xFFE8F3FF,
    val containerDark: Long = 0xFF4E5969
) {
    fun toMarkdown(): String = """
# Agent 主题配色

> Agent 可自由修改以下色值。填写十六进制颜色码（如 `#165DFF`）。

## 浅色模式
| 角色 | 色值 | 说明 |
|------|------|------|
| primary | `#${primary.toString(16).takeLast(6).uppercase()}` | 主色（按钮、链接、强调） |
| surface | `#${surface.toString(16).takeLast(6).uppercase()}` | 背景色 |
| containerLight | `#${containerLight.toString(16).takeLast(6).uppercase()}` | 卡片/容器背景 |

## 深色模式
| 角色 | 色值 | 说明 |
|------|------|------|
| darkPrimary | `#${darkPrimary.toString(16).takeLast(6).uppercase()}` | 主色（暗色模式） |
| darkSurface | `#${darkSurface.toString(16).takeLast(6).uppercase()}` | 背景色（暗色模式） |
| containerDark | `#${containerDark.toString(16).takeLast(6).uppercase()}` | 卡片/容器背景（暗色模式） |

## 配色建议
- 浅色模式 primary 建议亮度 40-60%，饱和度 60-90%
- 深色模式 primary 建议亮度 50-70%，饱和度 50-80%
- surface/container 建议使用低饱和度中性色
- 参考: https://m3.material.io/theme-builder

## 修改命令
```
self.theme primary=#FF6B35 surface=#FFF8F0
```
""".trimIndent()

    companion object {
        fun fromFile(f: java.io.File): AgentTheme {
            if (!f.exists()) return AgentTheme()
            val text = try { f.readText() } catch (_: Exception) { "" }
            fun readHex(key: String, default: Long): Long {
                val m = Regex("$key.*?#([0-9A-Fa-f]{6})").find(text)
                return m?.groupValues?.get(1)?.toLongOrNull(16)?.let { 0xFF000000 or it } ?: default
            }
            return AgentTheme(
                primary = readHex("primary", 0xFF165DFF),
                surface = readHex("surface", 0xFFFFFFFF),
                darkPrimary = readHex("darkPrimary", 0xFF6AA1FF),
                darkSurface = readHex("darkSurface", 0xFF272E3B),
                containerLight = readHex("containerLight", 0xFFE8F3FF),
                containerDark = readHex("containerDark", 0xFF4E5969),
            )
        }
    }
}
