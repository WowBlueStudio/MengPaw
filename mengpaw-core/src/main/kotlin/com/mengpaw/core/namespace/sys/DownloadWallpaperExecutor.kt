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

    /** 查询下载任务状态 (P1 修复: 下载中断/失败 Agent 可验证, 对齐 DownloadManager.Query)。 */
    suspend fun downloadStatus(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext
            ?: return ExecutionResult.fail("SysExecutor not initialized", errorCode = ErrorCodes.ERR_INTERNAL)
        val id = args.firstOrNull()?.toLongOrNull()
            ?: return ExecutionResult.fail(
                "Usage: sys.download.status <下载ID> (ID 来自 sys.download 返回值)",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return ExecutionResult.fail("DownloadManager 不可用", errorCode = ErrorCodes.ERR_INTERNAL)
        return try {
            val cursor = dm.query(DownloadManager.Query().setFilterById(id))
            cursor.use {
                if (!it.moveToFirst()) {
                    return ExecutionResult.fail("未找到下载任务 id=$id (可能已被系统清理)", errorCode = ErrorCodes.ERR_NOT_FOUND)
                }
                fun colInt(name: String): Int? {
                    val idx = it.getColumnIndex(name)
                    return if (idx >= 0) it.getInt(idx) else null
                }
                fun colString(name: String): String? {
                    val idx = it.getColumnIndex(name)
                    return if (idx >= 0) it.getString(idx) else null
                }
                val status = colInt(DownloadManager.COLUMN_STATUS)
                val statusText = when (status) {
                    DownloadManager.STATUS_PENDING -> "排队中"
                    DownloadManager.STATUS_RUNNING -> "下载中"
                    DownloadManager.STATUS_PAUSED -> "已暂停"
                    DownloadManager.STATUS_SUCCESSFUL -> "已完成"
                    DownloadManager.STATUS_FAILED -> "失败"
                    else -> "未知 (${status ?: -1})"
                }
                val reason = colInt(DownloadManager.COLUMN_REASON)
                val reasonText = if (status == DownloadManager.STATUS_FAILED) " (原因码 ${reason ?: -1})" else ""
                val title = colString(DownloadManager.COLUMN_TITLE) ?: id.toString()
                val total = colInt(DownloadManager.COLUMN_TOTAL_SIZE_BYTES) ?: 0
                val received = colInt(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR) ?: 0
                val url = colString(DownloadManager.COLUMN_URI) ?: ""
                ExecutionResult.ok(
                    "id=$id | $title | $statusText$reasonText | ${received}/${total} bytes | $url"
                )
            }
        } catch (e: Exception) {
            ExecutionResult.fail("查询下载状态失败: ${e.message}", errorCode = ErrorCodes.ERR_IO)
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
