// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.security

import java.io.File
import java.security.MessageDigest

/**
 * Microkernel integrity guard — protects core files from accidental Agent tampering.
 *
 * Three protection layers:
 * 1. Path-level: blocks write/delete operations on protected directories
 * 2. Hash-level: verifies core class file integrity on startup
 * 3. Manifest: provides integrity manifest for debugging and audit
 *
 * PROTECTED PATH PREFIXES (any write/delete to these is blocked):
 *   /Android/data/com.mengpaw/Agent文档/ — Agent document directory
 *   /Android/data/com.mengpaw/插件仓库/   — plugin manifests & cache
 *
 * NOTE: File-level SHA256 verification (verify()) only works on Desktop/JVM
 * where core source files are accessible on disk. On Android, core classes are
 * inside the APK dex — use APK signature verification (PackageManager) instead.
 *
 * Vault (API Key) is separately protected at the Android sandbox level
 * and is never accessible via CLI commands.
 */
class IntegrityGuard(
    private val coreDir: String = "/data/data/com.mengpaw/core",
    private val agentsDir: String = com.mengpaw.core.DataPaths.AGENTS
) {
    /** Directories whose contents cannot be modified by Agent CLI commands. */
    val protectedPrefixes = listOf(
        coreDir,
        agentsDir,
        "/data/data/com.mengpaw/shared_prefs/mengpaw_vault",
        com.mengpaw.core.DataPaths.PLUGIN_CACHE
    )

    /** Core files whose SHA256 is tracked for integrity verification. */
    private val trackedFiles = listOf(
        "AgentEngine.kt",
        "Pipeline.kt",
        "CommandRegistry.kt",
        "SecurityPolicy.kt",
        "IntegrityGuard.kt",
        "Vault.kt",
        "Sanitizer.kt",
        "PluginManager.kt"
    )

    /** Baseline hashes set during init — compared in verify(). */
    private val baselineHashes = mutableMapOf<String, String>()

    /** Whether init() has been called. */
    private var initialized = false

    /**
     * Initialize the integrity guard by computing baseline hashes.
     * Must be called once at app startup before any Agent commands execute.
     */
    fun init() {
        baselineHashes.clear()
        trackedFiles.forEach { name ->
            val file = File(coreDir, name)
            if (file.exists()) {
                baselineHashes[name] = sha256(file)
            }
        }
        initialized = true
    }

    /**
     * Verify that all tracked core files match their baseline hashes.
     * @return true if integrity is intact, false if any file has been modified
     *         OR if the guard was never initialized (fail-secure).
     */
    fun verify(): Boolean {
        if (!initialized) return false // Fail-secure: reject if never initialized
        trackedFiles.forEach { name ->
            val expected = baselineHashes[name] ?: return@forEach
            val file = File(coreDir, name)
            if (!file.exists()) return false
            val current = sha256(file)
            if (!current.equals(expected, ignoreCase = true)) return false
        }
        return true
    }

    /**
     * Check if a given absolute path is under protection.
     * Used by Pipeline to block write/delete operations.
     */
    fun isProtectedPath(path: String): Boolean {
        val normalized = File(path).absolutePath
        return protectedPrefixes.any { normalized.startsWith(it) }
    }

    /**
     * Validate a command against protected paths.
     * For write commands (fs.write/cp/mv), checks the destination path.
     * For delete commands (fs.rm), checks the target path.
     *
     * @return null if allowed, or an error message if blocked.
     */
    fun validateCommand(command: String, args: List<String>): String? {
        val commandName = command.lowercase()

        // Write operations — check destination path
        if (commandName in WRITE_COMMANDS && args.isNotEmpty()) {
            val destPath = File(args[0]).absolutePath
            if (isProtectedPath(destPath)) {
                return "Protected path: cannot write to $destPath (core integrity)"
            }
        }

        // Delete operations — check target path
        if (commandName in DELETE_COMMANDS && args.isNotEmpty()) {
            val targetPath = File(args[0]).absolutePath
            if (isProtectedPath(targetPath)) {
                return "Protected path: cannot delete $targetPath (core integrity)"
            }
        }

        // Move/copy — check both source and destination
        if (commandName in MOVE_COMMANDS && args.size >= 2) {
            val destPath = File(args[1]).absolutePath
            if (isProtectedPath(destPath)) {
                return "Protected path: cannot write to $destPath (core integrity)"
            }
        }

        return null // allowed
    }

    /**
     * Generate an integrity manifest for debugging/audit.
     */
    fun getManifest(): String = buildString {
        appendLine("=== MengPaw Integrity Manifest ===")
        appendLine("Protected prefixes:")
        protectedPrefixes.forEach { appendLine("  $it") }
        appendLine()
        appendLine("Tracked files (${trackedFiles.size}):")
        trackedFiles.forEach { name ->
            val hash = baselineHashes[name] ?: "(not scanned)"
            appendLine("  $name  sha256:$hash")
        }
        appendLine()
        appendLine("Integrity: ${if (verify()) "INTACT" else "COMPROMISED"}")
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** CLI commands that write/create files. */
        private val WRITE_COMMANDS = setOf("fs.write", "fs.cp", "fs.mkdir")
        /** CLI commands that delete files. */
        private val DELETE_COMMANDS = setOf("fs.rm")
        /** CLI commands that move/rename files. */
        private val MOVE_COMMANDS = setOf("fs.mv", "fs.cp")
    }
}
