// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.namespace.NotifyBus
import kotlinx.serialization.json.*

/**
 * Built-in agent.* CLI commands — Agent document management.
 *
 * memory.* 18 条命令已拆至 [AgentMemoryExecutor] (2026-08-01, ≥50KB 文件拆分),
 * 经 `+ memoryExecutor.commands` 合并注册, 命令名与命名空间不变。
 */
class AgentExecutor(private val docManager: AgentDocManager) {

    /** Resolve the effective agent name, falling back to default. */
    private fun agentName(ctx: ExecutionContext) = ctx.agentName ?: "agent"

    /** 记忆三轨执行器 (memory.* 命令, 拆自本类)。 */
    private val memoryExecutor = AgentMemoryExecutor()

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        // 注意: commands 键集是 CLI.md agent 表 (AgentDocManager.registeredAgentCommands) 的
        // 唯一来源 — 新增/重命名命令后 CLI.md 自动反映, 无需双份维护 (发现性铁律 v0.31.0)。
        "docs" to ::docs,
        "cli" to ::cli,
        "modes" to ::modes,
        "boost" to ::boost,
        "boost.delete" to ::boostDelete,
        "profile" to ::profile,
        "soul" to ::soul,
        "audit" to ::audit,
        "browser-tools" to ::browserTools,
        "dream" to ::dream,
        "cleanup" to ::cleanup,
        "storage" to ::storageReport,
        "sessions" to ::sessions,
        "session.delete" to ::sessionDelete,
        "session.archive" to ::sessionArchive,
        "session.current" to ::sessionCurrent,
        "read" to ::readFile,
        "write" to ::writeFile,
        "ls" to ::listFiles,
        "rm" to ::deleteFile,
        "mkdir" to ::makeDir,
        "output" to ::output
    ) + memoryExecutor.commands

    init {
        // 注入注册键集供 CLI.md agent 表动态生成 — 新增命令自动入手册
        docManager.registeredAgentCommands = commands.keys.sorted()
    }

    private suspend fun docs(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val docs = docManager.listDocs()
        return ExecutionResult.ok("Agent 文档 (${docs.size}):\n" + docs.joinToString("\n") { "  • $it" })
    }

    /** Delete boost.md — Agent has completed initialization. */
    private suspend fun boostDelete(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val agent = agentName(ctx)
        val ok = AgentDocs.deleteBoost(agent)
        return if (ok) ExecutionResult.ok("boost.md 已删除。你已完成初始化，不再需要引导文件。")
        else ExecutionResult.ok("boost.md 不存在——你早已完成初始化。")
    }

    /** Slash command mode menu — 8 execution modes (modes.md, template-provided). */
    private suspend fun modes(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val modesDoc = docManager.getDoc(AgentDocType.MODES)
        if (modesDoc.isBlank()) return ExecutionResult.ok("(modes.md 不存在)")
        return ExecutionResult.ok(modesDoc)
    }

    /** First-run bootstrap ritual — guide the Agent through initial setup. */
    private suspend fun boost(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val boostDoc = docManager.getDoc(AgentDocType.BOOST)
        if (boostDoc.isBlank()) return ExecutionResult.ok(buildString {
            appendLine("(boost.md 不存在 — 你已完成初始化)")
            appendLine()
            appendLine("这说明你已经不是第一次醒来了。你的 soul/profile/memory 已经建立。")
            appendLine("继续做你该做的事。")
        })
        return ExecutionResult.ok(boostDoc)
    }

    private suspend fun cli(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        // FIX(自检报告 P0-2): 惰性生成 — CLI.md 缺失或插件活跃数变化时自动重生成,
        // 插件 install/disable 后下次查询即拿到最新表, 不依赖任何插件变更钩子。
        val pm = docManager.pluginManager
        if (pm != null && docManager.cliDocStale(pm)) docManager.regenerateCliDoc(pm)
        val cliDoc = docManager.getDoc(AgentDocType.CLI)
        return ExecutionResult.ok(cliDoc.ifEmpty { "(CLI.md 尚未生成 — 插件系统未就绪)" })
    }

    private suspend fun profile(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(docManager.getDoc(AgentDocType.PROFILE))
    }

    private suspend fun soul(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(docManager.getDoc(AgentDocType.SOUL))
    }

    /** Clean workspace — screenshots, temp files, old checkpoints. Use --dry-run to preview. */
    private suspend fun cleanup(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val dryRun = args.contains("--dry-run")
        val agent = agentName(ctx)
        if (dryRun) {
            val report = buildCleanupPreview(agent)
            return ExecutionResult.ok(report)
        }
        val result = com.mengpaw.kernel.agent.DreamEngine.cleanupWorkspace()
        return ExecutionResult.ok(buildString {
            appendLine("清理完成:")
            appendLine("- 删除文件: ${result.filesDeleted} 个")
            appendLine("- 释放空间: ${result.bytesFreed / 1024}KB")
            if (result.dirsCleaned.isNotEmpty()) {
                appendLine("- 清理目录: ${result.dirsCleaned.joinToString(", ")}")
            }
            appendLine()
            appendLine("提示: 用 agent.storage 查看当前空间占用。用 agent.cleanup --dry-run 预览。")
        })
    }

    /** Build a preview of what cleanup would remove. */
    private fun buildCleanupPreview(agent: String): String {
        val sb = StringBuilder("## 可清理内容预览\n\n")
        // Screenshots
        val ssDir = java.io.File(com.mengpaw.kernel.DataPaths.SCREENSHOTS)
        if (ssDir.exists()) {
            val ssCount = ssDir.listFiles()?.count { it.isFile } ?: 0
            val ssSize = ssDir.listFiles()?.sumOf { it.length() } ?: 0L
            if (ssCount > 0) sb.appendLine("- 📸 截图: $ssCount 个文件 (${ssSize / 1024}KB)")
        }
        // Checkpoints
        val cpDir = java.io.File(com.mengpaw.kernel.DataPaths.CHECKPOINTS)
        if (cpDir.exists()) {
            val cpCount = cpDir.listFiles()?.count { it.isFile } ?: 0
            val cpSize = cpDir.listFiles()?.sumOf { it.length() } ?: 0L
            if (cpCount > 0) sb.appendLine("- 💾 检查点: $cpCount 个文件 (${cpSize / 1024}KB)")
        }
        // Mid-term memory older than 7 days
        val midDates = AgentDocs.listMidTermDates(agent)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val oldDates = midDates.filter { it < today }
        if (oldDates.isNotEmpty()) sb.appendLine("- 📝 中期记忆: ${oldDates.size} 个历史分片 (用 agent.memory.mid.delete 逐个清理)")
        // Temp files
        val tmpDir = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "tmp")
        if (tmpDir.exists()) {
            val tmpCount = tmpDir.listFiles()?.count { it.isFile } ?: 0
            if (tmpCount > 0) sb.appendLine("- 🗑 临时文件: $tmpCount 个")
        }
        return if (sb.length < 50) "无需要清理的内容。" else sb.toString().trimEnd()
    }

    /** Comprehensive storage usage report — per-directory breakdown. */
    private suspend fun storageReport(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val agent = agentName(ctx)
        return ExecutionResult.ok(buildString {
            appendLine("## 存储用量")
            appendLine()
            // Agent workspace
            val wsDir = java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, agent)
            val wsSize = dirSize(wsDir)
            appendLine("### 工作区 (Agent文档/$agent)")
            appendLine("- 总大小: ${formatSize(wsSize)}")
            // Detail: memory
            val memDir = java.io.File(com.mengpaw.kernel.DataPaths.midTermMemoryDir(agent))
            if (memDir.exists()) {
                val memFiles = memDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.name } ?: emptyList()
                val memSize = memFiles.sumOf { it.length() }
                appendLine("  - memory/ ${memFiles.size} 个分片 (${formatSize(memSize)})")
            }
            val ltm = java.io.File(com.mengpaw.kernel.DataPaths.longTermMemoryFile(agent))
            if (ltm.exists()) appendLine("  - 长期记忆: ${formatSize(ltm.length())} (${AgentDocs.countLongTermEntries(agent)} 条)")
            // Plugins
            val plugDir = java.io.File(com.mengpaw.kernel.DataPaths.PLUGIN_CACHE)
            val plugSize = dirSize(plugDir)
            appendLine()
            appendLine("### 插件仓库")
            appendLine("- 总大小: ${formatSize(plugSize)}")
            // Sessions
            val sessionsDir = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "sessions")
            val sessionCount = if (sessionsDir.exists()) sessionsDir.listFiles()?.count { it.extension == "json" } ?: 0 else 0
            val sessionSize = if (sessionsDir.exists()) sessionsDir.listFiles()?.sumOf { it.length() } ?: 0L else 0L
            val historyFile = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "session_history.json")
            val historyRecords = try {
                if (historyFile.exists()) Json.parseToJsonElement(historyFile.readText()).jsonArray.size else 0
            } catch (_: Exception) { 0 }
            appendLine()
            appendLine("### 会话")
            appendLine("- 消息文件: $sessionCount 个 (${formatSize(sessionSize)})")
            appendLine("- 历史索引: $historyRecords 条")
            // Screenshots
            val ssDir = java.io.File(com.mengpaw.kernel.DataPaths.SCREENSHOTS)
            if (ssDir.exists()) {
                val ssCount = ssDir.listFiles()?.count { it.isFile } ?: 0
                val ssSize = ssDir.listFiles()?.sumOf { it.length() } ?: 0L
                appendLine()
                appendLine("### 截图")
                appendLine("- $ssCount 个文件 (${formatSize(ssSize)})")
                appendLine("- 清理: agent.cleanup")
            }
        })
    }

    private fun dirSize(dir: java.io.File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.listFiles()?.forEach {
            total += if (it.isDirectory) dirSize(it) else it.length()
        }
        return total
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))}MB"
    }

    /** Dream mode: organize memories, archive, summarize — never delete. */
    private suspend fun dream(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        // 梦境提供者 SPI: 第三方可实现 DreamProvider 注册覆盖 (plugin-dream 为内置默认)
        val provider = com.mengpaw.kernel.agent.DreamProviderRegistry.active()
        if (args.isNotEmpty() && args[0] == "stats") {
            return ExecutionResult.ok(provider.stats())
        }
        if (args.isNotEmpty() && args[0] == "history") {
            return ExecutionResult.ok(provider.history())
        }
        // FIX: sessionId → agentName; sessionId 是 UUID 而 DreamEngine 需要 agent 目录名
        val result = provider.organize(agentName(ctx))
        val cleanup = com.mengpaw.kernel.agent.DreamEngine.cleanupWorkspace()
        return ExecutionResult.ok("""
梦境完成:
- 翻阅记忆: ${result.memoriesReviewed} 个中期分片
- 已备份: ${result.archived} 个 → memory/backup/ (30天后自动清理)
- 提炼产物: {date}_dream.md (工作区根)
- 清理临时文件: ${cleanup.filesDeleted} 个, 释放 ${cleanup.bytesFreed / 1024}KB
""".trimIndent())
    }

    /** Browser plugin development capabilities. */
    private suspend fun browserTools(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(AgentDocManager.Companion.BROWSER_TOOLS_MD)
    }

    /** Cross-session index: search saved session history by keyword. */
    private suspend fun sessions(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val file = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "session_history.json")
        if (!file.exists()) return ExecutionResult.ok("(no saved sessions)")

        val raw = try { file.readText() } catch (_: Exception) {
            return ExecutionResult.fail("Cannot read session history", errorCode = ErrorCodes.ERR_INTERNAL)
        }
        if (raw.isBlank()) return ExecutionResult.ok("(no sessions)")

        val keyword = args.firstOrNull()?.lowercase()
        val limit = args.getOrNull(1)?.toIntOrNull() ?: 20
        val results = mutableListOf<String>()

        try {
            val arr = Json.parseToJsonElement(raw).jsonArray
            for (el in arr) {
                val obj = el.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content ?: ""
                val preview = obj["preview"]?.jsonPrimitive?.content ?: ""
                val ts = obj["timestamp"]?.jsonPrimitive?.long ?: 0L
                val count = obj["messageCount"]?.jsonPrimitive?.int ?: 0
                val agent = obj["agentName"]?.jsonPrimitive?.content ?: ""
                val compacted = obj["compacted"]?.jsonPrimitive?.boolean ?: false

                if (keyword != null && !title.lowercase().contains(keyword) && !preview.lowercase().contains(keyword)) continue

                val date = if (ts > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(ts)) else "?"
                val tag = if (compacted) "[压]" else ""
                results.add("$tag[$agent] $date · $title$tag · ${count}msgs")
            }
        } catch (_: Exception) {
            return ExecutionResult.fail("Session history file is corrupted. 💡 下次启动会自动重置。当前数据可能已备份为 session_history.json.bak。", errorCode = ErrorCodes.ERR_INTERNAL)
        }

        if (results.isEmpty()) return ExecutionResult.ok(
            if (keyword != null) "(no sessions matching '$keyword')" else "(no sessions)"
        )

        val header = if (keyword != null) "会话索引 (匹配 '$keyword', ${results.size}):\n" else "会话索引 (${results.size}):\n"
        return ExecutionResult.ok(header + results.take(limit).joinToString("\n") { "  • $it" })
    }

    /** agent.session.delete <id> — delete a session record and its message file. */
    private suspend fun sessionDelete(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: agent.session.delete <id>\n💡 使用 agent.sessions 先查看会话列表获取 ID。", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val id = args[0]
        val historyFile = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "session_history.json")
        if (!historyFile.exists()) return ExecutionResult.fail("No session history file found.", errorCode = ErrorCodes.ERR_NOT_FOUND)

        return try {
            val raw = historyFile.readText()
            val arr = Json.parseToJsonElement(raw).jsonArray
            val filtered = arr.filter { it.jsonObject["id"]?.jsonPrimitive?.content != id }
            if (filtered.size == arr.size) return ExecutionResult.fail("Session not found: $id", errorCode = ErrorCodes.ERR_NOT_FOUND)

            val newJson = JsonArray(filtered)
            // 标准原子写: tmp 写好后再覆盖 — rename 失败不丢原文件 (旧写法先删后搬)
            val tmp = java.io.File(historyFile.parentFile, "session_history.json.tmp")
            tmp.writeText(newJson.toString())
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), historyFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } finally {
                if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }
            }

            // Delete session message file
            val sessionFile = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "sessions/$id.json")
            if (sessionFile.exists()) { sessionFile.delete() }

            ExecutionResult.ok("会话 $id 已删除。")
        } catch (e: Exception) {
            ExecutionResult.fail("删除失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    /** agent.session.archive <id> [--unarchive] — toggle archive state of a session. */
    private suspend fun sessionArchive(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: agent.session.archive <id> [--unarchive]\n💡 归档后会话从默认视图隐藏，可用 --unarchive 恢复。", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val id = args[0]
        val unarchive = args.contains("--unarchive")
        val historyFile = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "session_history.json")
        if (!historyFile.exists()) return ExecutionResult.fail("No session history file found.", errorCode = ErrorCodes.ERR_NOT_FOUND)

        return try {
            val raw = historyFile.readText()
            val arr = Json.parseToJsonElement(raw).jsonArray
            var found = false
            val updated = arr.map { el ->
                val obj = el.jsonObject.toMutableMap()
                if (obj["id"]?.jsonPrimitive?.content == id) {
                    found = true
                    obj.toMutableMap().apply { put("archived", JsonPrimitive(!unarchive)) }
                } else obj
            }
            if (!found) return ExecutionResult.fail("Session not found: $id", errorCode = ErrorCodes.ERR_NOT_FOUND)

            val newJson = JsonArray(updated.map { JsonObject(it) })
            // 标准原子写: Files.move 覆盖 (Windows 上 File.renameTo 无法覆盖已存在目标)
            val tmp = java.io.File(historyFile.parentFile, "session_history.json.tmp")
            tmp.writeText(newJson.toString())
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), historyFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } finally {
                if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }
            }

            ExecutionResult.ok(if (unarchive) "会话 $id 已取消归档。" else "会话 $id 已归档。")
        } catch (e: Exception) {
            ExecutionResult.fail("归档失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    /** agent.session.current — show current session info. */
    private suspend fun sessionCurrent(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val file = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "current_session.json")
        if (!file.exists()) return ExecutionResult.ok("(no active session)")

        return try {
            val text = file.readText()
            var sid = "(legacy)"
            var msgCount = 0
            try {
                val wrapper = Json.parseToJsonElement(text).jsonObject
                sid = wrapper["sessionId"]?.jsonPrimitive?.content ?: "(legacy)"
                msgCount = wrapper["messages"]?.jsonArray?.size ?: 0
            } catch (_: Exception) {
                // Old format: plain array
                msgCount = Json.parseToJsonElement(text).jsonArray.size
            }
            ExecutionResult.ok("当前会话: $sid\n消息数: $msgCount\n最后修改: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(file.lastModified()))}")
        } catch (e: Exception) {
            ExecutionResult.fail("读取失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    /** View command audit trail (security feature). */
    private suspend fun audit(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val count = args.firstOrNull()?.toIntOrNull() ?: 50
        val entries = com.mengpaw.kernel.cli.Pipeline.getGlobalAuditLog(count)
        if (entries.isEmpty()) return ExecutionResult.ok("(No audit entries)")
        return ExecutionResult.ok(entries.joinToString("\n") { e ->
            "${if (e.success) "OK" else "FAIL"} [${e.sessionId}] ${e.command}: ${e.output.take(80)}"
        })
    }

    // ── File I/O (built-in, no plugin needed) ──────────────────────

    /**
     * Paths the Agent may NEVER write to — protects APK core files, system binaries.
     * Reading from these paths is allowed (Agent needs to inspect its own config/docs).
     *
     * Strategy: deny-list, NOT allow-list. Agent can access everything except:
     * - Non-data system partitions (/system, /vendor)
     * - App private binaries outside its workspace
     */
    private val WRITE_BLOCKED_PREFIXES = listOf(
        "/system/", "/vendor/", "/product/", "/odm/",
        "/data/app/", // installed APKs
        "/data/dalvik-cache/",
        // P1 修复: 应用私有数据 (插件 AAR、会话库等) — agent 不可写
        "/data/data/", "/data/user/"
    )

    /** Paths blocked from deletion — extends write blocked with critical agent files. */
    private val RM_BLOCKED_PREFIXES = listOf(
        "/system/", "/vendor/", "/product/", "/odm/",
        "/data/app/", "/data/dalvik-cache/",
        // P1 修复: 应用私有数据 — agent 不可删
        "/data/data/", "/data/user/",
        // Core agent files — use dedicated commands (agent.memory.rm, etc.) instead
        // soul.md, profile.md, agents.md are deletable via agent.rm (Agent owns them)
    )

    /**
     * Resolve path with traversal protection (canonical path resolves ../ and symlinks).
     * 相对路径以 Agent 工作区 {AGENTS}/{agent}/ 为基准 — 提示词教的工作区相对语义
     * (agent.read profile.md) 由此成为现实。
     * 前导 "/" 宽容 (FIX 自检报告 P0-2): Agent 常按 Unix 习惯写 "/Agent文档/MengPaw",
     * Android 上被 File.isAbsolute 当根目录绝对路径 → 必然不存在。字面解析失败时,
     * 去掉前导 / 按工作区重试; 真实系统绝对路径 (/data/...) 存在时不受影响。
     */
    private fun resolvePath(raw: String, agent: String): java.io.File? {
        val trimmed = raw.trim()
        val file = if (java.io.File(trimmed).isAbsolute) java.io.File(trimmed)
                   else java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, "$agent/$trimmed")
        val canonical = try { file.canonicalFile } catch (_: Exception) { null }
        if ((canonical == null || !canonical.exists()) && trimmed.startsWith("/") && trimmed.length > 1) {
            val retry = java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, "$agent/${trimmed.trimStart('/')}")
            val retryCanonical = try { retry.canonicalFile } catch (_: Exception) { null }
            if (retryCanonical != null && retryCanonical.exists()) return retryCanonical
        }
        return canonical
    }

    /** agent.read <path> — read any file (no restrictions beyond filesystem). */
    private suspend fun readFile(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("用法: agent.read <path>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val path = args.joinToString(" ")
        val file = resolvePath(path, agentName(ctx))
            ?: return ExecutionResult.fail("路径无效: $path", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        if (!file.exists()) return ExecutionResult.fail(
            // FIX(自检报告 P0-2): 输出解析后的真实路径 — Agent 盲试时能据此修正基准
            "文件不存在: $path (解析为 ${file.absolutePath})\n" +
            "工作区根: ${com.mengpaw.kernel.DataPaths.AGENTS}/${agentName(ctx)} — 相对路径以它为基准",
            errorCode = ErrorCodes.ERR_NOT_FOUND)
        if (file.isDirectory) {
            val listing = file.listFiles()?.take(50)?.joinToString("\n") { f ->
                "${if (f.isDirectory) "📁" else "📄"} ${f.name} (${if (f.isFile) "${f.length()}B" else "-"})"
            } ?: "(空目录)"
            return ExecutionResult.ok("$path:\n$listing")
        }
        return try {
            val content = file.readText().take(100_000)
            ExecutionResult.ok(content)
        } catch (e: Exception) {
            ExecutionResult.fail("读取失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    /** agent.ls <path> — list files in a directory. Defaults to workspace root. */
    private suspend fun listFiles(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val agent = agentName(ctx)
        val defaultPath = "${com.mengpaw.kernel.DataPaths.AGENTS}/$agent"
        val path = if (args.isEmpty()) defaultPath else args.joinToString(" ")
        val dir = resolvePath(path, agent) ?: return ExecutionResult.fail("路径无效: $path")
        if (!dir.exists()) return ExecutionResult.fail(
            "路径不存在: $path (解析为 ${dir.absolutePath})\n" +
            "工作区根: ${com.mengpaw.kernel.DataPaths.AGENTS}/$agent — 相对路径以它为基准")
        if (!dir.isDirectory) {
            // Single file — show its info
            return ExecutionResult.ok("📄 ${dir.name} — ${dir.length()}B — ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(dir.lastModified()))}")
        }
        val files = dir.listFiles()?.sortedBy { if (it.isDirectory) 0 else 1 } ?: emptyList()
        if (files.isEmpty()) return ExecutionResult.ok("$path/\n(空目录)")
        return ExecutionResult.ok(buildString {
            appendLine("$path/")
            files.forEach { f ->
                val icon = if (f.isDirectory) "📁" else "📄"
                val size = if (f.isFile) " ${formatSize(f.length())}" else ""
                val date = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(f.lastModified()))
                appendLine("  $icon ${f.name}$size · $date")
            }
            appendLine()
            appendLine("${files.size} 个项目")
        })
    }

    /** agent.rm <path> — delete a file or empty directory. Blocked on system paths. Requires --force for files. */
    private suspend fun deleteFile(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val flags = args.filter { it.startsWith("--") }
        val pathArgs = args.filter { !it.startsWith("--") }
        val force = flags.contains("--force")
        if (pathArgs.isEmpty()) return ExecutionResult.fail(
            "用法: agent.rm <path> [--force]\n" +
            "删除文件或空目录。文件需要 --force 确认（不可逆）。系统路径受保护。\n" +
            "先预览: agent.ls <path> 查看要删的文件。"
        )
        val path = pathArgs.joinToString(" ")
        val file = resolvePath(path, agentName(ctx)) ?: return ExecutionResult.fail("路径无效: $path")
        val canonical = file.absolutePath
        if (!file.exists()) return ExecutionResult.fail("文件不存在: $path")
        if (file.isDirectory && (file.listFiles()?.isNotEmpty() == true)) {
            return ExecutionResult.fail("目录非空: $path (${file.listFiles()?.size ?: 0} 个项目)。\n请先删除目录中的文件，或用 agent.memory.mid.delete 删除中期记忆分片。")
        }
        if (file.isFile && !force) {
            val isOutput = canonical.startsWith(com.mengpaw.kernel.DataPaths.OUTPUT)
            return ExecutionResult.fail(buildString {
                appendLine("⚠️ 即将永久删除文件: $path (${formatSize(file.length())})")
                appendLine()
                appendLine("此操作不可逆。确认删除请执行: agent.rm $path --force")
                if (isOutput) {
                    appendLine()
                    appendLine("⚠️ 此文件在输出目录中，删除后用户将无法在文件管理器中找到它。")
                    appendLine("建议先确认用户是否需要此文件再删除。")
                }
            })
        }
        if (RM_BLOCKED_PREFIXES.any { canonical.startsWith(it) }) {
            return ExecutionResult.fail("禁止删除系统/应用目录: $path")
        }
        return try {
            val size = file.length()
            val ok = file.delete()
            if (ok) ExecutionResult.ok("已删除: $path (${formatSize(size)})\n\n如需恢复，从孪生设备同步: twin.sync")
            else ExecutionResult.fail("删除失败: $path")
        } catch (e: Exception) {
            ExecutionResult.fail("删除异常: ${e.message}")
        }
    }

    /** agent.mkdir <path> — create a directory. */
    private suspend fun makeDir(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("用法: agent.mkdir <path>")
        val path = args.joinToString(" ")
        val dir = resolvePath(path, agentName(ctx)) ?: return ExecutionResult.fail("路径无效: $path")
        if (dir.exists()) return ExecutionResult.fail("已存在: $path")
        return try {
            dir.mkdirs()
            ExecutionResult.ok("已创建目录: $path")
        } catch (e: Exception) {
            ExecutionResult.fail("创建失败: ${e.message}")
        }
    }

    /** agent.output — 显示输出目录。HTML/MD/PDF 等用户文档写出到此目录，文件管理器可访问。 */
    private suspend fun output(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val dir = java.io.File(com.mengpaw.kernel.DataPaths.OUTPUT)
        if (!dir.exists()) dir.mkdirs()
        val files = dir.listFiles()?.sortedBy { if (it.isDirectory) 0 else 1 } ?: emptyList()
        return ExecutionResult.ok(buildString {
            appendLine("📂 输出目录: ${com.mengpaw.kernel.DataPaths.OUTPUT}")
            appendLine("   状态: ${if (dir.canWrite()) "可写" else "⚠️ 不可写"}")
            val totalSize = files.sumOf { it.length() }
            if (totalSize > 0) appendLine("   总大小: ${formatSize(totalSize)}")
            appendLine()
            if (files.isEmpty()) {
                appendLine("(空)")
            } else {
                files.forEach { f ->
                    val icon = if (f.isDirectory) "📁" else "📄"
                    val size = if (f.isFile) " ${formatSize(f.length())}" else ""
                    appendLine("  $icon ${f.name}$size")
                }
                appendLine()
                appendLine("${files.size} 个项目")
            }
            appendLine()
            appendLine("写文件: agent.write <路径> <内容>")
            appendLine("示例: agent.write ${com.mengpaw.kernel.DataPaths.OUTPUT}/report.html <html内容>")
        })
    }

    /** agent.write <path> <content> — write file. Blocked on system/app paths only. */
    private suspend fun writeFile(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("用法: agent.write <path> <content>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val path = args.first()
        val content = args.drop(1).joinToString(" ")
        val file = resolvePath(path, agentName(ctx))
            ?: return ExecutionResult.fail("路径无效: $path", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        // Deny-list check: block writes to system/app partitions
        val canonical = file.path
        if (WRITE_BLOCKED_PREFIXES.any { canonical.startsWith(it) }) {
            return ExecutionResult.fail("禁止写入系统/应用目录: $path", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }
        return try {
            file.parentFile?.mkdirs()
            // 标准原子写: tmp 写好后 Files.move(REPLACE_EXISTING) 覆盖 — 失败保留原文件
            val tmp = java.io.File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(content)
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } finally {
                if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }
            }
            // 仅对系统提示词中的三个缓存文件触发精确失效
            val wsRoot = "${com.mengpaw.kernel.DataPaths.AGENTS}/${agentName(ctx)}"
            val cachedDocs = setOf("agents.md", "soul.md", "memory/memory.md")
            if (canonical.startsWith(wsRoot) && cachedDocs.any { canonical.endsWith("/$it") }) {
                com.mengpaw.kernel.agent.AgentDocs.notifyDocChanged(agentName(ctx), canonical)
            }
            val isOutput = canonical.startsWith(com.mengpaw.kernel.DataPaths.OUTPUT)
            val msg = buildString {
                append("已写入: $path (${content.length} 字符)")
                if (isOutput) {
                    append("\n\n📱 用户可在文件管理器的 ${com.mengpaw.kernel.DataPaths.OUTPUT} 找到此文件")
                }
            }
            if (isOutput) {
                try {
                    NotifyBus.message("📄 Agent 生成了文件: ${file.name} (${formatSize(file.length())})")
                } catch (_: Exception) {}
            }
            ExecutionResult.ok(msg)
        } catch (e: Exception) {
            val isOutput = canonical.startsWith(com.mengpaw.kernel.DataPaths.OUTPUT)
            val errMsg = buildString {
                append("写入失败: ${e.message}")
                if (isOutput) append("\n输出目录: ${com.mengpaw.kernel.DataPaths.OUTPUT}")
            }
            ExecutionResult.fail(errMsg, errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }
}
