// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginContext
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Automatic update plugin for MengPaw Shell and Browser.
 *
 * ## Features
 * - Checks GitHub / Gitee Releases for new versions (fallback: GitHub → Gitee → ghproxy)
 * - WiFi-only scanning (optional, configurable)
 * - Auto-download option
 * - Installs APK via system package installer
 * - CLI: update.check / update.download / update.install / update.auto
 *
 * ## 职责拆分 (批次3)
 * 下载/安装/签名校验拆到 [UpdateDownloader] (构造参数传依赖闭包);
 * 测试可见的 internal 成员 (formatCheckResult/scheduleAutoCheck/
 * compareVersions/sha256/formatSize/镜像 URL) 原样保留在本类。
 */
class UpdatePlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "update-plugin", name = "自动更新", version = "",  // 内置插件: 无版本号 (随 Shell APK 更新)
        type = PluginType.NATIVE, author = "MengPaw",
        description = "WiFi 环境自动检测更新，可选自动下载安装。检查 GitHub/Gitee Releases(双源回退)。",
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
    private var autoCheckEnabled = false
    private var autoDownloadEnabled = false
    private var lastCheckTime = 0L
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // P2 修复 (幂等保护): scheduleAutoCheck 由 onInstall 与 update.auto on 双路径触发,
    // 无保护时每调一次就多跑一个 while 循环 (多份定时器同时扫更新)。
    // internal 为测试可见性 (CAS 幂等单测)。
    internal val autoCheckStarted = AtomicBoolean(false)
    private var latestRelease: ReleaseInfo? = null

    /** 下载/安装委托 — 依赖经构造参数注入 (批次3 拆分)。 */
    private val downloader = UpdateDownloader(
        releaseProvider = { latestRelease },
        wifiGateEnabled = { autoCheckEnabled },
        isWifiConnected = ::isWifiConnected,
        formatSize = ::formatSize,
        pluginVersion = "builtin"  // 内置插件无独立版本号 — User-Agent 用固定标识
    )

    data class ReleaseInfo(
        val tag: String, val name: String, val body: String,
        val shellUrl: String, val shellSize: Long,
        val browserUrl: String, val browserSize: Long
    )

    // ── Lifecycle ───────────────────────────────────────────────────────

    override suspend fun onInstall(ctx: PluginContext) {
        // Android context 由 Shell MainActivity.deferInit 注入 (companion.appContext)
        // 注入前安装则 install/auto 暂不可用, check/download 不受影响
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
            if (response.status.value !in 200..299) return null

            val json = Json.parseToJsonElement(response.bodyAsText())
            if (json !is JsonObject) return null
            val tag = (json["tag_name"] as? JsonPrimitive)?.content ?: return null
            val name = (json["name"] as? JsonPrimitive)?.content ?: tag
            val body = (json["body"] as? JsonPrimitive)?.content?.take(500) ?: ""

            // Find shell + browser APK assets
            val assets = (json["assets"] as? JsonArray) ?: JsonArray(emptyList())
            var shellUrl = ""; var shellSize = 0L
            var browserUrl = ""; var browserSize = 0L
            assets.forEach { a ->
                if (a !is JsonObject) return@forEach
                val dUrl = (a["browser_download_url"] as? JsonPrimitive)?.content ?: ""
                val dSize = (a["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
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

    /** internal 为测试可见性 (版本新旧判定单测)。 */
    internal fun formatCheckResult(current: String, release: ReleaseInfo): ExecutionResult {
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

    // ── update.download / update.install (delegated to UpdateDownloader) ─

    private suspend fun download(args: List<String>, ctx: ExecutionContext): ExecutionResult =
        downloader.download(args, ctx)

    private suspend fun install(args: List<String>, ctx: ExecutionContext): ExecutionResult =
        downloader.install(args, ctx)

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

    /** internal 为测试可见性 (CAS 幂等单测)。 */
    internal fun scheduleAutoCheck() {
        // CAS 幂等: 已有一个调度循环则不重复启动; 协程退出 (取消/异常) 后复位, 允许重新调度
        if (!autoCheckStarted.compareAndSet(false, true)) return
        scope.launch {
            try {
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
            } finally {
                autoCheckStarted.set(false)
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

    /** internal 为测试可见性 (版本号比较单测)。 */
    internal fun compareVersions(a: String, b: String): Int {
        val ap = a.split(".").map { it.toIntOrNull() ?: 0 }
        val bp = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(ap.size, bp.size)) {
            val av = ap.getOrElse(i) { 0 }; val bv = bp.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    /** internal 为测试可见性 (Locale.ROOT hex 输出单测)。 */
    internal fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Locale.ROOT: 默认 Locale 下 %02x 输出畸形 (阿拉伯语设备 — P2 修复)
        return digest.digest(bytes).joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
    }

    /** internal 为测试可见性 (格式化单测)。 */
    internal fun formatSize(bytes: Long): String = when {
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
        /** Android Context — 由 Shell MainActivity.deferInit 注入 (替代失效的 getAppContext 反射)。 */
        @Volatile var appContext: Context? = null

        private const val GITHUB_API_URL = "https://api.github.com/repos/WowBlueStudio/MengPaw/releases/latest"
        private const val GITEE_API_URL = "https://gitee.com/api/v5/repos/WowBlueStudio/MengPaw/releases/latest"
        private const val GHPROXY_API_URL = "https://ghproxy.com/$GITHUB_API_URL"
        /** Build a ghproxy URL for any GitHub-hosted download.
         *  internal 为测试可见性 (镜像 URL 单测)。 */
        internal fun ghproxyDownload(githubUrl: String): String = "https://ghproxy.com/$githubUrl"
        /** Build a Gitee download mirror URL from a GitHub download URL.
         *  internal 为测试可见性 (镜像 URL 单测)。 */
        internal fun giteeDownload(githubUrl: String): String =
            githubUrl.replace("github.com", "gitee.com")
    }
}
