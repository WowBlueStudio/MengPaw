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
) : IntegrityProvider {

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
     * 相对路径不参与判定 (工作区/输出目录内的相对路径都是合法可写区);
     * 仅绝对路径按前缀匹配, 且要求命中边界 (前缀后是 `/` 或路径结尾), 防相似前缀误伤。
     */
    fun isProtectedPath(path: String): Boolean {
        val normalized = try { File(path).absolutePath } catch (_: Exception) { return false }
        return protectedPrefixes.any { prefix ->
            // 前缀同样要规范化 — DataPaths 里的相对路径前缀 (配置/插件仓库) 未经
            // absolutePath 时永远匹配不上绝对路径入参 (原实现用裸字符串比较)。
            val absolutePrefix = try { File(prefix).absolutePath } catch (_: Exception) { prefix }
            normalized == absolutePrefix || normalized.startsWith("$absolutePrefix${File.separator}")
        }
    }

    /**
     * Validate a command against protected paths.
     *
     * 判定改为**按路径参数**通用化 (不再依赖命令名白名单): 命令名只用于定位参数起点
     * (首参不是绝对路径时视为命令名, 相对路径参数因此天然豁免)。这样两条通道共用同一判定 —
     * 注册命令经 [com.mengpaw.kernel.cli.Pipeline], Linux 命令经
     * [com.mengpaw.kernel.cli.LinuxCommandExecutor] → [com.mengpaw.kernel.security.SecurityGate]。
     * 历史上只认写命令白名单 + 仅经 Pipeline, 导致 Linux 通道的 `cp/mv/tee` 写核心目录无拦截。
     *
     * @return null if allowed, or an error message if blocked.
     */
    override fun validateCommand(command: String, args: List<String>): String? {
        val absolutePaths = extractAbsolutePaths(command, args)
        if (absolutePaths.isEmpty()) return null
        absolutePaths.firstOrNull { isProtectedPath(it) }?.let { hit ->
            return "受保护路径: $hit 属于核心区/Vault(API Key)/插件仓库/配置目录, 不允许经命令读写"
        }
        return null
    }

    /** 参数中的绝对路径 token。internal 供测试可见性。
     *  args 非空时只用 args; 否则按空格切整行并去掉命令名 (Linux 通道传整行形态)。
     *  绝对路径判定同时接受 `/` 开头 (Android/Linux) 与平台绝对路径 (Windows 测试环境)。 */
    internal fun extractAbsolutePaths(command: String, args: List<String>): List<String> {
        val tokens = if (args.isNotEmpty()) args
        else command.trim().split(Regex("\\s+")).drop(1)
        return tokens.filter { looksLikeAbsolutePath(it) }
    }

    /** 绝对路径判定 — `/x` 形态或平台绝对路径; 相对路径 (工作区内) 一律豁免。 */
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
