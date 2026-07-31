// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.hermes

import com.mengpaw.kernel.DataPaths
import com.mengpaw.plugin.framework.FrameworkPeerStore
import java.io.File

/**
 * 部落 LAN 自动组队 — 将 FrameworkPlugin 发现的局域网框架及其 Agent
 * 自动注册为团队成员。
 *
 * 数据流: FrameworkPeerStore.loadAll()（配置/framework_peers.json）
 *         → 逐 peer 取 name + agents → 写 Agent文档/team/{agentName}.md
 */
object TribeLanDiscovery {

    /**
     * 同步 LAN 框架成员到团队目录。
     * @param force 为 true 时覆盖已存在成员文件（默认保留手工编辑）
     * @return 本次新增的成员名列表
     */
    fun syncFromLan(force: Boolean = false): List<String> {
        val teamDir = File(DataPaths.TEAM).also { it.mkdirs() }
        val added = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        FrameworkPeerStore.loadAll().forEach { peer ->
            peer.agents.filter { it.isNotBlank() }.forEach { agent ->
                val safeName = agent.replace(Regex("[/\\\\]"), "_")
                val file = File(teamDir, "$safeName.md")
                if (!file.exists() || force) {
                    file.writeText(buildString {
                        appendLine("name: $agent")
                        appendLine("role: 框架成员(${peer.frameworkName.ifBlank { "MengPaw" }})")
                        appendLine("joined: ${System.currentTimeMillis()}")
                        appendLine("status: active")
                        appendLine("framework: ${peer.name}")
                        appendLine("address: ${peer.address}:${peer.port}")
                        appendLine("skills: ${peer.frameworkName} ${peer.capabilities.joinToString(" ")}")
                    })
                    added.add(agent)
                } else {
                    skipped.add(agent)
                }
            }
        }
        return added
    }
}
