// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

// ── 复杂度自动检测 (融合 QwenPaw SOUL.md + Claude Code 复杂度评分) ──

// P2 修复: 正则预编译为顶层常量 — 原实现每次调用都重复编译 (AgentViewModel 每条消息调一次)
private val RISK_REGEX = Regex("删除|卸载|rm |发布|部署|格式化|清空")
private val MODIFY_REGEX = Regex("创建|写入|修改|安装|配置|设置|编译|构建|生成")
private val SEQUENCE_REGEX = Regex("然后|之后|接着|再|并且|同时|;|；|第一步|第二步")
private val BATCH_REGEX = Regex("每个|所有|全部|批量|遍历|循环")

/**
 * 自动检测任务复杂度, 返回建议的 LoopMode.
 * 简单问答 → REACT | 明确任务 → GOAL | 复杂工程 → MISSION.
 */
internal fun detectComplexity(task: String): LoopMode {
    val score = scoreComplexity(task)
    return when {
        score <= 4 -> LoopMode.REACT
        score <= 7 -> LoopMode.GOAL
        score <= 10 -> LoopMode.MISSION
        // P2 修复: 11-15 分(最高复杂度)原落入 GOAL 与注释"复杂工程 → MISSION"矛盾 — 改为 MISSION
        else -> LoopMode.MISSION
    }
}

/**
 * 任务复杂度评分 (1-15).
 * 维度: 操作风险 + 跨域操作 + 任务长度 + 多步骤信号.
 */
internal fun scoreComplexity(task: String): Int {
    var score = 0
    if (RISK_REGEX.containsMatchIn(task)) score += 3
    else if (MODIFY_REGEX.containsMatchIn(task)) score += 2

    val domains = listOf("文件", "网络", "插件", "记忆", "系统", "浏览器", "搜索", "翻译", "应用")
    val domainHits = domains.count { task.contains(it) }
    score += domainHits.coerceAtMost(3)

    if (task.length > 200) score += 2
    else if (task.length > 80) score += 1

    if (SEQUENCE_REGEX.containsMatchIn(task)) score += 2
    if (BATCH_REGEX.containsMatchIn(task)) score += 2

    return score
}
