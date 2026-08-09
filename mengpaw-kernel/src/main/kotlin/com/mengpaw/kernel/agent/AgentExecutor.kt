// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.Pipeline

/**
 * Built-in agent.* CLI commands — Agent document management.
 *
 * memory.* 18 条命令已拆至 [AgentMemoryExecutor] (2026-08-01, ≥50KB 文件拆分),
 * 经 `+ memoryExecutor.commands` 合并注册, 命令名与命名空间不变。
 * 文件命令 (read/write/ls/rm/mkdir/output) 拆至 [AgentFileCommands],
 * 存储/清理/梦境拆至 [AgentStorageCommands], 会话索引拆至 [AgentSessionCommands]
 * (400 行文件拆分)。
 */
class AgentExecutor(private val docManager: AgentDocManager) {

    /** Resolve the effective agent name, falling back to default. */
    private fun agentName(ctx: ExecutionContext) = ctx.agentName ?: "agent"

    /** 记忆三轨执行器 (memory.* 命令, 拆自本类)。 */
    private val memoryExecutor = AgentMemoryExecutor()

    /** 文件命令执行器 (read/write/ls/rm/mkdir/output)。 */
    private val fileCommands = AgentFileCommands()

    /** 存储/清理/梦境/浏览器工具命令执行器。 */
    private val storageCommands = AgentStorageCommands()

    /** 会话索引命令执行器 (sessions/session.*)。 */
    private val sessionCommands = AgentSessionCommands()

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        // 注意: commands 键集是 CLI.md agent 表 (AgentDocManager.registeredAgentCommands) 的
        // 唯一来源 — 新增/重命名命令后 CLI.md 自动反映, 无需双份维护 (发现性铁律 v0.31.0)。
        "docs" to ::docs,
        "cli" to ::cli,
        "modes" to ::modes,
        "boost" to ::boost,
        "boost.delete" to ::boostDelete,
        "profile" to ::profile,
        "soul" to ::soul,
        "audit" to ::audit,
        "browser-tools" to storageCommands::browserTools,
        "dream" to storageCommands::dream,
        "cleanup" to storageCommands::cleanup,
        "storage" to storageCommands::storageReport,
        "sessions" to sessionCommands::sessions,
        "session.delete" to sessionCommands::sessionDelete,
        "session.archive" to sessionCommands::sessionArchive,
        "session.current" to sessionCommands::sessionCurrent,
        "read" to fileCommands::readFile,
        "write" to fileCommands::writeFile,
        "ls" to fileCommands::listFiles,
        "rm" to fileCommands::deleteFile,
        "mkdir" to fileCommands::makeDir,
        "output" to fileCommands::output,
        "policy" to ::policy
    ) + memoryExecutor.commands

    init {
        // 注入注册键集供 CLI.md agent 表动态生成 — 新增命令自动入手册
        docManager.registeredAgentCommands = commands.keys.sorted()
    }

    // ── P1-7(自检报告): 命令前缀级权限策略 ─────────────────────────────

    /** 授权前缀形态校验 — 命令名形式 (小写字母/数字/点/下划线/中划线)。 */
    private val PREFIX_PATTERN = Regex("^[a-z0-9][a-z0-9.\\-_]*$")

    /**
     * agent.policy — per-agent 命令前缀级授权 (自检报告 P1-7)。
     * 多 Agent(tribe) 场景按 agent 粒度放开"受限但未硬禁"的命令 (blockList 恒优先, 不可绕过)。
     * 用法:
     *   agent.policy                                  → 列出全部授权
     *   agent.policy allow <命令前缀> [--to <agent>]  → 给指定 agent (默认自己) 授权命令前缀
     *   agent.policy deny <命令前缀> [--to <agent>]   → 收回授权
     */
    private suspend fun policy(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val policy = com.mengpaw.kernel.security.PolicyStore.sharedPolicy()
        if (args.isEmpty()) {
            val grants = policy.allAgentPolicies()
            if (grants.isEmpty()) {
                return ExecutionResult.ok(buildString {
                    appendLine("(无任何 agent 级授权)")
                    appendLine("用法: agent.policy allow <命令前缀> [--to <agent>] — 给指定 agent (默认自己) 放开受限命令;")
                    appendLine("      agent.policy deny <命令前缀> [--to <agent>] — 收回授权。")
                    appendLine("示例: agent.policy allow sys.screenshot --to 研究员")
                })
            }
            return ExecutionResult.ok(buildString {
                appendLine("Agent 级命令前缀授权:")
                grants.toSortedMap().forEach { (agent, prefixes) ->
                    appendLine("  • $agent: ${prefixes.joinToString(", ")}")
                }
                appendLine("(全局禁用命令如 proc.exec 不受授权影响, 恒拒绝)")
            })
        }
        val action = args[0]
        if (action != "allow" && action != "deny") {
            return ExecutionResult.fail(
                "用法: agent.policy allow|deny <命令前缀> [--to <agent>]\n" +
                "示例: agent.policy allow sys.screenshot --to 研究员",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        }
        val prefix = args.getOrNull(1)?.trim().orEmpty()
        if (prefix.isBlank() || prefix.length > 64 || !PREFIX_PATTERN.matches(prefix)) {
            return ExecutionResult.fail(
                "非法命令前缀: '$prefix' — 应为命令名形式 (如 sys.screenshot / net.curl)",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        }
        val toIdx = args.indexOf("--to")
        val target = if (toIdx >= 0 && toIdx + 1 < args.size && args[toIdx + 1].isNotBlank()) {
            args[toIdx + 1]
        } else agentName(ctx)
        if (target.isBlank()) {
            return ExecutionResult.fail("无法确定目标 agent — 请用 --to <agent> 指定", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }

        if (action == "allow") policy.grantAgent(target, prefix) else policy.revokeAgent(target, prefix)
        val persisted = com.mengpaw.kernel.security.PolicyStore.save()
        return ExecutionResult.ok(buildString {
            appendLine("已${if (action == "allow") "授权" else "收回"} '$prefix' ${if (action == "allow") "给" else "自"} $target")
            appendLine("当前授权: ${policy.agentPolicies(target).joinToString(", ").ifEmpty { "无" }}")
            if (action == "allow") {
                val blockedHit = policy.getBlockList().any { prefix.startsWith(it) || it.startsWith(prefix) }
                if (blockedHit) {
                    appendLine("⚠️ 注意: 该前缀命中全局禁用表 (${policy.getBlockList().joinToString(", ")}), 授权不会生效")
                }
            }
            if (!persisted) appendLine("⚠️ 持久化失败 — 授权仅本次运行生效")
        })
    }

    private suspend fun docs(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val docs = docManager.listDocs()
        return ExecutionResult.ok("Agent 文档 (${docs.size}):\n" + docs.joinToString("\n") { "  • $it" })
    }

    /** Delete boost.md — Agent has completed initialization. */
    private suspend fun boostDelete(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val agent = agentName(ctx)
        val ok = AgentDocs.deleteBoost(agent)
        return if (ok) ExecutionResult.ok("boost.md 已删除。你已完成初始化，不再需要引导文件。")
        else ExecutionResult.ok("boost.md 不存在——你早已完成初始化。")
    }

    /** Slash command mode menu — 8 execution modes (modes.md, template-provided). */
    private suspend fun modes(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val modesDoc = docManager.getDoc(AgentDocType.MODES)
        if (modesDoc.isBlank()) return ExecutionResult.ok("(modes.md 不存在)")
        return ExecutionResult.ok(modesDoc)
    }

    /** First-run bootstrap ritual — guide the Agent through initial setup. */
    private suspend fun boost(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val boostDoc = docManager.getDoc(AgentDocType.BOOST)
        if (boostDoc.isBlank()) return ExecutionResult.ok(buildString {
            appendLine("(boost.md 不存在 — 你已完成初始化)")
            appendLine()
            appendLine("这说明你已经不是第一次醒来了。你的 soul/profile/memory 已经建立。")
            appendLine("继续做你该做的事。")
        })
        return ExecutionResult.ok(boostDoc)
    }

    private suspend fun cli(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        // v0.34.3: CLI.md 工作区文档删除 — 命令发现走 self.tools/self.search,
        // agent.cli 保留为轻量指引入口 (22KB 全表不再每轮负担)。
        return ExecutionResult.ok(
            "## 命令发现指引 (v0.34.3, CLI.md 已移除)\n\n" +
            "- 完整命令列表: self.tools [命名空间]\n" +
            "- 自然语言搜索: self.search <描述> [--top N]\n" +
            "- 端口参考: self.ports\n\n" +
            "**参数纯净规则**: 路径/名称/URL/时间戳参数必须是单个参数, 禁止附加描述文本; 含空格用引号包裹。\n" +
            "**安全分级**: 普通放行 / 中危需信任权限 / 高危弹窗确认 (JSON+reason)。"
        )
    }

    private suspend fun profile(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(docManager.getDoc(AgentDocType.PROFILE))
    }

    private suspend fun soul(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(docManager.getDoc(AgentDocType.SOUL))
    }

    /** View command audit trail (security feature). */
    private suspend fun audit(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val count = args.firstOrNull()?.toIntOrNull() ?: 50
        val entries = Pipeline.getGlobalAuditLog(count)
        if (entries.isEmpty()) return ExecutionResult.ok("(No audit entries)")
        return ExecutionResult.ok(entries.joinToString("\n") { e ->
            "${if (e.success) "OK" else "FAIL"} [${e.sessionId}] ${e.command}: ${e.output.take(80)}"
        })
    }
}
