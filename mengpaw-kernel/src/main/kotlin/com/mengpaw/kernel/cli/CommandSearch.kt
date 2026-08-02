// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.cli

/**
 * 命令索引 — 每个注册命令的检索元数据.
 *
 * 设计思路: BM25 是杆子 (精确匹配), 同义词表是捆在杆子上的树枝.
 * Agent 用自然语言描述需求 → 查询词落在同义词树枝上 → 滑到正确的命令杆子上.
 */
data class CommandIndex(
    val fullName: String,           // "agent.memory.keep"
    val namespace: String,          // "agent"
    val description: String,        // LLM 友好的简短描述
    val usage: String = "",         // 用法示例, 如 "agent.memory.keep <内容>"
    val zhKeywords: List<String> = emptyList(),  // 中文同义词树枝
    val enKeywords: List<String> = emptyList()   // 英文同义词树枝
)

/**
 * BM25 命令检索引擎 — 零依赖, 内存占用 < 50KB, 检索延迟 μs 级.
 *
 * 评分规则:
 *   - 查询词命中 fullName → +10 分 (精确匹配, 置信度最高)
 *   - 查询词命中 zhKeywords / enKeywords → +8 分 (刻意对齐的关键词)
 *   - 查询词命中 description → +5 分 (描述性匹配)
 *   - 子串命中 (非完整词) → +2 分 (模糊兜底)
 */
object CommandSearch {
    private val index = mutableListOf<CommandIndex>()

    /** 注册一条命令到索引. 同 fullName 已存在时不覆盖 (保护 BuiltinCommandIndex 精编关键词). */
    @Synchronized
    fun register(cmd: CommandIndex) {
        insertOrUpdate(cmd, force = false)
    }

    /** 强制注册/更新一条命令 (插件重新激活时用). */
    @Synchronized
    fun registerOrUpdate(cmd: CommandIndex) {
        insertOrUpdate(cmd, force = true)
    }

    private fun insertOrUpdate(cmd: CommandIndex, force: Boolean) {
        val idx = index.indexOfFirst { it.fullName == cmd.fullName }
        if (idx >= 0) {
            if (force) {
                index[idx] = cmd // 强制覆盖: 用户更新了插件关键词
            }
            // 不强制: 保护 BuiltinCommandIndex 的精编关键词
            return
        }
        index.add(cmd)
    }

    /** 批量注册. */
    @Synchronized
    fun registerAll(cmds: List<CommandIndex>) {
        index.addAll(cmds)
    }

    /** 按命名空间移除所有已索引命令 (插件卸载时调用). */
    @Synchronized
    fun removeByNamespace(namespace: String) {
        index.removeAll { it.namespace == namespace }
    }

    /** 清空索引 (用于重建). */
    @Synchronized
    fun clear() {
        index.clear()
    }

    /** 全量枚举 (UI 命令补全用 — 与 mutator 同步, 防止并发 add 时 CME). */
    @Synchronized
    fun all(): List<CommandIndex> = index.toList()

    /** 索引大小. */
    @Synchronized
    fun size(): Int = index.size

    /** 索引统计: 命令数 + 覆盖的命名空间. */
    fun stats(): String {
        if (index.isEmpty()) return "(命令索引为空)"
        val namespaces = index.map { it.namespace }.distinct().sorted()
        return "已索引 ${index.size} 条命令, 覆盖 ${namespaces.size} 个命名空间: ${namespaces.joinToString(", ")}"
    }

    /**
     * 用自然语言查询搜索命令.
     * @param query 自然语言查询, 中文或英文
     * @param topK 返回的候选命令数 (默认 5)
     * @return 按得分降序排列的命令索引列表
     */
    fun search(query: String, topK: Int = 5): List<CommandIndex> {
        if (query.isBlank() || index.isEmpty()) return emptyList()
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()

        return index.map { cmd -> cmd to score(cmd, tokens) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    /**
     * 格式化搜索结果供 LLM 消费.
     * 输出紧凑的命令摘要, 含名称 + 描述 + 用法, 控制上下文占用.
     */
    fun formatResults(results: List<CommandIndex>, query: String = ""): String {
        if (results.isEmpty()) return "(未找到匹配 \"$query\" 的命令. 用 self.tools [ns] 查看完整列表.)"

        return buildString {
            if (query.isNotBlank()) append("搜索 \"$query\" 的结果 (${results.size} 条):\n\n")
            results.forEachIndexed { i, cmd ->
                append("${i + 1}. ${cmd.fullName}")
                if (cmd.usage.isNotBlank()) append(" — ${cmd.usage}")
                append('\n')
                if (cmd.description.isNotBlank()) append("   ${cmd.description}\n")
                append('\n')
            }
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private fun score(cmd: CommandIndex, tokens: List<String>): Int {
        var s = 0
        val nameLower = cmd.fullName.lowercase()
        val descLower = cmd.description.lowercase()
        for (token in tokens) {
            val t = token.lowercase()
            // 1. 精确命中命令名
            if (nameLower == t || nameLower.contains(".$t") || nameLower.contains(t)) s += 10
            // 2. 命中关键词 (每命中一个词加 8)
            else if (cmd.zhKeywords.any { it.equals(t, ignoreCase = true) || it.contains(t, ignoreCase = true) }) s += 8
            else if (cmd.enKeywords.any { it.equals(t, ignoreCase = true) || it.contains(t, ignoreCase = true) }) s += 8
            // 3. 命中描述
            else if (descLower.contains(t)) s += 5
            // 4. 子串命中 (模糊兜底)
            else if (nameLower.contains(t) || descLower.contains(t)) s += 2
        }
        return s
    }

    /** 简单分词: 按空格和常见标点切分, 过滤停用词, 合并双词短语. */
    private fun tokenize(text: String): List<String> {
        val raw = text.split(Regex("[\\s，。！？,.!?：:()（）/\\\\]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
        return raw + buildBigrams(raw)
    }

    /** 生成双词短语 (bigrams) — "网页 搜索" → "网页搜索" */
    private fun buildBigrams(words: List<String>): List<String> {
        if (words.size < 2) return emptyList()
        return words.windowed(2).map { it[0] + it[1] }
    }
}
