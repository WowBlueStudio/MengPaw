// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import android.app.WallpaperManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.app.DownloadManager
import com.mengpaw.core.namespace.SysExecutor
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import java.io.File
import java.io.FileInputStream

/**
 * 下载与壁纸 — sys.download / sys.wallpaper.set
 * (对齐 Termux:API termux-download / termux-wallpaper)。
 *
 * download 走系统 DownloadManager (无需存储权限, 下载到公共 Downloads);
 * wallpaper 需要 SET_WALLPAPER (normal 权限, 清单已声明)。
 */
internal object DownloadWallpaperExecutor {

    suspend fun download(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext
            ?: return ExecutionResult.fail("SysExecutor not initialized", errorCode = ErrorCodes.ERR_INTERNAL)
        val url = args.firstOrNull()
            ?: return ExecutionResult.fail(
                "Usage: sys.download <url> [文件名]",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return ExecutionResult.fail("DownloadManager 不可用", errorCode = ErrorCodes.ERR_INTERNAL)
        val fallbackName = url.substringAfterLast('/')
            .substringBefore('?')
            .takeIf { it.isNotBlank() && it.length <= 80 }
            ?: "download_${System.currentTimeMillis()}"
        val fileName = args.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: fallbackName
        return try {
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription("MengPaw Agent 下载")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val id = dm.enqueue(req)
            ExecutionResult.ok("已加入下载队列 (id=$id)。文件保存至: Downloads/$fileName, 完成时系统通知提醒。")
        } catch (e: Exception) {
            ExecutionResult.fail("下载启动失败: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
    }

    suspend fun wallpaperSet(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext
            ?: return ExecutionResult.fail("SysExecutor not initialized", errorCode = ErrorCodes.ERR_INTERNAL)
        val source = args.firstOrNull()
            ?: return ExecutionResult.fail(
                "Usage: sys.wallpaper.set <文件路径|file://|content://>",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        val wm = WallpaperManager.getInstance(app)
        return try {
            val input = when {
                source.startsWith("content://") -> app.contentResolver.openInputStream(Uri.parse(source))
                source.startsWith("file://") -> FileInputStream(Uri.parse(source).path.orEmpty())
                else -> FileInputStream(File(source))
            }
            if (input == null) {
                return ExecutionResult.fail("无法打开图片: $source", errorCode = ErrorCodes.ERR_IO)
            }
            input.use { wm.setStream(it) }
            ExecutionResult.ok("壁纸已设置: $source")
        } catch (e: Exception) {
            ExecutionResult.fail("设置壁纸失败: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
    }
}
