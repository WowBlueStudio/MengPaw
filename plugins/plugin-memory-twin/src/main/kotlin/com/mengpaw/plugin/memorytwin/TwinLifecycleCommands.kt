// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import kotlinx.coroutines.launch

/**
 * 孪生生命周期命令组 — 从 MemoryTwinPlugin 拆分 (start/stop/status)。
 *
 * 状态读写走 [TwinRuntimeState]; 全局依赖 (appContext/acpServer/acpTransport/
 * agentName/activeEngine) 读 [MemoryTwinPlugin] companion。
 * 命令注册名与返回语义与拆分前完全一致。
 */
internal class TwinLifecycleCommands(
    private val state: TwinRuntimeState
) {

    // ── Twin lifecycle commands ───────────────────────────────────

    suspend fun cmdStart(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (state.isRunning) return ExecutionResult.ok("孪生服务已在运行中")

        val server = MemoryTwinPlugin.acpServer ?: return ExecutionResult.fail(
            "ACP 服务未启动。请先执行 self.acp start，然后重试 twin.start"
        )
        val transport = MemoryTwinPlugin.acpTransport ?: return ExecutionResult.fail(
            "ACP 传输层未初始化。请检查 ACP 服务状态: self.acp status"
        )

        // 复用 MainActivity 激活时创建的 engine (双引擎债务修复, v0.22.0)
        // P1 修复: (1) lateinit 未初始化即比较导致崩溃 — 先查初始化状态;
        // (2) 复用路径 handler 未注册到 AcpServer — engine 就绪后统一确保已创建并注册
        val reused = MemoryTwinPlugin.activeEngine
        if (!state.isSyncEngineReady()) {
            state.assignSyncEngine(reused ?: TwinSyncEngine(
                serverSupplier = { MemoryTwinPlugin.acpServer }, transportSupplier = { MemoryTwinPlugin.acpTransport },
                agentName = MemoryTwinPlugin.agentName, deviceId = state.deviceId, deviceName = state.deviceName
            ))
        } else if (reused != null && reused !== state.syncEngine) {
            // 引擎被外部切换 (activeEngine 更新) — 跟随新引擎
            state.assignSyncEngine(reused)
        }
        if (!state.isAcpHandlerReady() || state.handlerEngine !== state.syncEngine) {
            state.assignAcpHandler(TwinAcpHandler(state.syncEngine))
            state.handlerEngine = state.syncEngine
            server.registerHandler(state.acpHandler)
        }

        val context = MemoryTwinPlugin.appContext
        if (context != null && state.autoCollectReceiver == null) {
            // P2: mDNS 自动发现 (TwinDiscovery) 已删除 — 节点发现由 plugin-framework 通讯录
            // + twin.peer.add 手动 IP 承担; 这里只保留能力卡的系统状态自动采集。
            // P1.4: Register auto-collect broadcast receivers
            state.autoCollectReceiver = TwinCapabilityAutoCollect.register(context) { card ->
                state.scope.launch {
                    android.util.Log.i("MengPawTwin", "系统状态变化,自动更新能力卡")
                    state.syncEngine.broadcastCapability(card.toJson())
                }
            }
        }

        state.isRunning = true
        // ── P4: 设备库存 — 启动时登记本机那一行 (有差异才写; 随 dev 命名空间同步到达其他端) ──
        TwinInventoryFacts.register()
        try {
            com.mengpaw.kernel.device.DeviceCommands.refreshLocal()
        } catch (e: Exception) {
            com.mengpaw.kernel.error.ErrorCollector.report(e, "TwinLifecycle.inventoryRefresh")
        }
        // P1 修复: 不再用 `reused == null` 门控 — 复用既有 engine 时 (stop→start) 也要重启自动同步,
        // 否则心跳与 60s 同步静默停摆。startAutoSync 自身幂等 (已在跑则直接返回)。
        state.syncEngine.startAutoSync()

        return ExecutionResult.ok(buildString {
            appendLine("孪生服务已启动")
            appendLine("- 设备: ${state.deviceName} (${state.deviceId.take(12)}...)")
            appendLine("- 自动同步: 每 60 秒 (WiFi)")
            appendLine("- 心跳保活: 每 30 秒")
            appendLine()
            appendLine("下一步:")
            appendLine("- twin.peers — 查看已发现节点")
            appendLine("- 通过侧边栏 MengPaw 框架图标 5 连击发起配对")
        })
    }

    suspend fun cmdStop(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return if (stopTwinService()) {
            ExecutionResult.ok("孪生服务已停止 — 使用 twin.start 重新启动")
        } else {
            ExecutionResult.ok("孪生服务未在运行")
        }
    }

    fun stopTwinService(): Boolean {
        if (!state.isRunning) return false
        state.syncEngine.stopAutoSync()
        // P1.4: Unregister auto-collect
        state.autoCollectReceiver?.let {
            try { MemoryTwinPlugin.appContext?.unregisterReceiver(it) } catch (_: Exception) {}
            state.autoCollectReceiver = null
        }
        state.isRunning = false
        return true
    }

    suspend fun cmdStatus(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        // P1-4: 服务未启动时也要能回答"上次同步成没成" — 读落盘状态, 而不是直接一句"未启动"
        if (!state.isRunning) {
            val p = TwinStateStore.load()
            return ExecutionResult.ok(buildString {
                appendLine("孪生服务: 未启动。使用 twin.start 启动。")
                appendLine("- 上次同步: ${if (p.hasSynced) formatTime(p.lastSyncAt) else "从未"}")
                if (p.hasSynced) appendLine("- 上次接收: ${p.filesReceived} 个文件, 冲突 ${p.conflicts}")
                if (p.lastError.isNotBlank()) appendLine("- 上次错误: ${p.lastError}")
            })
        }

        val stateFlow = state.syncEngine.syncState.value
        return ExecutionResult.ok(buildString {
            appendLine("## 孪生状态")
            appendLine("- 服务: 运行中")
            appendLine("- 设备: ${state.deviceName}")
            appendLine("- 指纹: ${state.deviceId.take(16)}...")
            appendLine("- 协议版本: 0.3 (v0.2 双栈兼容)")
            appendLine("- 同步阶段: ${stateFlow.phase}")
            appendLine("- 在线节点: ${stateFlow.onlinePeers}/${stateFlow.totalPeers}")
            appendLine("- QoS: ${state.syncEngine.qosLevel.name}")
            appendLine("- 上次同步: ${if (stateFlow.lastSyncAt > 0) formatTime(stateFlow.lastSyncAt) else "从未"}")
            if (stateFlow.lastFilesReceived > 0 || stateFlow.lastConflicts > 0) {
                appendLine("- 上次接收: ${stateFlow.lastFilesReceived} 个文件, 冲突 ${stateFlow.lastConflicts}")
            }
            TwinStateStore.load().lastError.takeIf { it.isNotBlank() }?.let { appendLine("- 上次错误: $it") }
            // 健康度数字 (按需输出, 不进提示词): Agent 判断"对象库/账本是否还在长"时用
            try {
                val objects = com.mengpaw.kernel.memory.SnapshotStore.objectCount()
                val bytes = com.mengpaw.kernel.memory.SnapshotStore.storeSizeBytes()
                appendLine("- 对象库: $objects 个对象 / ${formatBytes(bytes)}")
                val ledger = com.mengpaw.kernel.memory.MemoryLedger.entries(MemoryTwinPlugin.agentName).size
                appendLine("- 记忆账本: $ledger 条")
            } catch (_: Exception) {
                // 健康度只是附加信息, 采集失败不影响状态主体
            }
        })
    }

    /** 字节数 → 人类可读 (状态输出用)。 */
    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** 时间戳 → `HH:mm:ss` (状态输出统一口径)。 */
    private fun formatTime(ts: Long): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(ts))
}
