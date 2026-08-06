// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.hermes

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.llm.LlmProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * 部落共享记忆存储 — SHA256 内容指纹去重 + 自动 LLM 压缩。
 *
 * ## 去重
 * 每条记忆计算 SHA256 指纹，写入 .fingerprints.json 索引。
 * 内容重复的记忆不重复落盘（ACP 广播场景防重复）。
 *
 * ## 压缩
 * 超过 [COMPACT_THRESHOLD] 条时自动触发（或 `tribe.memo --compact` 手动）：
 * 最旧的 N 条 → LLM 合并为摘要 memo_compact_{ts}.md → 删除原文件。
 */
object TribeMemoStore {

    /** 触发自动压缩的记忆条数阈值。 */
    const val COMPACT_THRESHOLD = 100
    /** 单次压缩的旧记忆条数。 */
    const val COMPACT_BATCH = 100

    private val memosDir: File get() = File(DataPaths.TEAM_MEMOS).also { it.mkdirs() }
    private val fingerprintFile: File get() = File(memosDir, ".fingerprints.json")

    @Serializable
    private data class FingerprintIndex(val entries: Map<String, String> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    sealed class PublishResult {
        /** 已发布。 */
        data class Published(val file: File) : PublishResult()
        /** 内容重复，已跳过。 */
        data class Duplicate(val hash: String) : PublishResult()
    }

    /** 发布一条共享记忆（去重 + 超阈值自动压缩）。 */
    fun publish(content: String, author: String, source: String = "本地"): PublishResult {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return PublishResult.Duplicate(sha256(trimmed))

        val hash = sha256(trimmed)
        val index = readFingerprints()
        index.entries[hash]?.let { return PublishResult.Duplicate(hash) }

        val file = File(memosDir, "memo_${System.currentTimeMillis()}.md")
        try {
            file.writeText("""
# 团队共享记忆
- 作者: $author
- 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}
- 来源: $source
- 指纹: $hash

$trimmed
""".trimIndent())
            val updated = FingerprintIndex(index.entries + (hash to file.name))
            fingerprintFile.writeText(json.encodeToString(updated))
            return PublishResult.Published(file)
        } catch (e: Exception) {
            ErrorCollector.report(e, "TribeMemoStore.publish")
            try { file.delete() } catch (_: Exception) {}
            throw e
        }
    }

    /** 列出最近 N 条记忆（按修改时间倒序）。 */
    fun listRecent(limit: Int = 10): List<File> =
        memosDir.listFiles()
            ?.filter { it.extension == "md" && !it.name.startsWith(".fingerprints") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            ?: emptyList()

    /** 当前记忆条数（不含指纹索引）。 */
    fun count(): Int =
        memosDir.listFiles()?.count { it.extension == "md" && !it.name.startsWith(".fingerprints") } ?: 0

    /**
     * 自动压缩：当记忆条数超过阈值时，用 LLM 合并最旧的记忆为摘要。
     * @param llm LLM 提供者（null 时跳过压缩，仅返回 0）
     * @return 压缩删除的条数
     */
    suspend fun compactIfNeeded(llm: LlmProvider?): Int {
        if (count() <= COMPACT_THRESHOLD) return 0
        if (llm == null) return 0
        return compactOldest(llm)
    }

    /** 手动压缩最旧的 COMPACT_BATCH 条记忆为摘要。 */
    suspend fun compactOldest(llm: LlmProvider): Int {
        val all = memosDir.listFiles()
            ?.filter { it.extension == "md" && !it.name.startsWith(".fingerprints") }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()
        if (all.size < 2) return 0
        val batch = all.take(COMPACT_BATCH)

        val contents = batch.joinToString("\n\n") { file ->
            try { "【${file.nameWithoutExtension}】\n${file.readText().take(2000)}" } catch (_: Exception) { "" }
        }
        val summary = runCatching {
            llm.complete("""
你是团队记忆管理员。将以下团队共享记忆压缩为结构化要点摘要（保留关键事实、数字、结论，去除重复），中文输出：

$contents
            """.trimIndent())
        }.getOrNull() ?: return 0

        // 写摘要文件
        val summaryFile = File(memosDir, "memo_compact_${System.currentTimeMillis()}.md")
        summaryFile.writeText("""
# 团队共享记忆 (自动压缩)
- 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}
- 压缩自: ${batch.size} 条记忆

$summary
""".trimIndent())

        // 删除原文件并重建指纹索引
        var deleted = 0
        batch.forEach { file ->
            try { file.delete(); deleted++ } catch (_: Exception) {}
        }
        rebuildFingerprints()
        return deleted
    }

    /** 删除指定文件并更新指纹索引（移除失效条目）。 */
    fun deleteFile(file: File): Boolean {
        val ok = try { file.delete() } catch (_: Exception) { false }
        if (ok) rebuildFingerprints()
        return ok
    }

    // ── 内部 ──────────────────────────────────────────────────

    private fun readFingerprints(): FingerprintIndex {
        return try {
            if (!fingerprintFile.exists()) FingerprintIndex()
            else json.decodeFromString<FingerprintIndex>(fingerprintFile.readText())
        } catch (_: Exception) { FingerprintIndex() }
    }

    private fun rebuildFingerprints() {
        try {
            val entries = mutableMapOf<String, String>()
            memosDir.listFiles()
                ?.filter { it.extension == "md" && !it.name.startsWith(".fingerprints") }
                ?.forEach { file ->
                    val text = try { file.readText() } catch (_: Exception) { "" }
                    // 从文件头解析已有指纹，或重新计算
                    val hash = Regex("指纹:\\s*([0-9a-f]{64})").find(text)?.groupValues?.get(1)
                        ?: sha256(text.removePrefix("# 团队共享记忆").trim())
                    entries[hash] = file.name
                }
            fingerprintFile.writeText(json.encodeToString(FingerprintIndex(entries)))
        } catch (e: Exception) {
            ErrorCollector.report(e, "TribeMemoStore.rebuildFingerprints")
        }
    }

    /** SHA-256 十六进制摘要。 */
    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            // Locale.ROOT: 默认 Locale 下 %02x 输出畸形 (阿拉伯语设备 — P2 修复)
            .joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
}
