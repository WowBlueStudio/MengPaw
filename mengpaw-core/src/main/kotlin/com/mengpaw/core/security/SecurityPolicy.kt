package com.mengpaw.core.security

/**
 * Core security policy that governs what Agent CLI commands are allowed.
 */
class SecurityPolicy {
    private val restrictedPatterns = listOf(
        Regex("rm\\s+(-rf?\\s+)?/"),                 // rm -rf /
        Regex("mkfs\\."),                              // Format commands
        Regex("dd\\s+if=.*of=/dev"),                   // Raw device write
        Regex("chmod\\s+777\\s+/"),                    // Permission escalation
    )

    private val blockList = listOf(
        "proc.exec"
    )

    fun isAllowed(command: String): Boolean {
        // Check allow/block lists
        if (blockList.any { command.startsWith(it) }) return false

        // Check dangerous patterns
        for (pattern in restrictedPatterns) {
            if (pattern.containsMatchIn(command)) return false
        }

        return true
    }
}
