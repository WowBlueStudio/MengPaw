// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.security

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.security.IntegrityProvider
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
    private val agentsDir: String = DataPaths.AGENTS
) : IntegrityProvider {
    /** Directories whose contents cannot be modified by Agent CLI commands. */
    val protectedPrefixes = listOf(
        coreDir,
        agentsDir,
        "/data/data/com.mengpaw/shared_prefs/mengpaw_vault",
        DataPaths.PLUGIN_CACHE
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

    /** Cached Android context for APK signature verification. */
    private var appContext: android.content.Context? = null

    /**
     * Initialize the integrity guard.
     * - On JVM/Desktop: computes baseline SHA-256 hashes of core .kt source files.
     * - On Android: stores context for APK signature verification (source files don't
     *   exist as .kt on disk; they are compiled into the DEX).
     */
    fun init(context: android.content.Context? = null) {
        baselineHashes.clear()
        appContext = context

        // Desktop path: hash .kt source files
        trackedFiles.forEach { name ->
            val file = File(coreDir, name)
            if (file.exists()) {
                baselineHashes[name] = sha256(file)
            }
        }

        // Android path: verify APK signature via PackageManager
        if (context != null && baselineHashes.isEmpty()) {
            try {
                val pm = context.packageManager
                val packageName = context.packageName
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val signingInfo = pm.getPackageInfo(packageName,
                        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                    if (signingInfo != null && signingInfo.hasMultipleSigners()) {
                        // Multiple signers may indicate tampering
                        initialized = true // mark initialized but fail verify()
                        return
                    }
                    val certs = signingInfo?.apkContentsSigners ?: signingInfo?.signingCertificateHistory
                    if (certs != null && certs.isNotEmpty()) {
                        // Store the first signing certificate's SHA-256 as baseline
                        baselineHashes["android:apk-signature"] = sha256(certs[0].toByteArray())
                        KernelLog.i("IntegrityGuard",
                            "APK signature baseline established: ${baselineHashes["android:apk-signature"]?.take(16)}...")
                    }
                } else {
                    // API 26-27: use deprecated GET_SIGNATURES
                    @Suppress("DEPRECATION")
                    val pkgInfo = pm.getPackageInfo(packageName,
                        android.content.pm.PackageManager.GET_SIGNATURES)
                    val sigs = pkgInfo.signatures
                    if (sigs != null && sigs.isNotEmpty()) {
                        baselineHashes["android:apk-signature"] = sha256(sigs[0].toByteArray())
                        KernelLog.i("IntegrityGuard",
                            "APK signature baseline (legacy): ${baselineHashes["android:apk-signature"]?.take(16)}...")
                    }
                }
            } catch (e: Exception) {
                KernelLog.w("IntegrityGuard", "Cannot verify APK signature: ${e.message}")
            }
        }
        initialized = true
    }

    /** Compute SHA-256 of a byte array (for APK certificate). */
    private fun sha256(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    /**
     * Verify that all tracked core files match their baseline hashes.
     * - On Desktop: checks .kt source file hashes.
     * - On Android: verifies APK signing certificate matches baseline.
     * @return true if integrity is intact, false if any file has been modified
     *         OR if the guard was never initialized (fail-secure).
     */
    fun verify(): Boolean {
        if (!initialized) return false // Fail-secure: reject if never initialized

        // Android path: re-verify APK signature
        val ctx = appContext
        if (ctx != null && baselineHashes.containsKey("android:apk-signature")) {
            return try {
                val pm = ctx.packageManager
                val currentHash = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val signingInfo = pm.getPackageInfo(ctx.packageName,
                        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                    val certs = signingInfo?.apkContentsSigners ?: signingInfo?.signingCertificateHistory
                    if (certs == null || certs.isEmpty()) return false
                    sha256(certs[0].toByteArray())
                } else {
                    @Suppress("DEPRECATION")
                    val pkgInfo = pm.getPackageInfo(ctx.packageName,
                        android.content.pm.PackageManager.GET_SIGNATURES)
                    val sigs = pkgInfo.signatures
                    if (sigs == null || sigs.isEmpty()) return false
                    sha256(sigs[0].toByteArray())
                }
                val expectedHash = baselineHashes["android:apk-signature"] ?: return false
                currentHash.equals(expectedHash, ignoreCase = true)
            } catch (e: Exception) { false }
        }

        // Desktop path: check file hashes
        if (baselineHashes.isEmpty()) return initialized // no baselines = nothing to verify
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
    override fun validateCommand(command: String, args: List<String>): String? {
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
