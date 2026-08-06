// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.session

/**
 * P1-5 会话收尾自动摘要 → 中期记忆。
 *
 * 问题: 中期记忆此前靠模型自觉 (agent.memory.record 手动调用), 长对话末尾模型
 * 十有八九忘了写。修复: 会话压缩 (context compaction) 成功时, 把压缩路径已生成的
 * 摘要文本自动写入当期中期记忆分片 — 规则触发而非 prompt 自觉, 零新增 LLM 调用。
 *
 * 本对象只含纯函数与无状态幂等护栏, 便于单测; 实际落盘走
 * [com.mengpaw.kernel.agent.AgentDocs.appendMidTermMemory] 写入队列
 * (与 agent.memory.record 同一路径/格式, 压缩调用点是薄封装 — 见 History.kt)。
 */
object AutoSummaryMemory {

    /**
     * 幂等标记 — 会话 id + 折叠次数, 同会话同轮压缩只写一次。
     * 同时作为来源标注: 检索中期记忆时可区分自动摘要与模型自觉记录的条目。
     */
    fun marker(sessionId: String, compactNo: Int): String =
        "[自动摘要 · 会话 $sessionId #$compactNo]"

    /**
     * 构建自动摘要条目正文 — 与 agent.memory.record 写入格式一致:
     * 时间戳前缀 (`## HH:mm:ss`) 由 appendMidTermMemory 统一添加, 本函数只返回内容体;
     * 内容以 blockquote 行标注来源与幂等标记, 不混入模型自觉记录。
     *
     * @param summary 压缩路径 LLM 生成的结构化摘要 (目标/进展/关键决策/下一步/关键上下文)
     * @return 条目正文; 摘要为空时返回空串 (调用方跳过, 不落空条目)
     */
    fun buildEntry(summary: String, sessionId: String, compactNo: Int): String {
        val body = summary.trim()
        if (body.isEmpty()) return ""
        return "> ${marker(sessionId, compactNo)}\n$body"
    }

    /**
     * 自动摘要幂等护栏 — 同会话同轮压缩只写一次。
     *
     * 生产路径上 SessionManager 的 inFlightCompressions 已串行化同会话压缩
     * (scheduleCompressionIfNeeded 同步登记 + awaitCompressionIfNeeded 在途放行),
     * 折叠次数由原子 merge 分配、单调递增, 每次压缩事件取到唯一序号;
     * [shouldWrite] 再以 (会话 id, 折叠次数) 登记去重兜底 — 重复提交同轮标记即拦截。
     */
    class WrittenGuard {
        private val counters = java.util.concurrent.ConcurrentHashMap<String, Int>()
        private val written = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        /** 为本会话下一轮压缩分配序号 (原子递增, 从 1 开始)。 */
        fun nextOrdinal(sessionId: String): Int =
            counters.merge(sessionId, 1, Int::plus) ?: 1

        /**
         * 幂等判定: 该 (会话, 折叠次数) 摘要未登记过才返回 true, 并登记。
         * @return true = 应写入 (本轮首次), false = 已写过, 跳过
         */
        fun shouldWrite(sessionId: String, compactNo: Int): Boolean =
            written.add(marker(sessionId, compactNo))
    }
}
