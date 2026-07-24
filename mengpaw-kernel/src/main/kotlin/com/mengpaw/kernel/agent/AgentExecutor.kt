// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import kotlinx.serialization.json.*

/**
 * Built-in agent.* CLI commands — Agent document management.
 */
class AgentExecutor(private val docManager: AgentDocManager) {
    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "docs" to ::docs,
        "memory" to ::memory,
        "memory.record" to ::memoryRecord,
        "cli" to ::cli,
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
        "write" to ::writeFile
    )

    private suspend fun docs(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val docs = docManager.listDocs()
        return ExecutionResult.ok("Agent 文档 (${docs.size}):\n" + docs.joinToString("\n") { "  • $it" })
    }

    private suspend fun memory(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return if (args.isEmpty()) {
            // Show index only (passive query — no full content)
            ExecutionResult.ok(docManager.getMemoryIndex())
        } else {
            val results = docManager.searchMemory(args.joinToString(" "))
            if (results.isEmpty()) ExecutionResult.ok("(无匹配记忆)")
            else ExecutionResult.ok(results.joinToString("\n---\n") { "[${it.id}] ${it.title}\n${it.content.take(300)}" })
        }
    }

    private suspend fun memoryRecord(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: agent.memory.record <content>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val content = args.joinToString(" ")
        val entry = MemoryRecord(
            id = "mem-${System.currentTimeMillis().toString().takeLast(6)}",
            date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()),
            title = content.take(60),
            keywords = content.split(" ").filter { it.length > 1 }.take(5),
            content = content
        )
        docManager.updateMemory(entry)
        return ExecutionResult.ok("Memory recorded: ${entry.id}")
    }

    private suspend fun cli(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val cliDoc = docManager.getDoc(AgentDocType.CLI)
        return ExecutionResult.ok(cliDoc.ifEmpty { "(CLI.md not yet generated)" })
    }

    private suspend fun profile(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(docManager.getDoc(AgentDocType.PROFILE))
    }

    private suspend fun soul(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(docManager.getDoc(AgentDocType.SOUL))
    }

    /** Clean workspace and temp files. */
    private suspend fun cleanup(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val result = com.mengpaw.kernel.agent.DreamEngine.cleanupWorkspace()
        return ExecutionResult.ok("清理完成: ${result.filesDeleted} 个文件, 释放 ${result.bytesFreed / 1024}KB\n${result.dirsCleaned.joinToString("\n") { "  • $it" }}")
    }

    /** Storage usage report. */
    private suspend fun storageReport(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val base = com.mengpaw.kernel.agent.DreamEngine.storageReport()
        // Append session file stats
        val sessionsDir = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "sessions")
        val sessionCount = if (sessionsDir.exists()) sessionsDir.listFiles()?.count { it.extension == "json" } ?: 0 else 0
        val sessionSize = if (sessionsDir.exists()) sessionsDir.listFiles()?.sumOf { it.length() } ?: 0L else 0L
        val historyFile = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "session_history.json")
        val historyRecords = try {
            if (historyFile.exists()) Json.parseToJsonElement(historyFile.readText()).jsonArray.size else 0
        } catch (_: Exception) { 0 }
        return ExecutionResult.ok("$base\n会话: $sessionCount 个文件 (${sessionSize / 1024}KB), $historyRecords 条索引记录")
    }

    /** Dream mode: organize memories, archive, summarize — never delete. */
    private suspend fun dream(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isNotEmpty() && args[0] == "stats") {
            return ExecutionResult.ok(com.mengpaw.kernel.agent.DreamEngine.dreamStats())
        }
        if (args.isNotEmpty() && args[0] == "history") {
            return ExecutionResult.ok(com.mengpaw.kernel.agent.DreamEngine.dreamHistory())
        }
        // FIX: sessionId → agentName; sessionId 是 UUID 而 DreamEngine 需要 agent 目录名
        val result = com.mengpaw.kernel.agent.DreamEngine.dream(ctx.agentName ?: "agent-001")
        val cleanup = com.mengpaw.kernel.agent.DreamEngine.cleanupWorkspace()
        return ExecutionResult.ok("""
梦境完成:
- 翻阅记忆: ${result.memoriesReviewed} 条
- 自动标签: ${result.tagsAdded} 个
- 交叉链接: ${result.linksFound} 组
- 归档(30天+): ${result.archived} 条 → Memory.archive.md (原文永久保留)
- 生成摘要: ${result.summarized} 条
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
            // Atomic write updated history
            val tmp = java.io.File(historyFile.parentFile, "session_history.json.tmp")
            tmp.writeText(newJson.toString())
            tmp.renameTo(historyFile)
            if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }

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
            val tmp = java.io.File(historyFile.parentFile, "session_history.json.tmp")
            tmp.writeText(newJson.toString())
            tmp.renameTo(historyFile)
            if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }

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
        "/data/dalvik-cache/"
    )

    /** Resolve path with traversal protection (canonical path resolves ../ and symlinks). */
    private fun resolvePath(raw: String): java.io.File? {
        val file = if (java.io.File(raw).isAbsolute) java.io.File(raw)
                   else java.io.File(com.mengpaw.kernel.DataPaths.BASE, raw)
        return try { file.canonicalFile } catch (_: Exception) { null }
    }

    /** agent.read <path> — read any file (no restrictions beyond filesystem). */
    private suspend fun readFile(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("用法: agent.read <path>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val path = args.joinToString(" ")
        val file = resolvePath(path)
            ?: return ExecutionResult.fail("路径无效: $path", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        if (!file.exists()) return ExecutionResult.fail("文件不存在: $path", errorCode = ErrorCodes.ERR_NOT_FOUND)
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

    /** agent.write <path> <content> — write file. Blocked on system/app paths only. */
    private suspend fun writeFile(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("用法: agent.write <path> <content>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val path = args.first()
        val content = args.drop(1).joinToString(" ")
        val file = resolvePath(path)
            ?: return ExecutionResult.fail("路径无效: $path", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        // Deny-list check: block writes to system/app partitions
        val canonical = file.path
        if (WRITE_BLOCKED_PREFIXES.any { canonical.startsWith(it) }) {
            return ExecutionResult.fail("禁止写入系统/应用目录: $path", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }
        return try {
            file.parentFile?.mkdirs()
            // Atomic write via tmp+rename
            val tmp = java.io.File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(content)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
            ExecutionResult.ok("已写入: $path (${content.length} 字符)")
        } catch (e: Exception) {
            ExecutionResult.fail("写入失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }
}
