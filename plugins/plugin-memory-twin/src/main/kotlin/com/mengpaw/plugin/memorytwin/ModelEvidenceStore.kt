// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 一条实测事实 — 真实使用中**被证实或被拒绝**的模型能力。
 *
 * 只记录可被事实推翻的维度 (视觉/工具/上下文), **不因任务成败改档位**:
 * 任务失败更可能是任务本身或网络的问题, 用成败去调模型档位只会引入噪声。
 */
enum class EvidenceFact {
    /** 带图请求成功返回 → 视觉能力被证实。 */
    VISION_OK,

    /** 带图请求被上游拒绝 (400/模型不支持图片) → 视觉能力被否证。 */
    VISION_REJECTED,

    /** 工具调用成功。 */
    TOOL_OK,

    /** 工具调用被上游拒绝 (400/tools unsupported)。 */
    TOOL_REJECTED,

    /** 一次成功的长上下文调用, value = 该次实际上下文 token 数。 */
    CONTEXT_OK,

    /** 一次上下文超限失败, value = 触发超限时的 token 规模 (上界证据)。 */
    CONTEXT_OVERFLOW,

    SUCCESS,
    FAILURE
}

/**
 * 模型实测证据 (per provider|model)。
 *
 * 与 [ModelCapabilityRules] 的关系: 规则是"知识与声明", 本对象是"经验与证据" —
 * 判定优先听证据 (`LEARNED` > `EXTERNAL` > `BUILTIN`)。这样模型换代后, 不必等谁去改规则:
 * 真实使用一次就能把判定纠正过来。
 */
@Serializable
data class ModelEvidence(
    val model: String,
    val provider: String = "",
    val visionOk: Int = 0,
    val visionRejected: Int = 0,
    val toolsOk: Int = 0,
    val toolsRejected: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    /** 实测成功使用过的最大上下文 token 数。 */
    val maxObservedContext: Int = 0,
    /** 实测触发上下文超限的 token 规模 (取最小的一次 = 最保守上界)。 */
    val ctxOverflowAt: Int = 0,
    val updatedAt: Long = 0L
) {
    /** 证据总条数 — 写进能力卡, 让路由与对端知道这份画像有多可信。 */
    val factCount: Int
        get() = visionOk + visionRejected + toolsOk + toolsRejected + successCount + failureCount

    /** 是否已有足以推翻声明的事实 (视觉/工具被明确否证过)。 */
    val hasContradiction: Boolean
        get() = visionRejected > 0 || toolsRejected > 0 || ctxOverflowAt > 0
}

/** 证据存储键 — provider 参与隔离 (同一模型名在不同网关能力可能不同)。 */
internal fun evidenceKey(model: String, provider: String): String =
    "${provider.trim().lowercase()}|${model.trim().lowercase()}"

/**
 * 实测证据存储 — `{AGENTS}/twin/model-evidence.json`。
 *
 * 本机各设备各自积累 (不随工作区同步), 但会经能力卡广播给对端 — 于是"某台设备试出来的结论"
 * 会被全网用于路由决策, 形成跨设备的集体经验。
 *
 * 落盘策略: 内存缓存 + 每次变更原子写 (tmp + rename); 文件损坏视为空库, 不影响采集。
 */
object ModelEvidenceStore {

    const val FILE_NAME = "model-evidence.json"

    /** 证据条目上限 — 超出按最后更新丢弃, 防无限膨胀。 */
    private const val MAX_ENTRIES = 200

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()
    private var cache: MutableMap<String, ModelEvidence>? = null

    /** 证据文件路径。 */
    fun filePath(): File = File(DataPaths.AGENTS, "twin/$FILE_NAME")

    /** 读取某模型的证据 (无则 null)。 */
    fun evidenceFor(model: String, provider: String = ""): ModelEvidence? {
        if (model.isBlank()) return null
        synchronized(lock) { return load().get(evidenceKey(model, provider)) }
    }

    /** 全部证据快照 (按更新时间倒序)。 */
    fun snapshot(): List<ModelEvidence> = synchronized(lock) {
        load().values.sortedByDescending { it.updatedAt }
    }

    /**
     * 记录一条实测事实并落盘。
     *
     * @param value 仅 [EvidenceFact.CONTEXT_OK] / [EvidenceFact.CONTEXT_OVERFLOW] 使用 (token 数)
     * @return 更新后的证据
     */
    fun record(model: String, provider: String, fact: EvidenceFact, value: Int = 0): ModelEvidence {
        val key = evidenceKey(model, provider)
        synchronized(lock) {
            val map = load()
            val old = map[key] ?: ModelEvidence(model = model.trim(), provider = provider.trim())
            val now = System.currentTimeMillis()
            val updated = when (fact) {
                EvidenceFact.VISION_OK -> old.copy(visionOk = old.visionOk + 1)
                EvidenceFact.VISION_REJECTED -> old.copy(visionRejected = old.visionRejected + 1)
                EvidenceFact.TOOL_OK -> old.copy(toolsOk = old.toolsOk + 1)
                EvidenceFact.TOOL_REJECTED -> old.copy(toolsRejected = old.toolsRejected + 1)
                EvidenceFact.CONTEXT_OK ->
                    old.copy(maxObservedContext = maxOf(old.maxObservedContext, value))
                EvidenceFact.CONTEXT_OVERFLOW -> old.copy(
                    // 取最小的一次溢出规模 = 最保守的已知上界
                    ctxOverflowAt = if (old.ctxOverflowAt <= 0) value else minOf(old.ctxOverflowAt, value)
                )
                EvidenceFact.SUCCESS -> old.copy(successCount = old.successCount + 1)
                EvidenceFact.FAILURE -> old.copy(failureCount = old.failureCount + 1)
            }.copy(updatedAt = now)
            map[key] = updated
            evictIfNeeded(map)
            save(map)
            return updated
        }
    }

    /** 清除单个模型的证据。 */
    fun clear(model: String, provider: String = ""): Boolean = synchronized(lock) {
        val map = load()
        val removed = map.remove(evidenceKey(model, provider)) != null
        if (removed) save(map)
        removed
    }

    /** 清空全部证据 (测试与 `twin.model reset` 使用)。 */
    fun clearAll(): Int = synchronized(lock) {
        val n = load().size
        cache = mutableMapOf()
        save(cache ?: mutableMapOf())
        n
    }

    /** 丢弃缓存 — 测试夹具重置用 (下次访问重新读盘)。 */
    internal fun invalidateCache() {
        synchronized(lock) { cache = null }
    }

    // ── 内部: 载入 / 落盘 ─────────────────────────────────────────

    private fun load(): MutableMap<String, ModelEvidence> {
        cache?.let { return it }
        val fresh = mutableMapOf<String, ModelEvidence>()
        val file = filePath()
        if (file.exists() && file.isFile) {
            try {
                val list = json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(ModelEvidence.serializer()),
                    file.readText()
                )
                list.forEach { fresh[evidenceKey(it.model, it.provider)] = it }
            } catch (e: Exception) {
                // 损坏 → 视为空库 (不阻断能力采集, 也不删除用户数据)
                ErrorCollector.report(e, "ModelEvidenceStore.load")
            }
        }
        cache = fresh
        return fresh
    }

    private fun save(map: Map<String, ModelEvidence>) {
        try {
            val file = filePath()
            file.parentFile?.mkdirs()
            val text = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ModelEvidence.serializer()),
                map.values.toList()
            )
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(text)
            var renamed = false
            try { renamed = tmp.renameTo(file) } catch (e: Exception) { ErrorCollector.report(e, "ModelEvidenceStore.renameTo") }
            if (!renamed) {
                if (file.exists()) file.delete()
                try { renamed = tmp.renameTo(file) } catch (_: Exception) { false }
            }
            if (!renamed) {
                ErrorCollector.report(
                    java.io.IOException("证据原子写失败, 已保留 tmp: $tmp"),
                    "ModelEvidenceStore.save"
                )
            } else if (tmp.exists()) {
                try { tmp.delete() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            ErrorCollector.report(e, "ModelEvidenceStore.save")
        }
    }

    private fun evictIfNeeded(map: MutableMap<String, ModelEvidence>) {
        if (map.size <= MAX_ENTRIES) return
        val drop = map.entries.sortedBy { it.value.updatedAt }.take(map.size - MAX_ENTRIES)
        drop.forEach { map.remove(it.key) }
    }
}
