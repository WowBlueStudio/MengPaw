// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File

/**
 * 孪生同步状态持久化 (P1-4 补丁)。
 *
 * 为什么需要: `TwinSyncState` 原本只在内存, 进程重启后 `twin.status` 一律显示
 * "上次同步: 从未" —— Agent 无法自证"上一次同步到底成没成", 也就不敢据 status 判断是否要重试。
 *
 * 落 `{AGENTS}/twin/twin-state.json` (原子写), 只存**结果事实** (时间/数量/最后错误),
 * 不存在线人数这类瞬时量; 运行时状态仍以内存 `StateFlow` 为准。
 */
internal object TwinStateStore {

    data class Persisted(
        val lastSyncAt: Long = 0L,
        val filesReceived: Int = 0,
        val conflicts: Int = 0,
        val lastError: String = ""
    ) {
        val hasSynced: Boolean get() = lastSyncAt > 0L
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun file(): File = File(DataPaths.TWIN_DIR, "twin-state.json")

    fun load(): Persisted {
        return try {
            val f = file()
            if (!f.isFile) return Persisted()
            val obj = json.parseToJsonElement(f.readText()).jsonObject
            Persisted(
                lastSyncAt = obj["lastSyncAt"]?.jsonPrimitive?.long ?: 0L,
                filesReceived = obj["filesReceived"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                conflicts = obj["conflicts"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                lastError = obj["lastError"]?.jsonPrimitive?.content.orEmpty()
            )
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinStateStore.load")
            Persisted()
        }
    }

    /** 记录一次同步结果; 失败时保留上次成功时间, 只更新 lastError。 */
    fun save(lastSyncAt: Long, filesReceived: Int, conflicts: Int, lastError: String = "") {
        try {
            val prev = load()
            val payload = buildJsonObject {
                put("lastSyncAt", kotlinx.serialization.json.JsonPrimitive(
                    if (lastSyncAt > 0L) lastSyncAt else prev.lastSyncAt
                ))
                put("filesReceived", kotlinx.serialization.json.JsonPrimitive(filesReceived))
                put("conflicts", kotlinx.serialization.json.JsonPrimitive(conflicts))
                put("lastError", kotlinx.serialization.json.JsonPrimitive(lastError))
            }.toString()
            val target = file()
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
            tmp.writeText(payload)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                try { tmp.delete() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            // 状态落盘失败不影响同步本身 — 只记错误, 下次再写
            ErrorCollector.report(e, "TwinStateStore.save")
        }
    }
}
