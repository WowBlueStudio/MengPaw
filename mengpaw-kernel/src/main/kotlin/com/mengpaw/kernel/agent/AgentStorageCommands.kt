// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import kotlinx.serialization.json.*

/**
 * agent.* 存储/清理/梦境/浏览器工具命令执行器 (拆自 AgentExecutor,
 * 400 行文件拆分)。storageReport 经 [formatSize]/[dirSize] 顶层函数共享。
 */
internal class AgentStorageCommands {

    /** Resolve the effective agent name, falling back to default. */
    private fun agentName(ctx: ExecutionContext) = ctx.agentName ?: "agent"

    /** Clean workspace — screenshots, temp files, old checkpoints. Use --dry-run to preview. */
    internal suspend fun cleanup(args: List<String>, ctx: ExecutionContext): ExecutionResult {
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
    internal suspend fun storageReport(args: List<String>, ctx: ExecutionContext): ExecutionResult {
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

    /** Dream mode: organize memories, archive, summarize — never delete. */
    internal suspend fun dream(args: List<String>, ctx: ExecutionContext): ExecutionResult {
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
    internal suspend fun browserTools(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(AgentDocManager.Companion.BROWSER_TOOLS_MD)
    }
}
