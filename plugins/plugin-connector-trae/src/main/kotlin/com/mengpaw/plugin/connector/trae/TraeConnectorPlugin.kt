// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.connector.trae

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
 * TREA IDE 通讯连接器 (外部分发, 不内置) — SSH 执行 trae-cli (python CLI) 对接 bytedance/trae-agent。
 *
 * 上游: bytedance/trae-agent (MIT 许可) — 通用软件工程 agent; 命令名是 `trae-cli` (非 trae-agent)。
 * 通道: SSH (Windows 11 自带 OpenSSH Server)。
 *
 * 工具映射 (framework.call <peer> <tool>):
 *   run      {task, provider?, model?, workingDir?} → `trae-cli run "<task>" [--provider X] [--model Y] [--working-dir Z]`
 *   show-config                                      → `trae-cli show-config`
 *
 * 配置: connector-trae.config (凭据) / connector-trae.info (状态)。
 */
class TraeConnectorPlugin : Plugin, FrameworkAdapter {

    override val metadata = PluginMetadata(
        id = "connector-trae-plugin",
        name = "TREA IDE 通讯",
        version = "0.1.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "TREA IDE 通讯连接器 — SSH 执行 trae-cli, 经 framework.connect/call 委派任务到 PC",
        minCoreVersion = "0.20.0",
        commands = listOf(
            "connector-trae.config",
            "connector-trae.info"
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

    override val frameworkName: String = "trea-ide"

    @Volatile private var transport: SshTransport? = null
    @Volatile private var connectedHost: String = ""

    override suspend fun connect(target: FrameworkTarget): Result<Unit> = withContext(Dispatchers.IO) {
        val cfg = ConnectorConfigStore.read(PLUGIN_ID)
        if (cfg.user.isBlank()) {
            return@withContext Result.failure(IllegalStateException(
                "未配置 SSH 凭据 — 先执行 connector-trae.config --user <用户名> [--password <密码> | --key-path <PEM路径>]"
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
        val probe = t.exec("${cliOf(cfg)} --help", 30_000)
        if (probe.isFailure) {
            t.disconnect()
            return@withContext Result.failure(IllegalStateException(
                "SSH 已连通但 trae-cli 不可用: ${probe.exceptionOrNull()?.message} — 检查 PC 端 trae-agent 安装或 --cli-path"
            ))
        }
        this@TraeConnectorPlugin.transport = t
        this@TraeConnectorPlugin.connectedHost = "${target.address}:$port"
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
                val cmd = buildString {
                    append("\"$cli\" run \"").append(SshTransport.shellEscape(task)).append("\"")
                    args["provider"]?.takeIf { it.isNotBlank() }?.let { append(" --provider ").append(SshTransport.shellEscape(it)) }
                    args["model"]?.takeIf { it.isNotBlank() }?.let { append(" --model ").append(SshTransport.shellEscape(it)) }
                    args["workingDir"]?.takeIf { it.isNotBlank() }?.let { append(" --working-dir ").append(SshTransport.shellEscape(it)) }
                }
                t.exec(cmd, 300_000).map { it.stdout.trim() } // 长任务最多 5 分钟
            }
            "show-config" -> t.exec("\"$cli\" show-config", 30_000).map { it.stdout.trim() }
            else -> Result.failure(IllegalStateException("未知工具: $tool (可用: run / show-config)"))
        }
    }

    override fun isOnline(): Boolean = transport?.isConnected() == true

    // ── Plugin lifecycle ────────────────────────────────────────────────

    override suspend fun onInstall(ctx: PluginContext) {
        FrameworkAdapterRegistry.register(this)
        ctx.log("TREA IDE 通讯连接器已注册 — connector-trae.config 配置凭据后 framework.add/connect 对接")
    }

    override suspend fun onUninstall() {
        disconnect()
        FrameworkAdapterRegistry.unregister(frameworkName)
    }

    override suspend fun onUpgrade(newVersion: String) {}

    private fun cliOf(cfg: ConnectorConfigStore.ConnectorConfig): String =
        cfg.cliPath?.takeIf { it.isNotBlank() } ?: "trae-cli"

    companion object {
        const val PLUGIN_ID = "connector-trae-plugin"
    }
}
