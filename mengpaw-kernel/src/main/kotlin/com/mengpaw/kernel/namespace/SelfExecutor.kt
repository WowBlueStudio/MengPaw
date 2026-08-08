// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.namespace

import com.mengpaw.kernel.cli.CommandSearch
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.serialization.json.put

/**
 * Self-introspection namespace - allows Agent to query its own state.
 * Uses command dispatch maps instead of nested when-blocks for clarity.
 *
 * ACP/Trigger/MCP 子命令组已拆至 [SelfAcpCommands]/[SelfTriggerCommands]/
 * [SelfMcpCommands]; AcpHolder/AgentTheme 拆至独立文件 (400 行文件拆分)。
 */
object SelfExecutor {
    /** Set by AgentEngine during buildPipeline so self.tools can enumerate available commands. */
    @Volatile var commandRegistry: com.mengpaw.kernel.cli.CommandRegistry? = null

    private val acpCommands = SelfAcpCommands()
    private val triggerCommands = SelfTriggerCommands()
    private val mcpCommands = SelfMcpCommands()

    val commands = mapOf(
        "status" to ::status,
        "config" to ::config,
        "stats" to ::stats,
        "version" to ::version,
        "avatar" to ::avatar,
        "theme" to ::theme,
        "mcp" to mcpCommands::mcp,
        "trigger" to triggerCommands::triggerCmd,
        "acp" to acpCommands::acpCmd,
        "tools" to ::toolsCmd,
        "ports" to ::portsCmd,
        "search" to ::searchCmd,
        "search.stats" to ::searchStatsCmd,
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

    /**
     * 读取/写入 Agent 配置 — 真实持久化到配置目录 (DataPaths.CONFIG)。
     * 用法:
     *   self.config                → 列出全部配置文件
     *   self.config <key>          → 读取指定键
     *   self.config <key> <value>  → 写入键值（持久化到 配置/<key>）
     *
     * 修复: 原实现返回硬编码假配置且写入即丢 — Agent 误信配置已修改（还可能被缓存）。
     * 现改为读写真实配置文件，与 SettingsViewModel (theme_mode/background_mode 等) 同一存储。
     */
    private suspend fun config(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val configDir = java.io.File(com.mengpaw.kernel.DataPaths.CONFIG)
        if (args.isEmpty()) {
            val files = configDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
            if (files.isEmpty()) {
                return ExecutionResult.ok("(无配置文件)\n\n用法: self.config <key> <value> 创建配置项")
            }
            return ExecutionResult.ok(buildString {
                appendLine("配置文件目录: ${configDir.absolutePath}")
                files.forEach { f ->
                    val value = f.readText().trim().replace("\n", "\\n")
                    appendLine("${f.name} = $value")
                }
                appendLine("\n用法: self.config <key> [<value>] — 读/写配置项")
            })
        }
        val key = args[0]
        // 路径安全校验 — 仅允许单段文件名，防目录穿越
        if (key.isBlank() || key.length > 64 || key.contains("/") || key.contains("\\") ||
            key == "." || key == ".." || key.startsWith(".")) {
            return ExecutionResult.fail("非法配置键: '$key'（仅允许普通文件名）", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
        if (args.size == 1) {
            val f = java.io.File(configDir, key)
            if (!f.exists()) {
                return ExecutionResult.fail("配置不存在: $key（写入: self.config $key <value>）", errorCode = ErrorCodes.ERR_NOT_FOUND)
            }
            return ExecutionResult.ok("$key = ${f.readText().trim()}")
        }
        val value = args.drop(1).joinToString(" ")
        return try {
            configDir.mkdirs()
            java.io.File(configDir, key).writeText(value)
            ExecutionResult.ok("已写入配置 $key = $value（持久化于 ${configDir.absolutePath}/$key）")
        } catch (e: Exception) {
            ErrorCollector.report(e, "SelfExecutor.config")
            ExecutionResult.fail("写入失败: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
    }

    /**
     * self.stats — 内存/CPU/线程 + P2-12(自检报告) token 消耗与耗时统计。
     * 子命令: self.stats events [--tail N] — 命令/LLM 事件流 (JSON lines, events.jsonl)。
     */
    private suspend fun stats(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.firstOrNull() == "events") {
            val tailIdx = args.indexOf("--tail")
            val n = if (tailIdx >= 0 && tailIdx + 1 < args.size) {
                args[tailIdx + 1].toIntOrNull()?.coerceIn(1, 500) ?: 20
            } else 20
            val lines = com.mengpaw.kernel.Telemetry.tailEvents(n)
            if (lines.isEmpty()) {
                return ExecutionResult.ok(buildString {
                    appendLine("(事件流为空 — 命令与 LLM 请求事件将追加到 ${com.mengpaw.kernel.Telemetry.eventsFile.absolutePath})")
                    appendLine("用法: self.stats events [--tail N]")
                })
            }
            return ExecutionResult.ok(buildString {
                appendLine("事件流 (尾部 ${lines.size} 条):")
                lines.forEach { appendLine("  $it") }
                appendLine("文件: ${com.mengpaw.kernel.Telemetry.eventsFile.absolutePath}")
            })
        }
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val totalMb = runtime.totalMemory() / (1024 * 1024)
        return ExecutionResult.ok(
            "Memory: ${usedMb}MB / ${totalMb}MB\n" +
            "Processors: ${runtime.availableProcessors()}\n" +
            "Threads: ${Thread.activeCount()}\n" +
            com.mengpaw.kernel.Telemetry.tokenSummary() + "\n" +
            com.mengpaw.kernel.Telemetry.latencySummary()
        )
    }

    private suspend fun version(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok("檬爪 v${com.mengpaw.kernel.AgentEngine.CORE_VERSION}")
    }

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

    // ── Ports (网络端口/接口一览) ────────────────────────────────────

    /** 端口/网络接口一览 — 单一事实源 Ports.kt。Usage: self.ports [--json] */
    private suspend fun portsCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val jsonMode = args.contains("--json")
        if (jsonMode) {
            // 结构化输出 — 供 Agent 程序化消费
            val json = kotlinx.serialization.json.buildJsonObject {
                put("listening", kotlinx.serialization.json.buildJsonArray {
                    com.mengpaw.kernel.ports.Ports.ALL.filter { it.direction == com.mengpaw.kernel.ports.Ports.Direction.INBOUND }.forEach {
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("port", it.port); put("protocol", it.protocol); put("owner", it.owner); put("purpose", it.purpose)
                        })
                    }
                })
                put("outboundDefaults", kotlinx.serialization.json.buildJsonArray {
                    com.mengpaw.kernel.ports.Ports.ALL.filter { it.direction == com.mengpaw.kernel.ports.Ports.Direction.OUTBOUND }.forEach {
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("port", it.port); put("protocol", it.protocol); put("owner", it.owner)
                            put("purpose", it.purpose); put("configurable", it.configurable); put("configVia", it.configVia)
                        })
                    }
                })
            }
            return ExecutionResult.ok(json.toString())
        }
        return ExecutionResult.ok(com.mengpaw.kernel.ports.Ports.describe("zh"))
    }

    // ── Command Search (BM25 + 同义词表) ──────────────────────────────

    /** 用自然语言搜索命令. Usage: self.search <query> [--top N]
     *  无参时显示索引统计. */
    private suspend fun searchCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        // 解析 --top 参数
        val topArg = args.indexOfFirst { it == "--top" }
        val topK = if (topArg >= 0 && topArg + 1 < args.size) {
            args[topArg + 1].toIntOrNull()?.coerceIn(1, 20) ?: 5
        } else 5
        val query = if (topArg >= 0) args.take(topArg).joinToString(" ")
                    else args.joinToString(" ")
        if (query.isBlank()) return ExecutionResult.ok(
            buildString {
                appendLine(com.mengpaw.kernel.cli.CommandSearch.stats())
                appendLine()
                appendLine("用法: self.search <自然语言描述> [--top N]")
                appendLine("示例: self.search 网页搜索")
                appendLine("用自然语言描述你需要的操作, 返回最匹配的 top-5 命令.")
                appendLine("完整命令列表: self.tools [ns]")
            }
        )
        // FIX(自检报告 P0-1): 可用性过滤 — 索引含静态种子 (BuiltinCommandIndex) 与
        // 动态注册条目, 种子条目在插件未安装/停用时命中但执行必败。按真实注册表过滤,
        // 只返回当前可执行命令; 过滤后不足时从候选中补足 (保证 topK 稳定性)。
        val registry = commandRegistry
        val availableSet = registry?.list()?.toSet()
        val all = com.mengpaw.kernel.cli.CommandSearch.search(query, topK * 3)
        val results = if (availableSet == null) all.take(topK)
                      else all.filter { it.fullName in availableSet }.take(topK)
        return ExecutionResult.ok(com.mengpaw.kernel.cli.CommandSearch.formatResults(results, query, markActive = true))
    }

    /** 查看命令索引统计. Usage: self.search.stats */
    private suspend fun searchStatsCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(com.mengpaw.kernel.cli.CommandSearch.stats())
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
            containerLight = parseHex(map["containerLight"]) ?: current.containerLight,
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
