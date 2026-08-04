// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.cli

import java.util.concurrent.ConcurrentHashMap

/**
 * 只读命令结果缓存 — 消除 ReAct 循环内的重复查询（Agent 常反复查同一状态）。
 *
 * 白名单制：仅缓存纯查询命令（无副作用）；短 TTL 保时效；
 * 键含 agentName + sessionId 防跨会话数据串扰（self.status 输出含 sessionId）。
 * 缓存命中仍走 audit 记录（审计轨迹不丢）。
 */
class CommandResultCache(
    private val ttlMillis: Long = 5_000,
    private val maxEntries: Int = 128
) {
    private data class CacheEntry(val result: ExecutionResult, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** 命中返回结果（惰性过期），未命中或已过期返回 null。 */
    fun get(key: String): ExecutionResult? {
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) {
            cache.remove(key)
            return null
        }
        return entry.result
    }

    fun put(key: String, result: ExecutionResult) {
        // P2 修复: 大输出不入缓存（agent.memory/docs 可达 MB 级 — 防内存滞留）
        if (result.output.length > MAX_CACHE_OUTPUT_CHARS) return
        if (cache.size >= maxEntries) {
            // 容量保护：移除最早过期的一项（惰性近似）
            cache.entries.minByOrNull { it.value.expiresAt }?.let { cache.remove(it.key) }
        }
        cache[key] = CacheEntry(result, System.currentTimeMillis() + ttlMillis)
    }

    /** 会话内缓存键 — 命令 + 参数 + agent + session（防跨会话串扰）。 */
    fun keyFor(command: String, args: List<String>, agentName: String?, sessionId: String): String =
        "$command|${args.joinToString("|")}|$agentName|$sessionId"

    fun clear() = cache.clear()

    companion object {
        /** 缓存输出上限 — 超限不入缓存（防大对象滞留内存）。 */
        private const val MAX_CACHE_OUTPUT_CHARS = 64 * 1024

        /**
         * 可缓存白名单 — 纯查询/无副作用命令。
         * 恒定类（无参）：self.status/config/version/ports/search.stats/acp fingerprint、
         *   sys.device/camera/sensors/permission.list/notification.id/calendar.calendars
         * 有参查询：self.tools/search、sys.app.info/permission.check、agent.read/ls/sessions/audit
         * 写命令/时变昂贵命令（sys.battery 等）不入列 — 宁缺毋滥。
         */
        val CACHEABLE = setOf(
            // self.* 查询
            "self.status", "self.config", "self.version", "self.tools", "self.ports",
            "self.search", "self.search.stats", "self.acp.fingerprint", "self.acp.trusted",
            // sys.* 查询（mengpaw-core）
            "sys.device", "sys.camera", "sys.sensors", "sys.permission.list",
            "sys.permission.check", "sys.app.info", "sys.notification.id",
            "sys.calendar.calendars",
            // agent.* 查询（进程内文件/会话读）
            "agent.read", "agent.ls", "agent.sessions", "agent.audit",
            "agent.docs", "agent.memory", "agent.profile", "agent.boost", "agent.soul", "agent.modes"
        )
    }
}
