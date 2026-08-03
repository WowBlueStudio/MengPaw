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
 * 言简意赅 — 提示词前缀注入简洁引导（只动前缀，不删强要求句、不加反 Markdown 约束），
 * 让模型回答更简洁且不破坏流式分步与 Markdown 格式。middleware 由壳层链组装（[ConciseMiddleware]），
 * 插件开关 = middleware 内动态查询插件状态，停用即恢复原提示词。
 */
class ConcisePlugin : Plugin {

    override val metadata = PluginMetadata(
        id = PLUGIN_ID,
        name = "言简意赅",
        version = "0.2.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "提示词前缀注入简洁引导，回答更简洁且保留 Markdown 与分步思考（只动前缀）",
        minCoreVersion = "0.2.0",
        commands = listOf("concise.status")
    )

    override val commands: Map<String, CommandHandler> = mapOf(
        "status" to ::statusCmd
    )

    // ── concise.status — 变换规则与生效状态自检 ──────────────────────

    private suspend fun statusCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val active = PluginManager.globalInstance.status(PLUGIN_ID) == PluginStatus.ACTIVE
        // 模板失配自检: 最近一次变换未实际删除强要求句 → 提示词模板可能已升级
        val matched = if (active) lastTransformRemovedSentence else true
        val transformState = when {
            !active -> "⏸ 未激活（middleware 原样返回）"
            matched -> "✅ 生效（middleware 已挂载，停用插件即恢复原提示词）"
            else -> "⚠️ 模板失配（内核提示词已升级，删除句未命中 — 需同步 ConciseMiddleware 常量）"
        }
        return ExecutionResult.ok(
            "言简意赅 — 提示词前缀简洁引导\n" +
            "状态: $transformState\n" +
            "变换规则:\n" +
            "1. 前缀注入一行简洁引导（中英两版，幂等守卫防重复）\n" +
            "2. 不动强要求句（保住流式分步思考）与 Markdown 格式规范\n" +
            "自检: 查看实际提示词可发 !concise.status 或检查 Agent 回复是否保持 Markdown"
        )
    }

    companion object {
        const val PLUGIN_ID = "concise-plugin"
    }
}
