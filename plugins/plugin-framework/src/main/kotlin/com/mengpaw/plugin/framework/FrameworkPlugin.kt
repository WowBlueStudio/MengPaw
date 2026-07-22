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
        name = "框架发现",
        version = "0.1.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "局域网 MengPaw 框架发现 — mDNS 注册与扫描、指纹记录、信任管理",
        minCoreVersion = "0.9.1",
        commands = listOf(
            "framework.discover", "framework.peers",
            "framework.trust", "framework.untrust",
            "framework.info", "framework.ping"
        )
    )

    override val commands: Map<String, CommandHandler> = mapOf(
        "discover" to { args, ctx -> handleDiscover(args, ctx) },
        "peers" to { args, ctx -> handlePeers(args, ctx) },
        "trust" to { args, ctx -> handleTrust(args, ctx) },
        "untrust" to { args, ctx -> handleUntrust(args, ctx) },
        "info" to { args, ctx -> handleInfo(args, ctx) },
        "ping" to { args, ctx -> handlePing(args, ctx) }
    )

    private val discovery get() = FrameworkDiscovery.instance

    override suspend fun onInstall(ctx: PluginContext) {}
    override suspend fun onUninstall() {
        discovery?.stopDiscovery()
        discovery?.unregister()
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
