package com.mengpaw.core.memory

import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * A single memory entry stored as a markdown file.
 */
data class MemoryEntry(
    val id: String,
    val title: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val tags: List<String> = emptyList()
) {
    /** Convert to markdown document string. */
    fun toMarkdown(): String = buildString {
        appendLine("# $title")
        appendLine()
        if (tags.isNotEmpty()) {
            appendLine("> Tags: ${tags.joinToString(", ")}")
            appendLine()
        }
        appendLine("> Created: ${formatTime(createdAt)}")
        appendLine("> Updated: ${formatTime(updatedAt)}")
        appendLine()
        appendLine("---")
        appendLine()
        append(content.trimStart())
        appendLine()
    }

    companion object {
        /** Parse a markdown file into a MemoryEntry. */
        fun fromMarkdown(file: File): MemoryEntry? {
            if (!file.exists()) return null
            val text = file.readText()
            val id = file.nameWithoutExtension

            // Extract title from first H1
            val title = text.lines().firstOrNull { it.startsWith("# ") }
                ?.removePrefix("# ")?.trim() ?: id

            // Extract tags from blockquote
            val tags = text.lines().firstOrNull { it.startsWith("> Tags:") }
                ?.removePrefix("> Tags:")?.trim()
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

            // Remove metadata header (content after first ---)
            val contentStart = text.indexOf("\n---\n")
            val content = if (contentStart >= 0) {
                text.substring(contentStart + 5).trim()
            } else text

            return MemoryEntry(
                id = id,
                title = title,
                content = content,
                tags = tags,
                createdAt = file.lastModified(),
                updatedAt = file.lastModified()
            )
        }

        private fun formatTime(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
    }
}

/**
 * Manages persistent agent memories as editable markdown files.
 * Each memory = one .md file in the memories directory.
 *
 * Directory structure:
 *   /data/data/com.mengpaw/files/memories/
 *     ├── meeting-notes.md
 *     ├── user-preferences.md
 *     └── task-checklist.md
 */
class MemoryManager(private val storageDir: String = "/data/data/com.mengpaw/files/memories") {
    private var cache: List<MemoryEntry>? = null
    private var cacheTime = 0L
    private val cacheTtlMs = 2000L
    private fun cachedList(): List<MemoryEntry> {
        val now = System.currentTimeMillis()
        if (cache == null || now - cacheTime > cacheTtlMs) {
            cache = dir.listFiles { f -> f.extension == "md" }
                ?.mapNotNull { MemoryEntry.fromMarkdown(it) }
                ?.sortedByDescending { it.updatedAt }
                ?: emptyList()
            cacheTime = now
        }
        return cache!!
    }

    private val dir: File get() = File(storageDir).also { it.mkdirs() }

    /** List all memories, newest first. */
    fun list(): List<MemoryEntry> = cachedList()

    /** Get a single memory by id (filename without .md). */
    fun get(id: String): MemoryEntry? {
        val file = File(dir, "$id.md")
        return MemoryEntry.fromMarkdown(file)
    }

    /** Create or overwrite a memory. */
    fun save(id: String, title: String, content: String, tags: List<String> = emptyList()): MemoryEntry {
        val entry = MemoryEntry(
            id = id,
            title = title,
            content = content,
            tags = tags
        )
        val file = File(dir, "$id.md")
        file.writeText(entry.toMarkdown()); cache = null
        return entry
    }

    /** Update an existing memory's content. */
    fun update(id: String, content: String): MemoryEntry? {
        val existing = get(id) ?: return null
        return save(id, existing.title, content, existing.tags)
    }

    /** Delete a memory. */
    fun delete(id: String): Boolean {
        val ok = File(dir, "$id.md").delete()
        cache = null
        return ok
    }

    /** Search memories by keyword in title or content. */
    fun search(query: String): List<MemoryEntry> {
        val q = query.lowercase()
        return list().filter { entry ->
            entry.title.lowercase().contains(q) ||
            entry.content.lowercase().contains(q) ||
            entry.tags.any { it.lowercase().contains(q) }
        }
    }

    /** Get total count and rough storage size. */
    fun stats(): Pair<Int, Long> {
        val files = dir.listFiles { f -> f.extension == "md" } ?: emptyArray()
        return files.size to files.sumOf { it.length() }
    }

    /** Install default memory documents (CLI reference, tool index, etc.). */
    fun installDefaults() {
        if (get("cli-reference") == null) {
            save("cli-reference", "MengPaw CLI 参考文档", CliReference.content,
                listOf("reference", "cli", "documentation"))
        }
        // Tool index — condensed version for quick Agent lookup
        if (get("tool-index") == null) {
            save("tool-index", "Tool 索引", generateToolIndex(),
                listOf("reference", "tools", "index"))
        }
    }

    /** Generate a condensed tool/CLI index for Agent preload. */
    fun generateToolIndex(): String = """
# Tool 索引

> 所有可用 CLI 工具的快速索引。需要详细信息时加载对应文档。
> 完整参考: memory.read cli-reference

## fs — 文件系统 (8)
cat / ls / write / rm / mkdir / cp / mv / stat

## ui — 界面操控 (7)
click / swipe / input / screenshot / back / home / wait

## proc — 进程管理 (3)
ps / kill / exec

## net — 网络 (3)
curl / get / post

## self — 内省 (4)
status / config / stats / version

## memory — 记忆系统 (6)
ls / read / write / rm / search / stats

## skill — Skill 系统 (4)
ls / run / enable / disable

**共 35 个命令 · 7 个命名空间**
""".trimIndent()
}
