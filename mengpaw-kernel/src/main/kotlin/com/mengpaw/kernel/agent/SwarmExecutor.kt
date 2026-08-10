// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.AgentEngine
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

    /** AgentEngine 注入 (AgentEngine.init 调用) — swarm.run 触发火种执行。 */
    @Volatile var agentEngine: AgentEngine? = null

    fun attachEngine(engine: AgentEngine) { agentEngine = engine }

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "status" to ::statusCmd,
        "run" to ::runCmd
    )

    /**
     * 火种模式主动触发 (v0.35.5, 用户定案) — Agent 自主把任务切成
     * 拆解→并行 Worker→验证→合成; 返回完整火种报告。
     */
    private suspend fun runCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val task = args.joinToString(" ").trim()
        if (task.isBlank()) return ExecutionResult.fail("用法: swarm.run <任务> — 火种模式并行拆解执行")
        val engine = agentEngine ?: return ExecutionResult.fail("火种引擎未就绪 (AgentEngine 未注入)")
        return try {
            ExecutionResult.ok(engine.runWithSwarm(task))
        } catch (e: Exception) {
            ExecutionResult.fail("火种执行失败: ${e.message ?: "未知错误"}")
        }
    }

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
