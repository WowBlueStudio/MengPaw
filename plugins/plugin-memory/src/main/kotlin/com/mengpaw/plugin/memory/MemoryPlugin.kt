// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.memory

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.cli.ErrorCodes
import com.mengpaw.core.plugin.Plugin
import com.mengpaw.core.plugin.PluginContext
import com.mengpaw.core.plugin.PluginMetadata
import com.mengpaw.core.plugin.PluginType
import java.io.File

/**
 * Memory system plugin — provides memory.* CLI commands.
 * Markdown-based persistent memory with LRU caching.
 */
class MemoryPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "memory-plugin",
        name = "记忆系统",
        version = "1.0.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "Markdown 持久化记忆系统，含 LRU 缓存和被动索引",
        minCoreVersion = "0.2.0",
        commands = listOf("memory.ls", "memory.read", "memory.write", "memory.rm", "memory.search", "memory.stats")
    )

    override val commands: Map<String, com.mengpaw.core.plugin.CommandHandler> = mapOf(
        "ls" to ::ls, "read" to ::read, "write" to ::write,
        "rm" to ::rm, "search" to ::search, "stats" to ::stats
    )

    private var storageDir = com.mengpaw.core.DataPaths.MEMORIES
    private var cache: List<MemoryEntry>? = null
    private var cacheTime = 0L

    override suspend fun onInstall(ctx: PluginContext) {
        storageDir = "${ctx.storageDir}/memories"
        File(storageDir).mkdirs()
    }

    // ── CLI commands ──────────────────────────────────────────────────

    private suspend fun ls(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val entries = cachedList()
        if (entries.isEmpty()) return ExecutionResult.ok("(No memories)")
        return ExecutionResult.ok(entries.joinToString("\n") { "• ${it.id} — ${it.title} [${it.tags.joinToString(",")}] (${it.content.length} chars)" })
    }

    private suspend fun read(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: memory read <id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val entry = get(args[0]) ?: return ExecutionResult.fail("Memory not found: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
        return ExecutionResult.ok(entry.toMarkdown())
    }

    private suspend fun write(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: memory write <id> <content>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val id = args[0]; val content = args.drop(1).joinToString(" ")
        save(id, id.replace("-", " "), content)
        return ExecutionResult.ok("Memory saved: $id")
    }

    private suspend fun rm(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: memory rm <id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        return if (delete(args[0])) ExecutionResult.ok("Deleted: ${args[0]}")
        else ExecutionResult.fail("Not found: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
    }

    private suspend fun search(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: memory search <query>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val q = args.joinToString(" ").lowercase()
        val found = cachedList().filter { it.title.lowercase().contains(q) || it.content.lowercase().contains(q) }
        return ExecutionResult.ok(if (found.isEmpty()) "(No matches)" else found.joinToString("\n") { "• ${it.id} — ${it.title}" })
    }

    private suspend fun stats(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val (count, size) = fileStats()
        return ExecutionResult.ok("Memories: $count | Size: ${size / 1024}KB")
    }

    // ── Memory CRUD ───────────────────────────────────────────────────

    private fun cachedList(): List<MemoryEntry> {
        val now = System.currentTimeMillis()
        if (cache == null || now - cacheTime > 2000) {
            cache = dir.listFiles { f -> f.extension == "md" }
                ?.mapNotNull { MemoryEntry.fromMarkdown(it) }
                ?.sortedByDescending { it.updatedAt } ?: emptyList()
            cacheTime = now
        }
        return cache!!
    }

    private val dir: File get() = File(storageDir).also { it.mkdirs() }

    fun get(id: String): MemoryEntry? {
        val file = File(dir, "$id.md")
        return MemoryEntry.fromMarkdown(file)
    }

    fun save(id: String, title: String, content: String, tags: List<String> = emptyList()): MemoryEntry {
        val entry = MemoryEntry(id = id, title = title, content = content, tags = tags)
        File(dir, "$id.md").writeText(entry.toMarkdown()); cache = null
        return entry
    }

    fun delete(id: String): Boolean {
        val ok = File(dir, "$id.md").delete(); cache = null; return ok
    }

    private fun fileStats(): Pair<Int, Long> {
        val files = dir.listFiles { f -> f.extension == "md" } ?: emptyArray()
        return files.size to files.sumOf { it.length() }
    }
}

// ── MemoryEntry (in-plugin copy for independence) ─────────────────────

data class MemoryEntry(
    val id: String, val title: String, val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val tags: List<String> = emptyList()
) {
    fun toMarkdown(): String = buildString {
        appendLine("# $title")
        if (tags.isNotEmpty()) { appendLine(); appendLine("> Tags: ${tags.joinToString(", ")}") }
        appendLine(); appendLine("---"); appendLine(); append(content.trimStart()); appendLine()
    }

    companion object {
        fun fromMarkdown(file: File): MemoryEntry? {
            if (!file.exists()) return null
            val text = file.readText()
            val title = text.lines().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim() ?: file.nameWithoutExtension
            val tags = text.lines().firstOrNull { it.startsWith("> Tags:") }?.removePrefix("> Tags:")?.trim()?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val contentStart = text.indexOf("\n---\n")
            val content = if (contentStart >= 0) text.substring(contentStart + 5).trim() else text
            return MemoryEntry(id = file.nameWithoutExtension, title = title, content = content, tags = tags)
        }
    }
}
