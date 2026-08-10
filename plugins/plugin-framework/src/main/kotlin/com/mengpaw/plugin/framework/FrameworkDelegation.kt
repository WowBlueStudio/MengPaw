// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.framework

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult

/**
 * 框架信任门禁 + 指挥舰委派 (v0.35.5 用户拍板) — 拆自 FrameworkPlugin (400 行文件拆分)。
 *
 * - [frameworkTrustGate]: connect/call/delegate 共用信任门禁
 * - [acpPeerIdFor]: 方案 A 的 ACP 入站信任键 (mengpaw-<指纹短码>)
 * - [frameworkDelegateCmd]: 指挥舰委派 — 直发 TWIN_DELEGATE, 对端 Agent 自主执行
 */

/**
 * framework.connect/call/delegate 信任门禁 — 通讯录节点不存在或未信任时返回拒绝结果,
 * 已信任返回 null (放行)。纯函数, 供命令层共用 + 回归测试。
 */
internal fun frameworkTrustGate(
    peerName: String,
    peer: FrameworkPeerStore.FrameworkPeer?
): ExecutionResult? {
    if (peer == null) {
        return ExecutionResult.fail("通讯录中无此节点: $peerName",
            errorCode = com.mengpaw.kernel.cli.ErrorCodes.ERR_NOT_FOUND)
    }
    if (!peer.trusted) {
        return ExecutionResult.fail(
            "节点未信任: $peerName — 请先执行 framework.trust ${peer.fingerprint.ifBlank { peerName }} --yes 信任后委派",
            errorCode = com.mengpaw.kernel.cli.ErrorCodes.ERR_PERMISSION_DENIED
        )
    }
    return null
}

/**
 * ACP 入站信任键 (方案 A, v0.35.5) — "mengpaw-<指纹短码>", 与
 * FrameworkPairEngine.localFrom() 一致: 对端以此键写 PromptFirewall 后,
 * 本机发的 DELEGATE/TWIN_DELEGATE 才能通过对端信任门禁。
 */
internal fun acpPeerIdFor(fingerprint: String): String =
    "mengpaw-${FrameworkPeerStore.shortCodeOf(fingerprint)}"

/**
 * 指挥舰委派 (v0.35.5, 用户定案): 把任务直发已信任框架的 ACP 9876 —
 * 对端 TwinAcpHandler 经 PromptFirewall 信任门禁后落 inbox, 对端 Agent 自主执行
 * (可自行进入火种模式), 结果经孪生工作区同步回传。所有 ACP 框架一视同仁。
 */
internal suspend fun frameworkDelegateCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
    if (args.size < 2) return ExecutionResult.fail(
        "用法: framework.delegate <peer-name> <task> — 委派任务到已信任框架执行 (对端 Agent 自主处理)",
        errorCode = com.mengpaw.kernel.cli.ErrorCodes.ERR_INVALID_INPUT)
    val peerName = args[0]
    val task = args.drop(1).joinToString(" ").trim()
    val peer = FrameworkPeerStore.loadAll().find { it.name == peerName }
    // 信任门禁与 connect/call 同源 — 未信任节点禁止委派
    frameworkTrustGate(peerName, peer)?.let { return it }
    val p = peer ?: return ExecutionResult.fail("通讯录中无此节点: $peerName",
        errorCode = com.mengpaw.kernel.cli.ErrorCodes.ERR_NOT_FOUND)

    // 直发 TWIN_DELEGATE: from = mengpaw-<本机指纹短码> (对端 PromptFirewall 信任键, 方案 A)
    // v0.36 舰队闭环: 携带 delegateId + 回传地址 — 对端执行完 fleet.reply 回传结果
    val delegateId = java.util.UUID.randomUUID().toString().replace("-", "").take(10)
    val callbackAddress = com.mengpaw.plugin.framework.FrameworkPairEngine.localIpv4() ?: p.address
    val from = acpPeerIdFor(com.mengpaw.plugin.framework.FrameworkIdentity.fingerprint)
    com.mengpaw.kernel.agent.FleetRuntimeStore.startTask(
        delegateId, task, p.name,
        commander = "mengpaw-${FrameworkPeerStore.shortCodeOf(com.mengpaw.plugin.framework.FrameworkIdentity.fingerprint)}")
    val msg = com.mengpaw.kernel.acp.AcpMessage.twinDelegate(
        from = from, to = "*", task = task,
        delegateId = delegateId, callbackAddress = callbackAddress,
        callbackPort = com.mengpaw.kernel.ports.Ports.ACP)
    val sent = com.mengpaw.kernel.namespace.AcpHolder.server.sendDirect(msg, p.address, p.port)
    return if (sent) {
        ExecutionResult.ok(
            "已委派到 ${p.name} (${p.address}:${p.port}) — 委派 ID: $delegateId\n" +
            "对端 Agent 将自主执行 (可自行进入火种模式推进), 完成后自动回传; fleet.status 查看进度。")
    } else {
        ExecutionResult.fail(
            "委派发送失败: ${p.name} (${p.address}:${p.port}) 不可达\n" +
            "检查: 1) 对端是否在线 2) 两台设备同一 WiFi 3) 防火墙是否拦截 ACP 端口 9876",
            errorCode = com.mengpaw.kernel.cli.ErrorCodes.NETWORK_OFFLINE)
    }
}
