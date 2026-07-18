// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.security

/**
 * Core security policy that governs what Agent CLI commands are allowed.
 * Integrates with IntegrityGuard for path-level protection.
 */
class SecurityPolicy(
    private val integrityGuard: IntegrityGuard = IntegrityGuard()
) {
    /** Dangerous shell patterns — always blocked. (Inspired by QwenPaw ToolGuard) */
    private val restrictedPatterns = listOf(
        // File destruction
        Regex("rm\\s+(-rf?\\s+)?/"),
        Regex("mkfs\\."),
        Regex("dd\\s+if=.*of=/dev"),
        // Fork bombs & DoS
        Regex(":\\s*\\(\\s*\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:"),
        Regex("kill\\s+-9\\s+(-1|1)\\b"),
        // Pipe to shell
        Regex("\\b(curl|wget)\\b\\s+.*\\|.*\\b(bash|sh|zsh)\\b"),
        // Reverse shell
        Regex("/dev/(tcp|udp)/"),
        Regex("\\bnc\\s+.*-e\\s*\\S+"),
        // System tampering
        Regex("\\b(reboot|shutdown|halt|poweroff)\\b"),
        Regex("/etc/(passwd|shadow|sudoers|authorized_keys)"),
        // Permission escalation
        Regex("chmod\\s+(777|a\\+rwx)\\s+/"),
        Regex("sudo\\s+(systemctl|service|usermod|mount|umount|iptables|ufw)\\b"),
        // Obfuscation
        Regex("base64\\s+(-d|--decode)\\s*\\|\\s*(bash|sh|zsh)"),
        Regex("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]"),            // Control chars
        Regex("[\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]"), // Unicode whitespace
    )

    /** Commands always blocked regardless of context. */
    private val blockList = mutableListOf("proc.exec", "proc.system")

    /**
     * Check whether [command] is allowed, including integrity guard checks.
     * @param command Full command string (e.g. "fs.cat /path/to/file")
     * @return true if the command is permitted.
     */
    fun isAllowed(command: String): Boolean {
        val cmdName = command.split(" ").firstOrNull() ?: return false

        // Block list check
        if (blockList.any { cmdName.startsWith(it) }) return false

        // Dangerous pattern check
        for (pattern in restrictedPatterns) {
            if (pattern.containsMatchIn(command)) return false
        }

        return true
    }

    /**
     * Validate a command with its parsed arguments against integrity guard.
     * Returns an error message if the command would violate core integrity, or null if allowed.
     */
    fun validateIntegrity(commandName: String, args: List<String>): String? {
        return integrityGuard.validateCommand(commandName, args)
    }

    /** Block list change log for audit trail. */
    private val blockListAudit = mutableListOf<Pair<Long, String>>()

    /** Add a command to the block list at runtime. Logs the change for audit. */
    fun blockCommand(command: String, reason: String = "") {
        if (command !in blockList) {
            blockList.add(command)
            blockListAudit.add(System.currentTimeMillis() to "BLOCKED: $command (reason: ${reason.ifEmpty { "unspecified" }})")
        }
    }

    /** Get current block list (for auditing). */
    fun getBlockList(): List<String> = blockList.toList()

    /** Get the block list change audit trail. */
    fun getBlockListAudit(): List<String> = blockListAudit.map { "${it.first}: ${it.second}" }
}
