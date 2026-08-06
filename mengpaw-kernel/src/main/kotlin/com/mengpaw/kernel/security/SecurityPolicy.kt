// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.security

import com.mengpaw.kernel.security.IntegrityProvider
import com.mengpaw.kernel.security.NoOpIntegrityProvider
import kotlinx.serialization.json.*

/**
 * Core security policy that governs what Agent CLI commands are allowed.
 *
 * P1-7(自检报告): 支持 per-agent 命令前缀级授权表 — 多 Agent(tribe) 场景按 agent 粒度
 * 放开"受限但未硬禁"的命令。优先级: blockList 恒拒绝 > agent 级 grant > restrictedPatterns。
 * 授权经 [PolicyStore] 持久化到 {BASE}/配置/policy.json, 启动恢复。
 */
class SecurityPolicy(
    private val integrityProvider: IntegrityProvider = NoOpIntegrityProvider
) {
    private val restrictedPatterns = listOf(
        Regex("rm\\s+(-rf?\\s+)?/"),
        Regex("mkfs\\."),
        Regex("dd\\s+if=.*of=/dev"),
        Regex(":\\s*\\(\\s*\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:"),
        Regex("kill\\s+-9\\s+(-1|1)\\b"),
        Regex("\\b(curl|wget)\\b\\s+.*\\|.*\\b(bash|sh|zsh)\\b"),
        Regex("/dev/(tcp|udp)/"),
        Regex("\\bnc\\s+.*-e\\s*\\S+"),
        Regex("\\b(reboot|shutdown|halt|poweroff)\\b"),
        Regex("/etc/(passwd|shadow|sudoers|authorized_keys)"),
        Regex("chmod\\s+(777|a\\+rwx)\\s+/"),
        Regex("sudo\\s+(systemctl|service|usermod|mount|umount|iptables|ufw)\\b"),
        Regex("base64\\s+(-d|--decode)\\s*\\|\\s*(bash|sh|zsh)"),
        Regex("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]"),
        Regex("[\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]"),
    )

    private val syncLock = Any()
    private val blockList = mutableListOf("proc.exec", "proc.system")

    // ── P1-7(自检报告): per-agent 命令前缀授权表 ──────────────────────
    // agentName → 已授权命令前缀列表。仅"受限但未硬禁"的命令可被授权放开
    // (跳过 restrictedPatterns); blockList 恒优先, grant 不能绕过。
    private val agentGrants = mutableMapOf<String, MutableList<String>>()

    /**
     * 命令是否放行。
     * @param agentName 非空时先查该 agent 的 grant 表 — 命中的前缀放行
     *   (跳过 restrictedPatterns); blockList 命中恒拒绝, grant 不生效。
     *   无授权或 agentName 为 null 时走原有默认策略。
     */
    fun isAllowed(command: String, agentName: String? = null): Boolean {
        val cmdName = command.split(" ").firstOrNull() ?: return false
        synchronized(syncLock) {
            // 限制永远先于放行: blockList 命中恒拒绝 — grant 也不能绕过 (proc.exec 永不可开)
            if (blockList.any { cmdName.startsWith(it) }) return false
            // agent 级显式授权 → 跳过 restrictedPatterns (授权覆盖"受限但未硬禁"的命令)
            if (agentName != null && agentGrants[agentName]?.any { cmdName.startsWith(it) } == true) return true
        }
        for (pattern in restrictedPatterns) {
            if (pattern.containsMatchIn(command)) return false
        }
        return true
    }

    /** 给指定 agent 授权命令前缀 (幂等)。 */
    fun grantAgent(agentName: String, commandPrefix: String) {
        val prefix = commandPrefix.trim()
        if (prefix.isBlank()) return
        synchronized(syncLock) {
            val list = agentGrants.getOrPut(agentName) { mutableListOf() }
            if (prefix !in list) list.add(prefix)
        }
    }

    /** 收回指定 agent 的命令前缀授权 (幂等; 清空后移除空表)。 */
    fun revokeAgent(agentName: String, commandPrefix: String) {
        synchronized(syncLock) {
            agentGrants[agentName]?.remove(commandPrefix.trim())
            agentGrants[agentName]?.takeIf { it.isEmpty() }?.let { agentGrants.remove(agentName) }
        }
    }

    /** 查询指定 agent 的授权前缀列表。 */
    fun agentPolicies(agentName: String): List<String> =
        synchronized(syncLock) { agentGrants[agentName]?.toList() ?: emptyList() }

    /** 全部 agent 授权表 (只读快照)。 */
    fun allAgentPolicies(): Map<String, List<String>> =
        synchronized(syncLock) { agentGrants.mapValues { it.value.toList() } }

    /** 全量替换授权表 (持久化加载用)。 */
    fun replaceAgentGrants(grants: Map<String, List<String>>) {
        synchronized(syncLock) {
            agentGrants.clear()
            grants.forEach { (agent, prefixes) ->
                if (agent.isNotBlank()) {
                    agentGrants[agent] = prefixes.filter { it.isNotBlank() }.toMutableList()
                }
            }
        }
    }

    /** 授权表序列化为 JSON (PolicyStore 持久化用)。 */
    fun grantsJson(): String = synchronized(syncLock) {
        buildJsonObject {
            put("grants", buildJsonObject {
                agentGrants.forEach { (agent, prefixes) ->
                    put(agent, JsonArray(prefixes.map { JsonPrimitive(it) }))
                }
            })
        }.toString()
    }

    /** 从 JSON 恢复授权表 (幂等; 损坏文件忽略, 保持内存态)。 */
    fun loadGrantsJson(json: String) {
        try {
            val root = Json.parseToJsonElement(json).jsonObject
            val grantsObj = root["grants"]?.jsonObject ?: return
            replaceAgentGrants(grantsObj.mapValues { (_, arr) ->
                arr.jsonArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            })
        } catch (_: Exception) {
            // 损坏/空文件 — 保持内存态 (启动静默, 不抛)
        }
    }

    /** 原子写授权表到文件 (tmp + Files.move 覆盖, 参照全项目写入铁律)。 */
    fun saveTo(file: java.io.File): Boolean = try {
        file.parentFile?.mkdirs()
        val tmp = java.io.File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(grantsJson())
        java.nio.file.Files.move(
            tmp.toPath(), file.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
        true
    } catch (_: Exception) {
        false
    }

    /** 从文件恢复授权表 (文件不存在时静默保持内存态)。 */
    fun loadFrom(file: java.io.File) {
        try {
            if (file.exists()) loadGrantsJson(file.readText())
        } catch (_: Exception) {
            // 读取失败静默 — 保持默认策略
        }
    }

    fun validateIntegrity(commandName: String, args: List<String>): String? {
        return integrityProvider.validateCommand(commandName, args)
    }

    private val blockListAudit = mutableListOf<Pair<Long, String>>()

    fun blockCommand(command: String, reason: String = "") {
        synchronized(syncLock) {
            if (command !in blockList) {
                blockList.add(command)
                blockListAudit.add(System.currentTimeMillis() to "BLOCKED: $command (reason: ${reason.ifEmpty { "unspecified" }})")
            }
        }
    }

    fun getBlockList(): List<String> = synchronized(syncLock) { blockList.toList() }
    fun getBlockListAudit(): List<String> = synchronized(syncLock) { blockListAudit.map { "${it.first}: ${it.second}" } }
}
