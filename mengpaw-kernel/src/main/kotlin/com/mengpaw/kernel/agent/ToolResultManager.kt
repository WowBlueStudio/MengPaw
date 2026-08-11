// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.KernelLog
import java.io.File

/**
 * Manages tool result pruning and cleanup for context window efficiency.
 *
 * QwenPaw-style tool result offloading: long tool outputs (> threshold) are
 * saved to disk; only a snippet stays in context. Two-tier: recent steps get
 * higher threshold, older steps get aggressive pruning.
 */
class ToolResultManager(private val agentName: String) {

    companion object {
        /** Recent steps (<=3): generous threshold before offloading to disk. */
        const val TOOL_SNIPPET_RECENT_BYTES = 30_000
        /** Older steps: aggressive truncation, keep only snippet + file path. */
        const val TOOL_SNIPPET_OLD_BYTES = 1_200  // 上下文瘦身 (v0.26): 2000 → 1200
        /** Auto-clean tool result files older than this (days). */
        const val TOOL_RESULT_RETENTION_DAYS = 5L
    }

    /**
     * Prune a tool result if it exceeds the threshold for its step age.
     * Saves the full output to disk and returns a snippet with a file reference.
     *
     * @param commandLine the command that produced the output
     * @param rawOutput the raw tool output
     * @param step the current step number (1-based)
     * @return the pruned (or original) output string
     */
    fun pruneToolResult(commandLine: String, rawOutput: String, step: Int): String {
        val threshold = if (step <= 3) TOOL_SNIPPET_RECENT_BYTES else TOOL_SNIPPET_OLD_BYTES
        if (rawOutput.length <= threshold) return rawOutput

        val fileUuid = java.util.UUID.randomUUID().toString().take(8)
        val dir = File(com.mengpaw.kernel.DataPaths.toolResultsDir(agentName)).also { it.mkdirs() }
        val file = File(dir, "$fileUuid.txt")
        return try {
            file.writeText(rawOutput)
            val snippet = rawOutput.take(threshold / 2)
            "$snippet\n... [完整输出 (${rawOutput.length} 字节): tool_results/$fileUuid.txt — 用 cat 查阅]"
        } catch (_: Exception) {
            rawOutput.take(threshold)
        }
    }

    /** Clean up old tool result cache files. Called periodically. */
    fun cleanupOldToolResults() {
        try {
            val dir = File(com.mengpaw.kernel.DataPaths.toolResultsDir(agentName))
            if (!dir.exists()) return
            val cutoff = System.currentTimeMillis() - TOOL_RESULT_RETENTION_DAYS * 24 * 3600 * 1000L
            dir.listFiles()?.forEach { f ->
                if (f.lastModified() < cutoff) f.delete()
            }
        } catch (_: Exception) {}
    }
}
