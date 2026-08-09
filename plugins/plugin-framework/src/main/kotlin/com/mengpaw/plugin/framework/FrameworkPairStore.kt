// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.framework

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.KernelLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 配对请求存储变化监听 (UI 红点/列表刷新)。 */
typealias PairListener = () -> Unit

/**
 * 框架通讯录配对请求存储 (v0.35.1 框架发现流程调整) —
 * 添加框架改为"请求-同意"双向流程: 发起方发送 FRAMEWORK_PAIR_REQUEST,
 * 接收方经 UI 同意/拒绝 (FRAMEWORK_PAIR_ACCEPT/DECLINE), 双方入册。
 * pendingCount 驱动通讯录"添加框架"按钮红点角标。
 */
object FrameworkPairStore {
    private const val FILE_NAME = "framework_pair_requests.json"
    private val file: File get() = File(DataPaths.CONFIG, FILE_NAME)

    enum class PairStatus { PENDING, ACCEPTED, DECLINED }

    data class PairRequest(
        val requestId: String,
        val fromFingerprint: String,
        val fromName: String,
        val fromAddress: String,
        val fromPort: Int = com.mengpaw.kernel.ports.Ports.ACP,
        val fromType: String = "mengpaw",
        val requestedAt: Long = System.currentTimeMillis(),
        val status: PairStatus = PairStatus.PENDING,
        val read: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("requestId", requestId)
            put("fromFingerprint", fromFingerprint)
            put("fromName", fromName)
            put("fromAddress", fromAddress)
            put("fromPort", fromPort)
            put("fromType", fromType)
            put("requestedAt", requestedAt)
            put("status", status.name)
            put("read", read)
        }

        companion object {
            fun fromJson(o: JSONObject): PairRequest = PairRequest(
                requestId = o.optString("requestId", ""),
                fromFingerprint = o.optString("fromFingerprint", ""),
                fromName = o.optString("fromName", ""),
                fromAddress = o.optString("fromAddress", ""),
                fromPort = o.optInt("fromPort", com.mengpaw.kernel.ports.Ports.ACP),
                fromType = o.optString("fromType", "mengpaw"),
                requestedAt = o.optLong("requestedAt", 0L),
                status = try {
                    PairStatus.valueOf(o.optString("status", "PENDING"))
                } catch (_: Exception) { PairStatus.PENDING },
                read = o.optBoolean("read", false)
            )
        }
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<PairListener>()

    /** 待处理请求数 — UI 红点角标数据源 (通讯录"添加框架"按钮)。 */
    @Volatile
    var pendingCount = 0
        private set

    fun addListener(l: PairListener) { listeners.add(l) }
    fun removeListener(l: PairListener) { listeners.remove(l) }

    fun loadAll(): List<PairRequest> {
        return try {
            if (!file.exists() || file.length() == 0L) return emptyList()
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { PairRequest.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    fun pending(): List<PairRequest> = loadAll().filter { it.status == PairStatus.PENDING }

    fun add(req: PairRequest) {
        val all = loadAll().toMutableList()
        all.removeAll { it.requestId == req.requestId }
        all.add(0, req)
        writeAll(all)
    }

    fun update(requestId: String, transform: (PairRequest) -> PairRequest) {
        val all = loadAll().map { if (it.requestId == requestId) transform(it) else it }
        writeAll(all)
    }

    fun markRead(requestId: String) = update(requestId) { it.copy(read = true) }

    fun findByRequestId(id: String): PairRequest? = loadAll().find { it.requestId == id }

    private fun writeAll(requests: List<PairRequest>) {
        try {
            file.parentFile?.mkdirs()
            val arr = JSONArray()
            requests.forEach { arr.put(it.toJson()) }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(arr.toString(2))
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            KernelLog.w("FrameworkPairStore", "write failed: ${e.message}")
        } finally {
            pendingCount = loadAll().count { it.status == PairStatus.PENDING }
            listeners.toList().forEach { it() }
        }
    }
}
