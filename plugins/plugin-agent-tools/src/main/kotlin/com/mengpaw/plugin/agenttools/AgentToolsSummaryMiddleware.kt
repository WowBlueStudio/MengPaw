// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.agenttools

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.agent.AgentMiddleware
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 命令集摘要缓存 — mtime 指纹保证字节稳定（系统提示词是 LLM prefix cache 第三级）。
 * 指纹不变时零磁盘 I/O；import/remove 时主动 invalidate 双保险。
 */
object AgentToolsSummary {

    /** agentName → (指纹, 摘要) */
    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()

    /** 目录指纹：文件名+长度+lastModified 拼接哈希。变化才重建摘要。 */
    fun fingerprint(agentName: String): Long {
        return try {
            val dir = AgentToolsStore.toolsDir(agentName)
            if (!dir.exists()) return 0L
            dir.listFiles { f -> f.isFile && f.extension == "json" && !f.name.endsWith(".tmp") }
                ?.sortedBy { it.name }
                ?.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
                ?.hashCode()?.toLong() ?: 0L
        } catch (_: Exception) { 0L }
    }

    /** 获取摘要：指纹未变走缓存，变了重建。 */
    fun summaryFor(agentName: String): String {
        val fp = fingerprint(agentName)
        val cached = cache[agentName]
        if (cached != null && cached.first == fp) return cached.second
        val summary = AgentToolsStore.buildSummary(agentName)
        cache[agentName] = fp to summary
        return summary
    }

    /** import/remove 后主动失效（下次调用立即重建）。 */
    fun invalidate(agentName: String) {
        cache.remove(agentName)
    }
}

/**
 * 摘要注入 middleware — 装配进 AgentSessionFactory 的 chain 后，
 * 每次 refreshSystemPrompt 将已注册命令集的紧凑摘要追加到系统提示词。
 * 去重守卫（"已注册命令集" !in prompt）防重复注入（仿 memoryMw）。
 */
val AgentToolsSummaryMiddleware: AgentMiddleware = AgentMiddleware { prompt, agentName ->
    val summary = AgentToolsSummary.summaryFor(agentName)
    if (summary.isNotBlank() && "已注册命令集" !in prompt) {
        "$prompt\n\n## 已注册命令集（Agent Tools）\n$summary\n\n" +
        "需要某条命令的完整参数时: tools.search <关键词> 或 agent.read ${DataPaths.agentToolsDir(agentName)}/<名称>.json"
    } else {
        prompt
    }
}
