// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import com.mengpaw.kernel.acp.AcpMessage
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.ports.Ports

/**
 * fleet 命名空间命令 (v0.36 深度进化) — 舰队指挥 (shell 注入 additionalNamespaces,
 * 可访问 framework 通讯录与内核 FleetRuntimeStore)。
 *
 * 角色模型: 指挥舰 (发起方) / 坦克·步兵 (执行方, 可自行进入火种模式) / 同步交付。
 * 闭环: fleet.delegate → TWIN_DELEGATE (带 delegateId+回传地址) → 对端 inbox →
 * 对端执行完 fleet.reply → FLEET_RESULT → 指挥舰 FleetRuntimeStore 状态回收 → fleet.status。
 */
object FleetShellCommands {

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "peers" to ::peersCmd,
        "delegate" to ::delegateCmd,
        "status" to ::statusCmd,
        "reply" to ::replyCmd
    )

    /** 舰队成员总览 — 已信任框架 = 舰队成员 (方案 A: 一视同仁)。 */
    private suspend fun peersCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val members = com.mengpaw.plugin.framework.FrameworkPeerStore.loadAll().filter { it.trusted }
        if (members.isEmpty()) {
            return ExecutionResult.ok("舰队无成员 — 先添加并信任框架: framework.trust <fingerprint> --yes")
        }
        val sb = buildString {
            appendLine("## 舰队成员 (${members.size})")
            members.forEach { p ->
                val online = p.lastSeen > System.currentTimeMillis() - 120_000
                appendLine("- ${p.name} · ${p.frameworkType} · ${p.address}:${p.port} · ${if (online) "在线" else "离线"}")
            }
            appendLine()
            appendLine("委派: fleet.delegate <节点名> <任务> | 进度: fleet.status")
        }
        return ExecutionResult.ok(sb.toString().trimEnd())
    }

    /** 指挥舰委派 — 直发 TWIN_DELEGATE (带 delegateId + 回传地址), 状态 SENT 落盘。 */
    private suspend fun delegateCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail(
            "用法: fleet.delegate <peer-name> <task> — 委派任务到已信任框架执行 (对端可自行进入火种模式)")
        val peerName = args[0]
        val task = args.drop(1).joinToString(" ").trim()
        val peer = com.mengpaw.plugin.framework.FrameworkPeerStore.loadAll().find { it.name == peerName }
            ?: return ExecutionResult.fail("通讯录中无此节点: $peerName (先 framework.add 或配对入册)")
        if (!peer.trusted) {
            return ExecutionResult.fail(
                "节点未信任: $peerName — 请先执行 framework.trust ${peer.fingerprint.ifBlank { peerName }} --yes")
        }

        val delegateId = java.util.UUID.randomUUID().toString().replace("-", "").take(10)
        val callbackAddress = com.mengpaw.plugin.framework.FrameworkPairEngine.localIpv4() ?: peer.address
        com.mengpaw.kernel.agent.FleetRuntimeStore.startTask(
            delegateId, task, peerName,
            commander = "mengpaw-${com.mengpaw.plugin.framework.FrameworkPeerStore.shortCodeOf(com.mengpaw.plugin.framework.FrameworkIdentity.fingerprint)}")

        val from = "mengpaw-${com.mengpaw.plugin.framework.FrameworkPeerStore.shortCodeOf(com.mengpaw.plugin.framework.FrameworkIdentity.fingerprint)}"
        val msg = AcpMessage.twinDelegate(
            from = from, to = "*", task = task,
            delegateId = delegateId, callbackAddress = callbackAddress, callbackPort = Ports.ACP)
        val sent = com.mengpaw.kernel.namespace.AcpHolder.server.sendDirect(msg, peer.address, peer.port)
        return if (sent) {
            ExecutionResult.ok(
                "已委派到 ${peer.name} (${peer.address}:${peer.port}) — 委派 ID: $delegateId\n" +
                "对端 Agent 自主执行 (可自行进入火种模式), 完成后自动回传; fleet.status 查看进度")
        } else {
            ExecutionResult.fail(
                "委派发送失败: ${peer.name} 不可达 — 检查对端在线/同一 WiFi/防火墙 9876")
        }
    }

    /** 舰队任务状态 — 委派 ID / 任务 / 状态 / 结果。 */
    private suspend fun statusCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val tasks = com.mengpaw.kernel.agent.FleetRuntimeStore.list()
        if (tasks.isEmpty()) {
            return ExecutionResult.ok("暂无舰队委派任务 — fleet.delegate <节点> <任务> 发起")
        }
        val sb = buildString {
            appendLine("## 舰队任务 (${tasks.size})")
            tasks.forEach { t ->
                val icon = when (t.status) {
                    "DONE" -> "✅"; "FAILED" -> "❌"; else -> "🔄"
                }
                appendLine("$icon ${t.delegateId} [${t.status}] → ${t.peerName}")
                appendLine("   任务: ${t.task.take(80)}")
                if (t.result.isNotBlank()) appendLine("   结果: ${t.result.take(200)}")
                appendLine("   时间: ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t.updatedAt))}")
            }
        }
        return ExecutionResult.ok(sb.toString().trimEnd())
    }

    /** 执行方回传结果 — 读委派记录的回传地址, 发 FLEET_RESULT 给指挥舰。 */
    private suspend fun replyCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("用法: fleet.reply <delegateId> <结果> [--fail]")
        val delegateId = args[0]
        val result = args.filter { it != "--fail" }.drop(1).joinToString(" ").trim()
        val success = !args.contains("--fail")
        val task = com.mengpaw.kernel.agent.FleetRuntimeStore.find(delegateId)
            ?: return ExecutionResult.fail("未知委派 ID: $delegateId (inbox 中 twin_delegate_*.md 可查)")
        if (task.callbackAddress.isBlank()) {
            return ExecutionResult.fail("该委派无回传地址 — 结果请写入工作区文档并孪生同步")
        }
        val from = "mengpaw-${com.mengpaw.plugin.framework.FrameworkPeerStore.shortCodeOf(com.mengpaw.plugin.framework.FrameworkIdentity.fingerprint)}"
        val msg = AcpMessage.fleetResult(from, task.commander, delegateId, result, success)
        val sent = com.mengpaw.kernel.namespace.AcpHolder.server.sendDirect(msg, task.callbackAddress, task.callbackPort)
        return if (sent) ExecutionResult.ok("已回传结果到指挥舰 (${task.callbackAddress}:${task.callbackPort})")
        else ExecutionResult.fail("回传失败: 指挥舰不可达 (${task.callbackAddress}:${task.callbackPort})")
    }
}
