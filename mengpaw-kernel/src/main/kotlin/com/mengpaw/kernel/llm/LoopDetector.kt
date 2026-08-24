// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

/**
 * 循环检测器 — 持有单次运行/单会话的循环检测状态。
 *
 * 背景 (P2-2 拆解): 原循环检测状态 (recentCommands/consecutiveFailures) 挂在共享的
 * PromptEngine 上, 主 ReAct 循环与并行 worker 若并发调用会产生数据竞争; 且跨会话
 * 复用需手动 reset 防污染。本类把检测状态收敛为"每运行一个实例", 天然隔离:
 * 每次 runReActLoop 新建一个 [LoopDetector] 放进 ReActStepState, 用完即弃, 无需跨
 * 任务 reset, 也允许多个并发循环各自独立检测。
 *
 * [detectLoop] 三通道检测 (P1-3 补强, 原仅精确串匹配存在缺口):
 *  1. 精确串重复 — 同命令完整串在窗口内 >= [exactThreshold] 次;
 *  2. 命令名级别重复 — 同命令**首 token** >= [nameThreshold] 次, 捕获 `ls -la` vs `ls -al`
 *     等参数等价变体绕行;
 *  3. 周期2交替 — 窗口内恰好两种命令且无相邻重复 (A,B,A,B…), 累计 >= [alternationThreshold],
 *     捕获"两条命令来回横跳"的假循环 (各自出现次数均不到阈值时仍会被拦截)。
 * 读命令/安全命令 (见 [safeCommands]) 永不触发。
 */
internal class LoopDetector(
    private val maxWindow: Int = 8,
    private val exactThreshold: Int = 5,
    private val nameThreshold: Int = 5,
    private val alternationThreshold: Int = 6
) {
    private val recentCommands = ArrayDeque<String>()
    private var consecutiveFailures = 0

    /** 安全/只读命令 — 永不触发循环检测 (与旧 PromptEngine 语义一致)。 */
    private val safeCommands = setOf(
        "agent.docs", "agent.cli", "agent.memory", "agent.profile", "agent.boost", "agent.modes",
        "agent.soul", "agent.audit", "agent.storage", "agent.sessions",
        // Linux 只读命令
        "cat", "head", "tail", "grep", "sed", "find", "stat", "ls", "less", "more", "wc", "du", "df", "file",
        "self.stats", "self.version", "self.time", "self.tools", "self.search", "self.status",
        "plugin.list", "plugin.info", "plugin.marketplace",
        "sys.battery", "sys.network", "sys.cpu", "sys.memory", "sys.storage",
    )

    /**
     * 检测循环。返回 true 表示应终止循环。
     * 注意: 检测到循环前, 该命令也会计入窗口 (供后续交替检测)。
     */
    fun detectLoop(command: String): Boolean {
        val commandName = command.substringBefore(' ').substringBefore('\t')
        if (commandName in safeCommands) return false
        recentCommands.addLast(command)
        if (recentCommands.size > maxWindow) recentCommands.removeFirst()
        val window = recentCommands.toList()
        // 1) 精确串重复
        if (window.count { it == command } >= exactThreshold) return true
        // 2) 命令名级别重复 (等价变体绕行)
        if (window.count { it.substringBefore(' ').substringBefore('\t') == commandName } >= nameThreshold) return true
        // 3) 周期2交替假循环
        if (window.size >= alternationThreshold && isAlternatingTwo(window)) return true
        return false
    }

    /** 连续失败检测。成功清零; 连续失败 >= 5 返回 true (应停止)。 */
    fun trackResult(success: Boolean): Boolean {
        if (success) { consecutiveFailures = 0; return false }
        consecutiveFailures++
        return consecutiveFailures >= 5
    }

    /** 清空窗口与失败计数 (供显式复用场景; 正常每运行新建实例无需调用)。 */
    fun reset() {
        recentCommands.clear()
        consecutiveFailures = 0
    }

    /** 窗口内恰好两种命令且无相邻重复 → 周期2交替 (A,B,A,B…)。 */
    private fun isAlternatingTwo(seq: List<String>): Boolean {
        val distinct = seq.distinct()
        if (distinct.size != 2) return false
        for (i in 1 until seq.size) {
            if (seq[i] == seq[i - 1]) return false
        }
        return true
    }
}
