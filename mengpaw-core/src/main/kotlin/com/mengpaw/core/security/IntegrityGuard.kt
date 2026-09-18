// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.security

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.security.IntegrityProvider
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Microkernel integrity guard — protects core files from accidental Agent tampering.
 *
 * Three protection layers:
 * 1. Path-level: blocks write/delete operations on protected directories
 * 2. Hash-level: verifies core class file integrity on startup
 * 3. Manifest: provides integrity manifest for debugging and audit
 *
 * PROTECTED PATH PREFIXES (any write/delete to these is blocked):
 *   核心目录 (Desktop .kt 源)        — coreDir
 *   Vault (API Key 加密存储)         — shared_prefs/mengpaw_vault*
 *   插件仓库/                        — DataPaths.PLUGIN_CACHE
 *   配置/                            — DataPaths.CONFIG
 *
 * 注: Agent 工作区 (Agent文档/) 与输出目录是**设计上的可写区** (v0.36.x 命令去重后
 * Agent 直接用 Linux 命令读写工作区), 不在保护列表内 —— 工作区边界由 Linux 通道
 * 的默认 cwd 与写入路径规则约束, 而非路径级拦截。
 *
 * NOTE: File-level SHA256 verification (verify()) only works on Desktop/JVM
 * where core source files are accessible on disk. On Android, core classes are
 * inside the APK dex — use APK signature verification (PackageManager) instead.
 */
class IntegrityGuard(
    private val coreDir: String = "/data/data/com.mengpaw/core",
    /** 额外保护目录 (可选) — 生产不传 (工作区可写); 测试/特殊宿主可显式传入。 */
    private val extraProtectedDir: String? = null
) : IntegrityProvider, com.mengpaw.kernel.security.ProtectedPathAware {

    /** Vault 加密存储的真实前缀 (init 前用默认包名兜底)。 */
    @Volatile
    private var vaultPrefix: String = "/data/data/com.mengpaw/shared_prefs/mengpaw_vault"

    /** Directories whose contents cannot be modified by Agent CLI commands. */
    val protectedPrefixes: List<String>
        get() = buildList {
            add(coreDir)
            add(vaultPrefix)
            add(DataPaths.PLUGIN_CACHE)
            add(DataPaths.CONFIG)
            extraProtectedDir?.let { add(it) }
        }.filter { it.isNotBlank() }

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
     * P0 fix (fail-secure): init 时检测到 APK 多重签名 → 视为篡改。
     * 此前该分支只置 initialized=true 就 return — baselineHashes 为空时
     * verify() 的 "no baselines = nothing to verify" 路径恒返回 true,
     * 多重签名检测形同虚设。
     */
    @Volatile
    private var multiSignerTampered = false

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
        resolveVaultPrefix(context)

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
                        // Multiple signers may indicate tampering — fail-secure (P0 fix)
                        multiSignerTampered = true
                        initialized = true
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
        // Locale.ROOT: 默认 Locale 下 %02x 输出畸形 (阿拉伯语设备 — P2 修复)
        return digest.digest(data).joinToString("") { String.format(Locale.ROOT, "%02x", it) }
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
        if (multiSignerTampered) return false // P0 fix: 多重签名视为篡改, 恒拒绝

        // Android path: re-verify APK signature (并复查多重签名 — init 后安装态不应变化,
        // 但运行时复查成本低, 防 init 时被绕过)
        val ctx = appContext
        if (ctx != null) {
            var signingInfo: android.content.pm.SigningInfo? = null
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                signingInfo = try {
                    ctx.packageManager.getPackageInfo(ctx.packageName,
                        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                } catch (e: Exception) { return false }
                if (signingInfo != null && signingInfo.hasMultipleSigners()) return false
            }
            if (baselineHashes.containsKey("android:apk-signature")) {
                return try {
                    val pm = ctx.packageManager
                    val currentHash = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
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
        }

        // Desktop path: check file hashes
        // P0 fix (fail-secure): 无 baseline = 无法验证 = 拒绝 (此前返回 initialized 恒 true,
        // 签名异常/初始化失败场景下 verify() 静默放行)
        if (baselineHashes.isEmpty()) return false
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
     * Check if a given path is under protection.
     *
     * 判定纪律 (v0.47.1 加固):
     * - **规范化必须用 canonicalPath**: `File.absolutePath` 不解析 `..`
     *   (实测 `.../files/../../core/Vault.kt` 原样保留 `..`), 于是"绝对路径 + `..` 穿越"
     *   永远匹配不上保护前缀 → 保护可被 `cat /x/../../shared_prefs/...` 绕过。
     * - 前缀同样规范化 (DataPaths 里的相对路径前缀不经解析永远匹配不上绝对路径入参)。
     * - 命中要求边界 (前缀后是分隔符或路径结尾), 防相似前缀误伤。
     * - 相对路径由 [validateCommand] 先按 workDir 解析成绝对路径再进来。
     */
    fun isProtectedPath(path: String): Boolean = isProtectedPath(path, workDir = null)

    /** 带基准目录的判定 — 相对路径按 [workDir] 解析 (Linux 通道的 cwd 语义)。 */
    fun isProtectedPath(path: String, workDir: String?): Boolean {
        val normalized = canonicalize(path, workDir) ?: return false
        return protectedPrefixes.any { prefix ->
            val absPrefix = canonicalize(prefix, null) ?: return@any false
            normalized == absPrefix || normalized.startsWith("$absPrefix${File.separator}")
        }
    }

    /**
     * 规范化路径: 相对路径按 [workDir] 拼接 (缺失基准时保守返回 null — 不判定比误判安全)。
     * `..` 由 canonicalPath 解析; 目录不存在时 canonicalPath 仍可解析 (不要求存在),
     * 但极少数平台/路径会抛异常 → 退回 absolutePath (仅保证绝对化, 不解析 `..`)。
     */
    private fun canonicalize(path: String, workDir: String?): String? = try {
        val raw = if (looksLikeAbsolutePath(path) || workDir.isNullOrBlank()) path
        else File(workDir, path).path
        val file = File(raw)
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
    } catch (_: Exception) {
        null
    }

    /**
     * Validate a command against protected paths.
     *
     * 判定按**路径参数**通用化 (不依赖命令名白名单): 命令名只用于定位参数起点。
     * 相对路径参数按 [workDir] 解析后再判定 —— Linux 通道的 cwd 就是 ctx.workDir,
     * 且 shell 里 `cd X && cat rel` 是单行合法形态, 若不解析则 `cd <受保护目录>` +
     * 相对路径即可绕过 (v0.47.1 修复)。
     *
     * @param workDir 命令执行时的工作目录 (Linux 通道传 ctx.workDir); null = 相对路径不判定
     * @return null if allowed, or an error message if blocked.
     */
    override fun validateCommand(command: String, args: List<String>): String? =
        validateCommand(command, args, workDir = null)

    /** 带工作目录的完整判定 (实现 [com.mengpaw.kernel.security.ProtectedPathAware])。 */
    override fun validateCommand(command: String, args: List<String>, workDir: String?): String? {
        val candidates = extractPaths(command, args, workDir)
        if (candidates.isEmpty()) return null
        candidates.firstOrNull { isProtectedPath(it, workDir) }?.let { hit ->
            return "受保护路径: $hit 属于核心区/Vault(API Key)/插件仓库/配置目录, 不允许经命令读写"
        }
        return null
    }

    /** 参数中的路径 token (绝对路径 + 可按 workDir 解析的相对路径)。internal 供测试可见性。
     *  args 非空时只用 args; 否则按空格切整行并去掉命令名 (Linux 通道传整行形态)。 */
    internal fun extractPaths(command: String, args: List<String>, workDir: String?): List<String> {
        val tokens = if (args.isNotEmpty()) args
        else command.trim().split(Regex("\\s+")).drop(1)
        return tokens.mapNotNull { token ->
            when {
                looksLikeAbsolutePath(token) -> token
                // 相对路径: 仅在给了基准目录时参与判定 (无基准 = 无法解析, 不判定)
                !workDir.isNullOrBlank() && token.isNotBlank() && !token.startsWith("-") &&
                    !token.contains("://") && token != ">" && token != ">>" -> token
                else -> null
            }
        }
    }

    /** 保留旧名 (测试/调用方兼容) — 仅返回绝对路径 token。 */
    internal fun extractAbsolutePaths(command: String, args: List<String>): List<String> =
        extractPaths(command, args, workDir = null).filter { looksLikeAbsolutePath(it) }

    /** 绝对路径判定 — `/x` 形态或平台绝对路径。 */
    private fun looksLikeAbsolutePath(token: String): Boolean =
        token.startsWith("/") || token.startsWith("\\") || runCatching { File(token).isAbsolute }.getOrDefault(false)

    /** 把 Vault 前缀解析为真实 applicationId 下的 shared_prefs 路径。 */
    private fun resolveVaultPrefix(context: android.content.Context?) {
        vaultPrefix = try {
            val dataDir = context?.filesDir?.parentFile
            if (dataDir != null) {
                File(File(dataDir, "shared_prefs"), "mengpaw_vault").absolutePath
            } else {
                vaultPrefix
            }
        } catch (_: Exception) {
            vaultPrefix
        }
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
        // Locale.ROOT: 默认 Locale 下 %02x 输出畸形 (阿拉伯语设备 — P2 修复)
        return digest.digest(file.readBytes()).joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }

    companion object {
        /** Global singleton, initialized via [init] in MainActivity. */
        @Volatile
        var globalInstance: IntegrityGuard = IntegrityGuard()
            private set
    }
}
