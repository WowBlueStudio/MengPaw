// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.security

import com.mengpaw.kernel.security.IntegrityProvider
import com.mengpaw.kernel.security.NoOpIntegrityProvider

/**
 * Core security policy that governs what Agent CLI commands are allowed.
 */
class SecurityPolicy(
    private val integrityProvider: IntegrityProvider = NoOpIntegrityProvider
) {
    companion object {
        /** Global toggle for kernel integrity protection (controlled from Settings UI). */
        @Volatile var globalEnabled: Boolean = true
    }

    /** Per-instance toggle; checked together with [globalEnabled]. */
    var integrityEnabled: Boolean = true

    private val restrictedPatterns = listOf(
        Regex("rm\\s+(-rf?\\s+)?/"),
        Regex("mkfs\\."),
        Regex("dd\\s+if=.*of=/dev"),
        Regex(":\\s*\\(\\s*\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:"),
        Regex("kill\\s+-9\\s+(-1|1)\\b"),
        Regex("\\b(curl|wget)\\b\\s+.*\\|.*\\b(bash|sh|zsh)\\b"),
        Regex("/dev/(tcp|udp)/"),
        Regex("\\bnc\\s+.*-e\\s*\\S+"),
        Regex("\\b(reboot|shutdown|halt|poweroff)\\b"),
        Regex("/etc/(passwd|shadow|sudoers|authorized_keys)"),
        Regex("chmod\\s+(777|a\\+rwx)\\s+/"),
        Regex("sudo\\s+(systemctl|service|usermod|mount|umount|iptables|ufw)\\b"),
        Regex("base64\\s+(-d|--decode)\\s*\\|\\s*(bash|sh|zsh)"),
        Regex("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]"),
        Regex("[\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]"),
    )

    private val blockList = mutableListOf("proc.exec", "proc.system")

    fun isAllowed(command: String): Boolean {
        if (!globalEnabled || !integrityEnabled) return true
        val cmdName = command.split(" ").firstOrNull() ?: return false
        if (blockList.any { cmdName.startsWith(it) }) return false
        for (pattern in restrictedPatterns) {
            if (pattern.containsMatchIn(command)) return false
        }
        return true
    }

    fun validateIntegrity(commandName: String, args: List<String>): String? {
        return integrityProvider.validateCommand(commandName, args)
    }

    private val blockListAudit = mutableListOf<Pair<Long, String>>()

    fun blockCommand(command: String, reason: String = "") {
        if (command !in blockList) {
            blockList.add(command)
            blockListAudit.add(System.currentTimeMillis() to "BLOCKED: $command (reason: ${reason.ifEmpty { "unspecified" }})")
        }
    }

    fun getBlockList(): List<String> = blockList.toList()
    fun getBlockListAudit(): List<String> = blockListAudit.map { "${it.first}: ${it.second}" }
}
