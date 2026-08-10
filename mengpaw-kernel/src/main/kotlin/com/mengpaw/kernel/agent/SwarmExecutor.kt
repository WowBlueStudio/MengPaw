// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * swarm 命名空间命令 (v0.35.5) — 火种模式运行时状态查询。
 * 注册: PipelineManager.buildPipeline → registerNamespace("swarm", ...)。
 */
object SwarmExecutor {

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "status" to ::statusCmd
    )

    private suspend fun statusCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val rt = SwarmRuntimeStore.load()
            ?: return ExecutionResult.ok(
                "暂无进行中/未完成的火种任务。任务评分 8+ 自动进入火种模式, 或使用 /Swarm /Fleet。")
        val running = System.currentTimeMillis() - rt.updatedAt < SwarmRuntimeStore.STALE_AFTER_MS
        val sb = buildString {
            appendLine("## 火种运行时状态${if (running) "" else " (上次未完成, 已停滞)"}")
            appendLine("任务: ${rt.task.take(160)}")
            appendLine("开始: ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(rt.startedAt))}" +
                " · 步数: ${rt.consumedSteps}/${rt.totalSteps}")
            appendLine("子任务 (${rt.subtasks.size}):")
            rt.subtasks.forEach { s ->
                appendLine("  ${statusIcon(s.status)} ${s.id} [${s.status}] ${s.description.take(60)}" +
                    if (s.retries > 0) " · 重试 ${s.retries}" else "")
            }
        }
        return ExecutionResult.ok(sb.toString().trimEnd())
    }

    private fun statusIcon(status: String): String = when (status) {
        "VERIFIED" -> "✅"
        "DONE" -> "👍"
        "FAILED" -> "❌"
        "SKIPPED" -> "⏭️"
        "RUNNING" -> "🔄"
        else -> "⬜"
    }
}
