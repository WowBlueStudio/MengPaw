// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.hermes

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Tribe Kanban 看板 — 文件持久化的任务状态机。
 *
 * ## 存储布局
 * ```
 * {DataPaths.TEAM}/kanban/
 * ├── index.json            ← 快速列表索引
 * ├── {taskId}.json         ← 序列化 TribeTask
 * └── archive/              ← 终端态且超过 24h 的任务
 *     └── {taskId}.json
 * ```
 *
 * ## 线程安全
 * 所有写操作通过 [Mutex] 串行化，读操作（除 list 外）无锁。
 */
class TribeKanbanBoard {

    private val kanbanDir: File get() = File(DataPaths.TEAM, "kanban").also { it.mkdirs() }
    private val archiveDir: File get() = File(kanbanDir, "archive").also { it.mkdirs() }
    private val indexFile: File get() = File(kanbanDir, "index.json")

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // ── Public API ────────────────────────────────────────────────

    /** 创建任务并持久化。返回已写入的 [TribeTask]（含生成的 id）。 */
    suspend fun create(task: TribeTask): TribeTask = mutex.withLock {
        val saved = task.copy(status = TaskStatus.PENDING)
        writeTaskFile(saved)
        updateIndex(saved)
        return saved
    }

    /** 转换任务状态（自动校验合法性）。返回更新后的 [TribeTask]。 */
    suspend fun transition(
        taskId: String,
        newStatus: TaskStatus,
        result: String? = null,
        error: String? = null
    ): TribeTask = mutex.withLock {
        val current = readTaskFile(taskId)
            ?: throw NoSuchElementException("Task not found: $taskId")
        val updated = current
            .withStatus(newStatus)
            .copy(result = result ?: current.result, errorMessage = error ?: current.errorMessage)
        writeTaskFile(updated)
        updateIndex(updated)
        return updated
    }

    /** 读取单个任务。 */
    suspend fun get(taskId: String): TribeTask? {
        return readTaskFile(taskId) ?: readTaskFile(File(archiveDir, "$taskId.json").absolutePath)
    }

    /**
     * 列出任务，支持按状态过滤。
     * @param status 过滤状态（null = 全部）
     * @param limit 最大返回数
     * @param includeArchived 是否包含已归档任务
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun list(status: TaskStatus? = null, limit: Int = 20, includeArchived: Boolean = false): List<TribeTask> {
        val all = readIndex()
        val tasks = if (status == null) all else all.filter { it.status == status.name }
        val active = tasks.take(limit).mapNotNull { readTaskFile(it.id) }
        if (!includeArchived) return active
        val archived = archiveDir.listFiles()
            ?.filter { it.extension == "json" && it.name != "index.json" }
            ?.mapNotNull { readTaskFile(it.absolutePath) }
            ?: emptyList()
        return (active + archived).take(limit)
    }

    /** 取消任务（仅可取消 PENDING / ASSIGNED / RUNNING 状态）。 */
    suspend fun cancel(taskId: String): TribeTask = transition(taskId, TaskStatus.CANCELLED)

    /** 重试失败/超时任务（重置为 ASSIGNED，递增 retryCount）。 */
    suspend fun retry(taskId: String): TribeTask = mutex.withLock {
        val current = readTaskFile(taskId)
            ?: throw NoSuchElementException("Task not found: $taskId")
        require(current.canRetry()) { "Task $taskId cannot be retried (status=${current.status}, retries=${current.retryCount}/max=${current.maxRetries})" }
        val updated = current.copy(
            status = TaskStatus.ASSIGNED,
            retryCount = current.retryCount + 1,
            updatedAt = System.currentTimeMillis(),
            errorMessage = null
        )
        writeTaskFile(updated)
        updateIndex(updated)
        return updated
    }

    /** 归档超过指定时间（默认 24h）的终端状态任务。 */
    suspend fun archive(olderThanMs: Long = 86_400_000L): Int = mutex.withLock {
        val cutoff = System.currentTimeMillis() - olderThanMs
        var count = 0
        val terminalStatuses = setOf(TaskStatus.COMPLETED.name, TaskStatus.FAILED.name, TaskStatus.CANCELLED.name)
        val all = readIndex()
        for (entry in all) {
            if (entry.status in terminalStatuses && entry.updatedAt < cutoff) {
                val src = taskFile(entry.id)
                if (src.exists()) {
                    src.renameTo(File(archiveDir, "${entry.id}.json"))
                    count++
                }
            }
        }
        rebuildIndex()
        return count
    }

    /** 从 index.json 重建内存索引（启动时调用，恢复 inflight 任务）。 */
    suspend fun recoverInFlight(): List<TribeTask> = mutex.withLock {
        rebuildIndex()
        val all = readIndex()
        val inFlight = all.filter { it.status == TaskStatus.RUNNING.name || it.status == TaskStatus.ASSIGNED.name }
        val recovered = inFlight.mapNotNull { entry ->
            val task = readTaskFile(entry.id) ?: return@mapNotNull null
            if (entry.status == TaskStatus.RUNNING.name) {
                val updated = task.copy(
                    status = TaskStatus.FAILED,
                    errorMessage = "Process recovery — task was in-flight",
                    updatedAt = System.currentTimeMillis()
                )
                writeTaskFile(updated)
                updateIndex(updated)
                updated
            } else task
        }
        return recovered
    }

    /** 主动刷新索引（扫描文件系统恢复）。 */
    suspend fun reload() = mutex.withLock { rebuildIndex() }

    // ── 同步快照（供 UI / 路由等非 suspend 调用） ─────────────────

    /** 轻量任务状态（仅目标 Agent + 状态），供 UI 竖条 / 路由成功率统计。 */
    data class KanbanTaskLite(val toAgent: String, val status: TaskStatus)

    /**
     * 同步扫描 kanban 目录下所有任务 JSON 的状态快照。
     * 无锁读，解析失败的任务跳过（不崩溃）。用于 UI 轮询与成功率统计。
     */
    fun snapshotStatuses(): List<KanbanTaskLite> = try {
        File(DataPaths.TEAM, "kanban").listFiles()
            ?.filter { it.extension == "json" && it.parentFile.name == "kanban" }
            ?.mapNotNull { file ->
                runCatching { TribeTask.fromJson(file.readText()) }.getOrNull()
            }
            ?.map { KanbanTaskLite(it.toAgent, it.status) }
            ?: emptyList()
    } catch (_: Exception) { emptyList() }

    // ── 内部方法 ─────────────────────────────────────────────────

    private fun taskFile(taskId: String): File = File(kanbanDir, "$taskId.json")

    private fun writeTaskFile(task: TribeTask) {
        val file = taskFile(task.id)
        val tmp = File(kanbanDir, "${task.id}.json.tmp")
        try {
            tmp.writeText(TribeTask.toJson(task))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (e: Exception) {
            try { tmp.delete() } catch (_: Exception) {}
            ErrorCollector.report(e, "TribeKanbanBoard.writeTaskFile")
            throw e
        }
    }

    private fun readTaskFile(taskIdOrPath: String): TribeTask? {
        return try {
            val file = if (File(taskIdOrPath).isAbsolute) File(taskIdOrPath) else taskFile(taskIdOrPath)
            if (!file.exists()) return null
            TribeTask.fromJson(file.readText())
        } catch (e: Exception) {
            ErrorCollector.report(e, "TribeKanbanBoard.readTaskFile($taskIdOrPath)")
            null
        }
    }

    @Serializable
    private data class IndexEntry(
        val id: String,
        val title: String,
        val status: String,
        val priority: String,
        val fromAgent: String,
        val toAgent: String,
        val createdAt: Long,
        val updatedAt: Long
    )

    @Serializable
    private data class IndexData(val tasks: List<IndexEntry>)

    private fun buildIndexEntry(task: TribeTask) = IndexEntry(
        id = task.id, title = task.title.take(80),
        status = task.status.name, priority = task.priority.name,
        fromAgent = task.fromAgent, toAgent = task.toAgent,
        createdAt = task.createdAt, updatedAt = task.updatedAt
    )

    private fun updateIndex(task: TribeTask) {
        val all = readIndex().toMutableList()
        val idx = all.indexOfFirst { it.id == task.id }
        val entry = buildIndexEntry(task)
        if (idx >= 0) all[idx] = entry else all.add(entry)
        writeIndex(all)
    }

    private fun removeFromIndex(taskId: String) {
        val all = readIndex().toMutableList()
        all.removeAll { it.id == taskId }
        writeIndex(all)
    }

    private fun rebuildIndex() {
        val files = kanbanDir.listFiles()
            ?.filter { it.extension == "json" && it.name != "index.json" && it.parentFile == kanbanDir }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        val entries = files.mapNotNull { file ->
            readTaskFile(file.absolutePath)?.let { buildIndexEntry(it) }
        }
        writeIndex(entries)
    }

    private fun readIndex(): List<IndexEntry> {
        return try {
            if (!indexFile.exists()) return emptyList()
            json.decodeFromString<IndexData>(indexFile.readText()).tasks
        } catch (e: Exception) {
            ErrorCollector.report(e, "TribeKanbanBoard.readIndex")
            emptyList()
        }
    }

    private fun writeIndex(entries: List<IndexEntry>) {
        val tmp = File(kanbanDir, "index.json.tmp")
        try {
            tmp.writeText(json.encodeToString(IndexData(entries)))
            if (indexFile.exists()) indexFile.delete()
            tmp.renameTo(indexFile)
        } catch (e: Exception) {
            try { tmp.delete() } catch (_: Exception) {}
            ErrorCollector.report(e, "TribeKanbanBoard.writeIndex")
            throw e
        }
    }
}
