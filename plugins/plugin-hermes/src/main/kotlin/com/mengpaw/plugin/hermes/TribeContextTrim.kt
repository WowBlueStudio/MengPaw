// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.hermes

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 部落上下文裁剪传递 — 委派时将当前对话上下文裁剪为关键信息附带到任务。
 *
 * 参考 Hermes Agent 的 87% 压缩：读取 agent 的 dialog 归档（JSONL），
 * 取最近消息 → 裁剪到 [maxChars] → 附加到任务描述。
 * 超长内容写入 ref:// 引用文件（tool_results 目录）。
 */
object TribeContextTrim {

    private val json = Json { ignoreUnknownKeys = true }

    /** 单条消息的最大保留长度（防止单条消息撑爆）。 */
    private const val MAX_MESSAGE_CHARS = 1500

    data class TrimResult(
        /** 裁剪后的上下文文本。 */
        val text: String,
        /** 引用文件路径（ref:// 前缀），null 表示无引用。 */
        val refPath: String? = null,
        /** 原始字符数。 */
        val originalChars: Int = 0
    )

    /**
     * 读取指定 Agent 的对话归档并裁剪为上下文摘要。
     * @param agentName Agent 名（目录名）
     * @param maxChars 目标最大字符数（默认 2000）
     * @param maxMessages 最多取多少条最近消息（默认 20）
     */
    fun trimContext(agentName: String, maxChars: Int = 2000, maxMessages: Int = 20): TrimResult {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val archive = File(DataPaths.dialogArchiveDir(agentName), "$today.jsonl")
        if (!archive.exists()) return TrimResult("", originalChars = 0)

        val lines = try { archive.readLines() } catch (e: Exception) { ErrorCollector.report(e, "TribeContextTrim.read"); return TrimResult("") }
        if (lines.isEmpty()) return TrimResult("", originalChars = 0)

        val messages = lines.takeLast(maxMessages).mapNotNull { line ->
            try {
                val obj = json.parseToJsonElement(line).jsonObject
                val role = obj["role"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val content = obj["content"]?.jsonPrimitive?.content ?: return@mapNotNull null
                "【${roleLabel(role)}】${content.take(MAX_MESSAGE_CHARS)}"
            } catch (_: Exception) { null }
        }
        if (messages.isEmpty()) return TrimResult("", originalChars = 0)

        val originalChars = messages.joinToString("\n").length
        var text = messages.joinToString("\n")
        var refPath: String? = null

        // 超过 maxChars: 裁剪 + 全文落盘 ref:// 引用
        if (text.length > maxChars) {
            val full = text
            text = text.take(maxChars)
            refPath = writeRefFile(agentName, full)
        }

        return TrimResult(
            text = text,
            refPath = refPath,
            originalChars = originalChars
        )
    }

    /** 将完整上下文写入 tool_results 目录并返回 ref:// 路径。 */
    private fun writeRefFile(agentName: String, content: String): String? {
        return try {
            val dir = File(DataPaths.toolResultsDir(agentName)).also { it.mkdirs() }
            val fileName = "tribe_ctx_${UUID.randomUUID().toString().take(8)}.txt"
            File(dir, fileName).writeText(content)
            "ref://tool_results/$fileName"
        } catch (e: Exception) {
            ErrorCollector.report(e, "TribeContextTrim.writeRefFile")
            null
        }
    }

    /** 将 [TrimResult] 格式化为任务附带的上下文段落。 */
    fun formatForTask(trim: TrimResult): String {
        if (trim.text.isBlank()) return ""
        val refNote = trim.refPath?.let { "\n\n[完整上下文: $it — 需要时用 agent.read 查阅]" } ?: ""
        return "\n\n## 委派参考上下文（${trim.originalChars} 字符裁剪而来）\n${trim.text}$refNote"
    }

    private fun roleLabel(role: String): String = when (role) {
        "user" -> "用户"
        "assistant" -> "助手"
        else -> "系统"
    }
}
