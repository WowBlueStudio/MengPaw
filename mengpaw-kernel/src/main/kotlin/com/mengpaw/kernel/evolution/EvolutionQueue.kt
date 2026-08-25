// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.evolution

import com.mengpaw.kernel.DataPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 一条待进化队列项 — 触发一次静默分支进化。
 * type = "failure" 记录一条失败模式; type = "correction" 记录一条用户纠正。
 * 失败模式按 (命令名+错误码) 去重: 同模式只保留一条, 分支处理时从 failures.jsonl
 * 读取真实 repeatCount/上下文, 队列本身只作"有新东西要进化"的触发标记。
 */
@Serializable
data class EvolutionQueueItem(
    val id: String,
    val type: String,
    val agentName: String,
    val timestamp: Long,
    // failure 字段
    val command: String = "",
    val errorCode: String = "",
    val message: String = "",
    val task: String = "",
    val sessionId: String = "",
    val contextSnippet: String = "",
    // correction 字段
    val correction: String = ""
) {
    val isFailure: Boolean get() = type == "failure"
}

/**
 * 进化队列 (v0.44 静默分支进化) — "只要出失败/纠正就排队进化" 的持久化队列。
 *
 * - 落盘 `{agent}/evolution/queue.jsonl` (每行一条, 原子写, 重启不丢)。
 * - 失败模式按 (命令名+错误码) 去重 — 同模式本次未处理前不重复入队 (幂等)。
 * - 用户纠正不按模式去重 (每条纠正独立)。
 * - 线程安全, 永不抛异常。
 */
object EvolutionQueue {

    private val json = Json { encodeDefaults = true }
    private val lock = Any()
    /** 内存待处理队列 (懒加载自磁盘)。 */
    private val pending = mutableListOf<EvolutionQueueItem>()
    private var loaded = false
    private var nextId = 0L

    /** 入队一条失败 (去重: 同 agent 同命令名+错误码待处理则跳过)。返回是否新入队。 */
    fun enqueueFailure(
        agentName: String?,
        command: String,
        errorCode: String,
        message: String,
        task: String = "",
        sessionId: String = "",
        contextSnippet: String = ""
    ): Boolean {
        return try {
            val agent = agentFileOf(agentName)
            val cmdName = commandNameOf(command)
            synchronized(lock) {
                ensureLoaded()
                val exists = pending.any {
                    it.isFailure && it.agentName == agent &&
                        commandNameOf(it.command) == cmdName && it.errorCode == errorCode
                }
                if (exists) return false
                pending.add(EvolutionQueueItem(
                    id = "eq_${nextId++}",
                    type = "failure",
                    agentName = agent,
                    timestamp = System.currentTimeMillis(),
                    command = command.take(200),
                    errorCode = errorCode.take(60),
                    message = message.take(300),
                    task = task.take(200),
                    sessionId = sessionId.take(80),
                    contextSnippet = contextSnippet.take(400)
                ))
                true
            }.also { if (it) rewrite() }
        } catch (_: Exception) { false }
    }

    /** 入队一条用户纠正 (不按模式去重)。 */
    fun enqueueCorrection(agentName: String?, correction: String, contextSnippet: String, task: String): Boolean {
        return try {
            val agent = agentFileOf(agentName)
            synchronized(lock) {
                ensureLoaded()
                pending.add(EvolutionQueueItem(
                    id = "eq_${nextId++}",
                    type = "correction",
                    agentName = agent,
                    timestamp = System.currentTimeMillis(),
                    correction = correction.take(200),
                    contextSnippet = contextSnippet.take(400),
                    task = task.take(300)
                ))
                true
            }.also { if (it) rewrite() }
        } catch (_: Exception) { false }
    }

    /** 待处理队列项 (新→旧)。agentName 为空返回全部。 */
    fun pendingItems(agentName: String?): List<EvolutionQueueItem> {
        return try {
            synchronized(lock) {
                ensureLoaded()
                pending.filter { agentName == null || it.agentName == agentFileOf(agentName) }.reversed()
            }
        } catch (_: Exception) { emptyList() }
    }

    fun hasPending(agentName: String?): Boolean = pendingItems(agentName).isNotEmpty()

    /** 待处理项数 (供限流/触发判定)。 */
    fun pendingCount(agentName: String?): Int = pendingItems(agentName).size

    /** 处理成功后移除指定项 (按 id)。 */
    fun removeProcessed(agentName: String?, processed: Collection<EvolutionQueueItem>) {
        try {
            val ids = processed.mapTo(HashSet()) { it.id }
            synchronized(lock) {
                ensureLoaded()
                pending.removeAll { it.id in ids }
            }
            rewrite()
        } catch (_: Exception) {}
    }

    /** 清空待处理队列。 */
    fun clear(agentName: String?) {
        try {
            val agent = agentFileOf(agentName)
            synchronized(lock) {
                ensureLoaded()
                pending.removeAll { it.agentName == agent }
            }
            rewrite()
        } catch (_: Exception) {}
    }

    // ── 持久化 (单文件共享, 项按 agent 字段过滤) ──────────────────
    // 统一存 `{BASE}/进化档案/queue.jsonl` — 不落 Agent文档/ 下 (防被误判为假 Agent,
    // 同 failures.jsonl 无主记录的处理; 队列跨 agent 共享一个文件)。

    private val queueFile: File get() = File(DataPaths.EVOLUTION, "queue.jsonl")

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            val file = queueFile
            if (!file.exists()) return
            file.readLines().forEach { line ->
                if (line.isBlank()) return@forEach
                try {
                    val item = json.decodeFromString<EvolutionQueueItem>(line)
                    pending.add(item)
                    nextId = maxOf(nextId, item.id.removePrefix("eq_").toLongOrNull() ?: 0)
                } catch (_: Exception) { /* 跳过坏行 */ }
            }
        } catch (_: Exception) { /* 读取失败降级为空队列 */ }
    }

    private fun rewrite() {
        try {
            val file = queueFile
            file.parentFile?.mkdirs()
            atomicWrite(file, pending.joinToString("\n") { json.encodeToString(it) } +
                if (pending.isEmpty()) "" else "\n")
        } catch (_: Exception) {}
    }

    private fun atomicWrite(file: File, content: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            tmp.writeText(content)
            java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            try { tmp.delete() } catch (_: Exception) {}
            throw e
        }
    }

    // ── 工具 ───────────────────────────────────────────────────────

    private fun agentFileOf(agentName: String?): String =
        agentName?.replace(Regex("[/\\\\]"), "_")?.takeIf { it.isNotBlank() } ?: EvolutionStore.DEFAULT_AGENT

    private fun commandNameOf(raw: String): String =
        raw.replace(Regex("\\r\\n|\\r|\\n"), " ").trim().substringBefore(' ').take(40)

    /** 测试隔离: 清空内存态 (持久化文件不动)。 */
    internal fun resetForTest() {
        synchronized(lock) {
            pending.clear()
            loaded = false
            nextId = 0L
        }
    }
}
