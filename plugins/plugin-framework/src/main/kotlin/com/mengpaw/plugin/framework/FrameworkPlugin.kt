// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.framework

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.plugin.*

/**
 * 框架发现插件 — 局域网 mDNS 注册 / 扫描 / 指纹管理。
 *
 * CLI 命令 (framework.*):
 *   discover [--wait] — 启动局域网扫描 (默认异步, --wait 同步等待 3s)
 *   add <name> <address> [port] [--type <type>] — 手动添加节点
 *   peers    — 列出已知框架
 *   trust <fingerprint> [--yes] — 信任框架 (需 --yes 确认)
 *   untrust <fingerprint> — 移除信任
 *   info <fingerprint> — 框架详情
 *   ping <fingerprint> — 存活检测
 */
class FrameworkPlugin : Plugin {

    companion object {
        /** 配对请求 handler 实例 — 反注册需同一实例 (v0.35.1)。 */
        @Volatile private var pairHandler: FrameworkPairHandler? = null
    }

    override val metadata = PluginMetadata(
        id = "framework-plugin",
        name = "框架发现",
        version = "", // 内置插件, 随 Shell APK 版本更新
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "框架通信协议插件 — mDNS 发现/信任/通讯录 + 本机 MCP 网关 (9881) + 连接器分派 (OpenClaw/QwenPaw 等)",
        minCoreVersion = "0.9.1",
        commands = listOf(
            "framework.discover", "framework.add", "framework.peers",
            "framework.trust", "framework.untrust",
            "framework.info", "framework.ping",
            "framework.connect", "framework.call",
            "framework.disconnect", "framework.adapters",
            // v0.35.2 审查闭环: Agent 侧配对请求操作入口
            "framework.pair.ls", "framework.pair.accept", "framework.pair.decline"
        )
    )

    override val commands: Map<String, CommandHandler> = mapOf(
        "discover" to { args, ctx -> handleDiscover(args, ctx) },
        "add" to { args, ctx -> handleAdd(args, ctx) },
        "peers" to { args, ctx -> handlePeers(args, ctx) },
        "trust" to { args, ctx -> handleTrust(args, ctx) },
        "untrust" to { args, ctx -> handleUntrust(args, ctx) },
        "info" to { args, ctx -> handleInfo(args, ctx) },
        "ping" to { args, ctx -> handlePing(args, ctx) },
        "connect" to { args, ctx -> handleConnect(args, ctx) },
        "call" to { args, ctx -> handleCall(args, ctx) },
        "disconnect" to { args, ctx -> handleDisconnect(args, ctx) },
        "adapters" to { args, ctx -> handleAdapters(args, ctx) },
        "pair.ls" to { args, ctx -> handlePairLs(args, ctx) },
        "pair.accept" to { args, ctx -> handlePairAccept(args, ctx) },
        "pair.decline" to { args, ctx -> handlePairDecline(args, ctx) }
    )

    private val discovery get() = FrameworkDiscovery.instance

    // ── 配对请求 (v0.35.2 审查闭环: Agent 认知层缺口) ──────────────────

    private suspend fun handlePairLs(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        // 顺带清理过期已处理请求 (7 天)
        try { FrameworkPairStore.cleanupExpired() } catch (_: Exception) {}
        val all = FrameworkPairStore.loadAll()
        if (all.isEmpty()) {
            return ExecutionResult.ok(
                "暂无配对请求。添加框架流程: 通讯录 → 添加框架 → 扫描/手动 → 发送请求, 对方红点查看并同意。")
        }
        val pending = all.filter { it.status == FrameworkPairStore.PairStatus.PENDING }
        val sb = StringBuilder("配对请求 (待处理 ${pending.size} / 共 ${all.size}):\n\n")
        all.forEach { r ->
            val statusLabel = when (r.status) {
                FrameworkPairStore.PairStatus.PENDING -> "待处理"
                FrameworkPairStore.PairStatus.ACCEPTED -> "已同意"
                FrameworkPairStore.PairStatus.DECLINED -> "已拒绝"
            }
            sb.appendLine("${r.requestId} · ${r.fromName} (${r.fromAddress}:${r.fromPort}) · $statusLabel")
        }
        if (pending.isNotEmpty()) {
            sb.appendLine("\n同意: framework.pair.accept <requestId>; 拒绝: framework.pair.decline <requestId>")
        }
        return ExecutionResult.ok(sb.toString().trimEnd())
    }

    private suspend fun handlePairAccept(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val id = args.firstOrNull()
            ?: return ExecutionResult.fail("用法: framework.pair.accept <requestId> (framework.pair.ls 查看)", errorCode = com.mengpaw.kernel.cli.ErrorCodes.ERR_INVALID_INPUT)
        val req = FrameworkPairStore.findByRequestId(id)
            ?: return ExecutionResult.fail("未找到请求 $id — framework.pair.ls 查看当前请求", errorCode = com.mengpaw.kernel.cli.ErrorCodes.ERR_NOT_FOUND)
        if (req.status != FrameworkPairStore.PairStatus.PENDING) {
            return ExecutionResult.ok("该请求已处理: ${req.status}")
        }
        val sent = FrameworkPairEngine.accept(req)
        return if (sent) {
            ExecutionResult.ok("已同意 ${req.fromName} 的配对请求 — 双方已加入框架通讯录")
        } else {
            ExecutionResult.fail(
                "已本地入册 ${req.fromName}, 但回执发送失败 — 请确认对方在线且同一 WiFi (防火墙可能拦截); framework.pair.ls 复查")
        }
    }

    private suspend fun handlePairDecline(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val id = args.firstOrNull()
            ?: return ExecutionResult.fail("用法: framework.pair.decline <requestId> (framework.pair.ls 查看)", errorCode = com.mengpaw.kernel.cli.ErrorCodes.ERR_INVALID_INPUT)
        val req = FrameworkPairStore.findByRequestId(id)
            ?: return ExecutionResult.fail("未找到请求 $id — framework.pair.ls 查看当前请求", errorCode = com.mengpaw.kernel.cli.ErrorCodes.ERR_NOT_FOUND)
        if (req.status != FrameworkPairStore.PairStatus.PENDING) {
            return ExecutionResult.ok("该请求已处理: ${req.status}")
        }
        val sent = FrameworkPairEngine.decline(req)
        return if (sent) {
            ExecutionResult.ok("已拒绝 ${req.fromName} 的配对请求")
        } else {
            ExecutionResult.fail("已本地标记拒绝, 但回执发送失败 — 请确认对方在线")
        }
    }

    override suspend fun onInstall(ctx: PluginContext) {
        // 本机标准 MCP 网关 (9881) — 任何 MCP client 直连 MengPaw 工具
        McpGateway.start()
        // v0.35.1 框架发现流程调整: 注册配对请求 handler (请求-同意双向入册)
        try {
            pairHandler = FrameworkPairHandler()
            pairHandler?.let {
                com.mengpaw.kernel.namespace.AcpHolder.server.registerHandler(it)
            }
            // v0.35.4 修复: 确保 ACP 端口在监听 — 此前只有手动 self.acp start 才接收
            // 配对请求, 对端"添加"后本机收不到弹窗/红点
            com.mengpaw.kernel.namespace.AcpHolder.ensureListening()
        } catch (e: Exception) {
            com.mengpaw.kernel.KernelLog.w("FrameworkPlugin", "register pair handler failed: ${e.message}")
        }
    }
    override suspend fun onUninstall() {
        try {
            pairHandler?.let {
                com.mengpaw.kernel.namespace.AcpHolder.server.unregisterHandler(it)
            }
            pairHandler = null
        } catch (_: Exception) {}
        McpGateway.stop()
        discovery?.stopDiscovery()
        discovery?.unregister()
    }

    // ── 连接器分派 (协议升级: 非 MengPaw 框架经 SPI 适配器接入) ──────

    private suspend fun handleConnect(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val peerName = args.firstOrNull()
            ?: return ExecutionResult.fail("用法: framework.connect <peer-name>", errorCode = com.mengpaw.kernel.cli.ErrorCodes.ERR_INVALID_INPUT)
        val peer = FrameworkPeerStore.loadAll().find { it.name == peerName }
        // v0.35.5 信任门禁: 未信任节点禁止连接/委派 — 对齐"信任后即可 framework.connect"的引导语义
        frameworkTrustGate(peerName, peer)?.let { return it }
        val p = peer ?: return ExecutionResult.fail("通讯录中无此节点: $peerName",
            errorCode = com.mengpaw.kernel.cli.ErrorCodes.ERR_NOT_FOUND)
        val adapter = com.mengpaw.kernel.spi.FrameworkAdapterRegistry.find(p.frameworkType)
            ?: return ExecutionResult.fail(
                "框架类型 '${p.frameworkType}' 无连接器 — 请安装对应连接器插件 (plugin.search connector)",
                errorCode = com.mengpaw.kernel.cli.ErrorCodes.ERR_NOT_FOUND
            )
        val target = com.mengpaw.kernel.spi.FrameworkTarget(
            name = p.name, type = p.frameworkType,
            address = p.address.split(":").firstOrNull() ?: p.address,
            port = p.port
        )
        return adapter.connect(target).fold(
            onSuccess = { ExecutionResult.ok("已连接 ${p.frameworkType} 节点: ${p.name} (${p.address})") },
            onFailure = {
                // v0.34.3: 联络无效 → 横幅提醒 (侧边栏关闭后用户能感知)
                try {
                    com.mengpaw.kernel.namespace.NotifyBus.banner(
                        "⚠️ 无法联络框架 ${p.name} (${p.address}): ${it.message?.take(60) ?: "连接失败"}",
                        com.mengpaw.kernel.namespace.NotifyBus.NotifyLevel.WARN
                    )
                } catch (_: Exception) {}
                ExecutionResult.fail("连接失败: ${it.message}")
            }
        )
    }

    private suspend fun handleCall(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail(
            "用法: framework.call <peer-name> <tool> [jsonArgs]", errorCode = com.mengpaw.kernel.cli.ErrorCodes.ERR_INVALID_INPUT)
        val peerName = args[0]
        val tool = args[1]
        val peer = FrameworkPeerStore.loadAll().find { it.name == peerName }
        // v0.35.5 信任门禁: 与 connect 同源 — 未信任节点禁止调用远端工具
        frameworkTrustGate(peerName, peer)?.let { return it }
        val p = peer ?: return ExecutionResult.fail("通讯录中无此节点: $peerName",
            errorCode = com.mengpaw.kernel.cli.ErrorCodes.ERR_NOT_FOUND)
        val adapter = com.mengpaw.kernel.spi.FrameworkAdapterRegistry.find(p.frameworkType)
            ?: return ExecutionResult.fail("框架类型 '${p.frameworkType}' 无连接器")
        if (!adapter.isOnline()) return ExecutionResult.fail("连接器未在线 — 先执行 framework.connect $peerName")
        val jsonArgs = if (args.size > 2) {
            try {
                val json = org.json.JSONObject(args.drop(2).joinToString(" "))
                val map = mutableMapOf<String, String>()
                for (key in json.keys()) { map[key] = json.optString(key, "") }
                map
            } catch (_: Exception) { emptyMap() }
        } else emptyMap()
        return adapter.callTool(tool, jsonArgs).fold(
            onSuccess = { ExecutionResult.ok(it) },
            onFailure = { ExecutionResult.fail("调用失败: ${it.message}") }
        )
    }

    private suspend fun handleDisconnect(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val peerName = args.firstOrNull()
            ?: return ExecutionResult.fail("用法: framework.disconnect <peer-name>", errorCode = com.mengpaw.kernel.cli.ErrorCodes.ERR_INVALID_INPUT)
        val peer = FrameworkPeerStore.loadAll().find { it.name == peerName }
        val adapter = peer?.let { com.mengpaw.kernel.spi.FrameworkAdapterRegistry.find(it.frameworkType) }
        adapter?.disconnect()
        return ExecutionResult.ok("已断开: $peerName")
    }

    private suspend fun handleAdapters(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val adapters = com.mengpaw.kernel.spi.FrameworkAdapterRegistry.list()
        val gatewayStatus = if (McpGateway.isRunning) "运行中 (127.0.0.1:${com.mengpaw.kernel.ports.Ports.MCP_LOCAL})" else "未运行"
        val sb = StringBuilder()
        sb.append("## 框架通信协议\n")
        sb.append("- 本机 MCP 网关: $gatewayStatus\n")
        sb.append("- 已注册连接器: ${adapters.size}\n\n")
        if (adapters.isEmpty()) {
            sb.append("> 无连接器插件 — 安装后自动注册 (plugin.search connector)\n")
        } else {
            adapters.forEach { a ->
                sb.append("- ${a.frameworkName}: ${if (a.isOnline()) "在线" else "离线"}")
                if (a.toolsDescription.isNotBlank()) {
                    sb.append("\n    工具: ${a.toolsDescription}")
                }
                sb.append("\n")
            }
        }
        return ExecutionResult.ok(sb.toString())
    }

    // ── CLI handlers ──

    private suspend fun handleDiscover(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        discovery?.startDiscovery()
        // v0.34.3: 发现结果在内存 (discoveredPeers), 不再自动入册 — 用户确认后入册
        if (!args.contains("--wait")) {
            return ExecutionResult.ok(
                "🔍 mDNS 扫描已启动 (约 3 秒完成) — 稍后执行 framework.discover --wait 查看发现结果;\n" +
                "发现的节点需确认后入册 (framework.add <名称> <IP> <端口> 或侧边栏添加按钮)。\n" +
                "未发现时检查: 同一 WiFi / 防火墙; 或用 framework.add <名称> <IP> <端口> 手动添加节点。"
            )
        }
        kotlinx.coroutines.delay(3000)
        val peers = discovery?.discoveredPeers?.toList().orEmpty()
        if (peers.isEmpty()) return ExecutionResult.ok(
            "未发现局域网框架。请确保其他设备已启动 MengPaw 并在同一 WiFi (防火墙可能拦截 mDNS); 或手动添加: framework.add <名称> <IP> <端口>。\n" +
            "已入册节点: framework.peers")
        val sb = StringBuilder("发现 ${peers.size} 个框架 (未入册):\n\n")
        peers.forEach { p ->
            sb.appendLine("${p.name} · v${p.version} · ${p.address}:${p.port}")
            sb.appendLine("   指纹: ${p.fingerprint}")
            sb.appendLine("   入册: framework.add ${p.name} ${p.address} ${p.port} (或侧边栏添加)")
            sb.appendLine()
        }
        sb.appendLine("> 跨版本说明: 旧版对端不广播设备标识 (did) 时, 指纹暂按 IP 回退 (换 IP 会变); 双方升级到最新版后指纹固定。")
        return ExecutionResult.ok(sb.toString().trimEnd())
    }

    /**
     * 手动添加框架节点 — mDNS 发现不可用时的替代通道 (信任后同样可委派)。
     * Usage: framework.add <name> <address> [port] [--type <frameworkType>]
     */
    private suspend fun handleAdd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail(
            "用法: framework.add <name> <address> [port] [--type <frameworkType>]\n" +
            "例: framework.add 书房手机 192.168.1.5 9876\n" +
            "    framework.add qwen-node 192.168.1.8 --type qwenpaw",
            errorCode = com.mengpaw.kernel.cli.ErrorCodes.ERR_INVALID_INPUT)
        val name = args[0]
        val address = args[1]
        val typeIdx = args.indexOf("--type")
        val frameworkType = if (typeIdx >= 0 && typeIdx + 1 < args.size) args[typeIdx + 1] else "mengpaw"
        // 显式端口 = 位置 2 (跳过 --type 及其值)
        val explicitPort = args.getOrNull(2)?.toIntOrNull()
        val defaultPort = FrameworkPeerStore.FRAMEWORK_TYPES[frameworkType]?.takeIf { it > 0 }
            ?: com.mengpaw.kernel.ports.Ports.ACP
        val port = explicitPort ?: defaultPort
        if (port !in 1..65535) return ExecutionResult.fail(
            "非法端口: $port (1-65535)", errorCode = com.mengpaw.kernel.cli.ErrorCodes.ERR_INVALID_INPUT)
        val fingerprint = FrameworkPeerStore.computeFingerprint(frameworkType, "$address:$port")
        val existing = FrameworkPeerStore.findByFingerprint(fingerprint)
        if (existing != null) {
            return ExecutionResult.fail("节点已存在: ${existing.name} (${existing.address}:${existing.port})\n" +
                "如地址/端口有变, 请先 framework.untrust $fingerprint 再重新添加")
        }
        val peer = FrameworkPeerStore.FrameworkPeer(
            fingerprint = fingerprint,
            name = name,
            version = "手动添加",
            frameworkName = "MengPaw",
            address = address,
            port = port,
            frameworkType = frameworkType,
            lastSeen = System.currentTimeMillis()
        )
        FrameworkPeerStore.save(peer)
        return ExecutionResult.ok(
            "已添加框架节点: $name (${address}:${port}, 类型 $frameworkType)\n" +
            "指纹: $fingerprint\n" +
            "下一步: framework.trust $fingerprint --yes 信任后即可 framework.connect $name 委派任务。")
    }

    private suspend fun handlePeers(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val peers = FrameworkPeerStore.loadAll()
        if (peers.isEmpty()) return ExecutionResult.ok("暂无已知框架。执行 framework.discover 扫描局域网。")
        val sb = StringBuilder("已知框架 (${peers.size})：\n\n")
        peers.forEach { p ->
            val online = if (p.lastSeen > System.currentTimeMillis() - 120_000) "在线" else "离线"
            val trust = if (p.trusted) "已信任" else "未信任"
            sb.appendLine("${p.name} · v${p.version} · ${p.address}:${p.port}")
            sb.appendLine("   指纹: ${p.fingerprint} · $online · $trust")
            sb.appendLine()
        }
        return ExecutionResult.ok(sb.toString().trimEnd())
    }

    private suspend fun handleTrust(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val fp = args.firstOrNull() ?: return ExecutionResult.fail("用法: framework.trust <fingerprint> [--yes]")
        val peer = FrameworkPeerStore.findByFingerprint(fp)
            ?: return ExecutionResult.fail("未找到指纹为 $fp 的框架 (用 framework.discover / framework.peers 查看)")
        if (peer.trusted) return ExecutionResult.ok("${peer.name} 已在信任列表")
        // 二次确认: 信任 = GUEST → TRUSTED 全量放行 (委派/记忆共享/工具调用), 需显式 --yes
        if (!args.contains("--yes")) {
            return ExecutionResult.fail(
                "⚠️ 信任 ${peer.name} (${peer.address}:${peer.port}, ${peer.frameworkType}) 将放行: 任务委派 / 记忆共享 / 工具调用。\n" +
                "信任状态未改变。确认请执行: framework.trust $fp --yes")
        }
        FrameworkPeerStore.save(peer.copy(trusted = true))
        return ExecutionResult.ok("已信任框架: ${peer.name} ($fp) — 可 framework.connect ${peer.name} 或 framework.call ${peer.name} <tool> 委派任务")
    }

    private suspend fun handleUntrust(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val fp = args.firstOrNull() ?: return ExecutionResult.fail("用法: framework.untrust <fingerprint>")
        val peer = FrameworkPeerStore.findByFingerprint(fp)
            ?: return ExecutionResult.fail("未找到指纹为 $fp 的框架")
        FrameworkPeerStore.save(peer.copy(trusted = false))
        return ExecutionResult.ok("已撤销信任: ${peer.name} ($fp)")
    }

    private suspend fun handleInfo(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val fp = args.firstOrNull() ?: return ExecutionResult.fail("用法: framework.info <fingerprint>")
        val peer = FrameworkPeerStore.findByFingerprint(fp)
            ?: return ExecutionResult.fail("未找到指纹为 $fp 的框架")
        val online = discovery?.ping(peer.address, peer.port) == true
        val sb = StringBuilder()
        sb.appendLine("框架信息")
        sb.appendLine("名称: ${peer.name}")
        sb.appendLine("版本: ${peer.version}")
        sb.appendLine("地址: ${peer.address}:${peer.port}")
        sb.appendLine("指纹: ${peer.fingerprint}")
        sb.appendLine("状态: ${if (online) "在线" else "离线"}")
        sb.appendLine("信任: ${if (peer.trusted) "已信任" else "未信任"}")
        if (peer.capabilities.isNotEmpty()) sb.appendLine("能力: ${peer.capabilities.joinToString()}")
        sb.appendLine("最后探测: ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(peer.lastSeen))}")
        return ExecutionResult.ok(sb.toString())
    }

    private suspend fun handlePing(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val fp = args.firstOrNull() ?: return ExecutionResult.fail("用法: framework.ping <fingerprint>")
        val peer = FrameworkPeerStore.findByFingerprint(fp)
            ?: return ExecutionResult.fail("未找到指纹为 $fp 的框架")
        val alive = discovery?.ping(peer.address, peer.port) == true
        if (alive) {
            FrameworkPeerStore.save(peer.copy(lastSeen = System.currentTimeMillis()))
            return ExecutionResult.ok("${peer.name} 在线 (${peer.address}:${peer.port})")
        }
        return ExecutionResult.ok("${peer.name} 无响应 (${peer.address}:${peer.port})")
    }
}

/**
 * framework.connect/call 信任门禁 (v0.35.5) — 通讯录节点不存在或未信任时返回拒绝结果,
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
