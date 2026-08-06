// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.hermes

import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import kotlinx.coroutines.CoroutineScope

/**
 * 部落协作生命周期命令组 — 从 TribePlugin 拆分。
 * 服务启停 / 状态查询 / 对端心跳探测 (start/stop/status/peers/ping)。
 *
 * 依赖通过构造参数注入 (参照 SessionCompressor 模式); 全局依赖
 * (agentName/acpServer/isRunning) 读 [TribePlugin] companion。
 * 命令注册名与返回语义与拆分前完全一致。
 */
internal class TribeLifecycleCommands(
    private val kanbanBoard: TribeKanbanBoard,
    private val delegateEngine: TribeDelegateEngine,
    private val heartbeatMonitor: TribeHeartbeatMonitor,
    private val handlerRef: () -> TribeAcpHandler?,
    private val setHandler: (TribeAcpHandler?) -> Unit,
    private val scope: CoroutineScope
) {

    // ─────────────────────────────────────────────────────────────
    // 生命周期命令
    // ─────────────────────────────────────────────────────────────

    suspend fun cmdStart(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (TribePlugin.isRunning) return ExecutionResult.ok("部落协作已在运行中。")

        val server = TribePlugin.acpServer
        if (server == null) {
            return ExecutionResult.ok("⚠️ ACP 未启动，将以文件系统模式运行（仅本地协作）。使用 `self.acp start` 启动 ACP。")
        }

        // 恢复 Kanban inflight 任务
        val recovered = kanbanBoard.recoverInFlight()
        if (recovered.isNotEmpty()) {
            com.mengpaw.kernel.KernelLog.i("TribePlugin", "恢复 ${recovered.size} 个 inflight 任务")
        }

        // 创建并注册 ACP handler
        val handler = TribeAcpHandler(TribePlugin.agentName, kanbanBoard, delegateEngine, heartbeatMonitor)
        setHandler(handler)
        server.registerHandler(handler)

        // 启动委派引擎和心跳
        delegateEngine.start()
        heartbeatMonitor.start()

        // 启动收件箱监视器（Agent 自动感知新任务）
        TribeInboxWatcher.start(scope)

        TribePlugin.isRunning = true
        return ExecutionResult.ok("✅ 部落协作已启动。ACP 模式 — 支持实时委派、超时重试、心跳检测、收件箱感知。")
    }

    suspend fun cmdStop(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (!TribePlugin.isRunning) return ExecutionResult.ok("部落协作未在运行。")
        // P1 修复: 先停 handler 再停引擎 — 停止后 ACP 消息不再进入任务执行
        handlerRef()?.active = false
        delegateEngine.stop()
        heartbeatMonitor.stop()
        heartbeatMonitor.markAllOffline()
        TribeInboxWatcher.stop()
        setHandler(null)
        TribePlugin.isRunning = false
        return ExecutionResult.ok("🛑 部落协作已停止。")
    }

    suspend fun cmdStatus(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val running = TribePlugin.isRunning
        val acpOk = TribePlugin.acpServer != null
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
    // 心跳 / 对端
    // ─────────────────────────────────────────────────────────────

    suspend fun cmdPeers(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (!TribePlugin.isRunning) return ExecutionResult.ok("部落协作未启动，无法获取对端信息。先运行 `tribe.start`。")
        val online = heartbeatMonitor.getOnlinePeers()
        if (online.isEmpty()) return ExecutionResult.ok("📡 当前无在线对端。")
        return ExecutionResult.ok(online.joinToString("\n") { peer ->
            "• ${peer.agentName} (${peer.agentId}) — 在线"
        })
    }

    suspend fun cmdPing(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: tribe.ping <agent-id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val agent = args[0]
        val online = heartbeatMonitor.isPeerOnline(agent)
        return if (online) ExecutionResult.ok("🏓 $agent 在线")
        else ExecutionResult.ok("🏓 $agent 离线（或不在心跳范围内）")
    }
}
