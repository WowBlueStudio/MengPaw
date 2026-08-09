// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── 记忆孪生激活 — 拆自 AppRoot.kt (2026-08-06, >400 行文件拆分批次4) ──

/**
 * 记忆孪生激活: 安装/激活插件 + 启动 ACP 服务。
 * 在 IO 协程中执行 (P2 修复: 原 AppRoot 内 runBlocking 主线程执行插件安装, 点击即卡 UI);
 * 安装失败时兜底二次激活 (插件可能已安装未激活)。
 */
internal suspend fun installAndActivateTwin(context: android.content.Context, name: String) {
    android.util.Log.i("MengPawTwin", "激活开始: agent=$name")
    val plugin = com.mengpaw.plugin.memorytwin.MemoryTwinPlugin()
    com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.appContext = context
    com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.agentName = name
    val pm = com.mengpaw.kernel.plugin.PluginManager.globalInstance
    val installResult = pm.install(plugin)
    installResult.fold(
        onSuccess = {
            pm.activate(plugin.metadata.id).fold(
                onSuccess = {
                    android.util.Log.i("MengPawTwin", "插件激活成功")
                    startAcpForTwin(context, name)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "记忆孪生已激活", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                onFailure = { e ->
                    android.util.Log.e("MengPawTwin", "激活失败: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "激活失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            )
        },
        onFailure = { e ->
            android.util.Log.e("MengPawTwin", "安装失败: ${e.message}", e)
            // 安装失败兜底: 可能已安装未激活 — 尝试二次激活
            pm.activate(plugin.metadata.id).fold(
                onSuccess = {
                    android.util.Log.i("MengPawTwin", "二次激活成功")
                    startAcpForTwin(context, name)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "记忆孪生已激活", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                onFailure = { e2 ->
                    android.util.Log.e("MengPawTwin", "二次激活失败: ${e2.message}", e2)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "激活失败: ${e2.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    )
}

/** 启动 ACP 服务 + 注册 TwinAcpHandler (接收配对请求) — internal: MainActivity.autoRestoreTwinIfNeeded 也调用 */
internal suspend fun startAcpForTwin(ctx: android.content.Context, agentName: String) {
    try {
        // v0.35.4 修复: 与框架配对共用 AcpHolder.server — FrameworkPairHandler 注册在
        // 同一 server 上, 否则实际监听 9876 的独立孪生 server 收不到 FRAMEWORK_PAIR_*
        val server = com.mengpaw.kernel.namespace.AcpHolder.server
        val profile = com.mengpaw.kernel.agent.AgentProfile.load(agentName)
        server.updateProfile(profile)

        // 幂等: 同 agent 重复激活 (autoRestore + 用户激活) 直接复用, 不重复注册 handler
        val existingEngine = com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.activeEngine
        if (existingEngine != null &&
            com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.agentName == agentName &&
            com.mengpaw.kernel.namespace.AcpHolder.transport?.isConnected() == true
        ) {
            android.util.Log.i("MengPawTwin", "孪生已激活, 复用现有 ACP 服务")
            return
        }
        // 切换 agent 重建: 先停旧自动同步 + 摘除旧 handler
        existingEngine?.stopAutoSync()
        com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.twinAcpHandler?.let { server.unregisterHandler(it) }

        // SECURITY: 设备指纹用于孪生身份 (密钥派生在配对流程, server 认证走 AcpCrypto)
        val deviceFingerprint = try { com.mengpaw.kernel.acp.AcpCrypto.myFingerprint() } catch (_: Exception) { "device-${System.currentTimeMillis()}" }
        val transport = com.mengpaw.kernel.namespace.AcpHolder.ensureListening()

        // 注册 TwinAcpHandler — 处理 CAPABILITY_ANNOUNCE 等孪生消息
        val syncEngine = com.mengpaw.plugin.memorytwin.TwinSyncEngine(
            serverSupplier = { server }, transportSupplier = { transport },
            agentName = agentName, deviceId = deviceFingerprint,
            deviceName = android.os.Build.MODEL ?: "Android")
        val handler = com.mengpaw.plugin.memorytwin.TwinAcpHandler(syncEngine)
        server.registerHandler(handler)
        com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.twinAcpHandler = handler

        // ── MCP-over-ACP 桥 (协议升级: 远程 MCP 调用走配对加密通道) ──
        try {
            val pm = com.mengpaw.kernel.plugin.PluginManager.globalInstance
            val mcpServer = com.mengpaw.kernel.mcp.McpServer(pm)
            // 反射注册 browser-mcp provider (remote 插件, 未安装时跳过) — 暴露 6 个浏览器 MCP 工具
            try {
                val pluginCls = Class.forName("com.mengpaw.plugin.browsermcp.BrowserMcpPlugin")
                val provider = pluginCls.getDeclaredConstructor().newInstance()
                    as com.mengpaw.kernel.mcp.McpToolProvider
                mcpServer.registerToolProvider(provider)
            } catch (_: Exception) {}
            server.enableMcpBridge(mcpServer)
            android.util.Log.i("MengPawTwin", "MCP-over-ACP 桥已启用")
        } catch (e: Exception) {
            android.util.Log.w("MengPawTwin", "MCP 桥启用失败: ${e.message}")
        }

        syncEngine.startAutoSync()  // 启动自动同步 (每60秒)
        // 加载 mDNS 发现的框架节点作为同步目标
        val frameworkPeers = com.mengpaw.plugin.framework.FrameworkPeerStore.loadAll()
        syncEngine.updatePeers(frameworkPeers.map { fp ->
            com.mengpaw.plugin.memorytwin.TwinPeerInfo(
                peerId = fp.name, agentName = fp.name,
                address = fp.address.split(":").firstOrNull() ?: fp.address,
                port = fp.port)
        })
        android.util.Log.i("MengPawTwin", "已注册 + 自动同步 + ${frameworkPeers.size} 个节点")

        com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.acpServer = server
        com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.acpTransport = transport
        com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.twinProfile = profile
        // 注入 engine 供 twin.start 复用 (双引擎债务修复, v0.22.0)
        com.mengpaw.plugin.memorytwin.MemoryTwinPlugin.activeEngine = syncEngine
        // 标记已激活, 下次启动自动恢复
        java.io.File(ctx.filesDir, "twin_activated").writeText(agentName)
        android.util.Log.i("MengPawTwin", "孪生服务已启动 (${frameworkPeers.size} 个节点)")
    } catch (e: Exception) {
        android.util.Log.e("MengPawTwin", "启动失败: ${e.message}", e)
    }
}
