// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.acp.AcpMessage
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.ports.Ports
import java.io.File
import java.util.Base64

/**
 * fleet 命名空间命令 (v0.36 平台化) — 常驻 kernel, 任何跑 kernel 的端
 * (Android / 桌面三端) 均可作指挥舰。平台依赖经 [FleetPlatform] 注入。
 *
 * 四大闭环: 委派 (delegate/status/reply) · 文件互传 (send/files) ·
 * 能力收集 (scan/capability) · 舰队总览 (peers)。
 */
object FleetExecutor {

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "peers" to ::peersCmd,
        "delegate" to ::delegateCmd,
        "status" to ::statusCmd,
        "reply" to ::replyCmd,
        "send" to ::sendCmd,
        "files" to ::filesCmd,
        "capability" to ::capabilityCmd,
        "scan" to ::scanCmd
    )

    private fun members(): List<FleetMember> =
        FleetPlatform.membersProvider?.invoke().orEmpty()

    private fun memberByName(name: String): FleetMember? = members().find { it.name == name }

    private fun localPeerId(): String =
        FleetPlatform.localPeerIdProvider?.invoke() ?: "mengpaw-unknown"

    private fun trustGate(member: FleetMember?): ExecutionResult? {
        if (member == null) return ExecutionResult.fail(
            "通讯录中无此节点 — 先配对入册 (framework.add / 添加框架页面)")
        if (!member.trusted) {
            return ExecutionResult.fail(
                "节点未信任: ${member.name} — 请先执行 framework.trust ${member.fingerprint.ifBlank { member.name }} --yes")
        }
        return null
    }

    /** 舰队成员总览 — 已信任框架 = 舰队成员 (发起方即总指挥)。 */
    private suspend fun peersCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val members = members().filter { it.trusted }
        if (members.isEmpty()) {
            return ExecutionResult.ok("舰队无成员 — 先添加并信任框架: framework.trust <fingerprint> --yes")
        }
        val sb = buildString {
            appendLine("## 舰队成员 (${members.size})")
            members.forEach { m ->
                val online = m.lastSeen > System.currentTimeMillis() - 120_000
                appendLine("- ${m.name} · ${m.frameworkType} · ${m.address}:${m.port} · ${if (online) "在线" else "离线"}")
            }
            appendLine()
            appendLine("委派: fleet.delegate <节点名> <任务> | 进度: fleet.status")
        }
        return ExecutionResult.ok(sb.toString().trimEnd())
    }

    /** 指挥舰委派 — 直发 TWIN_DELEGATE (delegateId + 回传地址), 状态 SENT 落盘。 */
    private suspend fun delegateCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("用法: fleet.delegate <peer-name> <task>")
        val member = memberByName(args[0])
        trustGate(member)?.let { return it }
        val m = member ?: return ExecutionResult.fail("通讯录中无此节点")
        val task = args.drop(1).joinToString(" ").trim()

        val delegateId = java.util.UUID.randomUUID().toString().replace("-", "").take(10)
        val callbackAddress = FleetPlatform.localIpv4Provider?.invoke() ?: m.address
        FleetRuntimeStore.startTask(delegateId, task, m.name, commander = localPeerId())

        val msg = AcpMessage.twinDelegate(
            from = localPeerId(), to = "*", task = task,
            delegateId = delegateId, callbackAddress = callbackAddress, callbackPort = Ports.ACP)
        val sent = com.mengpaw.kernel.namespace.AcpHolder.server.sendDirect(msg, m.address, m.port)
        return if (sent) {
            ExecutionResult.ok(
                "已委派到 ${m.name} (${m.address}:${m.port}) — 委派 ID: $delegateId\n" +
                "对端 Agent 自主执行 (可自行进入火种模式), 完成后自动回传; fleet.status 查看进度")
        } else {
            ExecutionResult.fail("委派发送失败: ${m.name} 不可达 — 检查对端在线/同一 WiFi/防火墙 9876")
        }
    }

    /** 舰队任务状态 — 委派 ID / 任务 / 状态 / 结果。 */
    private suspend fun statusCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val tasks = FleetRuntimeStore.list()
        if (tasks.isEmpty()) return ExecutionResult.ok("暂无舰队委派任务 — fleet.delegate <节点> <任务> 发起")
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
        val task = FleetRuntimeStore.find(delegateId)
            ?: return ExecutionResult.fail("未知委派 ID: $delegateId (inbox 中 twin_delegate_*.md 可查)")
        if (task.callbackAddress.isBlank()) {
            return ExecutionResult.fail("该委派无回传地址 — 结果请写入工作区文档并孪生同步")
        }
        val msg = AcpMessage.fleetResult(localPeerId(), task.commander, delegateId, result, success)
        val sent = com.mengpaw.kernel.namespace.AcpHolder.server.sendDirect(msg, task.callbackAddress, task.callbackPort)
        return if (sent) ExecutionResult.ok("已回传结果到指挥舰 (${task.callbackAddress}:${task.callbackPort})")
        else ExecutionResult.fail("回传失败: 指挥舰不可达 (${task.callbackAddress}:${task.callbackPort})")
    }

    /** 发送文件到已信任成员 — 任意格式, 接收方落 Fleet共享 目录。 */
    private suspend fun sendCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("用法: fleet.send <peer-name> <本地文件路径>")
        val member = memberByName(args[0])
        trustGate(member)?.let { return it }
        val m = member ?: return ExecutionResult.fail("通讯录中无此节点")
        val file = File(args.drop(1).joinToString(" "))
        if (!file.exists() || !file.isFile) return ExecutionResult.fail("文件不存在: ${file.absolutePath}")
        if (file.length() > com.mengpaw.kernel.acp.FleetFileHandler.MAX_FILE_BYTES) {
            return ExecutionResult.fail("文件超过 64MB 上限: ${file.length() / 1024 / 1024}MB")
        }
        val bytes = try { file.readBytes() } catch (e: Exception) { return ExecutionResult.fail("读取失败: ${e.message}") }
        val msg = AcpMessage.fleetFile(
            from = localPeerId(), to = "*", fileName = file.name,
            contentBase64 = Base64.getEncoder().encodeToString(bytes),
            sha256 = sha256Hex(bytes), size = bytes.size.toLong())
        val sent = com.mengpaw.kernel.namespace.AcpHolder.server.sendDirect(msg, m.address, m.port)
        return if (sent) ExecutionResult.ok("已发送 ${file.name} (${bytes.size / 1024}KB) 到 ${m.name}")
        else ExecutionResult.fail("发送失败: ${m.name} 不可达 (${m.address}:${m.port})")
    }

    /** 本机 Fleet共享 目录文件列表。 */
    private suspend fun filesCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val dir = File(com.mengpaw.kernel.DataPaths.FLEET_SHARE)
        val files = if (dir.exists()) dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList() else emptyList()
        if (files.isEmpty()) {
            return ExecutionResult.ok("Fleet共享 目录为空: ${dir.absolutePath}\n接收的文件自动落盘于此; 发送用 fleet.send <节点> <路径>")
        }
        return ExecutionResult.ok(buildString {
            appendLine("## Fleet共享 (${files.size}) — ${dir.absolutePath}")
            files.forEach { f ->
                appendLine("- ${f.name} · ${f.length() / 1024}KB · ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(f.lastModified()))}")
            }
        }.trimEnd())
    }

    /** 本机能力卡 (自查/调试)。 */
    private suspend fun capabilityCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val json = FleetPlatform.capabilityProvider?.invoke() ?: return ExecutionResult.fail("能力卡生成器未注入")
        val card = FleetCapability.fromJson(json) ?: return ExecutionResult.fail("能力卡解析失败")
        return ExecutionResult.ok(buildString {
            appendLine("## 本机能力 (${card.frameworkName})")
            appendLine("- 类型: ${card.frameworkType} · 版本: ${card.version}")
            appendLine("- 环境: ${card.environment} · 设备: ${card.deviceName}")
            appendLine("- 硬件: ${card.cpuCores} 核 · 内存 ${card.ramMB}MB · 磁盘剩余 ${card.diskFreeMB}MB")
            if (card.devTools.isNotEmpty()) appendLine("- 开发环境: ${card.devTools.joinToString(" / ")}")
        }.trimEnd())
    }

    /** 指挥所能力扫描 — 广播请求全部成员上报, 收集后写 Notes (规划分配依据)。 */
    private suspend fun scanCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val members = members().filter { it.trusted }
        if (members.isEmpty()) return ExecutionResult.fail("舰队无成员 — 先添加并信任框架")
        val callbackAddress = FleetPlatform.localIpv4Provider?.invoke()
            ?: return ExecutionResult.fail("无法确定本机局域网地址")
        FleetCapability.cache.clear()
        members.forEach { m ->
            val msg = AcpMessage.fleetCapability(
                from = localPeerId(), to = "*", capabilityJson = "{}",
                request = true, callbackAddress = callbackAddress, callbackPort = Ports.ACP)
            com.mengpaw.kernel.namespace.AcpHolder.server.sendDirect(msg, m.address, m.port)
        }
        kotlinx.coroutines.delay(3000)
        val caps = FleetCapability.cache.toMap()
        val notes = FleetCapability.formatNotes(caps)
        val agent = ctx.agentName ?: "MengPaw"
        val notesFile = File(com.mengpaw.kernel.DataPaths.AGENTS, "$agent/Notes/fleet_capabilities.md")
        try {
            notesFile.parentFile?.mkdirs()
            val tmp = File(notesFile.parentFile, "fleet_capabilities.md.tmp")
            tmp.writeText(notes)
            java.nio.file.Files.move(tmp.toPath(), notesFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            if (tmp.exists()) try { tmp.delete() } catch (_: Exception) {}
        } catch (e: Exception) {
            return ExecutionResult.fail("Notes 写入失败: ${e.message}")
        }
        return ExecutionResult.ok(
            "能力扫描完成: 收到 ${caps.size}/${members.size} 份上报\n" +
            "已写入 Notes: $notesFile\n" +
            if (caps.isEmpty()) "提示: 对端需 0.36 以上版本且已启动 ACP 监听" else "")
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
    }
}
