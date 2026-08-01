// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.framework

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.plugin.*

/**
 * 框架发现插件 — 局域网 mDNS 注册 / 扫描 / 指纹管理。
 *
 * CLI 命令 (framework.*):
 *   discover — 立即扫描局域网
 *   peers    — 列出已知框架
 *   trust <fingerprint> — 信任框架
 *   untrust <fingerprint> — 移除信任
 *   info <fingerprint> — 框架详情
 *   ping <fingerprint> — 存活检测
 */
class FrameworkPlugin : Plugin {

    override val metadata = PluginMetadata(
        id = "framework-plugin",
        name = "框架通信",
        version = "", // 内置插件, 随 Shell APK 版本更新
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "框架通信协议插件 — mDNS 发现/信任/通讯录 + 本机 MCP 网关 (9881) + 连接器分派 (OpenClaw/QwenPaw 等)",
        minCoreVersion = "0.9.1",
        commands = listOf(
            "framework.discover", "framework.peers",
            "framework.trust", "framework.untrust",
            "framework.info", "framework.ping",
            "framework.connect", "framework.call",
            "framework.disconnect", "framework.adapters"
        )
    )

    override val commands: Map<String, CommandHandler> = mapOf(
        "discover" to { args, ctx -> handleDiscover(args, ctx) },
        "peers" to { args, ctx -> handlePeers(args, ctx) },
        "trust" to { args, ctx -> handleTrust(args, ctx) },
        "untrust" to { args, ctx -> handleUntrust(args, ctx) },
        "info" to { args, ctx -> handleInfo(args, ctx) },
        "ping" to { args, ctx -> handlePing(args, ctx) },
        "connect" to { args, ctx -> handleConnect(args, ctx) },
        "call" to { args, ctx -> handleCall(args, ctx) },
        "disconnect" to { args, ctx -> handleDisconnect(args, ctx) },
        "adapters" to { args, ctx -> handleAdapters(args, ctx) }
    )

    private val discovery get() = FrameworkDiscovery.instance

    override suspend fun onInstall(ctx: PluginContext) {
        // 本机标准 MCP 网关 (9881) — 任何 MCP client 直连 MengPaw 工具
        McpGateway.start()
    }
    override suspend fun onUninstall() {
        McpGateway.stop()
        discovery?.stopDiscovery()
        discovery?.unregister()
    }

    // ── 连接器分派 (协议升级: 非 MengPaw 框架经 SPI 适配器接入) ──────

    private suspend fun handleConnect(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val peerName = args.firstOrNull()
            ?: return ExecutionResult.fail("用法: framework.connect <peer-name>", errorCode = com.mengpaw.kernel.cli.ErrorCodes.ERR_INVALID_INPUT)
        val peer = FrameworkPeerStore.loadAll().find { it.name == peerName }
            ?: return ExecutionResult.fail("通讯录中无此节点: $peerName")
        val adapter = com.mengpaw.kernel.spi.FrameworkAdapterRegistry.find(peer.frameworkType)
            ?: return ExecutionResult.fail(
                "框架类型 '${peer.frameworkType}' 无连接器 — 请安装对应连接器插件 (plugin.search connector)",
                errorCode = com.mengpaw.kernel.cli.ErrorCodes.ERR_NOT_FOUND
            )
        val target = com.mengpaw.kernel.spi.FrameworkTarget(
            name = peer.name, type = peer.frameworkType,
            address = peer.address.split(":").firstOrNull() ?: peer.address,
            port = peer.port
        )
        return adapter.connect(target).fold(
            onSuccess = { ExecutionResult.ok("已连接 ${peer.frameworkType} 节点: ${peer.name} (${peer.address})") },
            onFailure = { ExecutionResult.fail("连接失败: ${it.message}") }
        )
    }

    private suspend fun handleCall(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail(
            "用法: framework.call <peer-name> <tool> [jsonArgs]", errorCode = com.mengpaw.kernel.cli.ErrorCodes.ERR_INVALID_INPUT)
        val peerName = args[0]
        val tool = args[1]
        val peer = FrameworkPeerStore.loadAll().find { it.name == peerName }
            ?: return ExecutionResult.fail("通讯录中无此节点: $peerName")
        val adapter = com.mengpaw.kernel.spi.FrameworkAdapterRegistry.find(peer.frameworkType)
            ?: return ExecutionResult.fail("框架类型 '${peer.frameworkType}' 无连接器")
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
                sb.append("- ${a.frameworkName}: ${if (a.isOnline()) "在线" else "离线"}\n")
            }
        }
        return ExecutionResult.ok(sb.toString())
    }

    // ── CLI handlers ──

    private suspend fun handleDiscover(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        discovery?.startDiscovery()
        // 等待 3s 让 mDNS 解析完成
        kotlinx.coroutines.delay(3000)
        val peers = FrameworkPeerStore.loadAll()
        if (peers.isEmpty()) return ExecutionResult.ok("未发现局域网框架。请确保其他设备已启动 MengPaw 并在同一 WiFi。")
        val sb = StringBuilder("发现 ${peers.size} 个框架：\n\n")
        peers.forEach { p ->
            val online = if (p.lastSeen > System.currentTimeMillis() - 120_000) "🟢" else "⚫"
            val trust = if (p.trusted) "✓" else "?"
            sb.appendLine("$online [$trust] ${p.name} · v${p.version} · ${p.address}:${p.port}")
            sb.appendLine("   指纹: ${p.fingerprint}")
            if (p.capabilities.isNotEmpty()) sb.appendLine("   能力: ${p.capabilities.joinToString()}")
            sb.appendLine()
        }
        return ExecutionResult.ok(sb.toString().trimEnd())
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
        val fp = args.firstOrNull() ?: return ExecutionResult.fail("用法: framework.trust <fingerprint>")
        val peer = FrameworkPeerStore.findByFingerprint(fp)
            ?: return ExecutionResult.fail("未找到指纹为 $fp 的框架")
        FrameworkPeerStore.save(peer.copy(trusted = true))
        return ExecutionResult.ok("已信任框架: ${peer.name} ($fp)")
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
