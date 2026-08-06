// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.security.Sanitizer
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong

/**
 * 运行期遥测 (自检报告 P2-12): token 累计统计 + 事件流 (JSON lines)。
 *
 * 数据源:
 * - LLM 请求: AdaptiveLlmProvider 成功响应后 [recordLlm] — 累计 prompt/completion token 与最近耗时
 * - 命令执行: Pipeline.execute 每次执行 (成功/失败) [recordCommand] — 缓存命中不记录 (无真实执行)
 *
 * 事件落盘 {BASE}/events.jsonl (原子追加, 命令内容经 Sanitizer 脱敏),
 * `self.stats events [--tail N]` 读回尾部。
 */
object Telemetry {
    private val promptTokens = AtomicLong(0)
    private val completionTokens = AtomicLong(0)
    private val requestCount = AtomicLong(0)
    private val totalLatencyMs = AtomicLong(0)
    @Volatile private var lastLatencyMs = 0L

    /** 事件文件 — 测试可指向临时文件。 */
    @Volatile var eventsFile: File = File(DataPaths.BASE, "events.jsonl")

    /** 事件文件体积上限 — 超限时截断保留尾部 (防无限增长)。 */
    private const val MAX_EVENT_BYTES = 512 * 1024

    private val appendLock = Any()

    /** 记录一次 LLM 请求 (token + 耗时)。usage 缺失时传 0 — 只记耗时。 */
    fun recordLlm(prompt: Int, completion: Int, elapsedMs: Long) {
        promptTokens.addAndGet(prompt.toLong().coerceAtLeast(0))
        completionTokens.addAndGet(completion.toLong().coerceAtLeast(0))
        requestCount.incrementAndGet()
        lastLatencyMs = elapsedMs.coerceAtLeast(0)
        totalLatencyMs.addAndGet(elapsedMs.coerceAtLeast(0))
        appendEvent("llm", listOf(
            "prompt" to JsonPrimitive(prompt),
            "completion" to JsonPrimitive(completion),
            "ms" to JsonPrimitive(elapsedMs)
        ))
    }

    /** 记录一次命令执行 (成功/失败)。命令内容截断 200 字符并经 Sanitizer 脱敏。 */
    fun recordCommand(command: String, success: Boolean, elapsedMs: Long, agentName: String?) {
        appendEvent("cmd", listOf(
            "agent" to JsonPrimitive(agentName ?: "?"),
            "cmd" to JsonPrimitive(Sanitizer.sanitize(command.take(200))),
            "ok" to JsonPrimitive(success),
            "ms" to JsonPrimitive(elapsedMs)
        ))
    }

    /** 累计 token 摘要 — self.stats 输出用。 */
    fun tokenSummary(): String {
        val p = promptTokens.get()
        val c = completionTokens.get()
        return "Tokens: prompt=$p completion=$c total=${p + c}"
    }

    /** 耗时摘要 — self.stats 输出用 (最近一次 + 平均)。 */
    fun latencySummary(): String {
        val n = requestCount.get()
        val avg = if (n > 0) totalLatencyMs.get() / n else 0L
        return "Latency: last=${lastLatencyMs}ms avg=${avg}ms ($n requests)"
    }

    /** 读回事件流尾部 N 行 (供 self.stats events [--tail N])。 */
    fun tailEvents(n: Int): List<String> {
        val f = eventsFile
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().takeLast(n.coerceAtLeast(1))
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 测试隔离用 — 清空累计计数并删除事件文件。 */
    fun reset() {
        promptTokens.set(0)
        completionTokens.set(0)
        requestCount.set(0)
        totalLatencyMs.set(0)
        lastLatencyMs = 0L
        try { eventsFile.delete() } catch (_: Exception) {}
    }

    // ── 事件落盘 (原子追加: 读原内容 → tmp 全量写 → rename 替换) ──────

    private fun appendEvent(type: String, fields: List<Pair<String, JsonPrimitive>>) {
        val line = buildJsonObject {
            put("ts", JsonPrimitive(System.currentTimeMillis()))
            put("type", JsonPrimitive(type))
            fields.forEach { (k, v) -> put(k, v) }
        }.toString()
        synchronized(appendLock) {
            try {
                val f = eventsFile
                f.parentFile?.mkdirs()
                val existing = if (f.exists()) try { f.readText() } catch (_: Exception) { "" } else ""
                val kept = if (existing.length > MAX_EVENT_BYTES) {
                    // 超限截断: 只保留尾部, 并从完整行开始 (遥测失败不阻断命令执行)
                    existing.takeLast(MAX_EVENT_BYTES).substringAfter('\n')
                } else existing
                val tmp = File(f.parentFile, "${f.name}.tmp")
                tmp.writeText(kept + line + "\n")
                java.nio.file.Files.move(
                    tmp.toPath(), f.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                // 事件写入失败静默 — 遥测不得影响命令执行
            }
        }
    }
}
