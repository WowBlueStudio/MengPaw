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
 * 舰队委派运行时存储 (v0.36 深度进化) — 指挥舰侧任务状态追踪 + 对端侧回传地址记录。
 *
 * 职责:
 * - 指挥舰 (发起方): 委派任务 SENT → 收到 FLEET_RESULT 后 DONE/FAILED (结果回收)
 * - 执行方 (对端): 记录 incoming 委派的 delegateId → 回传地址, 供 fleet.reply 使用
 *
 * 原子写 (tmp + Files.move), 超 24h 无更新视为僵尸自动清理 (长任务留足窗口)。
 */
object FleetRuntimeStore {
    private const val FILE_NAME = "fleet_tasks.json"
    private val file: File get() = File(DataPaths.CONFIG, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** 超过该时长无更新的委派记录视为僵尸, load 时清理。 */
    const val STALE_AFTER_MS = 24 * 60 * 60 * 1000L

    enum class FleetStatus { SENT, DONE, FAILED }

    @Serializable
    data class FleetTask(
        val delegateId: String,
        val task: String,
        val peerName: String,
        val commander: String,
        val createdAt: Long,
        var status: String = FleetStatus.SENT.name,
        var result: String = "",
        var fromPeer: String = "",
        var updatedAt: Long = createdAt,
        /** 执行方侧: 委派来源的回传地址 (fleet.reply 用)。 */
        var callbackAddress: String = "",
        var callbackPort: Int = 0
    )

    /** 指挥舰: 发起委派记录 (SENT)。 */
    fun startTask(delegateId: String, task: String, peerName: String, commander: String) {
        upsert(FleetTask(
            delegateId = delegateId, task = task, peerName = peerName,
            commander = commander, createdAt = System.currentTimeMillis()
        ))
    }

    /** 执行方: 记录 incoming 委派 (回传地址), 不覆盖已有 SENT 记录。 */
    fun recordIncoming(delegateId: String, task: String, fromPeer: String, callbackAddress: String, callbackPort: Int) {
        val all = loadAll().toMutableList()
        val existing = all.find { it.delegateId == delegateId }
        if (existing != null) {
            if (callbackAddress.isNotBlank()) existing.callbackAddress = callbackAddress
            if (callbackPort > 0) existing.callbackPort = callbackPort
            existing.fromPeer = fromPeer
            existing.updatedAt = System.currentTimeMillis()
        } else {
            all.add(FleetTask(
                delegateId = delegateId, task = task, peerName = fromPeer, commander = fromPeer,
                createdAt = System.currentTimeMillis(),
                fromPeer = fromPeer,
                callbackAddress = callbackAddress, callbackPort = callbackPort
            ))
        }
        writeAll(all)
    }

    /** 指挥舰: 收到 FLEET_RESULT 后更新状态。返回 true 表示存在该委派 (防伪造)。 */
    fun markDone(delegateId: String, result: String, fromPeer: String, success: Boolean): Boolean {
        val all = loadAll().toMutableList()
        val t = all.find { it.delegateId == delegateId } ?: return false
        t.status = if (success) FleetStatus.DONE.name else FleetStatus.FAILED.name
        t.result = result
        t.fromPeer = fromPeer
        t.updatedAt = System.currentTimeMillis()
        writeAll(all)
        return true
    }

    fun find(delegateId: String): FleetTask? = loadAll().find { it.delegateId == delegateId }

    fun list(): List<FleetTask> = loadAll().sortedByDescending { it.updatedAt }

    fun clear() {
        try { if (file.exists()) file.delete() } catch (_: Exception) {}
    }

    private fun loadAll(): List<FleetTask> {
        return try {
            if (!file.exists() || file.length() == 0L) return emptyList()
            val tasks = json.decodeFromString<List<FleetTask>>(file.readText())
            val now = System.currentTimeMillis()
            val fresh = tasks.filter { now - it.updatedAt <= STALE_AFTER_MS }
            if (fresh.size != tasks.size) {
                if (fresh.isEmpty()) { clear() } else writeAll(fresh)
            }
            fresh
        } catch (_: Exception) { emptyList() }
    }

    private fun upsert(task: FleetTask) {
        val all = loadAll().toMutableList()
        all.removeAll { it.delegateId == task.delegateId }
        all.add(task)
        writeAll(all)
    }

    private fun writeAll(tasks: List<FleetTask>) {
        synchronized(this) {
            try {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(json.encodeToString(tasks))
                java.nio.file.Files.move(
                    tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: Exception) {
                ErrorCollector.report(e, "FleetRuntimeStore.writeAll")
            }
        }
    }
}
