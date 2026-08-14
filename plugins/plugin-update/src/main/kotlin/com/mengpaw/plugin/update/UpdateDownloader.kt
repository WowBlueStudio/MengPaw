// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.update

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File

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

    /** 是否已有下载好的 APK 待安装 — 供设置页显示"安装"入口 (v0.38.2)。 */
    val hasDownloaded: Boolean get() = downloadedApk?.exists() == true

    /** APK 下载大小上限 (512MB) — 防异常响应撑爆存储, 流式写入时按字节计数。 */
    private val maxApkBytes = 512L * 1024 * 1024

    // ── update.download ─────────────────────────────────────────────────

    suspend fun download(args: List<String>, ctx: ExecutionContext): ExecutionResult {
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

            // Try primary URL → Gitee mirror → ghproxy
            val downloadUrls = listOf(url, UpdatePlugin.giteeDownload(url), UpdatePlugin.ghproxyDownload(url))
            var downloaded = false
            for (dUrl in downloadUrls) {
                // 流式边下边写 (tmp + rename) — 此前 readBytes 把整个 APK 读进内存, 大包必 OOM
                val tmpFile = File(downloadDir, "${apkFile.name}.part")
                var conn: java.net.HttpURLConnection? = null
                try {
                    conn = java.net.URL(dUrl).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 15000; conn.readTimeout = 30000
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
                } catch (_: Exception) {
                    tmpFile.delete()  // 清理残片, 尝试下一源
                } finally {
                    try { conn?.disconnect() } catch (_: Exception) { }
                }
            }
            if (!downloaded) {
                return ExecutionResult.fail("下载失败 — 所有下载源均不可达。💡 建议检查网络或使用 VPN。", errorCode = ErrorCodes.ERR_INTERNAL)
            }

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

    suspend fun install(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val apk = downloadedApk ?: return ExecutionResult.fail("请先执行 update.download", errorCode = ErrorCodes.ERR_INVALID_INPUT)
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
            ExecutionResult.fail("安装失败: ${e.message}\n可能需要允许\"未知来源\"安装。", errorCode = ErrorCodes.ERR_INTERNAL)
        }
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

    /** SHA-256 hex (Locale.ROOT: 默认 Locale 下 %02x 输出畸形 — 阿拉伯语设备 P2 修复)。 */
    private fun sha256(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
    }
}
