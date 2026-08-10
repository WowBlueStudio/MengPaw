// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 火种模式运行时持久化 (v0.35.5) — 任务/预算/子任务进度落盘,
 * `swarm.status` 查询; 进程被杀后残留可恢复查看, 超 2h 无更新视为僵尸自动清理。
 * 原子写 (tmp + Files.move), 与 FrameworkPairStore 同模式。
 */
object SwarmRuntimeStore {
    private const val FILE_NAME = "swarm_runtime.json"
    private val file: File get() = File(DataPaths.CONFIG, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** 超过该时长无更新的运行记录视为僵尸, load 时清理。 */
    const val STALE_AFTER_MS = 2 * 60 * 60 * 1000L

    @Serializable
    data class SubtaskState(
        val id: String,
        val description: String,
        val status: String,
        val retries: Int = 0,
        val summary: String = ""
    )

    @Serializable
    data class Runtime(
        val task: String,
        val startedAt: Long,
        val totalSteps: Int,
        val consumedSteps: Int,
        val subtasks: List<SubtaskState>,
        val updatedAt: Long
    )

    fun save(runtime: Runtime) {
        // 并行 worker 并发快照 — synchronized 串行化, 防同 tmp 文件互踩
        synchronized(this) {
            try {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(json.encodeToString(runtime))
                java.nio.file.Files.move(
                    tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: Exception) {
                ErrorCollector.report(e, "SwarmRuntimeStore.save")
            }
        }
    }

    /** 读取运行时状态; 不存在/损坏/僵尸 (超时未更新) 返回 null (僵尸自动清理)。 */
    fun load(): Runtime? {
        return try {
            if (!file.exists() || file.length() == 0L) return null
            val rt = json.decodeFromString<Runtime>(file.readText())
            if (rt.updatedAt > 0 && System.currentTimeMillis() - rt.updatedAt > STALE_AFTER_MS) {
                clear()
                return null
            }
            rt
        } catch (_: Exception) { null }
    }

    fun clear() {
        try { if (file.exists()) file.delete() } catch (_: Exception) {}
    }
}
