package com.mengpaw.core.agent

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.cli.ErrorCodes

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
        "storage" to ::storageReport
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
        val result = com.mengpaw.core.agent.DreamEngine.cleanupWorkspace()
        return ExecutionResult.ok("清理完成: ${result.filesDeleted} 个文件, 释放 ${result.bytesFreed / 1024}KB\n${result.dirsCleaned.joinToString("\n") { "  • $it" }}")
    }

    /** Storage usage report. */
    private suspend fun storageReport(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(com.mengpaw.core.agent.DreamEngine.storageReport())
    }

    /** Dream mode: organize memories, archive, summarize — never delete. */
    private suspend fun dream(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isNotEmpty() && args[0] == "stats") {
            return ExecutionResult.ok(com.mengpaw.core.agent.DreamEngine.dreamStats())
        }
        if (args.isNotEmpty() && args[0] == "history") {
            return ExecutionResult.ok(com.mengpaw.core.agent.DreamEngine.dreamHistory())
        }
        val result = com.mengpaw.core.agent.DreamEngine.dream(ctx.sessionId)
        val cleanup = com.mengpaw.core.agent.DreamEngine.cleanupWorkspace()
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

    /** View command audit trail (security feature). */
    private suspend fun audit(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val count = args.firstOrNull()?.toIntOrNull() ?: 50
        val entries = com.mengpaw.core.cli.Pipeline.getGlobalAuditLog(count)
        if (entries.isEmpty()) return ExecutionResult.ok("(No audit entries)")
        return ExecutionResult.ok(entries.joinToString("\n") { e ->
            "${if (e.success) "OK" else "FAIL"} [${e.sessionId}] ${e.command}: ${e.output.take(80)}"
        })
    }
}
