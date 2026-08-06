// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.hermes

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.acp.AcpServer
import com.mengpaw.kernel.acp.AcpTransport
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

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
 *
 * ## 职责拆分 (批次3)
 * 命令实现按组拆到同包委托对象，主类只保留状态聚合 + 命令注册:
 * - [TribeLifecycleCommands] — start/stop/status/peers/ping
 * - [TribeTeamCommands] — team/discover/ask/role
 * - [TribeDelegationCommands] — delegate/route/template/fleet
 * - [TribeKanbanCommands] — task.list/show/cancel/retry/done + cleanup
 * - [TribeMessagingCommands] — memo/chat/discuss
 * - [TribeCommandUtils] — 共享参数解析 (parseFlags/ParsedArgs)
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

    // ── 命令组委托（按职责拆分, 构造参数传依赖闭包） ──────────────

    private val lifecycleCommands = TribeLifecycleCommands(
        kanbanBoard, delegateEngine, heartbeatMonitor,
        handlerRef = { tribeAcpHandler }, setHandler = { tribeAcpHandler = it }, scope
    )
    private val teamCommands = TribeTeamCommands(heartbeatMonitor, ::discoverTeamMembers, ::teamDir)
    private val delegationCommands = TribeDelegationCommands(kanbanBoard, delegateEngine, fleetEngine, ::discoverTeamMembers)
    private val kanbanCommands = TribeKanbanCommands(kanbanBoard)
    private val messagingCommands = TribeMessagingCommands(delegateEngine, ::discoverTeamMembers)

    // ── 文件系统辅助 ───────────────────────────────────────────

    private val teamDir: File get() = File(DataPaths.TEAM).also { it.mkdirs() }

    @Deprecated("Use TribeTeamStore.discoverMembers()")
    private fun discoverTeamMembers(): List<TeamMember> = TribeTeamStore.discoverMembers()

    // ── 命令注册 ───────────────────────────────────────────────

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        // ── 生命周期 ──
        "start" to lifecycleCommands::cmdStart,
        "stop" to lifecycleCommands::cmdStop,
        "status" to lifecycleCommands::cmdStatus,
        // ── 基础文件系统命令（原有 hermes.* 的 tribe 版本） ──
        "team" to teamCommands::cmdTeam,
        "discover" to teamCommands::cmdDiscover,
        "delegate" to delegationCommands::cmdDelegate,
        "ask" to teamCommands::cmdAsk,
        "memo" to messagingCommands::cmdMemo,
        "role" to teamCommands::cmdRole,
        // ── 模板 / 路由 / Fleet ──
        "template" to delegationCommands::cmdTemplate,
        "route" to delegationCommands::cmdRoute,
        "fleet" to delegationCommands::cmdFleet,
        // ── Kanban 看板 ──
        "task.list" to kanbanCommands::cmdTaskList,
        "task.show" to kanbanCommands::cmdTaskShow,
        "task.cancel" to kanbanCommands::cmdTaskCancel,
        "task.retry" to kanbanCommands::cmdTaskRetry,
        "task.done" to kanbanCommands::cmdTaskDone,
        // ── 聊天 / 讨论 ──
        "chat" to messagingCommands::cmdChat,
        "discuss" to messagingCommands::cmdDiscuss,
        // ── 心跳 / 对端 ──
        "peers" to lifecycleCommands::cmdPeers,
        "ping" to lifecycleCommands::cmdPing,
        // ── 维护 ──
        "cleanup" to kanbanCommands::cmdCleanup
    ) + mapOf(
        // ── 向后兼容（hermes.* → tribe.*） ──
        "hermes.team" to { a, c -> TribeBackwardCompat.team(a, c, teamCommands::cmdTeam) },
        "hermes.discover" to { a, c -> TribeBackwardCompat.discover(a, c, teamCommands::cmdDiscover) },
        "hermes.delegate" to { a, c -> TribeBackwardCompat.delegate(a, c, delegationCommands::cmdDelegate) },
        "hermes.ask" to { a, c -> TribeBackwardCompat.ask(a, c, teamCommands::cmdAsk) },
        "hermes.memo" to { a, c -> TribeBackwardCompat.memo(a, c, messagingCommands::cmdMemo) },
        "hermes.role" to { a, c -> TribeBackwardCompat.role(a, c, teamCommands::cmdRole) }
    )
}
