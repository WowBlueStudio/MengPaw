// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.connector.reasonix

import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginContext
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType
import com.mengpaw.kernel.spi.FrameworkAdapter
import com.mengpaw.kernel.spi.FrameworkAdapterRegistry
import com.mengpaw.kernel.spi.FrameworkTarget
import com.mengpaw.plugin.connector.common.ConnectorCommand
import com.mengpaw.plugin.connector.common.ConnectorConfigStore
import com.mengpaw.plugin.connector.common.SshTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reasonix 通讯连接器 (外部分发, 不内置) — SSH 执行 reasonix CLI (单 Go 二进制) 对接 DeepSeek-Reasonix。
 *
 * 上游: esengine/DeepSeek-Reasonix (MIT 许可) — DeepSeek 原生终端 agent, 本插件仅协议互操作调用。
 * 通道: SSH (Windows 11 自带 OpenSSH Server)。
 *
 * 工具映射 (framework.call <peer> <tool>):
 *   run      {task} → `reasonix run "<task>"` (一次性任务执行)
 *   version          → `reasonix --version`
 *
 * 配置: connector-reasonix.config (凭据) / connector-reasonix.info (状态)。
 */
class ReasonixConnectorPlugin : Plugin, FrameworkAdapter {

    override val metadata = PluginMetadata(
        id = "connector-reasonix-plugin",
        name = "Reasonix 通讯",
        version = "0.1.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "Reasonix 通讯连接器 — SSH 执行 reasonix CLI, 经 framework.connect/call 委派任务到 PC",
        minCoreVersion = "0.20.0",
        commands = listOf(
            "connector-reasonix.config",
            "connector-reasonix.info"
        )
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "config" to ConnectorCommand.configHandler(PLUGIN_ID),
        "info" to ConnectorCommand.infoHandler(PLUGIN_ID) {
            val t = transport
            if (t?.isConnected() == true) "在线 (SSH: $connectedHost)" else "离线"
        }
    )
    override val uiButtons: List<com.mengpaw.kernel.plugin.PluginUiButton> = emptyList()

    // ── FrameworkAdapter ────────────────────────────────────────────────

    override val frameworkName: String = "reasonix"

    @Volatile private var transport: SshTransport? = null
    @Volatile private var connectedHost: String = ""

    override suspend fun connect(target: FrameworkTarget): Result<Unit> = withContext(Dispatchers.IO) {
        val cfg = ConnectorConfigStore.read(PLUGIN_ID)
        if (cfg.user.isBlank()) {
            return@withContext Result.failure(IllegalStateException(
                "未配置 SSH 凭据 — 先执行 connector-reasonix.config --user <用户名> [--password <密码> | --key-path <PEM路径>]"
            ))
        }
        val host = target.address
        val port = if (cfg.sshPort in 1..65535) cfg.sshPort else 22
        val t = SshTransport(host, port, cfg.user, cfg.password, cfg.keyPath, cfg.keyPassphrase)
        val connected = t.connect()
        if (connected.isFailure) {
            t.disconnect()
            return@withContext Result.failure(IllegalStateException(
                "SSH 连接失败 (${target.address}:$port): ${connected.exceptionOrNull()?.message} — 检查 PC 端 OpenSSH Server 与凭据"
            ))
        }
        val probe = t.exec("${cliOf(cfg)} --version", 30_000)
        if (probe.isFailure) {
            t.disconnect()
            return@withContext Result.failure(IllegalStateException(
                "SSH 已连通但 reasonix CLI 不可用: ${probe.exceptionOrNull()?.message} — 检查 PC 端 reasonix 安装或 --cli-path"
            ))
        }
        this@ReasonixConnectorPlugin.transport = t
        this@ReasonixConnectorPlugin.connectedHost = "${target.address}:$port"
        Result.success(Unit)
    }

    override suspend fun disconnect() {
        transport?.disconnect()
        transport = null
        connectedHost = ""
    }

    override suspend fun callTool(tool: String, args: Map<String, String>): Result<String> = withContext(Dispatchers.IO) {
        val t = transport ?: return@withContext Result.failure(IllegalStateException("未连接 — 先执行 framework.connect <peer>"))
        val cfg = ConnectorConfigStore.read(PLUGIN_ID)
        val cli = cliOf(cfg)
        when (tool) {
            "run" -> {
                val task = args["task"]
                    ?: return@withContext Result.failure(IllegalStateException("run 需要 task 参数"))
                val cmd = "\"$cli\" run \"" + SshTransport.shellEscape(task) + "\""
                t.exec(cmd, 300_000).map { it.stdout.trim() } // 长任务最多 5 分钟
            }
            "version" -> t.exec("\"$cli\" --version", 30_000).map { it.stdout.trim() }
            else -> Result.failure(IllegalStateException("未知工具: $tool (可用: run / version)"))
        }
    }

    override fun isOnline(): Boolean = transport?.isConnected() == true

    // ── Plugin lifecycle ────────────────────────────────────────────────

    override suspend fun onInstall(ctx: PluginContext) {
        FrameworkAdapterRegistry.register(this)
        ctx.log("Reasonix 通讯连接器已注册 — connector-reasonix.config 配置凭据后 framework.add/connect 对接")
    }

    override suspend fun onUninstall() {
        disconnect()
        FrameworkAdapterRegistry.unregister(frameworkName)
    }

    override suspend fun onUpgrade(newVersion: String) {}

    private fun cliOf(cfg: ConnectorConfigStore.ConnectorConfig): String =
        cfg.cliPath?.takeIf { it.isNotBlank() } ?: "reasonix"

    companion object {
        const val PLUGIN_ID = "connector-reasonix-plugin"
    }
}
