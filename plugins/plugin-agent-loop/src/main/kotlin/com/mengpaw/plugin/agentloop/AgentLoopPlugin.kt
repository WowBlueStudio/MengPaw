// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.agentloop

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.cli.ErrorCodes
import com.mengpaw.core.plugin.Plugin
import com.mengpaw.core.plugin.PluginMetadata
import com.mengpaw.core.plugin.PluginType
import java.security.MessageDigest

/**
 * Agent Loop Plugin — controlled iterative execution with guardrails.
 *
 * ## Features
 * - Iteration control (default 100; 0 = unlimited, discouraged)
 * - Repeat detection: sliding-window hash + fuzzy similarity + cycle detection
 * - 3-level intervention escalation: REPLAN → EXPLORE → FORCE-DONE
 * - Completion check reminders
 * - Immutable audit ledger (last 2000 entries)
 *
 * ## Design references (MIT/Apache-2.0 licensed projects, architecture only):
 * - deer-flow LoopDetectionMiddleware: sliding-window hashing pattern
 * - LoopBuster: multi-strategy detection + circuit breaker
 * - behavioral-loop-detection: 3-level escalation ladder
 */
class AgentLoopPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "agent-loop-plugin", name = "Agent Loop", version = "0.1.0",
        type = PluginType.NATIVE, author = "MengPaw",
        description = "受控迭代执行+重复检测+干预规则+完成检查。主页扩展功能下激活。",
        permissions = emptyList(), minCoreVersion = "0.2.3",
        commands = listOf("loop.start", "loop.status", "loop.stop", "loop.config", "loop.ledger")
    )
    override val commands: Map<String, com.mengpaw.core.plugin.CommandHandler> = mapOf(
        "start" to ::start, "status" to ::status, "stop" to ::stop,
        "config" to ::config, "ledger" to ::ledger,
    )

    // ── State ───────────────────────────────────────────────────────────
    private var loopActive = false
    private var currentIteration = 0
    private var maxIterations = 100
    private var repeatDetector = RepeatDetector()
    private var ledger = LoopLedger()
    private var interventionRules = InterventionRules()
    private var completionChecker = CompletionChecker()

    // ── loop.start ──────────────────────────────────────────────────────

    private suspend fun start(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: loop.start <task>\n迭代执行任务，受循环护栏保护。",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)
        if (loopActive) return ExecutionResult.fail("Loop 已在运行中。使用 loop.stop 停止。", errorCode = ErrorCodes.ERR_INTERNAL)

        val task = args.joinToString(" ")
        loopActive = true; currentIteration = 0
        repeatDetector.reset(); ledger.clear()

        ledger.record("START", task, "maxIterations=$maxIterations rules=${interventionRules.summary()}")
        return ExecutionResult.ok("""
## Loop 已启动
- 任务: $task
- 最大迭代: ${if (maxIterations == 0) "无限制 ⚠️" else "$maxIterations 次"}
- 重复检测: 滑动窗口(${repeatDetector.windowSize}) + 模糊匹配(灵敏度 ${repeatDetector.sensitivity})
- 干预规则: ${interventionRules.summary()}

Agent 将在每次迭代中执行任务，直到完成或触发干预。
使用 loop.status 查看进度, loop.stop 停止。
""".trimIndent())
    }

    // ── loop.status ─────────────────────────────────────────────────────

    private suspend fun status(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (!loopActive) return ExecutionResult.ok("Loop 未运行。使用 loop.start <task> 启动。")
        return ExecutionResult.ok("""
## Loop 状态
- 状态: ${if (loopActive) "▶ 运行中" else "⏸ 已停止"}
- 当前迭代: $currentIteration / ${if (maxIterations == 0) "∞" else maxIterations.toString()}
- 重复检测: ${repeatDetector.status()}
- 最近干预: ${interventionRules.lastAction.ifBlank { "无" }}
- 账本: ${ledger.size()} 条记录
""".trimIndent())
    }

    // ── loop.stop ───────────────────────────────────────────────────────

    private suspend fun stop(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (!loopActive) return ExecutionResult.ok("Loop 未在运行。")
        loopActive = false; ledger.record("STOP", "手动停止", "iteration=$currentIteration")
        return ExecutionResult.ok("Loop 已停止。共执行 $currentIteration 次迭代。")
    }

    // ── loop.config ─────────────────────────────────────────────────────

    private suspend fun config(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) {
            return ExecutionResult.ok("""
## Loop 配置
- 最大迭代: $maxIterations (0=无限制)
- 重复检测窗口: ${repeatDetector.windowSize}
- 模糊匹配灵敏度: ${repeatDetector.sensitivity}%
- 干预规则:
  Level 1 (${interventionRules.level1Threshold}x): ${interventionRules.level1Action}
  Level 2 (${interventionRules.level2Threshold}x): ${interventionRules.level2Action}
  Level 3 (${interventionRules.level3Threshold}x): ${interventionRules.level3Action}
- 完成检查: ${if (completionChecker.enabled) "已启用" else "已禁用"}

用法:
  loop.config iterations=100      — 设置最大迭代次数
  loop.config window=20            — 设置重复检测窗口大小
  loop.config sensitivity=80       — 设置模糊匹配灵敏度 (50-100)
  loop.config rule.1=REPLAN        — 设置干预规则 (REPLAN/EXPLORE/FORCE-DONE/SKIP)
  loop.config rule.1.threshold=3   — 设置触发阈值
""".trimIndent())
        }

        args.forEach { arg ->
            val parts = arg.split("=", limit = 2)
            if (parts.size < 2) return@forEach
            when (parts[0]) {
                "iterations" -> { maxIterations = parts[1].toIntOrNull()?.coerceIn(0, 10000) ?: maxIterations }
                "window" -> { repeatDetector.windowSize = parts[1].toIntOrNull()?.coerceIn(5, 100) ?: repeatDetector.windowSize }
                "sensitivity" -> { repeatDetector.sensitivity = parts[1].toIntOrNull()?.coerceIn(50, 100) ?: repeatDetector.sensitivity }
                "rule.1" -> { interventionRules.level1Action = parts[1] }
                "rule.2" -> { interventionRules.level2Action = parts[1] }
                "rule.3" -> { interventionRules.level3Action = parts[1] }
                "rule.1.threshold" -> { interventionRules.level1Threshold = parts[1].toIntOrNull()?.coerceIn(2, 20) ?: interventionRules.level1Threshold }
                "rule.2.threshold" -> { interventionRules.level2Threshold = parts[1].toIntOrNull()?.coerceIn(2, 20) ?: interventionRules.level2Threshold }
                "rule.3.threshold" -> { interventionRules.level3Threshold = parts[1].toIntOrNull()?.coerceIn(2, 20) ?: interventionRules.level3Threshold }
                "completion" -> { completionChecker.enabled = parts[1] == "on" }
            }
        }
        ledger.record("CONFIG", args.joinToString(" "), "")
        return config(emptyList(), ctx)
    }

    // ── loop.ledger ─────────────────────────────────────────────────────

    private suspend fun ledger(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val n = args.firstOrNull()?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        val entries = ledger.last(n)
        if (entries.isEmpty()) return ExecutionResult.ok("(账本为空)")
        return ExecutionResult.ok(buildString {
            appendLine("## Loop 审计账本 (最近 $n 条)")
            appendLine("| 时间 | 事件 | 详情 |")
            appendLine("|------|------|------|")
            entries.forEach { e -> appendLine("| ${e.time} | ${e.event} | ${e.detail.take(60)} |") }
        })
    }

    // ── Public API (called by AgentEngine or middleware) ─────────────────

    /** Called before each iteration. Returns null if allowed, or an intervention message. */
    fun beforeIteration(command: String): String? {
        if (!loopActive) return null
        currentIteration++
        ledger.record("ITER", "$currentIteration", command.take(100))

        // Check max iterations
        if (maxIterations > 0 && currentIteration > maxIterations) {
            loopActive = false
            ledger.record("BREACH", "MAX_ITERATIONS", "$maxIterations")
            return "已达到最大迭代次数 ($maxIterations)。任务未完成。"
        }

        // Repeat detection
        val (repeats, confidence) = repeatDetector.check(command)
        if (repeats > 0) {
            ledger.record("REPEAT", "x$repeats", "confidence=$confidence%")
        }

        // Intervention escalation
        return interventionRules.check(repeats, confidence)
    }

    /** Called after each iteration. Checks if task is complete. */
    fun afterIteration(result: String): String? {
        if (!loopActive) return null
        ledger.record("RESULT", "ok", result.take(100))
        return completionChecker.check(result)
    }

    fun isActive(): Boolean = loopActive
    fun iterationCount(): Int = currentIteration
}

// ═══════════════════════════════════════════════════════════════════════
// Repeat Detector — sliding window + fuzzy match
// ═══════════════════════════════════════════════════════════════════════

class RepeatDetector {
    var windowSize = 20
    var sensitivity = 80 // 50-100, higher = stricter matching

    private val window = ArrayDeque<String>(windowSize)
    private var repeatCount = 0
    private var lastConfidence = 0

    fun reset() { window.clear(); repeatCount = 0; lastConfidence = 0 }

    /** Returns (repeatCount, confidence%). */
    fun check(command: String): Pair<Int, Int> {
        val normalized = normalize(command)
        // Exact hash match
        val hash = sha256(normalized).take(16)
        if (window.any { it == hash }) {
            repeatCount++
        } else {
            // Fuzzy match against recent window
            var similar = false
            for (entry in window.takeLast(8)) {
                if (fuzzyMatch(normalized, entry, sensitivity)) {
                    similar = true; break
                }
            }
            if (similar) repeatCount++
            else { repeatCount = 0 }
        }

        window.addLast(hash)
        if (window.size > windowSize) window.removeFirst()

        lastConfidence = if (repeatCount > 0) (repeatCount * 100 / windowSize).coerceIn(0, 100) else 0
        return repeatCount to lastConfidence
    }

    fun status(): String =
        if (repeatCount >= 3) "⚠️ 检测到重复 ($repeatCount 次, 置信度 $lastConfidence%)"
        else if (repeatCount > 0) "轻微重复 ($repeatCount 次)"
        else "正常"

    private fun normalize(cmd: String): String = cmd
        .replace(Regex("[0-9a-f]{8,}"), "<UUID>")
        .replace(Regex("\\d{10,13}"), "<TIMESTAMP>")
        .replace(Regex("\\d+"), "<N>")
        .replace(Regex("\\s+"), " ").trim().lowercase()

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun fuzzyMatch(a: String, b: String, threshold: Int): Boolean {
        if (a == b) return true
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return true
        var matches = 0
        for (i in 0 until minOf(a.length, b.length)) { if (a[i] == b[i]) matches++ }
        return (matches * 100 / maxLen) >= threshold
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Intervention Rules — 3-level escalation ladder
// ═══════════════════════════════════════════════════════════════════════

class InterventionRules {
    var level1Threshold = 3
    var level2Threshold = 5
    var level3Threshold = 8
    var level1Action = "REPLAN"
    var level2Action = "EXPLORE"
    var level3Action = "FORCE-DONE"
    var lastAction = ""

    fun check(repeatCount: Int, confidence: Int): String? {
        if (repeatCount < level1Threshold) return null

        return when {
            repeatCount >= level3Threshold -> {
                lastAction = level3Action
                when (level3Action) {
                    "SKIP" -> null
                    "FORCE-DONE" -> "已达到最大重复次数 ($repeatCount/${level3Threshold})。请以 Final Answer 结束当前任务，报告已完成和未完成的部分。"
                    "EXPLORE" -> "检测到严重循环 ($repeatCount/${level3Threshold})。请尝试完全不同的工具或策略。"
                    else -> "重复操作已达上限。停止当前方向，尝试替代方案。"
                }
            }
            repeatCount >= level2Threshold -> {
                lastAction = level2Action
                when (level2Action) {
                    "SKIP" -> null
                    "FORCE-DONE" -> "检测到持续循环 ($repeatCount/${level2Threshold})。请考虑结束任务并汇报结果。"
                    "EXPLORE" -> "检测到循环 ($repeatCount/${level2Threshold})。当前方法可能无效，请换一种方式。"
                    else -> "看起来你在重复相同的操作。请重新规划或换用不同工具。"
                }
            }
            repeatCount >= level1Threshold -> {
                lastAction = level1Action
                when (level1Action) {
                    "SKIP" -> null
                    "REPLAN" -> "检测到轻微重复 ($repeatCount/${level1Threshold})。请暂停并重新制定执行计划。"
                    else -> "注意: 相似操作重复了 $repeatCount 次。确认是否继续。"
                }
            }
            else -> null
        }
    }

    fun summary(): String = "L1($level1Threshold:$level1Action) L2($level2Threshold:$level2Action) L3($level3Threshold:$level3Action)"
}

// ═══════════════════════════════════════════════════════════════════════
// Completion Checker
// ═══════════════════════════════════════════════════════════════════════

class CompletionChecker {
    var enabled = true
    private var checkCountdown = 15

    fun check(result: String): String? {
        if (!enabled) return null
        checkCountdown--
        if (checkCountdown <= 0) {
            checkCountdown = 15
            // Check if result contains indicators of incompleteness
            val incomplete = result.length < 20 ||
                result.contains("error", ignoreCase = true) && !result.contains("fixed", ignoreCase = true) ||
                result.contains("failed", ignoreCase = true) && !result.contains("retry", ignoreCase = true)
            if (incomplete) {
                return "任务可能未完成。请确认: 1) 是否已调用必要工具? 2) 结果是否符合预期? 3) 是否需要 Final Answer?"
            }
            // Remind to use tools if only text output
            if (!result.contains("Command:") && !result.contains("Final Answer") && result.length < 100) {
                return "提示: 如果任务未完成，请调用工具继续操作。如果已完成，请输出 Final Answer。"
            }
        }
        return null
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Loop Ledger — immutable audit trail
// ═══════════════════════════════════════════════════════════════════════

class LoopLedger(private val maxSize: Int = 2000) {
    data class Entry(val time: String, val event: String, val detail: String)

    private val entries = mutableListOf<Entry>()

    fun record(event: String, detail: String, extra: String = "") {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        entries.add(Entry(time, event, if (extra.isNotBlank()) "$detail | $extra" else detail))
        if (entries.size > maxSize) entries.removeAt(0)
    }

    fun last(n: Int): List<Entry> = entries.takeLast(n)
    fun size(): Int = entries.size
    fun clear() { entries.clear() }
}

// ═══════════════════════════════════════════════════════════════════════
// Mission Monitor — track Worker + Verifier sub-Agent status
// ═══════════════════════════════════════════════════════════════════════

data class WorkerMonitor(
    val id: String, val task: String, val status: String, // pending|running|done|failed|verified
    val progress: Int = 0,  // 0-100
    val output: String = ""
)

data class VerifierMonitor(
    var totalWorkers: Int = 0,
    var verified: Int = 0,
    var failed: Int = 0,
    var currentCheck: String = ""
) {
    val summary: String get() = when {
        totalWorkers == 0 -> "等待 Worker..."
        verified + failed >= totalWorkers -> if (failed == 0) "✅ 全部通过 ($verified/$totalWorkers)" else "⚠️ $verified/$totalWorkers 通过 ($failed 失败)"
        else -> "验证中... $verified/$totalWorkers"
    }
}

object MissionMonitor {
    val workers = mutableListOf<WorkerMonitor>()
    val verifier = VerifierMonitor()
    var missionActive = false
    var missionGoal = ""

    fun reset() { workers.clear(); verifier.totalWorkers = 0; verifier.verified = 0; verifier.failed = 0; missionActive = false; missionGoal = "" }

    fun start(goal: String, workerCount: Int) {
        reset(); missionActive = true; missionGoal = goal
        verifier.totalWorkers = workerCount
    }

    fun updateWorker(id: String, task: String, status: String, progress: Int = 0, output: String = "") {
        val existing = workers.indexOfFirst { it.id == id }
        val w = WorkerMonitor(id, task, status, progress, output)
        if (existing >= 0) workers[existing] = w else workers.add(w)
        if (status == "verified") verifier.verified++
        if (status == "failed") verifier.failed++
    }

    fun stop() { missionActive = false }
}
