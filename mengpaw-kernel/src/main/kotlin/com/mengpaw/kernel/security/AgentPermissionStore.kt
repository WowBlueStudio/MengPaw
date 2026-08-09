// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.security

/**
 * Agent 权限等级 (v0.34.3 分级系统) — 智能体设置中配置, per-agent 持久化。
 *
 * - STANDARD (默认): 普通放行 / 中危拒绝 / 高危弹窗
 * - TRUSTED: 普通+中危放行 / 高危仍弹窗 (高危永远需要用户确认, 不可被权限等级绕过)
 *
 * 持久化 {BASE}/配置/agent_permissions.json (PolicyStore 同款范式: 懒加载 + 原子写)。
 * 权限等级只能由用户在智能体设置中调整 — Agent 无法自提权。
 */
enum class AgentPermissionLevel(val label: String) {
    STANDARD("标准"),
    TRUSTED("信任")
}

object AgentPermissionStore {

    private val lock = Any()
    @Volatile private var loaded = false
    @Volatile private var levels: MutableMap<String, AgentPermissionLevel> = mutableMapOf()

    /** 持久化文件 — 默认 {BASE}/配置/agent_permissions.json; 测试可指向临时文件。 */
    @Volatile var permissionFile: java.io.File =
        java.io.File(com.mengpaw.kernel.DataPaths.CONFIG, "agent_permissions.json")

    private fun ensureLoaded() {
        if (!loaded) synchronized(lock) {
            if (!loaded) {
                loadFrom(permissionFile)
                loaded = true
            }
        }
    }

    /** 当前 Agent 权限等级 — 未配置默认 STANDARD。 */
    fun levelOf(agent: String): AgentPermissionLevel {
        ensureLoaded()
        return levels[agent] ?: AgentPermissionLevel.STANDARD
    }

    /** 设置权限等级 (幂等) — 持久化成功返回 true。 */
    fun setLevel(agent: String, level: AgentPermissionLevel): Boolean {
        if (agent.isBlank()) return false
        synchronized(lock) {
            ensureLoaded()
            if (levels[agent] == level) return true
            levels = (levels + (agent to level)).toMutableMap()
            return save()
        }
    }

    // ── 持久化 (PolicyStore 范式: 原子写) ──

    private fun save(): Boolean = try {
        permissionFile.parentFile?.mkdirs()
        val tmp = java.io.File(permissionFile.parentFile, "${permissionFile.name}.tmp")
        tmp.writeText(
            levels.entries.joinToString(
                ",\n", prefix = "{", postfix = "}",
                transform = { "\"${it.key.replace("\"", "\\\"")}\": \"${it.value.name.lowercase()}\"" }
            )
        )
        java.nio.file.Files.move(
            tmp.toPath(), permissionFile.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
        true
    } catch (e: Exception) {
        com.mengpaw.kernel.KernelLog.w("AgentPermissionStore", "持久化失败: ${e.message}")
        false
    }

    private fun loadFrom(file: java.io.File) {
        try {
            if (!file.exists()) { levels = mutableMapOf(); return }
            val text = file.readText().trim()
            if (text.isEmpty()) { levels = mutableMapOf(); return }
            val json = kotlinx.serialization.json.Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
            levels = json?.mapNotNull { (k, v) ->
                val name = (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.uppercase()
                if (name != null) {
                    runCatching { AgentPermissionLevel.valueOf(name) }.getOrNull()?.let { k to it }
                } else null
            }?.toMap()?.toMutableMap() ?: mutableMapOf()
        } catch (e: Exception) {
            com.mengpaw.kernel.KernelLog.w("AgentPermissionStore", "权限文件损坏, 保持默认: ${e.message}")
            levels = mutableMapOf()
        }
    }

    /** 测试隔离用 — 指向临时文件并强制重载。 */
    fun resetForTest(file: java.io.File) {
        synchronized(lock) {
            permissionFile = file
            loaded = false
            levels = mutableMapOf()
        }
    }
}
