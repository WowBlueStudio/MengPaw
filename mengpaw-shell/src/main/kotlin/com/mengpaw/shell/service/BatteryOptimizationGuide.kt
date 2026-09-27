// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.service

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.mengpaw.kernel.DataPaths
import java.io.File

/**
 * 电池优化白名单一次性引导 (荣耀/vivo 等厂商省电策略会冻结甚至杀掉后台服务)。
 *
 * 设计约束:
 * - **只弹一次**: 标记文件 `CONFIG/battery_opt_prompted` 持久化 (读 [DataPaths.CONFIG],
 *   与 ShellService.readBackgroundMode 的配置标记同风格); 无论用户"去设置"还是"以后再说"
 *   都落标记, 绝不每次启动骚扰。系统设置页里仍有常驻的手动入口
 *   (ui/screens/settings/SystemSettingsContent.kt:130-141), 需要重新授权时用户可自行前往。
 * - 复用系统 API: ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (Manifest 早已声明
 *   REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, 本次**不新增任何权限**);
 *   无对应 Activity 时回退 ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS 设置列表页。
 * - 全部 IO 与 Intent 调用 try/catch; Activity 已销毁时 AlertDialog 不会崩溃但会被跳过。
 */
object BatteryOptimizationGuide {

    private const val TAG = "BatteryGuide"

    /** 一次性标记文件名 (位于 CONFIG 目录)。 */
    const val MARKER_FILE_NAME = "battery_opt_prompted"

    /** 纯判定: 是否需要弹一次性引导 (已弹过 / 已白名单 → 不需要)。 */
    fun shouldPromptOnce(markerExists: Boolean, alreadyIgnoring: Boolean): Boolean =
        !markerExists && !alreadyIgnoring

    /** 一次性引导 (主线程调用; 已弹过或已白名单时零副作用)。 */
    fun maybePromptOnce(activity: Activity) {
        try {
            val already = isIgnoringBatteryOptimizations(activity)
            val marker = markerFile(activity)
            val exists = marker?.exists() == true
            if (!shouldPromptOnce(exists, already)) {
                // 用户已手动白名单 → 补落标记, 今后不再打扰
                if (already && !exists) writeMarker(marker)
                return
            }
            AlertDialog.Builder(activity)
                .setTitle("后台保活设置")
                .setMessage(
                    "长时间任务在后台运行时，系统的省电策略可能中断 MengPaw。\n\n" +
                        "请在接下来的系统弹窗中允许 MengPaw「忽略电池优化」，以免任务中途停止。"
                )
                .setPositiveButton("去设置") { _, _ ->
                    writeMarker(marker)
                    requestIgnoreBatteryOptimizations(activity)
                }
                .setNegativeButton("以后再说") { _, _ -> writeMarker(marker) }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            Log.w(TAG, "电池优化引导失败: ${e.message}")
        }
    }

    /** 是否已被系统列入电池优化白名单 (免权限查询)。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    } catch (_: Exception) {
        false
    }

    /** 标记文件: CONFIG 目录优先, 不可用时回退私有 filesDir; 两者都不可用返回 null。 */
    fun markerFile(context: Context): File? = try {
        val configDir = File(DataPaths.CONFIG)
        if (configDir.exists() || configDir.mkdirs()) File(configDir, MARKER_FILE_NAME)
        else File(context.filesDir, MARKER_FILE_NAME)
    } catch (_: Exception) {
        try {
            File(context.filesDir, MARKER_FILE_NAME)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeMarker(marker: File?) {
        if (marker == null) return
        try {
            marker.parentFile?.mkdirs()
            // 先写临时文件再改名 — 避免写入中途被杀留下半截标记
            val tmp = File(marker.parentFile, "$MARKER_FILE_NAME.tmp")
            tmp.writeText("1")
            if (!tmp.renameTo(marker)) {
                marker.writeText("1")
                try { tmp.delete() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "保活标记写入失败: ${e.message}")
        }
    }

    /** 拉起系统"忽略电池优化"授权页 (与设置页既有实现同协议)。 */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            val pkgIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (pkgIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(pkgIntent)
                return
            }
            val listIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(listIntent)
        } catch (e: Exception) {
            Log.w(TAG, "打开电池优化设置失败: ${e.message}")
        }
    }
}
