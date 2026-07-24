// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.update

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.FileProvider
import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginContext
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Automatic update plugin for MengPaw Shell and Browser.
 *
 * ## Features
 * - Checks GitHub Releases for new versions
 * - WiFi-only scanning (optional, configurable)
 * - Auto-download option
 * - Installs APK via system package installer
 * - CLI: update.check / update.download / update.install / update.auto
 */
class UpdatePlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "update-plugin", name = "自动更新", version = "0.1.0",
        type = PluginType.NATIVE, author = "MengPaw",
        description = "WiFi 环境自动检测更新，可选自动下载安装。检查 GitHub Releases。",
        permissions = listOf("INTERNET", "ACCESS_NETWORK_STATE", "REQUEST_INSTALL_PACKAGES"),
        minCoreVersion = "0.2.3",
        commands = listOf("update.check", "update.download", "update.install", "update.auto")
    )
    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "check" to ::check, "download" to ::download,
        "install" to ::install, "auto" to ::autoConfig,
    )

    private val client = HttpClient(OkHttp) {
        engine { config { connectTimeout(15, TimeUnit.SECONDS); readTimeout(30, TimeUnit.SECONDS) } }
    }
    private var appContext: Context? = null
    private var autoCheckEnabled = false
    private var autoDownloadEnabled = false
    private var lastCheckTime = 0L
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var latestRelease: ReleaseInfo? = null
    private var downloadedApk: File? = null

    data class ReleaseInfo(
        val tag: String, val name: String, val body: String,
        val shellUrl: String, val shellSize: Long,
        val browserUrl: String, val browserSize: Long
    )

    // ── Lifecycle ───────────────────────────────────────────────────────

    override suspend fun onInstall(ctx: PluginContext) {
        try {
            appContext = Class.forName("com.mengpaw.shell.MainActivity")
                .getMethod("getAppContext").invoke(null) as? Context
        } catch (_: Exception) { }
        loadConfig()
        if (autoCheckEnabled) scheduleAutoCheck()
        ctx.log("自动更新插件已激活。${if (autoCheckEnabled) "WiFi 自动扫描已启用。" else ""}")
    }

    override suspend fun onUninstall() {
        scope.cancel()
        client.close()
    }

    // ── update.check ────────────────────────────────────────────────────

    private suspend fun check(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val currentVersion = getCurrentVersion() ?: return ExecutionResult.fail("无法获取当前版本", errorCode = ErrorCodes.ERR_INTERNAL)
        val force = args.contains("--force")

        // Cache: skip if checked within last hour (unless forced)
        if (!force && System.currentTimeMillis() - lastCheckTime < 3_600_000 && latestRelease != null) {
            val release = latestRelease ?: return ExecutionResult.fail("缓存失效", errorCode = ErrorCodes.ERR_INTERNAL)
            return formatCheckResult(currentVersion, release)
        }

        // Try GitHub → Gitee → ghproxy
        val urls = listOf(GITHUB_API_URL, GITEE_API_URL, GHPROXY_API_URL)
        var lastError: String? = null
        for ((i, url) in urls.withIndex()) {
            val result = tryFetchRelease(url)
            if (result != null) {
                latestRelease = result
                lastCheckTime = System.currentTimeMillis()
                return formatCheckResult(currentVersion, result)
            }
            lastError = if (i == urls.lastIndex) "所有更新源均不可达。💡 建议检查网络连接，或使用 VPN 访问 GitHub。" else null
        }

        return ExecutionResult.fail(lastError ?: "检查更新失败", errorCode = ErrorCodes.ERR_INTERNAL)
    }

    /** Try to fetch release info from a single URL. Returns null on failure. */
    private suspend fun tryFetchRelease(url: String): ReleaseInfo? {
        return try {
            val response = client.get(url) {
                if ("gitee" in url) header("Accept", "application/json")
                else header("Accept", "application/vnd.github.v3+json")
            }
            if (!response.status.isSuccess()) return null

            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val tag = json["tag_name"]?.jsonPrimitive?.content ?: return null
            val name = json["name"]?.jsonPrimitive?.content ?: tag
            val body = json["body"]?.jsonPrimitive?.content?.take(500) ?: ""

            // Find shell + browser APK assets
            val assets = json["assets"]?.jsonArray ?: emptyList()
            var shellUrl = ""; var shellSize = 0L
            var browserUrl = ""; var browserSize = 0L
            assets.forEach { a ->
                val obj = a.jsonObject
                val dUrl = obj["browser_download_url"]?.jsonPrimitive?.content ?: ""
                val dSize = obj["size"]?.jsonPrimitive?.long ?: 0L
                when {
                    dUrl.contains("mengpaw-shell") -> { shellUrl = dUrl; shellSize = dSize }
                    dUrl.contains("mengpaw-browser") -> { browserUrl = dUrl; browserSize = dSize }
                }
            }
            ReleaseInfo(tag, name, body, shellUrl, shellSize, browserUrl, browserSize)
        } catch (e: Exception) {
            ErrorCollector.report(e, "UpdatePlugin.tryFetch")
            null
        }
    }

    private fun formatCheckResult(current: String, release: ReleaseInfo): ExecutionResult {
        val isNewer = compareVersions(release.tag.removePrefix("v"), current) > 0
        val sb = StringBuilder()
        sb.appendLine(if (isNewer) "🔔 发现新版本!" else "✅ 已是最新版本")
        sb.appendLine("- 当前: v$current")
        sb.appendLine("- 最新: ${release.tag} — ${release.name}")
        if (release.shellUrl.isNotEmpty()) sb.appendLine("- Shell APK: ${formatSize(release.shellSize)}")
        if (release.browserUrl.isNotEmpty()) sb.appendLine("- Browser APK: ${formatSize(release.browserSize)}")
        if (isNewer) {
            sb.appendLine()
            sb.appendLine("更新内容:")
            sb.appendLine(release.body.take(300))
            sb.appendLine()
            sb.appendLine("执行 update.download 下载更新。")
        }
        return ExecutionResult.ok(sb.toString())
    }

    // ── update.download ─────────────────────────────────────────────────

    private suspend fun download(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val release = latestRelease ?: return ExecutionResult.fail("请先执行 update.check", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val target = args.firstOrNull()?.lowercase() ?: "shell"

        val (url, label) = when (target) {
            "shell" -> release.shellUrl to "Shell"
            "browser" -> release.browserUrl to "Browser"
            else -> return ExecutionResult.fail("请指定 shell 或 browser", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
        if (url.isEmpty()) return ExecutionResult.fail("该组件无可用下载", errorCode = ErrorCodes.ERR_NOT_FOUND)

        // Check WiFi if configured
        if (autoCheckEnabled && !isWifiConnected()) {
            return ExecutionResult.fail("未连接 WiFi。使用 update.auto wifi_only=false 允许移动网络下载。", errorCode = ErrorCodes.ERR_INTERNAL)
        }

        return try {
            val downloadDir = File(DataPaths.PLUGIN_CACHE, "updates").also { it.mkdirs() }
            val apkFile = File(downloadDir, "mengpaw-$target-${release.tag}.apk")

            // Try primary URL → Gitee mirror → ghproxy
            val downloadUrls = listOf(url, giteeDownload(url), ghproxyDownload(url))
            var downloadBytes: ByteArray? = null
            for (dUrl in downloadUrls) {
                try {
                    val response = client.get(dUrl)
                    if (response.status.isSuccess()) {
                        downloadBytes = response.bodyAsBytes()
                        break
                    }
                } catch (_: Exception) { /* try next */ }
            }
            if (downloadBytes == null) {
                return ExecutionResult.fail("下载失败 — 所有下载源均不可达。💡 建议检查网络或使用 VPN。", errorCode = ErrorCodes.ERR_INTERNAL)
            }

            apkFile.writeBytes(downloadBytes)
            downloadedApk = apkFile

            ExecutionResult.ok("""
## 下载完成: $label ${release.tag}
文件: ${apkFile.absolutePath}
大小: ${formatSize(apkFile.length())}

执行 update.install $target 安装更新。
""".trimIndent())
        } catch (e: Exception) {
            ErrorCollector.report(e, "UpdatePlugin.download")
            ExecutionResult.fail("下载失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    // ── update.install ──────────────────────────────────────────────────

    private suspend fun install(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val apk = downloadedApk ?: return ExecutionResult.fail("请先执行 update.download", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val context = appContext ?: return ExecutionResult.fail("无法获取 Context", errorCode = ErrorCodes.ERR_INTERNAL)

        if (!apk.exists()) {
            downloadedApk = null
            return ExecutionResult.fail("APK 文件不存在，请重新下载", errorCode = ErrorCodes.ERR_NOT_FOUND)
        }

        // SECURITY: Verify APK signature matches current app before installing
        val sigError = verifyApkSignature(context, apk)
        if (sigError != null) {
            downloadedApk = null
            apk.delete()
            return ExecutionResult.fail("签名验证失败: $sigError\nAPK 可能与官方版本不符，已删除。", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }

        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.update.provider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ExecutionResult.ok("正在安装 ${apk.name}...\n安装完成后请重启应用。")
        } catch (e: Exception) {
            ErrorCollector.report(e, "UpdatePlugin.install")
            ExecutionResult.fail("安装失败: ${e.message}\n可能需要允许"未知来源"安装。", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    // ── update.auto ─────────────────────────────────────────────────────

    private suspend fun autoConfig(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) {
            return ExecutionResult.ok("""
## 自动更新配置
- WiFi 扫描: ${if (autoCheckEnabled) "✅ 已启用" else "⛔ 已禁用"}
- 自动下载: ${if (autoDownloadEnabled) "✅ 已启用" else "⛔ 已禁用"}
- 上次检查: ${if (lastCheckTime > 0) java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(lastCheckTime)) else "从未"}

用法:
  update.auto on              — 启用 WiFi 自动扫描
  update.auto off             — 禁用自动扫描
  update.auto download=on     — 启用自动下载(检测到更新后自动下载)
  update.auto download=off    — 禁用自动下载
""".trimIndent())
        }

        when (args[0].lowercase()) {
            "on" -> { autoCheckEnabled = true; scheduleAutoCheck(); saveConfig() }
            "off" -> { autoCheckEnabled = false; saveConfig() }
            "download=on" -> { autoDownloadEnabled = true; saveConfig() }
            "download=off" -> { autoDownloadEnabled = false; saveConfig() }
        }
        return autoConfig(emptyList(), ctx)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun scheduleAutoCheck() {
        scope.launch {
            while (isActive) {
                delay(3_600_000) // Check every hour
                if (isWifiConnected()) {
                    try { check(emptyList(), ExecutionContext("auto")) } catch (_: Exception) { }
                    val release = latestRelease
                    if (autoDownloadEnabled && release != null) {
                        val current = getCurrentVersion()
                        if (current != null && compareVersions(release.tag.removePrefix("v"), current) > 0) {
                            try { download(listOf("shell"), ExecutionContext("auto")) } catch (_: Exception) { }
                        }
                    }
                }
            }
        }
    }

    private fun isWifiConnected(): Boolean {
        val ctx = appContext ?: return false
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (_: Exception) { false }
    }

    private fun getCurrentVersion(): String? {
        val ctx = appContext ?: return null
        return try {
            val pkgInfo = if (Build.VERSION.SDK_INT >= 33) {
                ctx.packageManager.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0L))
            } else {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            }
            pkgInfo.versionName
        } catch (_: Exception) { null }
    }

    private fun compareVersions(a: String, b: String): Int {
        val ap = a.split(".").map { it.toIntOrNull() ?: 0 }
        val bp = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(ap.size, bp.size)) {
            val av = ap.getOrElse(i) { 0 }; val bv = bp.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    /**
     * Verify the downloaded APK is signed with the same certificate as the currently
     * running app. Prevents installation of malicious APKs from compromised sources.
     * @return null if signature matches, or an error message.
     */
    private fun verifyApkSignature(context: Context, apk: File): String? {
        return try {
            val pm = context.packageManager
            // Get current app's signing certificate SHA-256
            val currentPkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName,
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName,
                    android.content.pm.PackageManager.GET_SIGNATURES)
            }
            val currentCerts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                currentPkgInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                currentPkgInfo.signatures
            } ?: return "Cannot read current app signature"

            val currentHash = sha256(currentCerts[0].toByteArray())

            // Get downloaded APK's signing certificate
            val apkPkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageArchiveInfo(apk.absolutePath,
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apk.absolutePath,
                    android.content.pm.PackageManager.GET_SIGNATURES)
            }
            if (apkPkgInfo == null) return "Cannot parse APK (corrupted file)"

            val apkCerts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                apkPkgInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                apkPkgInfo.signatures
            } ?: return "APK has no signature"

            val apkHash = sha256(apkCerts[0].toByteArray())

            if (!currentHash.equals(apkHash, ignoreCase = true)) {
                "Signature mismatch\n  Current: ${currentHash.take(16)}...\n  Downloaded: ${apkHash.take(16)}..."
            } else null
        } catch (e: Exception) {
            "Signature check error: ${e.message}"
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    private fun loadConfig() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences("mengpaw_settings", Context.MODE_PRIVATE)
        autoCheckEnabled = prefs.getBoolean("update_auto_check", true)
        autoDownloadEnabled = prefs.getBoolean("update_auto_download", false)
        lastCheckTime = prefs.getLong("update_last_check", 0L)
    }

    private fun saveConfig() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences("mengpaw_settings", Context.MODE_PRIVATE).edit().apply {
            putBoolean("update_auto_check", autoCheckEnabled)
            putBoolean("update_auto_download", autoDownloadEnabled)
            putLong("update_last_check", lastCheckTime)
            apply()
        }
    }

    companion object {
        private const val GITHUB_API_URL = "https://api.github.com/repos/WowBlueStudio/MengPaw/releases/latest"
        private const val GITEE_API_URL = "https://gitee.com/api/v5/repos/WowBlueStudio/MengPaw/releases/latest"
        private const val GHPROXY_API_URL = "https://ghproxy.com/$GITHUB_API_URL"
        /** Build a ghproxy URL for any GitHub-hosted download. */
        private fun ghproxyDownload(githubUrl: String): String = "https://ghproxy.com/$githubUrl"
        /** Build a Gitee download mirror URL from a GitHub download URL. */
        private fun giteeDownload(githubUrl: String): String =
            githubUrl.replace("github.com", "gitee.com")
    }
}
