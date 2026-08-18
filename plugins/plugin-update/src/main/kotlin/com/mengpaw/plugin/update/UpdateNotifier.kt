// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 更新相关通知与启动版本检测 — 从 UpdatePlugin/UpdateDownloader 拆分 (P2 修复 2026-08-15)。
 *
 * 1. [notifyIfUpdated] — 更新结果回传兜底: 系统安装器是异步外部流程, App 无法感知安装结果;
 *    改为启动时比较「上次记录版本」与当前 versionName, 有变化即通知「已更新」。
 *    任何来源的升级 (自动更新/手动装包/商店更新) 都会触发, 通用兜底。
 * 2. [currentVersion] — 当前应用版本号 (API 33 分支共享, 原 UpdatePlugin.getCurrentVersion 复用)。
 */
object UpdateNotifier {

    private const val PREF_VERSION_KEY = "update_last_launched_version"
    private const val NOTIFICATION_ID = 1002

    /** 当前应用 versionName; 读取失败返回 null。 */
    fun currentVersion(context: Context): String? {
        return try {
            val pkgInfo = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(
                    context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pkgInfo.versionName
        } catch (_: Exception) { null }
    }

    /** 启动版本检测 — 由 MainActivity.deferInit 在 appContext 注入后调用 (P2 修复)。 */
    fun notifyIfUpdated(context: Context) {
        try {
            val prefs = context.getSharedPreferences("mengpaw_settings", Context.MODE_PRIVATE)
            val last = prefs.getString(PREF_VERSION_KEY, null)
            val current = currentVersion(context) ?: return
            if (last != null && last != current) {
                showVersionUpdated(context, current)
            }
            prefs.edit().putString(PREF_VERSION_KEY, current).apply()
            // P1 修复 (2026-08-18): 每次启动做安装结果对账 — 已下载 APK 版本 ≤ 当前版本
            // 视为安装已生效 (含用户误点重复安装同一版本), 删除残留 APK,
            // 防设置页继续显示「安装」按钮。版本未变 (取消安装) 时旧 APK 保留可重试。
            UpdatePlugin.pruneInstalledApks(context, current)
        } catch (_: Exception) {}
    }

    private fun showVersionUpdated(context: Context, version: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel("version_update", "版本更新", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pi = launch?.let {
                PendingIntent.getActivity(context, 1, it, PendingIntent.FLAG_IMMUTABLE)
            }
            val builder = NotificationCompat.Builder(context, "version_update")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("MengPaw 已更新")
                .setContentText("当前版本 v$version，点击查看")
                .setAutoCancel(true)
            if (pi != null) builder.setContentIntent(pi)
            nm.notify(NOTIFICATION_ID, builder.build())
        } catch (_: Exception) {}
    }
}
