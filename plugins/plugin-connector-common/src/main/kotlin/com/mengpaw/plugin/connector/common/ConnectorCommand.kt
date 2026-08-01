// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.connector.common

import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.plugin.CommandHandler

/**
 * 连接器配置/状态命令共享实现 — 四个连接器插件复用。
 *
 * 命名空间由插件 id 派生 (namespaceFor): connector-claude-code-plugin → connector-claude-code,
 * 命令即 <ns>.config / <ns>.info。
 */
object ConnectorCommand {

    /**
     * config 命令 — 无参数查看脱敏配置; 带 --key value 参数则更新并原子保存。
     * Usage: <ns>.config [--user U] [--password P] [--key-path F] [--key-passphrase P]
     *                    [--ssh-port N] [--channel ssh|rest] [--agent-id ID] [--cli-path F]
     */
    fun configHandler(pluginId: String): CommandHandler = { args, _ ->
        val current = ConnectorConfigStore.read(pluginId)
        if (args.isEmpty()) {
            ExecutionResult.ok(
                "当前连接配置:\n" + ConnectorConfigStore.describe(pluginId, current) +
                "\n用法: ${nsOf(pluginId)}.config [--user U] [--password P] [--key-path F] [--key-passphrase P] [--ssh-port N] [--channel ssh|rest] [--agent-id ID] [--cli-path F]"
            )
        } else {
            var updated = current
            var i = 0
            while (i < args.size) {
                val key = args[i]
                val value = args.getOrNull(i + 1) ?: ""
                updated = when (key) {
                    "--user" -> updated.copy(user = value)
                    "--password" -> updated.copy(password = value.ifBlank { null })
                    "--key-path" -> updated.copy(keyPath = value.ifBlank { null })
                    "--key-passphrase" -> updated.copy(keyPassphrase = value.ifBlank { null })
                    "--ssh-port" -> updated.copy(sshPort = value.toIntOrNull() ?: 22)
                    "--channel" -> updated.copy(channel = if (value in listOf("ssh", "rest")) value else "ssh")
                    "--agent-id" -> updated.copy(agentId = value)
                    "--cli-path" -> updated.copy(cliPath = value.ifBlank { null })
                    else -> updated
                }
                i += 2
            }
            ConnectorConfigStore.write(pluginId, updated)
            ExecutionResult.ok("已保存连接配置:\n" + ConnectorConfigStore.describe(pluginId, updated))
        }
    }

    /** info 命令 — 连接器状态 + 配置摘要。 */
    fun infoHandler(pluginId: String, statusText: () -> String): CommandHandler = { _, _ ->
        ExecutionResult.ok(
            "连接器: ${pluginId.removeSuffix("-plugin")}\n" +
            "连接状态: ${statusText()}\n" +
            ConnectorConfigStore.describe(pluginId, ConnectorConfigStore.read(pluginId))
        )
    }

    /** 插件 id → 命令命名空间 (与 PluginManager.namespaceFor 同规则)。 */
    fun nsOf(pluginId: String): String = pluginId.removeSuffix("-plugin").removeSuffix("-ext")
}
