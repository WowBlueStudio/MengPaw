// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.agentmission

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType
import java.io.File

/**
 * Agent Mission Plugin — Worker + Verifier pattern for complex tasks.
 *
 * ## Architecture
 * 1. Main Agent decomposes complex task into subtasks
 * 2. Each subtask → Worker sub-Agent with isolated context
 * 3. Verifier sub-Agent validates each Worker's output
 * 4. Results composed into final report
 *
 * ## Key properties
 * - Independent context per Worker — no session pollution
 * - Verifier gate — only verified results enter final output
 * - Mission+ extends to remote devices/frameworks via ACP
 * - Failed subtasks can be retried or escalated
 */
class AgentMissionPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "agent-mission-plugin", name = "Mission", version = "0.1.0",
        type = PluginType.NATIVE, author = "MengPaw",
        description = "Worker+Verifier多Agent协作: 自动分解+独立上下文+结果验证",
        permissions = emptyList(), minCoreVersion = "0.2.3",
        commands = listOf("mission.start", "mission.status", "mission.stop",
            "mission.retry", "mission.report")
    )
    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "start" to ::start, "status" to ::status, "stop" to ::stop,
        "retry" to ::retry, "report" to ::report,
    )

    // ── State ───────────────────────────────────────────────────────────
    private var missionActive = false
    private val workers = mutableMapOf<String, WorkerState>()
    private var verifierResult = ""
    private val missionDir: File get() = File(DataPaths.AGENTS, "missions").also { it.mkdirs() }

    data class WorkerState(
        val id: String, val subtask: String, val context: String = "", // isolated context
        var status: String = "pending", // pending|running|done|failed|verified
        var output: String = "", var verifierNote: String = ""
    )

    // ── mission.start ───────────────────────────────────────────────────

    private suspend fun start(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: mission.start <complex-task>\n自动分解为子任务，Worker 独立执行，Verifier 验证。",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)
        if (missionActive) return ExecutionResult.fail("Mission 已在运行中。", errorCode = ErrorCodes.ERR_INTERNAL)

        val task = args.joinToString(" ")
        workers.clear(); verifierResult = ""; missionActive = true

        // The Agent should:
        // 1. Call mission.start with the high-level goal
        // 2. This command returns the decomposition template
        // 3. Agent fills in subtasks via self/tool calls
        // 4. Workers execute independently

        return ExecutionResult.ok(buildString {
            appendLine("## 🎯 Mission 已启动")
            appendLine("**目标**: $task")
            appendLine()
            appendLine("### Worker + Verifier 架构")
            appendLine("```")
            appendLine("  复杂任务")
            appendLine("    │")
            appendLine("    ├─ Worker-1 ─→ 独立上下文 ─→ 执行 ─→ 输出")
            appendLine("    ├─ Worker-2 ─→ 独立上下文 ─→ 执行 ─→ 输出")
            appendLine("    ├─ Worker-N ─→ 独立上下文 ─→ 执行 ─→ 输出")
            appendLine("    │")
            appendLine("    └─ Verifier ─→ 验证所有输出 ─→ ✅/❌")
            appendLine("```")
            appendLine()
            appendLine("### 下一步")
            appendLine("1. Agent 使用 `incubator.spawn` 创建 Worker 子 Agent")
            appendLine("2. 通过 `hermes.delegate` 或 `incubator.inbox` 分派子任务")
            appendLine("3. 每个 Worker 在自己的上下文中独立执行")
            appendLine("4. Worker 完成后，Verifier 验证结果")
            appendLine("5. 使用 `mission.status` 查看进度")
            appendLine("6. Agent 整理所有通过验证的结果，输出最终报告")
            appendLine()
            appendLine("### 建议的子任务分解")
            appendLine(suggestDecomposition(task))
        })
    }

    // ── mission.status ──────────────────────────────────────────────────

    private suspend fun status(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (!missionActive) return ExecutionResult.ok("Mission 未运行。")
        val allWorkers = getActiveWorkers()
        val done = allWorkers.count { it.status == "verified" || it.status == "done" }
        val running = allWorkers.count { it.status == "running" }
        val failed = allWorkers.count { it.status == "failed" }

        return ExecutionResult.ok(buildString {
            appendLine("## Mission 状态")
            appendLine("- Workers: ${allWorkers.size} ($done done, $running running${if (failed > 0) ", $failed failed" else ""})")
            appendLine("- Verifier: ${if (verifierResult.isNotEmpty()) "已完成" else "待验证"}")
            appendLine()
            allWorkers.forEach { w ->
                val icon = when (w.status) {
                    "verified" -> "✅"; "done" -> "👍"; "running" -> "▶️"
                    "failed" -> "❌"; else -> "⬜"
                }
                appendLine("### $icon ${w.id}: ${w.subtask.take(60)}")
                if (w.output.isNotBlank()) appendLine("   输出: ${w.output.take(100)}")
                if (w.verifierNote.isNotBlank()) appendLine("   验证: ${w.verifierNote}")
            }
        })
    }

    // ── mission.stop ────────────────────────────────────────────────────

    private suspend fun stop(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        missionActive = false
        return ExecutionResult.ok("Mission 已停止。Worker 子 Agent 不受影响，可独立继续。")
    }

    // ── mission.retry ───────────────────────────────────────────────────

    private suspend fun retry(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: mission.retry <worker-id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val id = args[0]
        return ExecutionResult.ok("""
## 重试 Worker: $id
1. 检查 Worker 失败原因
2. 修正输入或上下文
3. 通过 incubator.inbox 重新分派任务
4. Worker 在新上下文中重新执行
""".trimIndent())
    }

    // ── mission.report ──────────────────────────────────────────────────

    private suspend fun report(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val allWorkers = getActiveWorkers()
        val verified = allWorkers.filter { it.status == "verified" }
        val failed = allWorkers.filter { it.status == "failed" }

        return ExecutionResult.ok(buildString {
            appendLine("## Mission 报告")
            appendLine("### ✅ 已验证 (${verified.size})")
            verified.forEach { w -> appendLine("- **${w.id}**: ${w.subtask} → ${w.output.take(80)}") }
            if (failed.isNotEmpty()) {
                appendLine()
                appendLine("### ❌ 失败 (${failed.size})")
                failed.forEach { w -> appendLine("- **${w.id}**: ${w.subtask} → ${w.verifierNote.ifBlank { "未通过验证" }}")
            }
            appendLine()
            appendLine("### 下一步")
            appendLine("- 已验证结果可直接纳入 Final Answer")
            appendLine("- 失败项使用 mission.retry <id> 重试")
            appendLine("- 所有完成后使用 mission.stop 结束")
        })
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun suggestDecomposition(task: String): String = buildString {
        val keywords = task.lowercase()
        if ("部署" in keywords || "deploy" in keywords) {
            appendLine("  1. Worker-规划: 分析部署目标和环境要求")
            appendLine("  2. Worker-准备: 检查依赖、配置、密钥")
            appendLine("  3. Worker-执行: 执行部署脚本")
            appendLine("  4. Worker-验证: 检查服务可用性")
            appendLine("  5. Verifier: 端到端测试")
        } else if ("分析" in keywords || "analysis" in keywords || "数据" in keywords) {
            appendLine("  1. Worker-采集: 收集原始数据")
            appendLine("  2. Worker-清洗: 数据清洗和标准化")
            appendLine("  3. Worker-分析: 执行分析逻辑")
            appendLine("  4. Worker-可视化: 生成图表/报告")
            appendLine("  5. Verifier: 交叉验证结果")
        } else if ("构建" in keywords || "build" in keywords || "编译" in keywords) {
            appendLine("  1. Worker-源码: 拉取最新代码")
            appendLine("  2. Worker-依赖: 安装/更新依赖")
            appendLine("  3. Worker-编译: 执行构建")
            appendLine("  4. Worker-测试: 运行测试套件")
            appendLine("  5. Verifier: 检查构建产物")
        } else {
            appendLine("  1. Worker-调研: 收集信息和需求")
            appendLine("  2. Worker-规划: 制定执行方案")
            appendLine("  3. Worker-执行: 核心操作")
            appendLine("  4. Worker-验证: 结果检查")
            appendLine("  5. Verifier: 质量把关")
        }
    }

    private fun getActiveWorkers(): List<WorkerState> {
        if (workers.isNotEmpty()) return workers.values.toList()
        // Scan incubator agents as potential workers
        val incubatorDir = File(DataPaths.INCUBATOR)
        val result = mutableListOf<WorkerState>()
        incubatorDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val name = dir.name
            val soulFile = File(dir, "Soul.md")
            val role = if (soulFile.exists()) {
                try { Regex("角色:\\s*(.+)").find(soulFile.readText())?.groupValues?.get(1)?.trim() ?: "" }
                catch (_: Exception) { "" }
            } else ""
            result.add(WorkerState(name, role, status = "pending"))
        }
        return result
    }
}
