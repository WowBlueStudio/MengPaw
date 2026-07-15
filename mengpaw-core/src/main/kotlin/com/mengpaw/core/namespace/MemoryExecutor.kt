package com.mengpaw.core.namespace

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.memory.MemoryManager

/**
 * Memory namespace — Agent can read/write/search its own memories.
 *
 * Commands:
 *   memory ls              — list all memories
 *   memory read <id>       — read a memory
 *   memory write <id> <content> — create/update a memory
 *   memory rm <id>         — delete a memory
 *   memory search <query>  — search memories
 *   memory stats           — memory statistics
 */
object MemoryExecutor {
    private val manager = MemoryManager()

    val commands = mapOf(
        "ls" to ::ls,
        "read" to ::read,
        "write" to ::write,
        "rm" to ::rm,
        "search" to ::search,
        "stats" to ::stats
    )

    private suspend fun ls(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val memories = manager.list()
        if (memories.isEmpty()) return ExecutionResult.ok("(no memories)")
        return ExecutionResult.ok(memories.joinToString("\n") { m ->
            val tagStr = if (m.tags.isNotEmpty()) " [${m.tags.joinToString(",")}]" else ""
            "• ${m.id} — ${m.title}$tagStr (${m.content.length} chars)"
        })
    }

    private suspend fun read(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: memory read <id>")
        val memory = manager.get(args[0])
            ?: return ExecutionResult.fail("Memory not found: ${args[0]}")
        return ExecutionResult.ok("""
            # ${memory.title}
            ${if (memory.tags.isNotEmpty()) "Tags: ${memory.tags.joinToString(", ")}" else ""}
            ---
            ${memory.content}
        """.trimIndent())
    }

    private suspend fun write(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: memory write <id> <content>")
        val id = args[0]
        val content = args.drop(1).joinToString(" ")
        val title = id.replace("-", " ").replace("_", " ")
            .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
        manager.save(id, title, content)
        return ExecutionResult.ok("Memory saved: $id")
    }

    private suspend fun rm(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: memory rm <id>")
        return if (manager.delete(args[0])) ExecutionResult.ok("Deleted: ${args[0]}")
        else ExecutionResult.fail("Not found: ${args[0]}")
    }

    private suspend fun search(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: memory search <query>")
        val results = manager.search(args.joinToString(" "))
        if (results.isEmpty()) return ExecutionResult.ok("(no results)")
        return ExecutionResult.ok(results.joinToString("\n") { m ->
            "• ${m.id} — ${m.title}"
        })
    }

    private suspend fun stats(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val (count, size) = manager.stats()
        return ExecutionResult.ok("Memories: $count | Size: ${size / 1024}KB")
    }
}
