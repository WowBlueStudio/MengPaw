// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.evolution

import com.mengpaw.kernel.DataPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 一条进化失败记录 — 失败模式库的基础单元。
 * repeatCount 是绩效系统的核心指标:同一模式(命令+错误码)出现第 2 次起即"复现"。
 */
@Serializable
data class EvolutionFailure(
    val id: String,
    val timestamp: Long,
    val agentName: String,
    /** 失败的命令全名, 如 "fs.cat"、"agent.memory.keep"。未知命令时为原始输入。 */
    val command: String,
    /** ErrorCodes 或 ErrorType 名, 如 "ERR_INVALID_INPUT"、"TOOL_CALL_FAILED"。 */
    val errorCode: String,
    val message: String,
    /** 来源: "Pipeline" / "AgentEngine" / "UserCorrection"。 */
    val source: String,
    /** 该失败模式(命令+错误码)在记录中出现过的次数。 */
    val repeatCount: Int = 1,
    /** 是否已被 Agent 沉淀修正(经 evolution.mark-corrected)。 */
    val corrected: Boolean = false
)

/**
 * 一条用户反应切片 — "用户分身"的数据源。
 * Agent 在 L3(用户视角)提问时检索此档案, 借用"用户看到错误时的反应"审视自己。
 */
@Serializable
data class UserReaction(
    val id: String,
    val timestamp: Long,
    val agentName: String,
    /** 用户纠正原文(或撤回动作标记)。 */
    val correction: String,
    /** 上下文切片: 上一条 Agent 回复摘要。 */
    val contextSnippet: String,
    /** 当前任务(用户消息全文)。 */
    val task: String
)

/**
 * 进化系统存储层 — 失败模式库 + 用户反应档案。
 *
 * ## Design(仿 ErrorCollector)
 * - 内存环形缓冲最近 50 条失败记录(全局, 按 agent 字段过滤)。
 * - JSON-lines 持久化到 `{AGENTS}/{agent}/evolution/failures.jsonl`(按 agent 分文件)。
 * - 用户反应以追加式 Markdown 落盘 `reactions.md`, 供 Agent 直接读取。
 * - 所有写入使用原子操作 (tmp + rename, 全项目写入铁律), 防崩溃损坏。
 * - 所有方法线程安全且永不抛异常。
 *
 * ## 失败模式匹配(绩效核心)
 * key = command + errorCode;同一 key 再次出现 → repeatCount++,
 * 第 2 次起标记为"复现", 由钩子/引导升级为深层省察钩子。
 */
object EvolutionStore {

    private const val MAX_MEMORY = 50
    const val DEFAULT_AGENT = "default"

    private val json = Json { prettyPrint = false; encodeDefaults = true }
    private val buffer = ConcurrentLinkedQueue<EvolutionFailure>()
    private var nextId = 0L

    /** 失败模式统计 key → 出现次数(内存, 持续累计)。 */
    private val repeatIndex = mutableMapOf<String, Int>()
    private val lock = Any()

    // ── 失败记录 ────────────────────────────────────────────────────

    /**
     * 记录一次失败。返回创建的条目(带 repeatCount)。
     * agentName 为空时落到 "default" 档案。
     */
    fun recordFailure(
        agentName: String?,
        command: String,
        errorCode: String,
        message: String,
        source: String
    ): EvolutionFailure {
        return try {
            val agent = agentFileOf(agentName)
            val key = "$agent|$command|$errorCode"
            val entry = synchronized(lock) {
                val count = (repeatIndex[key] ?: 0) + 1
                repeatIndex[key] = count
                val e = EvolutionFailure(
                    id = "evo_${nextId++}",
                    timestamp = System.currentTimeMillis(),
                    agentName = agent,
                    command = command.take(120),
                    errorCode = errorCode.take(60),
                    message = message.take(300),
                    source = source.take(60),
                    repeatCount = count
                )
                buffer.add(e)
                while (buffer.size > MAX_MEMORY) { buffer.poll() ?: break }
                e
            }
            appendToFile(agent, entry)
            entry
        } catch (_: Exception) {
            EvolutionFailure("evo_err", System.currentTimeMillis(), agentFileOf(agentName), command, errorCode, message, source)
        }
    }

    /**
     * 将一条失败模式标记为已沉淀修正(Agent 已把教训写入记忆/技能)。
     * 供 evolution.mark-corrected 命令使用。
     */
    fun markCorrected(agentName: String?, failureId: String): Boolean {
        return try {
            val agent = agentFileOf(agentName)
            val updated = buffer.find { it.id == failureId && it.agentName == agent }?.copy(corrected = true)
            if (updated != null) {
                val list = buffer.toList().map { if (it.id == failureId) updated else it }
                buffer.clear(); buffer.addAll(list)
                rewriteFile(agent)
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    // ── 用户反应 ────────────────────────────────────────────────────

    /**
     * 记录一条用户反应(纠正/撤回)。追加到 reactions.md。
     * contextSnippet 为上下文切片(上一条 Agent 回复摘要)。
     */
    fun recordCorrection(agentName: String?, correction: String, contextSnippet: String, task: String) {
        try {
            val agent = agentFileOf(agentName)
            val file = reactionsFile(agent)
            file.parentFile?.mkdirs()
            val block = buildString {
                appendLine()
                appendLine("## ${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}")
                appendLine("- 纠正: ${correction.take(200)}")
                appendLine("- 上下文: ${contextSnippet.take(400)}")
                appendLine("- 任务: ${task.take(300)}")
            }
            atomicAppend(file, block + "\n")
        } catch (_: Exception) { /* 存储必须永不崩溃 */ }
    }

    // ── 查询 ────────────────────────────────────────────────────────

    /** 最近 n 条失败记录(新→旧)。agentName 为空时返回全部。 */
    fun recentFailures(agentName: String?, n: Int = 20): List<EvolutionFailure> =
        try {
            val agent = agentFileOf(agentName)
            buffer.toList().filter { agentName == null || it.agentName == agent }.takeLast(n).reversed()
        } catch (_: Exception) { emptyList() }

    /** 复现模式 Top N(同模式出现 ≥2 次), 按次数降序。 */
    fun repeatedPatterns(agentName: String?, n: Int = 10): List<EvolutionFailure> =
        try {
            val agent = agentFileOf(agentName)
            buffer.toList()
                .filter { it.agentName == agent }
                .groupBy { "${it.command}|${it.errorCode}" }
                .map { (_, list) -> list.maxBy { it.repeatCount } }
                .filter { it.repeatCount >= 2 }
                .sortedByDescending { it.repeatCount }
                .take(n)
        } catch (_: Exception) { emptyList() }

    /** 用户反应档案全文(供 Agent L3 提问与 evolution.reactions 查看)。 */
    fun reactionsText(agentName: String?): String {
        return try {
            val file = reactionsFile(agentFileOf(agentName))
            if (file.exists()) file.readText() else "(暂无用户纠正记录)"
        } catch (_: Exception) {
            "(读取失败)"
        }
    }

    /** 绩效摘要 — 供 evolution.audit 与引导注入。 */
    fun stats(agentName: String?): String {
        return try {
            val all = buffer.toList().filter { it.agentName == agentFileOf(agentName) }
            val repeated = all.filter { it.repeatCount >= 2 }
            val corrected = all.count { it.corrected }
            buildString {
                appendLine("进化绩效 (${agentFileOf(agentName)})")
                appendLine("记录失败: ${all.size}")
                appendLine("复现模式: ${repeated.size} 种")
                appendLine("已沉淀修正: $corrected")
                if (repeated.isNotEmpty()) {
                    appendLine()
                    appendLine("### 复现模式")
                    repeated.take(5).forEach { f ->
                        appendLine("- ${f.command} [${f.errorCode}] ×${f.repeatCount}${if (f.corrected) " ✅已修正" else ""}")
                    }
                }
                appendLine()
                appendLine("未沉淀的失败模式: 用 agent.memory.keep / agent.memory.project.save 沉淀教训, 再 evolution.mark-corrected <id> 标记已修正")
            }
        } catch (_: Exception) {
            "(统计失败)"
        }
    }

    // ── 文件 ────────────────────────────────────────────────────────

    private fun agentFileOf(agentName: String?): String =
        agentName?.replace(Regex("[/\\\\]"), "_")?.takeIf { it.isNotBlank() } ?: DEFAULT_AGENT

    private fun failuresFile(agent: String): File = File(DataPaths.evolutionFailuresFile(agent))
    private fun reactionsFile(agent: String): File = File(DataPaths.evolutionReactionsFile(agent))

    private fun appendToFile(agent: String, entry: EvolutionFailure) {
        try {
            val file = failuresFile(agent)
            file.parentFile?.mkdirs()
            atomicAppend(file, json.encodeToString(entry) + "\n")
        } catch (_: Exception) { }
    }

    /** 重写整个 jsonl(用于 corrected 状态落盘)。 */
    private fun rewriteFile(agent: String) {
        try {
            val file = failuresFile(agent)
            file.parentFile?.mkdirs()
            val lines = buffer.toList().filter { it.agentName == agent }
            atomicWrite(file, lines.joinToString("\n") { json.encodeToString(it) } + if (lines.isEmpty()) "" else "\n")
        } catch (_: Exception) { }
    }

    // ── 原子写入 (tmp + rename, 防崩溃损坏 — 全项目写入铁律) ───────

    /** 原子追加: 读原内容 → tmp 全量写 → rename 替换。 */
    private fun atomicAppend(file: File, content: String) {
        val old = if (file.exists()) try { file.readText() } catch (_: Exception) { "" } else ""
        atomicWrite(file, old + content)
    }

    /** 原子写入: tmp + rename, 失败时清理残留 tmp。 */
    private fun atomicWrite(file: File, content: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(file)) {
            // rename 失败(如 Windows 目标被占用): 尝试删除目标后重试一次
            try { file.delete() } catch (_: Exception) {}
            if (!tmp.renameTo(file)) { tmp.delete() }
        }
        if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }
    }
}
