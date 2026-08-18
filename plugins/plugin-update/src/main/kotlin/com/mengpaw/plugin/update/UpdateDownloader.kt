// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 更新下载/安装 — 从 UpdatePlugin 拆分 (update.download / update.install)。
 *
 * 依赖通过构造参数注入 (参照 SessionCompressor 模式): 最新版本读取 /
 * WiFi 门禁 / 当前版本读取 / 大小格式化。下载状态 (downloadedApk)
 * 由本类持有, 行为与拆分前完全一致。
 */
internal class UpdateDownloader(
    private val releaseProvider: () -> UpdatePlugin.ReleaseInfo?,
    private val wifiGateEnabled: () -> Boolean,
    private val isWifiConnected: () -> Boolean,
    private val formatSize: (Long) -> String,
    private val pluginVersion: String
) {
    private var downloadedApk: File? = null

    /** 下载进度回调 (已下载字节, 总字节; total<0 表示未知) — UI 显示进度 (v0.39.2 修复)。
     *  @Volatile: IO 线程写, UI 线程读。 */
    @Volatile var onProgress: ((downloaded: Long, total: Long) -> Unit)? = null

    /** 下载并发锁 — UI 手动下载与 update.auto 自动下载互斥, 防止同写 .part 文件 (P2 修复)。 */
    private val downloading = AtomicBoolean(false)

    /** 已唤起安装的 target+tag 组合键 (如 shell:v0.38.4) — 自动下载按目标跳过, 防安装中重复下载 (P2 修复 2026-08-15)。 */
    private var installPendingKey: String? = null

    /** 是否已有下载好的 APK 待安装 — 供设置页显示"安装"入口 (v0.38.2)。
     *  重启后内存态丢失, 按文件名约定扫描 updates 目录兜底 (P2 修复)。 */
    val hasDownloaded: Boolean get() {
        // P1 修复 (2026-08-18): 先做安装结果对账 — 当前版本已追平/高于已下载 APK 版本时
        // 删除残留 APK, 否则安装生效后设置页仍显示「安装」按钮误导用户重复安装。
        reconcileInstalledState()
        return downloadedApk?.exists() == true || findDownloadedApk("shell") != null
    }

    /**
     * 安装结果对账: 当前应用版本 ≥ 已下载 APK 版本 → 安装已生效/重复包,
     * 删除 APK 并清除「待安装」「安装中」状态。供 [hasDownloaded] 懒检查
     * 调用; 启动兜底由 UpdateNotifier 直接走 prune, 本方法为设置页刷新路径。
     */
    internal fun reconcileInstalledState() {
        val context = UpdatePlugin.appContext ?: return
        val current = UpdateNotifier.currentVersion(context) ?: return
        val removed = UpdatePlugin.pruneInstalledApks(context, current)
        if (removed.isNotEmpty()) {
            downloadedApk = downloadedApk?.takeIf { it.exists() }
            installPendingKey = null
        }
    }

    /** APK 下载大小上限 (512MB) — 防异常响应撑爆存储, 流式写入时按字节计数。 */
    private val maxApkBytes = 512L * 1024 * 1024

    // ── update.download ─────────────────────────────────────────────────

    suspend fun download(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        // 并发互斥: 已有下载任务进行中则拒绝 (UI 与自动检查双路径可能同时触发)
        if (!downloading.compareAndSet(false, true)) {
            return ExecutionResult.fail("已有下载任务进行中, 请稍后再试", errorCode = ErrorCodes.ERR_INTERNAL)
        }
        return try {
            doDownload(args, ctx)
        } finally {
            downloading.set(false)
        }
    }

    /** 下载入口 — 网络 + 文件 IO 全部移出调用方线程 (v0.40.1 修复: 原实现同步
     *  阻塞调用线程, 设置页/命令路径主线程触发时 Input dispatching timed out ANR,
     *  2026-08-16 设备实测; 与设置页 check/download 按钮 withContext(IO) 双保险)。 */
    private suspend fun doDownload(args: List<String>, ctx: ExecutionContext): ExecutionResult =
        withContext(Dispatchers.IO) { doDownloadIo(args, ctx) }

    /** 实际下载逻辑 (网络 + 文件 IO) — 必须经 doDownload 在 IO 调度器运行。 */
    private suspend fun doDownloadIo(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val release = releaseProvider() ?: return ExecutionResult.fail("请先执行 update.check", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val target = args.firstOrNull()?.lowercase() ?: "shell"

        val (url, label) = when (target) {
            "shell" -> release.shellUrl to "Shell"
            "browser" -> release.browserUrl to "Browser"
            else -> return ExecutionResult.fail("请指定 shell 或 browser", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
        if (url.isEmpty()) return ExecutionResult.fail("该组件无可用下载", errorCode = ErrorCodes.ERR_NOT_FOUND)

        // Check WiFi if configured
        if (wifiGateEnabled() && !isWifiConnected()) {
            return ExecutionResult.fail("未连接 WiFi。使用 update.auto wifi_only=false 允许移动网络下载。", errorCode = ErrorCodes.ERR_INTERNAL)
        }

        return try {
            val downloadDir = File(DataPaths.PLUGIN_CACHE, "updates").also { it.mkdirs() }
            val apkFile = File(downloadDir, "mengpaw-$target-${release.tag}.apk")

            // 下载源按 check 命中源优先排序 (v0.39.2 修复): 国内设备 Gitee 通但
            // GitHub HTTPS/ghproxy 常被墙 — 同源优先避免首源白等连接超时
            val giteeMirror = UpdatePlugin.giteeDownload(url)
            val proxyMirror = UpdatePlugin.ghproxyDownload(url)
            val downloadUrls = when (release.source) {
                "gitee" -> listOf(giteeMirror, url, proxyMirror)
                "ghproxy" -> listOf(proxyMirror, giteeMirror, url)
                else -> listOf(url, giteeMirror, proxyMirror)
            }
            val failures = mutableListOf<String>()
            var downloaded = false
            for (dUrl in downloadUrls) {
                // 流式边下边写 (tmp + rename) — 此前 readBytes 把整个 APK 读进内存, 大包必 OOM
                val tmpFile = File(downloadDir, "${apkFile.name}.part")
                var conn: java.net.HttpURLConnection? = null
                try {
                    conn = java.net.URL(dUrl).openConnection() as java.net.HttpURLConnection
                    // 连接快速失败切换 (10s) + 读取放宽 (60s) — 慢速网络大文件 30s 会误杀
                    conn.connectTimeout = 10000; conn.readTimeout = 60000
                    conn.instanceFollowRedirects = true  // 显式跟随 Gitee 302 → CDN
                    conn.setRequestProperty("User-Agent", "MengPaw-Update/$pluginVersion")
                    val code = conn.responseCode
                    if (code in 200..299) {
                        // 大小上限检查: 预检 Content-Length + 实际写入计数双保险
                        val declared = conn.contentLengthLong
                        if (declared > maxApkBytes) {
                            throw IllegalStateException("APK 超过 ${maxApkBytes / 1024 / 1024}MB 上限")
                        }
                        var total = 0L
                        conn.inputStream.use { ins ->
                            tmpFile.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    total += n
                                    if (total > maxApkBytes) {
                                        throw IllegalStateException("APK 超过 ${maxApkBytes / 1024 / 1024}MB 上限")
                                    }
                                    out.write(buf, 0, n)
                                    onProgress?.invoke(total, declared)
                                }
                            }
                        }
                        if (!tmpFile.renameTo(apkFile)) {
                            apkFile.delete()
                            if (!tmpFile.renameTo(apkFile)) throw IllegalStateException("文件写入失败 (rename)")
                        }
                        downloaded = true
                        break
                    }
                } catch (e: Exception) {
                    val err = e.message?.take(80) ?: "未知错误"
                    failures += "$dUrl → $err"
                    KernelLog.w("UpdatePlugin.download", "源失败 $dUrl: $err")
                    tmpFile.delete()  // 清理残片, 尝试下一源
                } finally {
                    try { conn?.disconnect() } catch (_: Exception) { }
                }
            }
            if (!downloaded) {
                val reason = failures.joinToString(" | ").take(280)
                return ExecutionResult.fail(
                    "下载失败 — 所有下载源均不可达。\n$reason\n💡 建议检查网络或使用 VPN。",
                    errorCode = ErrorCodes.ERR_INTERNAL
                )
            }

            downloadedApk = apkFile
            // 清理同目标旧版本 APK, 防 updates 目录无限累积 (P2 修复)
            cleanupOldApks(target, apkFile)
            // 自动下载完成通知 (update.auto download=on 后台下载, 用户无感知 — P1 修复);
            // 手动下载由 UI 直接反馈, 不重复通知
            if (ctx.sessionId == "auto") notifyDownloaded(target, release.tag)

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

    suspend fun install(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val target = args.firstOrNull()?.lowercase() ?: "shell"
        // 按目标选择 APK: 内存态优先 (须与目标匹配), 重启后按文件名约定扫描兜底 (P2 修复)
        val apk = downloadedApk?.takeIf { it.name.startsWith("mengpaw-$target-") }
            ?: findDownloadedApk(target)
            ?: return ExecutionResult.fail("请先执行 update.download $target", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val context = UpdatePlugin.appContext ?: return ExecutionResult.fail("无法获取 Context", errorCode = ErrorCodes.ERR_INTERNAL)

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
            // P0 修复 (2026-08-15): authority 必须与 Shell AndroidManifest 注册的
            // ${applicationId}.fileprovider 一致 — 此前误用不存在的 .update.provider,
            // FileProvider.getUriForFile 找不到 provider 直接抛异常, update.install 必然失败。
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            installPendingKey = "$target:${tagFromApkName(apk.name)}"
            ExecutionResult.ok("正在安装 ${apk.name}...\n安装完成后请重启应用。")
        } catch (e: Exception) {
            ErrorCollector.report(e, "UpdatePlugin.install")
            ExecutionResult.fail("安装失败: ${e.message}\n可能需要允许\"未知来源\"安装。", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    /** 自动下载是否应跳过该目标+版本 — install 唤起后至安装生效/被新版本取代前为 true (P2 修复)。 */
    internal fun shouldSkipAutoDownload(target: String, tag: String): Boolean =
        shouldSkipAutoDownload(installPendingKey, target, tag)

    /** 静态版跳过判断 — internal 为测试可见性 (P2 修复)。 */
    internal fun shouldSkipAutoDownload(pending: String?, target: String, tag: String): Boolean =
        pending == "$target:$tag"

    /** 清除「安装中」标记 — 当前版本已追上或版本被新版本取代时调用。 */
    internal fun clearInstallPending() { installPendingKey = null }

    /** 从 APK 文件名提取版本 tag (mengpaw-shell-v0.38.4.apk → v0.38.4) — internal 为测试可见性。 */
    internal fun tagFromApkName(name: String): String =
        name.removePrefix("mengpaw-shell-").removePrefix("mengpaw-browser-").removeSuffix(".apk")

    /**
     * Verify the downloaded APK is signed with the same certificate as the currently
     * running app. Prevents installation of malicious APKs from compromised sources.
     * 注意: Shell 与 Browser 必须使用同一签名证书, 否则跨组件安装会被误拒 (设计前提, 见开发指南 §5.1)。
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

    /** SHA-256 hex (Locale.ROOT: 默认 Locale 下 %02x 输出畸形 — 阿拉伯语设备 P2 修复)。 */
    private fun sha256(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
    }

    /** 按文件名约定扫描 updates 目录, 返回最新匹配的 APK — 重启后待安装状态兜底 (P2 修复)。 */
    internal fun findDownloadedApk(target: String): File? {
        return try {
            val dir = File(DataPaths.PLUGIN_CACHE, "updates")
            if (!dir.isDirectory) return null
            latestApkIn(dir, target)
        } catch (_: Exception) { null }
    }

    /** 目录内最新匹配的 APK — internal 为测试可见性 (P2 修复)。 */
    internal fun latestApkIn(dir: File, target: String): File? {
        return dir.listFiles { f -> f.isFile && f.name.startsWith("mengpaw-$target-") && f.name.endsWith(".apk") }
            ?.maxByOrNull { it.lastModified() }
    }

    /** 下载成功后清理同目标旧版本 APK, 防 updates 目录无限累积 (P2 修复)。 */
    private fun cleanupOldApks(target: String, keep: File) {
        try {
            cleanupOldApksIn(File(DataPaths.PLUGIN_CACHE, "updates"), target, keep)
        } catch (_: Exception) {}
    }

    /** 清理同目标旧版本 APK (保留 keep) — internal 为测试可见性 (P2 修复)。 */
    internal fun cleanupOldApksIn(dir: File, target: String, keep: File) {
        dir.listFiles { f -> f.isFile && f.name.startsWith("mengpaw-$target-") && f.name.endsWith(".apk") && f != keep }
            ?.forEach { it.delete() }
    }

    /** 自动下载完成通知 — 修复 update.auto 后台下载用户无感知问题 (P1 修复)。 */
    private fun notifyDownloaded(target: String, tag: String) {
        val context = UpdatePlugin.appContext ?: return
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel("update_download", "自动更新", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pi = launch?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
            }
            val builder = NotificationCompat.Builder(context, "update_download")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("MengPaw $target 更新已下载")
                .setContentText("$tag 已就绪, 打开设置页可安装")
                .setAutoCancel(true)
            if (pi != null) builder.setContentIntent(pi)
            nm.notify(1001, builder.build())
        } catch (_: Exception) {}
    }
}
