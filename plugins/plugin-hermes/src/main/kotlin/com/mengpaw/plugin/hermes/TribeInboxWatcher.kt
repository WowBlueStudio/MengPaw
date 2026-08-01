// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.hermes

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.agent.AgentMiddleware
import com.mengpaw.kernel.namespace.NotifyBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 部落收件箱监视器 — 轮询 Agent inbox 中的未处理部落任务数量，
 * 并在变化时通过 NotifyBus 提醒用户。
 *
 * 配合 [TribeInboxMiddleware] 将待办数注入 Agent 的 system prompt，
 * 让 Agent 自动感知新任务。
 */
object TribeInboxWatcher {

    /** 每个 Agent 的未处理任务计数（agentName → count）。 */
    @Volatile
    var counts: Map<String, Int> = emptyMap()
        private set

    private var job: Job? = null

    /** 上次提醒的 agent（避免重复轰炸）。 */
    @Volatile
    private var lastNotifiedAgent: String = ""

    /** 部落任务文件前缀。 */
    private val TASK_PREFIXES = listOf("tribe_task_", "tribe_delegate_", "task_")

    /** 查询指定 Agent 的未处理任务数。 */
    fun pendingCount(agentName: String): Int = counts[agentName] ?: 0

    /** 启动轮询（每 5s 扫描）。 */
    fun start(scope: CoroutineScope) {
        stop()
        job = scope.launch {
            while (isActive) {
                scan()
                delay(5000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** 立即扫描所有已知 Agent 的 inbox。 */
    fun scan() {
        val newCounts = mutableMapOf<String, Int>()
        val agentsDir = File(DataPaths.AGENTS)
        val dirs = try {
            agentsDir.listFiles()?.filter { it.isDirectory && it.name != "team" && it.name != "twin" && it.name != "acp" }
                ?: emptyList()
        } catch (_: Exception) { emptyList() }

        for (dir in dirs) {
            val inbox = File(dir, "inbox")
            val count = try {
                inbox.listFiles()?.count { file ->
                    TASK_PREFIXES.any { file.name.startsWith(it) }
                } ?: 0
            } catch (_: Exception) { 0 }
            if (count > 0) newCounts[dir.name] = count
        }
        counts = newCounts

        // 变化提醒（每个 agent 只提醒一次，直到处理完）
        if (newCounts.isNotEmpty() && lastNotifiedAgent != newCounts.keys.first()) {
            val agent = newCounts.keys.first()
            lastNotifiedAgent = agent
            NotifyBus.message("📋 部落: $agent 有 ${newCounts[agent]} 条新任务待处理")
        } else if (newCounts.isEmpty()) {
            lastNotifiedAgent = ""
        }
    }

    /** 主动标记已处理（处理完任务后调用，重置提醒）。 */
    fun markProcessed(agentName: String) {
        if (lastNotifiedAgent == agentName) lastNotifiedAgent = ""
    }
}

/**
 * 部落收件箱 Middleware — 将未处理任务数注入 Agent 的 system prompt。
 * 通过 [AgentEngine.setMiddleware] 或构造 chain 安装。
 */
val TribeInboxMiddleware: AgentMiddleware = AgentMiddleware { prompt, agentName ->
    val n = TribeInboxWatcher.pendingCount(agentName)
    if (n > 0) {
        "$prompt\n\n## 新部落任务\n你的 inbox 有 $n 条部落委派任务未处理，请优先查看 Agent文档/$agentName/inbox/ 下 tribe_task_*.md 并执行。"
    } else prompt
}
