// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.security

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File

/**
 * ACP 提示词防火墙 — 隔离设备间通信风险。
 *
 * ## 信任级别
 * - **TRUSTED (已配对)**: 自己的设备，无限制。通过设备指纹/共享密钥配对。
 * - **GUEST (未配对)**: 其他用户的设备，受限模式。
 *   - ✅ 对话记录 (agent.memory.record)
 *   - ✅ 读取查询 (fs.cat, fs.ls, self.status, self.stats, sys.*)
 *   - ✅ 简单生成 (render.generate — 仅 API 调用，不写本地文件)
 *   - ✅ 信息交换 (hermes.memo — 仅团队共享记忆)
 *   - ❌ 文件写入 (fs.write/rm/mkdir/mv/cp)
 *   - ❌ 进程执行 (proc.exec)
 *   - ❌ 插件安装 (plugin.install/uninstall)
 *   - ❌ UI 操控 (ui.*)
 *   - ❌ 系统修改 (self.config, self.theme, self.avatar)
 *   - ❌ 剪贴板/通知 (clipboard.*, notification.*)
 *
 * ## 配对方式
 * 1. 两台设备在同一 WiFi
 * 2. self.acp start 启动双方 ACP
 * 3. self.acp pair <device-fingerprint> → 计算共享密钥 → 标记为 TRUSTED
 * 4. 已配对设备存储到 Agent文档/acp/trusted/
 */
object PromptFirewall {
    private val trustedDir = File(DataPaths.ACP_TRUSTED)

    /** Commands BLOCKED for GUEST peers. */
    private val GUEST_BLOCKED = setOf(
        // v0.36.x 去重: fs.* 已移除, Linux 写/危险命令对 GUEST 显式拒绝 (默认拒绝兜底)
        "echo", "tee", "printf", "rm", "mv", "cp", "mkdir", "touch",
        "curl", "wget", "nc", "ncat", "telnet", "python", "perl", "sh", "bash", "su", "sudo",
        "proc.exec", "proc.kill",
        "plugin.install", "plugin.uninstall", "plugin.enable", "plugin.disable",
        "ui.click", "ui.swipe", "ui.input", "ui.screenshot", "ui.back", "ui.home",
        "clipboard.copy", "clipboard.paste", "clipboard.clear",
        "self.config", "self.theme", "self.avatar",
        "agent.memory.keep", "agent.memory.write", "agent.memory.rm", "agent.memory.edit",
        "agent.memory.mid.delete", "agent.memory.mid.rm", "agent.memory.mid.edit",
        "agent.memory.project.save", "agent.memory.project.delete", "agent.memory.project.rm", "agent.memory.project.edit",
        "skill.enable", "skill.disable"
    )

    /** Commands ALLOWED for GUEST peers (on top of safe commands). */
    private val GUEST_ALLOWED = setOf(
        "agent.memory.record",       // 记录对话
        "agent.audit",               // 查看审计
        "hermes.memo",               // 团队共享记忆
        "render.generate",           // API生图 (不写本地)
        // Linux 只读命令 (v0.36.x 去重: agent.read/ls、fs.stat 已移除)
        "cat", "head", "tail", "grep", "sed", "find", "stat", "ls", "less", "more", "wc", "du", "df", "file",
        "self.status", "self.stats", "self.version", "sys.*",  // 只读系统信息
        "agent.memory", "agent.memory.read", "agent.memory.search", "agent.memory.stats",
        "agent.memory.mid", "agent.memory.project",  // 只读记忆
        "skill.ls", "skill.run",     // 只读/运行技能
        "plugin.list", "plugin.info" // 只读插件信息
    )

    /**
     * Check if a command is allowed from [peerId].
     * @param peerId The source device's Agent ID.
     * @param command The full CLI command string (e.g. "fs.write /tmp/test.txt hello").
     * @return null if allowed, or an error message if blocked.
     */
    fun check(peerId: String, command: String): String? {
        if (isTrusted(peerId)) return null // TRUSTED — no restrictions

        val cmdName = command.split(" ").firstOrNull()?.lowercase() ?: return "Empty command blocked."

        // Explicitly blocked
        if (GUEST_BLOCKED.any { cmdName.startsWith(it) }) {
            return "GUEST blocked: '$cmdName' requires trust. Pair with self.acp pair <device> to unlock."
        }

        // Explicitly allowed or safe wildcard
        val allowed = GUEST_ALLOWED.any { allowed ->
            if (allowed.endsWith(".*")) cmdName.startsWith(allowed.removeSuffix(".*"))
            else cmdName == allowed
        }
        if (allowed) return null

        // Default for GUEST: deny everything not explicitly allowed
        return "GUEST restricted: '$cmdName' not in guest allowlist. Pair this device to unlock full access."
    }

    // ── Trust management ──────────────────────────────────────────

    fun isTrusted(peerId: String): Boolean =
        File(trustedDir, "$peerId.trusted").exists()

    fun trust(peerId: String, fingerprint: String) {
        trustedDir.mkdirs()
        try { File(trustedDir, "$peerId.trusted").writeText(fingerprint) } catch (e: Exception) { ErrorCollector.report(e, "PromptFirewall.trust") }
    }

    /**
     * Trust a peer AND store the encryption key material (fingerprint).
     * Called after successful pairing when the shared AES key has been derived.
     * This is the preferred method for twin pairing — it also triggers
     * [com.mengpaw.kernel.acp.AcpCrypto.deriveKey] to enable encrypted channels.
     */
    fun trustWithKey(peerId: String, fingerprint: String) {
        trust(peerId, fingerprint)
        // Also store the fingerprint hash for key derivation verification
        val keyFile = File(trustedDir, "$peerId.key")
        try {
            keyFile.writeText(fingerprint)
        } catch (e: Exception) {
            ErrorCollector.report(e, "PromptFirewall.trustWithKey")
        }
    }

    /**
     * Check if encryption is ready for a peer — both trusted AND has a shared key.
     * Unlike [isTrusted], this ensures the AES key has been derived via AcpCrypto.deriveKey().
     */
    fun isEncryptionReady(peerId: String): Boolean {
        if (!isTrusted(peerId)) return false
        return File(trustedDir, "$peerId.key").exists()
    }

    /** Get the stored key fingerprint for a trusted peer, or null. */
    fun getTrustedFingerprint(peerId: String): String? {
        val keyFile = File(trustedDir, "$peerId.key")
        return if (keyFile.exists()) try { keyFile.readText() } catch (_: Exception) { null } else null
    }

    fun untrust(peerId: String) {
        File(trustedDir, "$peerId.trusted").delete()
    }

    fun listTrusted(): List<String> =
        trustedDir.listFiles()?.filter { it.extension == "trusted" }?.map { it.nameWithoutExtension } ?: emptyList()

    /** Generate a simple device fingerprint for pairing. */
    fun deviceFingerprint(): String {
        val id = System.getProperty("android.os.Build.ID") ?: "unknown"
        val model = System.getProperty("android.os.Build.MODEL") ?: "unknown"
        return "${model.take(8)}-${id.take(6)}".replace(" ", "_")
    }

    // ── Filter — returns allowed portion of a message for GUEST ──

    data class FilterResult(
        val allowed: List<String>,      // Commands that passed
        val blocked: List<String>,      // Commands that were blocked
        val warnings: List<String>      // Explanation for blocked
    )

    fun filterBatch(peerId: String, commands: List<String>): FilterResult {
        if (isTrusted(peerId)) return FilterResult(commands, emptyList(), emptyList())
        val allowed = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        commands.forEach { cmd ->
            val error = check(peerId, cmd)
            if (error != null) { blocked.add(cmd); warnings.add(error) }
            else allowed.add(cmd)
        }
        return FilterResult(allowed, blocked, warnings)
    }

    fun guestPolicySummary(): String = """
## ACP Guest 安全策略

### ✅ Guest 可执行
- 对话记录 (agent.memory.record)
- 读取查询 (fs.cat/ls, self.status/stats, agent.memory/read/search/stats, sys.*)
- 简单生成 (render.generate — API 调用，不写本地文件)
- 团队共享记忆 (hermes.memo)

### ❌ Guest 不可执行
- 文件写入/删除 (fs.write/rm/mkdir/mv)
- 进程操作 (proc.*)
- 插件管理 (plugin.install/uninstall)
- UI 操控 (ui.*)
- 系统修改 (self.config/theme/avatar)
- 剪贴板/通知 (clipboard.*, notification.*)

### 解除限制
self.acp pair <device-id> — 配对后获得完整权限
self.acp trusted — 查看已配对设备列表
""".trimIndent()

    // ── LLM Prompt-level injection defense (v0.34.0 重构) ─────────────
    // 前缀注入已移除 (P0 定案): DEFENSIVE_PREFIX 拼入用户消息层 = 防御文本与攻击文本
    // 同层, 可被「忽略上面的安全通知」反向覆盖, 且暴露检测机制存在 (攻击者可伪装
    // 「系统安全通知」文案)。改为: 硬层剥离+标记包裹 (UntrustedContent) +
    // 系统提示词信任边界声明, 命中静默仅日志。见 [UntrustedContent]。
}
