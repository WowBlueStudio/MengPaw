// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.concise

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.plugin.CommandHandler
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginManager
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginStatus
import com.mengpaw.kernel.plugin.PluginType

/**
 * 言简意赅 — 去除系统提示词中的结构性输出干扰（强制 Thought/Action 样板、Markdown 装饰），
 * 让模型回答更简洁。middleware 由壳层链组装（[ConciseMiddleware]），
 * 插件开关 = middleware 内动态查询插件状态，停用即恢复原提示词。
 */
class ConcisePlugin : Plugin {

    override val metadata = PluginMetadata(
        id = PLUGIN_ID,
        name = "言简意赅",
        version = "0.1.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "去除系统提示词中的结构性输出干扰（强制 Thought/Action 样板、Markdown 装饰），让模型回答更简洁",
        minCoreVersion = "0.2.0",
        commands = listOf("concise.status")
    )

    override val commands: Map<String, CommandHandler> = mapOf(
        "status" to ::statusCmd
    )

    // ── concise.status — 变换规则与生效状态自检 ──────────────────────

    private suspend fun statusCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val active = PluginManager.globalInstance.status(PLUGIN_ID) == PluginStatus.ACTIVE
        return ExecutionResult.ok(
            "言简意赅 — 系统提示词精简\n" +
            "状态: ${if (active) "✅ 生效（middleware 已挂载，停用插件即恢复原提示词）" else "⏸ 未激活（middleware 原样返回）"}\n" +
            "变换规则:\n" +
            "1. 删除强制 Thought → Action → Action Input 完整序列要求（中英两版）\n" +
            "2. 追加反 Markdown 装饰约束（回复默认纯文本，代码/命令/路径可用代码块）\n" +
            "自检: 查看实际提示词可发 !concise.status 或检查 Agent 回复是否还带结构性样板"
        )
    }

    companion object {
        const val PLUGIN_ID = "concise-plugin"
    }
}
