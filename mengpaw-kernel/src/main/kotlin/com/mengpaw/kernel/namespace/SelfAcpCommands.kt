// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.namespace

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes

/**
 * self.acp 子命令执行器 — 设备间 ACP 通讯 (拆自 SelfExecutor, 400 行文件拆分)。
 * 经 [SelfExecutor.commands]["acp"] 委托注册, 命令形态与行为不变。
 */
internal class SelfAcpCommands {

    /** ACP device-to-device communication. Usage: self.acp [start|stop|peers|discover|delegate|share|pair|...] */
    internal suspend fun acpCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return acpUsage()
        val sub = args[0]
        return ACP_SUBCOMMANDS[sub]?.invoke(args, ctx)
            ?: acpUsage()
    }

    private val ACP_SUBCOMMANDS: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "start" to { _, _ ->
            // v0.35.4: 复用 AcpHolder.ensureListening — 与框架配对/孪生共享同一监听
            AcpHolder.ensureListening()
            ExecutionResult.ok("ACP 已启动，端口 ${com.mengpaw.kernel.ports.Ports.ACP}。其他设备可通过 self.acp discover 发现本设备。")
        },
        "stop" to { _, _ ->
            AcpHolder.transport?.close()
            AcpHolder.transport = null
            ExecutionResult.ok("ACP 已停止。")
        },
        "peers" to { _, _ ->
            val peers = AcpHolder.server.getPeers()
            if (peers.isEmpty()) ExecutionResult.ok("(无已连接设备)\n\n发现设备: self.acp discover")
            else ExecutionResult.ok(peers.joinToString("\n") { "• ${it.agentName} (${it.agentId}) @ ${it.address}:${it.port}" })
        },
        "discover" to { _, _ ->
            val peers = AcpHolder.server.discover()
            if (peers.isEmpty()) ExecutionResult.ok("(未发现其他设备)\n\n确保两台设备在同一 WiFi，且都已执行 self.acp start。")
            else ExecutionResult.ok("发现 ${peers.size} 个设备:\n" + peers.joinToString("\n") { "• ${it.agentName} (${it.agentId})" })
        },
        "delegate" to { a, _ ->
            if (a.size < 3) ExecutionResult.fail("Usage: self.acp delegate <peer-id> <task>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            else {
                val result = AcpHolder.server.delegate(a[1], a.drop(2).joinToString(" "))
                ExecutionResult.ok(if (result.success) "任务已委派。" else "委派失败: ${result.message}")
            }
        },
        "share" to { a, _ ->
            if (a.size < 3) ExecutionResult.fail("Usage: self.acp share memory|skill <peer-id> <id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            else {
                val type = a[1]; val peerId = a[2]; val id = a.getOrNull(3) ?: ""
                val result = if (type == "memory") AcpHolder.server.shareMemory(peerId, id)
                else AcpHolder.server.shareSkill(peerId, id)
                ExecutionResult.ok(if (result.success) "已共享。" else "共享失败: ${result.message}")
            }
        },
        "pair" to { a, _ ->
            if (a.size < 3) ExecutionResult.fail("Usage: self.acp pair <device-id> <peer-fingerprint>\n获取对方指纹: 让对方执行 self.acp fingerprint", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            else {
                val myFp = com.mengpaw.kernel.acp.AcpCrypto.myFingerprint()
                val peerFp = a[2]
                com.mengpaw.kernel.acp.AcpCrypto.deriveKey(myFp, peerFp, a[1])
                com.mengpaw.kernel.security.PromptFirewall.trust(a[1], peerFp)
                ExecutionResult.ok("已配对设备: ${a[1]}\n加密: AES-256-CBC (密钥已派生)\n该设备现在拥有完整访问权限。")
            }
        },
        "fingerprint" to { _, _ ->
            ExecutionResult.ok("本设备指纹: ${com.mengpaw.kernel.acp.AcpCrypto.myFingerprint()}\n将此指纹提供给配对方。")
        },
        "untrust" to { a, _ ->
            if (a.size < 2) ExecutionResult.fail("Usage: self.acp untrust <device-id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
            else { com.mengpaw.kernel.security.PromptFirewall.untrust(a[1]); ExecutionResult.ok("已解除配对: ${a[1]}") }
        },
        "trusted" to { _, _ ->
            val list = com.mengpaw.kernel.security.PromptFirewall.listTrusted()
            if (list.isEmpty()) ExecutionResult.ok("(无已配对设备)\n\n配对: self.acp pair <device-id>")
            else ExecutionResult.ok("已配对设备:\n" + list.joinToString("\n") { "• $it" })
        },
        "firewall" to { _, _ ->
            ExecutionResult.ok(com.mengpaw.kernel.security.PromptFirewall.guestPolicySummary())
        }
    )

    private fun acpUsage() = ExecutionResult.fail(
        "Usage: self.acp start|stop|peers|discover|delegate|share|pair|fingerprint|untrust|trusted|firewall",
        errorCode = ErrorCodes.ERR_INVALID_INPUT
    )
}
