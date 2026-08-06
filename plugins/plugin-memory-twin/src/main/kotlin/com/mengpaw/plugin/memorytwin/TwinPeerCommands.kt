// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import kotlinx.coroutines.launch

/**
 * 孪生对端命令组 — 从 MemoryTwinPlugin 拆分。
 * peers / peer.info / peer.add / pair / unpair / lost / recover。
 *
 * 状态读写走 [TwinRuntimeState]; 命令注册名与返回语义与拆分前完全一致。
 */
internal class TwinPeerCommands(
    private val state: TwinRuntimeState
) {

    // ── Peer management ───────────────────────────────────────────

    suspend fun cmdPeers(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        // P1 修复: isRunning 守卫 — 未启动时 syncEngine 未初始化
        if (!state.isRunning) return ExecutionResult.ok("孪生服务未启动,请先执行 twin.start")
        val peers = state.syncEngine.getPeers()
        if (peers.isEmpty()) return ExecutionResult.ok(buildString {
            appendLine("(无已知孪生节点)")
            appendLine()
            appendLine("可能原因:")
            appendLine("1. 对端设备未在同一 WiFi 网络")
            appendLine("2. 对端设备未激活孪生服务")
            appendLine("3. mDNS 发现失败 — 可尝试 twin.peer.add <ip> 手动添加")
        })
        return ExecutionResult.ok(buildString {
            appendLine("| 设备 | Agent | 地址 | 在线 | 同步 |")
            appendLine("|------|-------|------|:--:|------|")
            peers.forEach { p ->
                val lastSeen = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(p.lastSeen))
                val onlineIcon = if (p.online) "🟢" else "⚫"
                val syncState = if (p.lastSyncAt > 0) "已同步" else "待同步"
                appendLine("| ${p.peerId.take(8)}... | ${p.agentName} | ${p.address}:${p.port} | $onlineIcon | $syncState |")
            }
        })
    }

    suspend fun cmdPeerInfo(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        // P1 修复: isRunning 守卫 — 未启动时 syncEngine 未初始化
        if (!state.isRunning) return ExecutionResult.fail("孪生服务未启动,请先执行 twin.start")
        val peerId = args.getOrNull(0) ?: return ExecutionResult.fail("用法: twin.peer.info <peer-id>")
        val peers = state.syncEngine.getPeers()
        val peer = peers.find { it.peerId == peerId || it.peerId.startsWith(peerId) }
            ?: return ExecutionResult.fail("未找到节点: $peerId。使用 twin.peers 查看所有已知节点。")
        return ExecutionResult.ok(buildString {
            appendLine("## 节点信息")
            appendLine("- ID: ${peer.peerId}")
            appendLine("- Agent: ${peer.agentName}")
            appendLine("- 地址: ${peer.address}:${peer.port}")
            appendLine("- 在线: ${if (peer.online) "是" else "否"}")
            appendLine("- 最后在线: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(peer.lastSeen))}")
            if (peer.capabilityCard != null) {
                appendLine()
                appendLine("### 能力卡")
                appendLine(peer.capabilityCard)
            }
        })
    }

    suspend fun cmdPeerAdd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (!state.isRunning) return ExecutionResult.fail("孪生服务未启动,请先执行 twin.start")
        val address = args.getOrNull(0)
            ?: return ExecutionResult.fail("用法: twin.peer.add <ip> [port] [name]")
        val port = args.getOrNull(1)?.toIntOrNull() ?: com.mengpaw.kernel.ports.Ports.ACP
        val name = args.getOrNull(2)
        val peer = state.syncEngine.addManualPeer(address, port, name)
        return ExecutionResult.ok(buildString {
            appendLine("已手动添加节点:")
            appendLine("- ID: ${peer.peerId}")
            appendLine("- 地址: ${peer.address}:${peer.port}")
            appendLine()
            appendLine("使用 twin.sync ${peer.peerId} 发起同步")
        })
    }

    suspend fun cmdPair(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(
            "⚠️ 孪生配对不通过 CLI 执行。\n" +
            "在侧边栏找到 MengPaw 框架图标，连续点击 5 次即可发起配对。\n" +
            "双方会显示 6 位验证码，比对一致后配对完成。"
        )
    }

    suspend fun cmdUnpair(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(
            "⚠️ 解绑操作在侧边栏完成。\n" +
            "长按框架名片 → 点击「解除孪生」按钮即可。"
        )
    }

    // ── P1.2/P2.3: Device loss / revoke commands ─────────────────────

    suspend fun cmdLost(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val peerId = args.getOrNull(0)
            ?: return ExecutionResult.fail("用法: twin.lost <peer-id>\n\n标记设备丢失，广播解绑到所有在线节点。\n获取 peer-id: twin.peers")
        if (!state.isRunning) return ExecutionResult.fail("孪生服务未启动,请先执行 twin.start")

        val peers = state.syncEngine.getPeers()
        val peer = peers.find { it.peerId == peerId || it.peerId.startsWith(peerId) }
            ?: return ExecutionResult.fail("未找到节点: $peerId。使用 twin.peers 查看所有已知节点。")

        // 1. Broadcast revoke to all online peers (except the lost device)
        state.scope.launch { state.syncEngine.broadcastRevoke(peer.peerId) }

        // 2. Remove local trust
        val trustedFile = java.io.File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED, "${peer.peerId}.trusted")
        if (trustedFile.exists()) trustedFile.delete()
        val keyFile = java.io.File(com.mengpaw.kernel.DataPaths.ACP_TRUSTED, "${peer.peerId}.key")
        if (keyFile.exists()) keyFile.delete()

        // 3. Move peer to "lost" in display and remove from active peers
        state.syncEngine.onRevokeReceived(peer.peerId)

        // 4. Write audit record
        val auditMsg = buildString {
            appendLine("⚠️ 设备丢失标记")
            appendLine("- 设备: ${peer.agentName} (${peer.peerId})")
            appendLine("- 地址: ${peer.address}:${peer.port}")
            appendLine("- 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
            appendLine()
            appendLine("已执行:")
            appendLine("✓ 广播解绑到所有在线节点")
            appendLine("✓ 移除本地信任关系")
            appendLine()
            appendLine("如果找回了设备，使用以下命令重新配对:")
            appendLine("  twin.recover ${peer.peerId}")
            appendLine("  或在侧边栏 5 连击框架图标重新发起配对")
        }
        android.util.Log.w("MengPawTwin", auditMsg)
        return ExecutionResult.ok(auditMsg)
    }

    suspend fun cmdRecover(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val peerId = args.getOrNull(0)
            ?: return ExecutionResult.fail("用法: twin.recover <peer-id>\n\n找回设备后重新激活孪生。需要重新在两侧完成配对验证。")
        return ExecutionResult.ok(
            "设备找回流程:\n\n" +
            "1. 在找回的设备上: 确保已连接同一 WiFi\n" +
            "2. 在两侧执行 twin.start 启动孪生服务\n" +
            "3. 在任一侧侧边栏 5 连击框架图标发起配对\n" +
            "4. 验证配对码并确认\n" +
            "5. 配对完成后执行 twin.sync 全量同步恢复记忆\n\n" +
            "⚠️ 解绑期间的记忆不会自动恢复\n" +
            "⚠️ 如果设备未找回，可联系 wowblue 支持进行远程擦除"
        )
    }
}
