// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.connector.common

import com.mengpaw.kernel.DataPaths
import org.json.JSONObject
import java.io.File

/**
 * 连接器凭据配置 (原子 JSON 读写, tmp+rename 铁律)。
 *
 * 存储路径: {CONFIG}/{pluginId}-connector.json (如 connector-claude-code-plugin-connector.json)。
 * ⚠️ 凭据 (密码/PEM 密钥) 为本地明文 — 个人局域网场景可接受; 勿用于公网环境。
 */
object ConnectorConfigStore {

    /** 连接器配置 — 所有字段可选, 空值 = 未配置。 */
    data class ConnectorConfig(
        val user: String = "",
        val password: String? = null,
        val keyPath: String? = null,
        val keyPassphrase: String? = null,
        /** 通道: ssh (默认, SSH 执行 CLI) | rest (HTTP 直连, 仅 qwenpaw 支持)。 */
        val channel: String = "ssh",
        /** REST 通道的 Agent ID (qwenpaw)。 */
        val agentId: String = "default",
        /** REST 通道认证 token (qwenpaw app 开启认证时必填, Authorization: Bearer)。 */
        val token: String? = null,
        /** CLI 绝对路径 (Windows: 引号内路径, 如 "C:\tools\claude.cmd")。空 = PATH 查找。 */
        val cliPath: String? = null,
        /** SSH 端口, 默认 22。 */
        val sshPort: Int = 22
    )

    private fun file(pluginId: String): File = File(DataPaths.CONFIG, "$pluginId-connector.json")

    fun read(pluginId: String): ConnectorConfig = try {
        val f = file(pluginId)
        if (!f.exists() || f.length() == 0L) ConnectorConfig()
        else {
            val j = JSONObject(f.readText())
            ConnectorConfig(
                user = j.optString("user", ""),
                password = if (j.has("password") && !j.isNull("password")) j.optString("password") else null,
                keyPath = if (j.has("keyPath") && !j.isNull("keyPath")) j.optString("keyPath") else null,
                keyPassphrase = if (j.has("keyPassphrase") && !j.isNull("keyPassphrase")) j.optString("keyPassphrase") else null,
                channel = j.optString("channel", "ssh"),
                agentId = j.optString("agentId", "default"),
                token = if (j.has("token") && !j.isNull("token")) j.optString("token") else null,
                cliPath = if (j.has("cliPath") && !j.isNull("cliPath")) j.optString("cliPath") else null,
                sshPort = j.optInt("sshPort", 22)
            )
        }
    } catch (_: Exception) { ConnectorConfig() } // 损坏文件回退空配置

    /** 原子写入 (tmp + rename, Windows rename 失败时 delete+retry)。 */
    fun write(pluginId: String, cfg: ConnectorConfig) {
        try {
            val f = file(pluginId)
            f.parentFile?.mkdirs()
            val j = JSONObject()
                .put("user", cfg.user)
                .apply { if (cfg.password != null) put("password", cfg.password) }
                .apply { if (cfg.keyPath != null) put("keyPath", cfg.keyPath) }
                .apply { if (cfg.keyPassphrase != null) put("keyPassphrase", cfg.keyPassphrase) }
                .put("channel", cfg.channel)
                .put("agentId", cfg.agentId)
                .apply { if (cfg.token != null) put("token", cfg.token) }
                .apply { if (cfg.cliPath != null) put("cliPath", cfg.cliPath) }
                .put("sshPort", cfg.sshPort)
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(j.toString(2))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
            if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    /** 脱敏配置摘要 (密码/口令/token 显示 ****, 无 !! 断言)。 */
    fun describe(pluginId: String, cfg: ConnectorConfig): String = buildString {
        val password = cfg.password
        val keyPassphrase = cfg.keyPassphrase
        val token = cfg.token
        appendLine("SSH 主机配置: ${cfg.user.ifBlank { "未配置" }}@:${cfg.sshPort}")
        appendLine("认证: ${when {
            !password.isNullOrBlank() -> "密码 (${"*".repeat(password.length)})"
            !cfg.keyPath.isNullOrBlank() -> "PEM 密钥: ${cfg.keyPath}${if (!keyPassphrase.isNullOrBlank()) " (口令 ${"*".repeat(keyPassphrase.length)})" else ""}"
            else -> "未配置 (需 --password 或 --key-path)"
        }}")
        appendLine("通道: ${cfg.channel}${if (cfg.channel == "rest") " (agent-id: ${cfg.agentId})" else ""}")
        if (!token.isNullOrBlank()) appendLine("REST Token: ${"*".repeat(token.length)}")
        appendLine("CLI 路径: ${cfg.cliPath ?: "PATH 查找"}")
        appendLine("⚠️ 凭据明文存于本机 配置/$pluginId-connector.json — 仅限个人局域网使用")
    }
}
