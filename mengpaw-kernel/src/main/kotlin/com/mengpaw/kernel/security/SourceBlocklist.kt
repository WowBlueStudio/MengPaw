// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.security

import java.io.File
import java.net.URI

/**
 * 攻击来源黑名单 (P0 注入防护 v0.34.1) — 本地对话维度。
 *
 * 目的明确的提示词攻击命中时, 用户确认后将来源（域名/路径）拉黑持久化;
 * 后续同来源内容直接阻止 (整体不进上下文), 防「换注入变体再试」。
 *
 * 持久化范式照搬 PolicyStore: {BASE}/配置/blocklist.json, 懒加载 + 原子写
 * (tmp + Files.move REPLACE_EXISTING), 损坏文件静默保持内存态。
 *
 * 匹配语义: 精确 + 后缀。block "evil.com" → 命中 "sub.evil.com" (域名后缀),
 * 不误伤 "evil.com.evil.org"; block 路径 "/a/b" → 命中 "/a/b/c.txt" (路径前缀)。
 */
object SourceBlocklist {

    private val lock = Any()
    @Volatile private var loaded = false
    @Volatile private var entries: Set<String> = emptySet()

    /** 黑名单持久化文件 — 默认 {BASE}/配置/blocklist.json; 测试可指向临时文件。 */
    @Volatile var blocklistFile: File = File(com.mengpaw.kernel.DataPaths.CONFIG, "blocklist.json")

    private fun ensureLoaded() {
        if (!loaded) synchronized(lock) {
            if (!loaded) {
                loadFrom(blocklistFile)
                loaded = true
            }
        }
    }

    /** 是否命中黑名单 (精确 + 域名后缀 / 路径前缀)。 */
    fun isBlocked(source: String): Boolean {
        if (source.isBlank()) return false
        ensureLoaded()
        val s = source.trim()
        return entries.any { entry ->
            s == entry || s.startsWith("$entry/") || s.endsWith(".$entry")
        }
    }

    /** 拉黑来源 (幂等) — 持久化成功返回 true。@return 是否写入成功。 */
    fun block(source: String): Boolean {
        val s = source.trim()
        if (s.isEmpty()) return false
        synchronized(lock) {
            ensureLoaded()
            entries = entries + s
            return save()
        }
    }

    /** 解除拉黑 (幂等) — 持久化成功返回 true。@return 是否写入成功。 */
    fun unblock(source: String): Boolean {
        val s = source.trim()
        if (s.isEmpty()) return false
        synchronized(lock) {
            ensureLoaded()
            if (s !in entries) return true
            entries = entries - s
            return save()
        }
    }

    /** 当前黑名单全量 (排序稳定输出)。 */
    fun list(): List<String> {
        ensureLoaded()
        return entries.sorted()
    }

    // ── 持久化 (PolicyStore 范式: 原子写) ──

    private fun save(): Boolean = try {
        blocklistFile.parentFile?.mkdirs()
        val tmp = File(blocklistFile.parentFile, "${blocklistFile.name}.tmp")
        tmp.writeText(entries.sorted().joinToString(",\n", prefix = "[", postfix = "]", transform = { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }))
        java.nio.file.Files.move(tmp.toPath(), blocklistFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        true
    } catch (e: Exception) {
        com.mengpaw.kernel.KernelLog.w("SourceBlocklist", "持久化失败: ${e.message}")
        false
    }

    private fun loadFrom(file: File) {
        try {
            if (!file.exists()) { entries = emptySet(); return }
            val text = file.readText().trim()
            if (text.isEmpty()) { entries = emptySet(); return }
            val json = kotlinx.serialization.json.Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray
            entries = json?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        } catch (e: Exception) {
            // 损坏文件: 静默保持空内存态 (PolicyStore 同款), 不崩溃不覆盖
            com.mengpaw.kernel.KernelLog.w("SourceBlocklist", "黑名单文件损坏, 保持空列表: ${e.message}")
            entries = emptySet()
        }
    }

    /** 测试隔离用 — 指向临时文件并强制重载。 */
    fun resetForTest(file: File) {
        synchronized(lock) {
            blocklistFile = file
            loaded = false
            entries = emptySet()
        }
    }

    // ── 来源解析 ──

    /**
     * 从命令行文本提取来源键 (拉黑/判断用)。@return null = 无法定位来源 (不参与拉黑判断,
     * 防误伤; 攻击提醒照常触发)。
     *
     * - net.* 命令: 首个参数视为 URL → 提取 host 小写 (域名级, 拉黑一个域挡整个域)
     * - 其余命令: 首个参数原文 (路径参数即路径串; 文件内容注入的来源 = 被读文件路径)
     * - 零参数命令 → null
     */
    fun extractSource(commandLine: String): String? {
        val trimmed = commandLine.trim()
        if (trimmed.isEmpty()) return null
        val tokens = trimmed.split(Regex("\\s+"), limit = 3)
        val name = tokens[0]
        val firstArg = tokens.getOrNull(1)?.trim().orEmpty()
        if (firstArg.isEmpty()) return null
        return if (name.startsWith("net.")) {
            try {
                val url = if (firstArg.matches(Regex("(?i)^https?://.*"))) firstArg else "https://$firstArg"
                URI(url).host?.lowercase()
            } catch (e: Exception) { null }
        } else {
            firstArg
        }
    }
}
