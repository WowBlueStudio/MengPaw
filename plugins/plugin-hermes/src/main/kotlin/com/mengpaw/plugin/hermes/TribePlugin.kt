// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.hermes

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.acp.AcpServer
import com.mengpaw.kernel.acp.AcpTransport
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 部落协作 (Tribe) 多 Agent 协作插件。
 *
 * 取代 HermesPlugin，提供文件系统 + ACP 实时双模协作能力：
 * - 团队管理 (team invite/remove/list)
 * - Agent 发现 (discover)
 * - 委派任务 (delegate) 带优先级 / 超时 / 指数退避重试
 * - 提问 (ask)
 * - 共享记忆 (memo) 支持 ACP 推送
 * - 角色管理 (role)
 * - Kanban 看板状态机 (task list/show/cancel/retry)
 * - ACP 心跳存活检测 (peers/ping)
 * - 服务生命周期 (start/stop/status)
 *
 * ## 双模架构
 * - **文件模式**: 纯文件系统协作（离线 / 降级），写入 inbox + team/memos
 * - **ACP 模式**: 通过 ACP 协议实时推送，Kanban 状态追踪（在线）
 *
 * ## 向后兼容
 * `hermes.*` 命令通过 [TribeBackwardCompat] 路由到 `tribe.*`，显示弃用提示。
 */
class TribePlugin : Plugin {

    override val metadata = PluginMetadata(
        id = "tribe-plugin",
        name = "部落协作",
        version = "0.5.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "多 Agent 部落协作：LAN 自动组队、Kanban 委派（优先级+超时+嵌套链）、LLM 路由、任务模板、Fleet 并行、广播讨论、记忆去重压缩、上下文裁剪、ACP 实时消息、心跳检测",
        minCoreVersion = "0.2.0",
        commands = listOf(
            "tribe.start", "tribe.stop", "tribe.status",
            "tribe.team", "tribe.discover", "tribe.delegate",
            "tribe.ask", "tribe.memo", "tribe.role",
            "tribe.template", "tribe.route", "tribe.fleet",
            "tribe.chat", "tribe.discuss",
            "tribe.task.list", "tribe.task.show", "tribe.task.cancel", "tribe.task.retry", "tribe.task.done",
            "tribe.peers", "tribe.ping", "tribe.cleanup",
            "hermes.team", "hermes.discover", "hermes.delegate",
            "hermes.ask", "hermes.memo", "hermes.role"
        )
    )

    // ── 外部依赖注入（参照 MemoryTwinPlugin 模式） ────────────────

    companion object {
        @Volatile var agentName: String = "MengPaw"
        @Volatile var agentId: String = "default"
        @Volatile var acpServer: AcpServer? = null
        @Volatile var acpTransport: AcpTransport? = null
        @Volatile var llmProvider: com.mengpaw.kernel.llm.LlmProvider? = null
        @JvmField @Volatile var isRunning: Boolean = false
    }

    // ── 内部状态 ───────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kanbanBoard = TribeKanbanBoard()
    private val delegateEngine = TribeDelegateEngine(kanbanBoard, acpServer, scope)
    private val heartbeatMonitor = TribeHeartbeatMonitor(agentName, agentId, acpServer, scope)
    private var tribeAcpHandler: TribeAcpHandler? = null
    private val fleetEngine = TribeFleetEngine { task, targetId, targetName ->
        delegateEngine.delegate(task, targetId, targetName)
    }

    // ── 文件系统辅助 ───────────────────────────────────────────

    private val teamDir: File get() = File(DataPaths.TEAM).also { it.mkdirs() }

    @Deprecated("Use TribeTeamStore.discoverMembers()")
    private fun discoverTeamMembers(): List<TeamMember> = TribeTeamStore.discoverMembers()

    // ── 命令注册 ───────────────────────────────────────────────

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        // ── 生命周期 ──
        "start" to ::cmdStart,
        "stop" to ::cmdStop,
        "status" to ::cmdStatus,
        // ── 基础文件系统命令（原有 hermes.* 的 tribe 版本） ──
        "team" to ::cmdTeam,
        "discover" to ::cmdDiscover,
        "delegate" to ::cmdDelegate,
        "ask" to ::cmdAsk,
        "memo" to ::cmdMemo,
        "role" to ::cmdRole,
        // ── 模板 / 路由 / Fleet ──
        "template" to ::cmdTemplate,
        "route" to ::cmdRoute,
        "fleet" to ::cmdFleet,
        // ── Kanban 看板 ──
        "task.list" to ::cmdTaskList,
        "task.show" to ::cmdTaskShow,
        "task.cancel" to ::cmdTaskCancel,
        "task.retry" to ::cmdTaskRetry,
        "task.done" to ::cmdTaskDone,
        // ── 聊天 / 讨论 ──
        "chat" to ::cmdChat,
        "discuss" to ::cmdDiscuss,
        // ── 心跳 / 对端 ──
        "peers" to ::cmdPeers,
        "ping" to ::cmdPing,
        // ── 维护 ──
        "cleanup" to ::cmdCleanup,
        // ── 向后兼容（hermes.* → tribe.*） ──
        "hermes.team" to { a, c -> TribeBackwardCompat.team(a, c, ::cmdTeam) },
        "hermes.discover" to { a, c -> TribeBackwardCompat.discover(a, c, ::cmdDiscover) },
        "hermes.delegate" to { a, c -> TribeBackwardCompat.delegate(a, c, ::cmdDelegate) },
        "hermes.ask" to { a, c -> TribeBackwardCompat.ask(a, c, ::cmdAsk) },
        "hermes.memo" to { a, c -> TribeBackwardCompat.memo(a, c, ::cmdMemo) },
        "hermes.role" to { a, c -> TribeBackwardCompat.role(a, c, ::cmdRole) }
    )

    // ─────────────────────────────────────────────────────────────
    // 生命周期命令
    // ─────────────────────────────────────────────────────────────

    private suspend fun cmdStart(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (isRunning) return ExecutionResult.ok("部落协作已在运行中。")

        val server = acpServer
        if (server == null) {
            return ExecutionResult.ok("⚠️ ACP 未启动，将以文件系统模式运行（仅本地协作）。使用 `self.acp start` 启动 ACP。")
        }

        // 恢复 Kanban inflight 任务
        val recovered = kanbanBoard.recoverInFlight()
        if (recovered.isNotEmpty()) {
            com.mengpaw.kernel.KernelLog.i("TribePlugin", "恢复 ${recovered.size} 个 inflight 任务")
        }

        // 创建并注册 ACP handler
        val handler = TribeAcpHandler(agentName, kanbanBoard, delegateEngine, heartbeatMonitor)
        tribeAcpHandler = handler
        server.registerHandler(handler)

        // 启动委派引擎和心跳
        delegateEngine.start()
        heartbeatMonitor.start()

        // 启动收件箱监视器（Agent 自动感知新任务）
        TribeInboxWatcher.start(scope)

        isRunning = true
        return ExecutionResult.ok("✅ 部落协作已启动。ACP 模式 — 支持实时委派、超时重试、心跳检测、收件箱感知。")
    }

    private suspend fun cmdStop(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (!isRunning) return ExecutionResult.ok("部落协作未在运行。")
        // P1 修复: 先停 handler 再停引擎 — 停止后 ACP 消息不再进入任务执行
        tribeAcpHandler?.active = false
        delegateEngine.stop()
        heartbeatMonitor.stop()
        heartbeatMonitor.markAllOffline()
        TribeInboxWatcher.stop()
        tribeAcpHandler = null
        isRunning = false
        return ExecutionResult.ok("🛑 部落协作已停止。")
    }

    private suspend fun cmdStatus(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val running = isRunning
        val acpOk = acpServer != null
        val onlinePeers = if (running) heartbeatMonitor.getOnlinePeers().size else 0
        val allTasks = kanbanBoard.list(limit = 100)
        val pendingCount = allTasks.count { it.status == TaskStatus.PENDING || it.status == TaskStatus.ASSIGNED }
        val runningCount = allTasks.count { it.status == TaskStatus.RUNNING }
        val completedCount = allTasks.count { it.status == TaskStatus.COMPLETED }

        return ExecutionResult.ok("""
## 部落协作状态

| 项目 | 状态 |
|------|------|
| 服务状态 | ${if (running) "✅ 运行中" else "⏸️ 已停止"} |
| ACP 连接 | ${if (acpOk) "✅ 已连接" else "⚠️ 未连接（文件模式）"} |
| 在线对端 | $onlinePeers |
| 待处理任务 | $pendingCount |
| 执行中任务 | $runningCount |
| 已完成任务 | $completedCount |
        """.trimIndent())
    }

    // ─────────────────────────────────────────────────────────────
    // 团队管理
    // ─────────────────────────────────────────────────────────────

    private suspend fun cmdTeam(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val members = discoverTeamMembers()
        if (args.isEmpty()) {
            if (members.isEmpty()) return ExecutionResult.ok("当前无团队成员。使用 `tribe.discover` 发现其他 Agent 并用 `tribe.team invite` 邀请。")
            return ExecutionResult.ok("## 团队 (${members.size} 成员)\n\n" + members.joinToString("\n") { m ->
                "### ${m.name}\n- 角色: ${m.role}\n- ID: ${m.id}\n- 状态: ${m.status}\n- 擅长: ${m.skills}"
            })
        }
        if (args[0] == "invite" && args.size >= 3) {
            val id = args[1]; val r = args.drop(2).joinToString(" ")
            val file = File(teamDir, "$id.md")
            return try {
                file.writeText("name: $id\nrole: $r\njoined: ${System.currentTimeMillis()}\nstatus: active")
                // 注册到心跳监控
                heartbeatMonitor.registerPeer(id, id)
                ExecutionResult.ok("✅ 已邀请 Agent $id 加入团队，角色: $r")
            } catch (e: Exception) {
                ErrorCollector.report(e, "TribePlugin.team")
                ExecutionResult.fail("Write error: ${e.message}", errorCode = ErrorCodes.ERR_IO)
            }
        }
        if (args[0] == "remove" && args.size >= 2) {
            File(teamDir, "${args[1]}.md").delete()
            return ExecutionResult.ok("已将 ${args[1]} 移出团队")
        }
        return ExecutionResult.fail("Usage: tribe.team [invite <id> <role>|remove <id>]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
    }

    // ─────────────────────────────────────────────────────────────
    // 发现
    // ─────────────────────────────────────────────────────────────

    private suspend fun cmdDiscover(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        // --lan: 自动组队 — 从 FrameworkPlugin 发现的局域网框架同步成员
        if (args.contains("--lan")) {
            val force = args.contains("--force")
            val added = TribeLanDiscovery.syncFromLan(force = force)
            added.forEach { heartbeatMonitor.registerPeer(it, it) }
            if (added.isEmpty()) {
                return ExecutionResult.ok("LAN 自动组队完成，无新增成员（全部已存在，用 --force 覆盖）。")
            }
            return ExecutionResult.ok("🌐 LAN 自动组队：已将 ${added.size} 个 Agent 加入团队:\n" +
                added.joinToString("\n") { "• $it" })
        }

        // 默认: 扫描本地 Agent 目录
        val agentsDir = File(DataPaths.AGENTS)
        val dirs = try { agentsDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList() } catch (_: Exception) { emptyList() }
        val discovered = dirs.filter { it.name != "team" }.map { dir ->
            val profile = File(dir, "Profile.md").let {
                if (it.exists()) try { it.readText() } catch (e: Exception) { ErrorCollector.report(e, "TribePlugin.discover"); "(读取失败)" } else "(无档案)"
            }
            val name = Regex("名称:\\s*(.+)").find(profile)?.groupValues?.get(1)?.trim() ?: dir.name
            val role = Regex("角色:\\s*(.+)").find(profile)?.groupValues?.get(1)?.trim() ?: "未设定"
            "• $name (ID: ${dir.name}) — 角色: $role"
        }
        val hint = if (discovered.isEmpty())
            "未发现其他 Agent。创建新 Agent: 在 Agent文档/ 下新建目录并写入 Profile.md。"
        else "## 发现的 Agent (${discovered.size})\n\n${discovered.joinToString("\n")}"
        return ExecutionResult.ok("$hint\n\n> 💡 使用 `tribe.discover --lan` 自动将局域网框架成员加入团队。")
    }

    // ─────────────────────────────────────────────────────────────
    // 委派
    // ─────────────────────────────────────────────────────────────

    private suspend fun cmdDelegate(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: tribe.delegate <agent-name> <task> [--priority P0|P1|P2] [--timeout ms] [--mode file|acp|auto] [--template <name>] [--route]",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)

        // 解析参数
        val parsed = parseFlags(args)
        val posArgs = parsed.positional
        val flags = parsed.flags
        if (posArgs.isEmpty()) return ExecutionResult.fail(
            "Usage: tribe.delegate <agent-name> <task> [--priority P0|P1|P2]", errorCode = ErrorCodes.ERR_INVALID_INPUT)

        val priority = try { flags["priority"]?.let { TaskPriority.valueOf(it.uppercase()) } } catch (_: Exception) { null } ?: TaskPriority.P1
        val timeoutMs = flags["timeout"]?.toLongOrNull() ?: 120_000L
        val mode = try { flags["mode"]?.let { DelegateMode.valueOf(it.uppercase()) } } catch (_: Exception) { null } ?: DelegateMode.AUTO

        val members = discoverTeamMembers()

        // --route: 用 LLM 自动选择目标 Agent（此时 posArgs 全是任务描述）
        val useRoute = flags.containsKey("route")
        val target: String
        var taskDesc: String
        if (useRoute) {
            if (members.isEmpty()) return ExecutionResult.fail("团队为空，先用 tribe.discover --lan 自动组队。", errorCode = ErrorCodes.ERR_NOT_FOUND)
            val llm = llmProvider ?: return ExecutionResult.fail("LLM 未配置，无法路由。请先在设置中配置 API Key。", errorCode = ErrorCodes.ERR_INTERNAL)
            taskDesc = posArgs.joinToString(" ")
            val routeResult = TribeRouter.route(taskDesc, members, kanbanBoard.snapshotStatuses(), llm)
            target = routeResult.agent
            if (routeResult.agent.isBlank()) return ExecutionResult.fail("路由失败: ${routeResult.reason}", errorCode = ErrorCodes.ERR_INTERNAL)
        } else {
            if (posArgs.size < 2) return ExecutionResult.fail(
                "Usage: tribe.delegate <agent-name> <task> [--priority P0|P1|P2]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            target = posArgs[0]
            taskDesc = posArgs.drop(1).joinToString(" ")
        }

        // --template: 用预置模板包装任务描述
        val templateName = flags["template"]
        if (templateName != null) {
            val rendered = TribeTemplates.render(templateName, taskDesc, flags)
                ?: return ExecutionResult.fail("未知模板: $templateName。可用模板:\n${TribeTemplates.describe()}", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            taskDesc = rendered
        }

        // --context [maxChars]: 附加裁剪后的对话上下文（特性 10）
        val contextFlag = flags["context"]
        if (contextFlag != null) {
            val maxChars = contextFlag.toIntOrNull() ?: 2000
            val trim = TribeContextTrim.trimContext(ctx.sessionId.take(8), maxChars)
            if (trim.text.isNotBlank()) {
                taskDesc += TribeContextTrim.formatForTask(trim)
            }
        }

        val member = members.find { it.name == target || it.id == target }
            ?: return ExecutionResult.fail("Agent '$target' 不在团队中。先用 tribe.discover 发现并 tribe.team invite 邀请。",
                errorCode = ErrorCodes.ERR_NOT_FOUND)

        // --parent <taskId>: 嵌套委派链（特性 7）— 深度限制 + 环形检测
        var parentTaskId: String? = null
        var depth = 0
        val parentFlag = flags["parent"]
        if (parentFlag != null) {
            val parent = kanbanBoard.get(parentFlag)
                ?: return ExecutionResult.fail("父任务不存在: $parentFlag", errorCode = ErrorCodes.ERR_NOT_FOUND)
            if (parent.depth >= 3) return ExecutionResult.fail(
                "嵌套委派最多 3 层（父任务 depth=${parent.depth} 已达上限）。", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            // 环形检测: 沿父链检查目标是否已在链路中出现
            var cursor: TribeTask? = parent
            val chainAgents = mutableSetOf(cursor!!.fromAgent, cursor.toAgent)
            while (cursor?.parentTaskId != null) {
                cursor = kanbanBoard.get(cursor.parentTaskId!!)
                if (cursor != null) {
                    chainAgents.add(cursor.fromAgent)
                    chainAgents.add(cursor.toAgent)
                }
            }
            if (member.id in chainAgents || member.name in chainAgents) {
                return ExecutionResult.fail(
                    "检测到委派环: Agent '$target' 已在当前委派链中（A→B→A 不允许）。", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            }
            parentTaskId = parent.id
            depth = parent.depth + 1
        }

        // 创建 TribeTask
        val task = TribeTask(
            title = taskDesc.take(200),
            description = taskDesc,
            priority = priority,
            fromAgent = ctx.sessionId.take(8),
            toAgent = member.id,
            timeoutMs = timeoutMs,
            delegateMode = mode,
            parentTaskId = parentTaskId,
            depth = depth
        )

        // 通过 DelegateEngine 委派
        if (isRunning && acpServer != null && mode != DelegateMode.FILE) {
            return delegateEngine.delegate(task, member.id, member.name)
        }

        // 文件模式回退
        val inboxDir = File(DataPaths.AGENTS, "${member.id}/inbox").also { it.mkdirs() }
        val taskFile = File(inboxDir, "task_${System.currentTimeMillis()}.md")
        return try {
            taskFile.writeText("""
# 委派任务
- 来自: ${ctx.sessionId}
- 任务ID: ${task.id}
- 优先级: ${priority.label}
- 时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}

## 任务
$taskDesc

## 响应方式
完成后将结果写入 memory/ 目录（中期记忆）并通过 tribe.memo 通知。
""".trimIndent())
            ExecutionResult.ok("✅ 任务已委派给 ${member.name}（文件模式）。任务ID: ${task.id}")
        } catch (e: Exception) {
            ErrorCollector.report(e, "TribePlugin.delegate")
            ExecutionResult.fail("Write error: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 提问
    // ─────────────────────────────────────────────────────────────

    private suspend fun cmdAsk(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail(
            "Usage: tribe.ask <agent-name> <question>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val target = args[0]; val question = args.drop(1).joinToString(" ")
        val members = discoverTeamMembers()
        val member = members.find { it.name == target || it.id == target }
            ?: return ExecutionResult.fail("Agent '$target' 不在团队中。", errorCode = ErrorCodes.ERR_NOT_FOUND)

        val inboxDir = File(DataPaths.AGENTS, "${member.id}/inbox").also { it.mkdirs() }
        val qFile = File(inboxDir, "ask_${System.currentTimeMillis()}.md")
        return try {
            qFile.writeText("""
# 提问
- 来自: ${ctx.sessionId}
- 问题: $question
""".trimIndent())
            ExecutionResult.ok("✅ 已向 ${member.name} 提问。等待对方通过 tribe.memo 回复。")
        } catch (e: Exception) {
            ErrorCollector.report(e, "TribePlugin.ask")
            ExecutionResult.fail("Write error: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 共享记忆
    // ─────────────────────────────────────────────────────────────

    private suspend fun cmdMemo(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        // --compact: 手动压缩最旧的记忆为摘要
        if (args.contains("--compact")) {
            val llm = llmProvider ?: return ExecutionResult.fail(
                "LLM 未配置，无法压缩。请先在设置中配置 API Key。", errorCode = ErrorCodes.ERR_INTERNAL)
            val deleted = TribeMemoStore.compactOldest(llm)
            return ExecutionResult.ok("✅ 已压缩 ${deleted} 条记忆为摘要。当前剩余: ${TribeMemoStore.count()} 条")
        }

        // 无参数: 列出最近 10 条（含去重/压缩后的记忆）
        if (args.isEmpty()) {
            val memos = TribeMemoStore.listRecent(10)
            if (memos.isEmpty()) return ExecutionResult.ok("(无团队共享记忆)")
            return ExecutionResult.ok(memos.joinToString("\n---\n") {
                try { it.readText().take(300) } catch (e: Exception) { ErrorCollector.report(e, "TribePlugin.memo"); "(read error)" }
            })
        }
        val content = args.joinToString(" ")
        return try {
            when (val result = TribeMemoStore.publish(content, ctx.sessionId)) {
                is TribeMemoStore.PublishResult.Duplicate -> ExecutionResult.ok("🔁 共享记忆内容重复（指纹 ${result.hash.take(8)}），已跳过。")
                is TribeMemoStore.PublishResult.Published -> {
                    // 如果 ACP 运行中，广播 SHARE_MEMORY
                    if (isRunning) {
                        val server = acpServer
                        if (server != null) {
                            val msg = com.mengpaw.kernel.acp.AcpMessage.shareMemory(agentId, "*", content.take(200))
                            try { server.sendViaTransport(msg) } catch (_: Exception) {}
                        }
                    }
                    // 超阈值自动压缩
                    val compacted = TribeMemoStore.compactIfNeeded(llmProvider)
                    val compactNote = if (compacted > 0) "\n📦 已自动压缩 $compacted 条旧记忆为摘要。" else ""
                    ExecutionResult.ok("✅ 共享记忆已发布。${TribeMemoStore.count()} 条$compactNote")
                }
            }
        } catch (e: Exception) {
            ErrorCollector.report(e, "TribePlugin.memo")
            ExecutionResult.fail("Write error: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 角色
    // ─────────────────────────────────────────────────────────────

    private suspend fun cmdRole(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: tribe.role <role-description>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val roleDesc = args.joinToString(" ")
        val profile = File(DataPaths.AGENTS, "${ctx.sessionId.take(8)}/Profile.md")
        val current = if (profile.exists()) try { profile.readText() } catch (e: Exception) { ErrorCollector.report(e, "TribePlugin.role"); "" } else ""
        val updated = if (current.contains("角色:"))
            current.replace(Regex("角色:\\s*.+"), "角色: $roleDesc")
        else current + "\n角色: $roleDesc"
        profile.parentFile?.mkdirs()
        return try {
            profile.writeText(updated)
            ExecutionResult.ok("✅ 角色已设定: $roleDesc")
        } catch (e: Exception) {
            ErrorCollector.report(e, "TribePlugin.role")
            ExecutionResult.fail("Write error: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 任务模板
    // ─────────────────────────────────────────────────────────────

    private suspend fun cmdTemplate(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) {
            return ExecutionResult.ok("## 部落任务模板\n\n" + TribeTemplates.describe() + "\n\n用法: `tribe.delegate <agent> --template <name> <内容>`")
        }
        val t = TribeTemplates.all.find { it.name == args[0] }
            ?: return ExecutionResult.fail("未知模板: ${args[0]}。可用:\n${TribeTemplates.describe()}", errorCode = ErrorCodes.ERR_NOT_FOUND)
        return ExecutionResult.ok("### ${t.name} — ${t.desc}\n\n${t.skeleton}")
    }

    // ─────────────────────────────────────────────────────────────
    // LLM 能力路由
    // ─────────────────────────────────────────────────────────────

    private suspend fun cmdRoute(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: tribe.route <task-description>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val taskDesc = args.joinToString(" ")
        val members = discoverTeamMembers()
        if (members.isEmpty()) return ExecutionResult.fail(
            "团队为空。先用 `tribe.discover --lan` 自动组队，或用 `tribe.team invite <id> <role>` 邀请。", errorCode = ErrorCodes.ERR_NOT_FOUND)
        val llm = llmProvider ?: return ExecutionResult.fail(
            "LLM 未配置，无法路由。请先在设置中配置 API Key。", errorCode = ErrorCodes.ERR_INTERNAL)

        val result = TribeRouter.route(taskDesc, members, kanbanBoard.snapshotStatuses(), llm)
        if (result.agent.isBlank()) return ExecutionResult.fail("路由失败: ${result.reason}", errorCode = ErrorCodes.ERR_INTERNAL)
        val member = members.find { it.id == result.agent }
        return ExecutionResult.ok("🎯 推荐: **${member?.name ?: result.agent}**（置信度 ${(result.confidence * 100).toInt()}%）\n\n理由: ${result.reason}\n\n可直接执行: `tribe.delegate ${member?.name ?: result.agent} --route <任务>`")
    }

    // ─────────────────────────────────────────────────────────────
    // Fleet 并行执行
    // ─────────────────────────────────────────────────────────────

    private suspend fun cmdFleet(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: tribe.fleet <task>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val task = args.joinToString(" ")
        val members = discoverTeamMembers()
        if (members.isEmpty()) return ExecutionResult.fail(
            "团队为空。先用 `tribe.discover --lan` 自动组队。", errorCode = ErrorCodes.ERR_NOT_FOUND)

        val report = fleetEngine.run(task, members, llmProvider)
        return ExecutionResult.ok(report)
    }

    // ─────────────────────────────────────────────────────────────
    // Kanban 看板命令
    // ─────────────────────────────────────────────────────────────

    private suspend fun cmdTaskList(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val flags = parseFlags(args)
        val statusFilter = flags.flags["status"]?.let {
            try { TaskStatus.valueOf(it.uppercase()) } catch (_: Exception) { null }
        }
        val limit = flags.flags["limit"]?.toIntOrNull() ?: 20
        val includeArchived = flags.flags.containsKey("all")

        val tasks = kanbanBoard.list(status = statusFilter, limit = limit, includeArchived = includeArchived)
        if (tasks.isEmpty()) {
            val statusHint = if (statusFilter != null) "（状态: ${statusFilter.name}）" else ""
            return ExecutionResult.ok("📋 无任务$statusHint")
        }

        val sb = StringBuilder("## 📋 看板 (${tasks.size})\n\n")
        for (t in tasks) {
            val icon = when (t.status) {
                TaskStatus.PENDING -> "⏳"; TaskStatus.ASSIGNED -> "📤"; TaskStatus.RUNNING -> "🔄"
                TaskStatus.COMPLETED -> "✅"; TaskStatus.FAILED -> "❌"; TaskStatus.TIMED_OUT -> "⏰"
                TaskStatus.CANCELLED -> "🚫"
            }
            sb.appendLine("$icon **${t.title.take(60)}** (`${t.id}`)")
            sb.appendLine("   - 状态: ${t.status.name} | 优先级: ${t.priority.name} | 来自: ${t.fromAgent}")
            sb.appendLine("   - 重试: ${t.retryCount}/${t.maxRetries} | 创建: ${SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(t.createdAt))}")
            sb.appendLine()
        }
        return ExecutionResult.ok(sb.toString().trimEnd())
    }

    private suspend fun cmdTaskShow(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: tribe.task.show <task-id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val task = kanbanBoard.get(args[0])
            ?: return ExecutionResult.fail("任务不存在: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)

        return ExecutionResult.ok("""
## 任务详情: ${task.title}

| 字段 | 值 |
|------|-----|
| 任务ID | `${task.id}` |
| 状态 | ${task.status.name} |
| 优先级 | ${task.priority.name} (${task.priority.label}) |
| 来自 | ${task.fromAgent} → ${task.toAgent} |
| 创建 | ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(task.createdAt))} |
| 更新 | ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(task.updatedAt))} |
| 超时 | ${task.timeoutMs}ms |
| 重试 | ${task.retryCount}/${task.maxRetries} |
| 深度 | ${task.depth} |
| 父任务 | ${task.parentTaskId ?: "(无)"} |

${if (task.description.isNotEmpty()) "## 描述\n${task.description}\n" else ""}
${if (task.result != null) "## 结果\n${task.result}\n" else ""}
${if (task.errorMessage != null) "## 错误\n${task.errorMessage}\n" else ""}
        """.trimIndent())
    }

    private suspend fun cmdTaskCancel(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: tribe.task.cancel <task-id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        return try {
            kanbanBoard.cancel(args[0])
            ExecutionResult.ok("✅ 任务 ${args[0]} 已取消。")
        } catch (e: Exception) {
            ExecutionResult.fail("取消失败: ${e.message}", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
    }

    private suspend fun cmdTaskRetry(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: tribe.task.retry <task-id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        return try {
            kanbanBoard.retry(args[0])
            ExecutionResult.ok("🔄 任务 ${args[0]} 已重置为 ASSIGNED，等待重试。")
        } catch (e: Exception) {
            ExecutionResult.fail("重试失败: ${e.message}", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
    }

    /**
     * 完成任务并发回结果（嵌套委派链的响应端）。
     * 接收 Agent 完成任务后调用，结果通过 ACP RESULT 发回发起方；
     * 若本任务有父任务（嵌套委派），提示沿链回传。
     */
    private suspend fun cmdTaskDone(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail(
            "Usage: tribe.task.done <task-id> <result>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val taskId = args[0]
        val result = args.drop(1).joinToString(" ")

        val task = kanbanBoard.get(taskId)
            ?: return ExecutionResult.fail("任务不存在: $taskId", errorCode = ErrorCodes.ERR_NOT_FOUND)
        if (task.status == TaskStatus.COMPLETED) return ExecutionResult.ok("任务 $taskId 已完成。")

        kanbanBoard.transition(taskId, TaskStatus.COMPLETED, result = result)
        TribeInboxWatcher.markProcessed(taskId)

        // 通过 ACP 发 RESULT 给发起方（嵌套委派链回传）
        var forwardNote = ""
        if (isRunning) {
            val server = acpServer
            if (server != null && task.fromAgent.isNotBlank() && task.fromAgent != agentId) {
                val payload = kotlinx.serialization.json.buildJsonObject {
                    put("taskId", JsonPrimitive(taskId))
                    put("result", JsonPrimitive(result))
                    put("status", JsonPrimitive("COMPLETED"))
                    if (task.parentTaskId != null) {
                        put("parentTaskId", JsonPrimitive(task.parentTaskId!!))
                        put("origin", JsonPrimitive(taskId))
                    }
                }.toString()
                val msg = com.mengpaw.kernel.acp.AcpMessage.result(agentId, task.fromAgent, payload)
                try { server.sendViaTransport(msg); forwardNote = "，结果已发回 ${task.fromAgent}" } catch (_: Exception) {}
            }
        }

        // 嵌套委派提示: 父任务等待沿链回传
        val parentNote = if (task.parentTaskId != null && task.depth > 0)
            "\n\n🔗 本任务是嵌套委派（depth=${task.depth}）。父任务 `${task.parentTaskId}` 还等待结果，可用 `tribe.task.done ${task.parentTaskId} <合并后的结果>` 沿链回传。" else ""

        return ExecutionResult.ok("✅ 任务 $taskId 已完成$forwardNote。$parentNote")
    }

    // ─────────────────────────────────────────────────────────────
    // 聊天 / 讨论
    // ─────────────────────────────────────────────────────────────

    /** 部落广播 — 向所有团队成员群聊消息。 */
    private suspend fun cmdChat(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: tribe.chat <message>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val message = args.joinToString(" ")

        // 写本地团队共享收件箱
        val inbox = File(DataPaths.TEAM_INBOX).also { it.mkdirs() }
        val chatFile = File(inbox, "chat_${System.currentTimeMillis()}.md")
        try {
            chatFile.writeText("""
# 部落广播
- 来自: ${ctx.sessionId}
- 时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}

$message
""".trimIndent())
        } catch (e: Exception) {
            ErrorCollector.report(e, "TribePlugin.chat")
        }

        // ACP 广播给所有团队成员（在线时）
        var broadcastCount = 0
        if (isRunning) {
            val server = acpServer
            if (server != null) {
                val msg = com.mengpaw.kernel.acp.AcpMessage.tribeChat(agentId, "*", message)
                try { server.sendViaTransport(msg); broadcastCount = 1 } catch (_: Exception) {}
            }
        }
        val note = if (broadcastCount > 0) "，已广播到局域网" else ""
        return ExecutionResult.ok("📢 部落广播已发布$note。成员可在 team/inbox/ 查看。")
    }

    /** 部落讨论 — 让每个团队成员就主题发言。 */
    private suspend fun cmdDiscuss(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: tribe.discuss <topic>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val topic = args.joinToString(" ")
        val members = discoverTeamMembers()
        if (members.isEmpty()) return ExecutionResult.fail(
            "团队为空。先用 tribe.discover --lan 自动组队。", errorCode = ErrorCodes.ERR_NOT_FOUND)

        // 并行向每个成员委派发言（P1, 60s 超时），失败不中断
        val contributions = coroutineScope {
            members.map { m ->
                async(Dispatchers.IO) {
                    val task = TribeTask(
                        title = "讨论发言: $topic",
                        description = "请就以下主题发表你的观点（你是角色: ${m.role}）:\n\n$topic",
                        priority = TaskPriority.P1,
                        fromAgent = ctx.sessionId.take(8),
                        toAgent = m.id,
                        timeoutMs = 60_000L,
                        delegateMode = DelegateMode.AUTO
                    )
                    val result = delegateEngine.delegate(task, m.id, m.name)
                    m.name to result
                }
            }.awaitAll()
        }

        val sb = StringBuilder("## 💬 部落讨论: $topic\n\n")
        contributions.forEach { (name, result) ->
            if (result.success) {
                sb.appendLine("### ${name}\n${result.output.take(500)}\n")
            } else {
                sb.appendLine("### ${name}\n⏰ (未发言: ${result.error ?: "超时"})\n")
            }
        }
        return ExecutionResult.ok(sb.toString().trimEnd())
    }

    // ─────────────────────────────────────────────────────────────
    // 心跳 / 对端
    // ─────────────────────────────────────────────────────────────

    private suspend fun cmdPeers(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (!isRunning) return ExecutionResult.ok("部落协作未启动，无法获取对端信息。先运行 `tribe.start`。")
        val online = heartbeatMonitor.getOnlinePeers()
        if (online.isEmpty()) return ExecutionResult.ok("📡 当前无在线对端。")
        return ExecutionResult.ok(online.joinToString("\n") { peer ->
            "• ${peer.agentName} (${peer.agentId}) — 在线"
        })
    }

    private suspend fun cmdPing(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: tribe.ping <agent-id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val agent = args[0]
        val online = heartbeatMonitor.isPeerOnline(agent)
        return if (online) ExecutionResult.ok("🏓 $agent 在线")
        else ExecutionResult.ok("🏓 $agent 离线（或不在心跳范围内）")
    }

    // ─────────────────────────────────────────────────────────────
    // 清理
    // ─────────────────────────────────────────────────────────────

    private suspend fun cmdCleanup(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val flags = parseFlags(args)
        val archived = if (flags.flags.containsKey("archive")) kanbanBoard.archive() else 0
        return ExecutionResult.ok("✅ 已清理。归档完成/失败/取消任务: $archived")
    }

    // ─────────────────────────────────────────────────────────────
    // 辅助方法
    // ─────────────────────────────────────────────────────────────

    private data class ParsedArgs(val positional: List<String>, val flags: Map<String, String>)

    /** 解析 `--key value` 风格参数。 */
    private fun parseFlags(args: List<String>): ParsedArgs {
        val positional = mutableListOf<String>()
        val flags = mutableMapOf<String, String>()
        var i = 0
        while (i < args.size) {
            if (args[i].startsWith("--") && args[i].length > 2) {
                val key = args[i].removePrefix("--")
                if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                    flags[key] = args[i + 1]
                    i += 2
                } else {
                    flags[key] = "true"
                    i += 1
                }
            } else {
                positional.add(args[i])
                i += 1
            }
        }
        return ParsedArgs(positional, flags)
    }
}
