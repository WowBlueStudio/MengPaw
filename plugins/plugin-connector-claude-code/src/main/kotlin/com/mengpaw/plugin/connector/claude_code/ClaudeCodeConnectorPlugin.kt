// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.connector.claude_code

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
 * Claude Code 通讯连接器 (外部分发, 不内置) — SSH 执行 claude CLI (headless) 对接 Anthropic Claude Code。
 *
 * 上游: Claude Code 为 Anthropic 闭源商业 CLI — 本插件仅协议互操作调用 (`claude -p`),
 *       不复制其代码; claude-agent-sdk-python (MIT) 仅作协议参考。jsch (MIT) 提供 SSH。
 * 通道: SSH (Windows 11 自带 OpenSSH Server, 零 PC 端安装)。
 *
 * 工具映射 (framework.call <peer> <tool>):
 *   run      {prompt, model?, maxTurns?} → `claude -p "<prompt>" [--model X] [--max-turns N]`
 *   version                            → `claude --version`
 *
 * 配置: connector-claude-code.config (凭据) / connector-claude-code.info (状态)。
 */
class ClaudeCodeConnectorPlugin : Plugin, FrameworkAdapter {

    override val metadata = PluginMetadata(
        id = "connector-claude-code-plugin",
        name = "Claude Code 通讯",
        version = "0.1.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "Claude Code 通讯连接器 — SSH 执行 claude CLI (headless), 经 framework.connect/call 委派任务到 PC",
        minCoreVersion = "0.20.0",
        commands = listOf(
            "connector-claude-code.config",
            "connector-claude-code.info"
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

    override val frameworkName: String = "claude-code"
    override val toolsDescription: String =
        "run {prompt,model?,maxTurns?} — 一次委派任务 (claude -p, 阻塞最长 5 分钟); version — CLI 版本"

    @Volatile private var transport: SshTransport? = null
    @Volatile private var connectedHost: String = ""

    override suspend fun connect(target: FrameworkTarget): Result<Unit> = withContext(Dispatchers.IO) {
        val cfg = ConnectorConfigStore.read(PLUGIN_ID)
        if (cfg.user.isBlank()) {
            return@withContext Result.failure(IllegalStateException(
                "未配置 SSH 凭据 — 先执行 connector-claude-code.config --user <用户名> [--password <密码> | --key-path <PEM路径>]"
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
        // 探测 claude CLI (验证通道 + 记录版本)
        val probe = t.exec("${cliOf(cfg)} --version", 30_000)
        if (probe.isFailure) {
            t.disconnect()
            return@withContext Result.failure(IllegalStateException(
                "SSH 已连通但 claude CLI 不可用: ${probe.exceptionOrNull()?.message} — 检查 PC 端 Claude Code 安装或 --cli-path"
            ))
        }
        this@ClaudeCodeConnectorPlugin.transport = t
        this@ClaudeCodeConnectorPlugin.connectedHost = "${target.address}:$port"
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
                val prompt = args["prompt"]
                    ?: return@withContext Result.failure(IllegalStateException("run 需要 prompt 参数"))
                val cmd = buildString {
                    append("\"$cli\" -p \"").append(SshTransport.shellEscape(prompt)).append("\"")
                    args["model"]?.takeIf { it.isNotBlank() }?.let { append(" --model ").append(SshTransport.shellEscape(it)) }
                    args["maxTurns"]?.takeIf { it.isNotBlank() }?.let { append(" --max-turns ").append(SshTransport.shellEscape(it)) }
                }
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
        ctx.log("Claude Code 通讯连接器已注册 — connector-claude-code.config 配置凭据后 framework.add/connect 对接")
    }

    override suspend fun onUninstall() {
        disconnect()
        FrameworkAdapterRegistry.unregister(frameworkName)
    }

    override suspend fun onUpgrade(newVersion: String) {}

    private fun cliOf(cfg: ConnectorConfigStore.ConnectorConfig): String =
        cfg.cliPath?.takeIf { it.isNotBlank() } ?: "claude"

    companion object {
        const val PLUGIN_ID = "connector-claude-code-plugin"
    }
}
