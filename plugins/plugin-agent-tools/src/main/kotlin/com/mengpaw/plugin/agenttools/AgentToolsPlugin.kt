// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.agenttools

import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.plugin.CommandHandler
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Agent 命令集插件 — Agent 导入外部 CLI 命令集（GitHub CLI / 飞书 CLI 等）,
 * 注册 per-agent 索引，紧凑摘要注入系统提示词，之后快速调用无需遍历完整命令文档。
 *
 * 命令注册为 `tools.*`（插件 id `tools-plugin` 自动派生命名空间）。
 * 命令集 JSON 清单存 `Agent文档/{agent}/tools/{name}.json`。
 */
class AgentToolsPlugin : Plugin {

    override val metadata = PluginMetadata(
        id = "tools-plugin",
        name = "Agent 命令集",
        version = "0.1.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "Agent 命令集注册 — 导入外部 CLI 命令集(gh/飞书等)，摘要注入系统提示词快速调用",
        minCoreVersion = "0.17.0",
        commands = listOf("tools.import", "tools.ls", "tools.remove", "tools.search")
    )

    override val commands: Map<String, CommandHandler> = mapOf(
        "import" to ::importCmd,
        "ls" to ::lsCmd,
        "remove" to ::removeCmd,
        "search" to ::searchCmd,
    )

    // ── tools.import <名称> <URL|JSON> ────────────────────────────────

    private suspend fun importCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) {
            return ExecutionResult.fail(
                "用法: tools.import <名称> <URL|JSON>\n" +
                "导入外部 CLI 命令集（GitHub CLI / 飞书 CLI 等）的 JSON 清单，注册后摘要注入系统提示词，Agent 可直接调用。\n" +
                "示例: tools.import gh https://example.com/gh-commands.json", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
        val name = args[0]
        val raw = args.drop(1).joinToString(" ")
        val agent = ctx.agentName ?: "MengPaw"

        val existing = AgentToolsStore.readAll(agent)
        if (existing.size >= AgentToolsStore.MAX_SETS_PER_AGENT && existing.none { it.name == name }) {
            return ExecutionResult.fail(
                "最多注册 ${AgentToolsStore.MAX_SETS_PER_AGENT} 个命令集，请先 tools.remove <名称> 释放空间", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }

        val rawText = if (raw.startsWith("http://") || raw.startsWith("https://")) {
            withContext(Dispatchers.IO) { AgentToolsStore.fetch(raw) }.fold(
                onSuccess = { it },
                onFailure = { return ExecutionResult.fail(it.message ?: "拉取失败", errorCode = ErrorCodes.ERR_INTERNAL) })
        } else raw

        return AgentToolsStore.parseAndValidate(name, rawText).fold(
            onSuccess = { set ->
                AgentToolsStore.save(agent, set).fold(
                    onSuccess = { overwritten ->
                        AgentToolsSummary.invalidate(agent)
                        ExecutionResult.ok(
                            "✅ 命令集 '${set.name}' 已导入（${set.commands.size} 条命令${if (overwritten) "，覆盖旧版本" else ""}）\n" +
                            "摘要已注入系统提示词，Agent 可直接调用。查看: tools.ls / tools.search <关键词>")
                    },
                    onFailure = { ExecutionResult.fail(it.message ?: "保存失败", errorCode = ErrorCodes.ERR_INTERNAL) }
                )
            },
            onFailure = { ExecutionResult.fail(it.message ?: "校验失败", errorCode = ErrorCodes.ERR_INVALID_INPUT) }
        )
    }

    // ── tools.ls ─────────────────────────────────────────────────────

    private suspend fun lsCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val agent = ctx.agentName ?: "MengPaw"
        val sets = AgentToolsStore.readAll(agent)
        if (sets.isEmpty()) {
            return ExecutionResult.ok(
                "(未注册命令集)\n用法: tools.import <名称> <URL|JSON> — 导入 GitHub CLI / 飞书 CLI 等外部命令集")
        }
        val out = buildString {
            appendLine("## 已注册命令集 (${sets.size}/${AgentToolsStore.MAX_SETS_PER_AGENT})")
            appendLine("| 名称 | 命令数 | 来源 | 导入时间 |")
            appendLine("|---|---|---|---|")
            sets.forEach { set ->
                appendLine("| ${set.name} | ${set.commands.size} | ${set.source.take(40).ifBlank { "手动粘贴" }} | ${set.importedAt} |")
            }
            appendLine()
            appendLine("细节: agent.read ${AgentToolsStore.toolsDir(agent).absolutePath}/<名称>.json")
            appendLine("检索: tools.search <关键词>")
        }
        return ExecutionResult.ok(out)
    }

    // ── tools.remove <名称> ──────────────────────────────────────────

    private suspend fun removeCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) {
            return ExecutionResult.fail("用法: tools.remove <名称>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
        val agent = ctx.agentName ?: "MengPaw"
        val name = args[0]
        if (!AgentToolsStore.remove(agent, name)) {
            return ExecutionResult.fail("命令集 '$name' 不存在。tools.ls 查看已注册的命令集。", errorCode = ErrorCodes.ERR_NOT_FOUND)
        }
        AgentToolsSummary.invalidate(agent)
        return ExecutionResult.ok("已移除命令集 '$name'，系统提示词摘要已同步更新")
    }

    // ── tools.search <关键词> ────────────────────────────────────────

    private suspend fun searchCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val q = args.joinToString(" ").trim().lowercase()
        if (q.isEmpty()) {
            return ExecutionResult.fail("用法: tools.search <关键词>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
        val agent = ctx.agentName ?: "MengPaw"
        val hits = AgentToolsStore.readAll(agent).flatMap { set ->
            set.commands.filter { cmd ->
                cmd.name.lowercase().contains(q) || cmd.description.lowercase().contains(q)
            }.map { matched -> set to matched }
        }
        if (hits.isEmpty()) {
            return ExecutionResult.ok("未找到匹配 '$q' 的命令。tools.ls 查看全部命令集，或 tools.import 导入新命令集。")
        }
        val out = buildString {
            appendLine("## 命中 (${hits.size})")
            hits.forEach { (set, cmd) ->
                appendLine("### [${set.name}] ${cmd.name}")
                if (cmd.description.isNotBlank()) appendLine(cmd.description)
                if (cmd.usage.isNotBlank()) appendLine("   用法: ${cmd.usage}")
                appendLine()
            }
        }
        return ExecutionResult.ok(out.trim())
    }
}
