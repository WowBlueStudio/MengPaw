// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import com.mengpaw.core.namespace.SysExecutor
import com.mengpaw.core.namespace.checkSelf
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import java.lang.ref.WeakReference

/** Permission listing, checking, and requesting (runtime dialog + settings fallback). */
internal object PermissionExecutor {

    private const val PERM_REQUEST_CODE = 9001

    // ── 权限清单维护规则 (P2 修复, 防双源漂移) ──
    // 本文件所有权限数组以 mengpaw-shell/AndroidManifest.xml 的 <uses-permission> 为唯一基准:
    // 1. Manifest 未声明的权限不可被授予 — requestPermissions 直接抛 SecurityException,
    //    不得列入 DIALOG/SETTINGS (曾漂移出 READ_PHONE_STATE/SEND_SMS/READ_CONTACTS/WRITE_SETTINGS, 已清除)
    // 2. 新增可运行时申请的权限: 先声明于 Manifest, 再同步加入 DIALOG/SETTINGS 与 PERMISSION_LABELS
    // 3. PERMISSION_LABELS 是 sys.permission.list 的输出清单 — 须与 Manifest 一一对应

    private val DIALOG_PERMISSIONS = setOf(
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
        // v0.36.x sys.* 敏感命令组 (风险等级 MID, TRUSTED 放行)
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.CALL_PHONE,
    )

    private val SETTINGS_PERMISSIONS = setOf(
        Manifest.permission.SYSTEM_ALERT_WINDOW,
        Manifest.permission.REQUEST_INSTALL_PACKAGES,
        Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
    )

    private val PERMISSION_LABELS = mapOf(
        Manifest.permission.ACCESS_FINE_LOCATION to "GPS 定位",
        Manifest.permission.ACCESS_COARSE_LOCATION to "粗略定位",
        Manifest.permission.CAMERA to "相机",
        Manifest.permission.RECORD_AUDIO to "录音",
        Manifest.permission.READ_EXTERNAL_STORAGE to "读取存储",
        Manifest.permission.WRITE_EXTERNAL_STORAGE to "写入存储",
        Manifest.permission.READ_MEDIA_IMAGES to "读取图片",
        Manifest.permission.SYSTEM_ALERT_WINDOW to "悬浮窗",
        Manifest.permission.POST_NOTIFICATIONS to "通知",
        Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS to "忽略电池优化",
        Manifest.permission.QUERY_ALL_PACKAGES to "查询应用列表",
        Manifest.permission.REQUEST_INSTALL_PACKAGES to "安装应用",
        Manifest.permission.READ_CALENDAR to "读取日历",
        Manifest.permission.WRITE_CALENDAR to "写入日历",
        Manifest.permission.SEND_SMS to "发送短信",
        Manifest.permission.READ_SMS to "读取短信",
        Manifest.permission.READ_CONTACTS to "读取联系人",
        Manifest.permission.READ_CALL_LOG to "读取通话记录",
        Manifest.permission.CALL_PHONE to "拨打电话",
    )

    private val PERMISSION_GUIDE = mapOf(
        Manifest.permission.POST_NOTIFICATIONS to "Android 13+ 通知权限。安装后默认禁止，必须手动授权。" +
            "使用 sys.permission.request POST_NOTIFICATIONS 弹出授权对话框。",
        Manifest.permission.CAMERA to "相机权限。使用 sys.permission.request CAMERA 申请。",
        Manifest.permission.ACCESS_FINE_LOCATION to "GPS 精确定位。使用 sys.permission.request ACCESS_FINE_LOCATION 申请。",
        Manifest.permission.RECORD_AUDIO to "录音权限。使用 sys.permission.request RECORD_AUDIO 申请。",
        Manifest.permission.SEND_SMS to "发送短信权限 (sys.sms.send)。使用 sys.permission.request SEND_SMS 申请。",
        Manifest.permission.READ_SMS to "读取短信权限 (sys.sms.list)。使用 sys.permission.request READ_SMS 申请。",
        Manifest.permission.READ_CONTACTS to "读取联系人权限 (sys.contacts.list)。使用 sys.permission.request READ_CONTACTS 申请。",
        Manifest.permission.READ_CALL_LOG to "读取通话记录权限 (sys.calllog.list)。使用 sys.permission.request READ_CALL_LOG 申请。",
        Manifest.permission.CALL_PHONE to "拨打电话权限 (sys.phone.call)。使用 sys.permission.request CALL_PHONE 申请。",
    )

    private fun requireApp(): Context? = SysExecutor.appContext

    suspend fun permissionList(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = requireApp() ?: return ExecutionResult.fail("SysExecutor not initialized")
        // P2 修复 (双源漂移): 原内联 perms 列表是 PERMISSION_LABELS 的手写子集,
        // 缺 ACCESS_COARSE_LOCATION/READ_MEDIA_IMAGES/READ_CALENDAR/WRITE_CALENDAR/
        // REQUEST_INSTALL_PACKAGES 共 6 项 — 两处各改各的必漂移。
        // 现统一以 PERMISSION_LABELS 为唯一输出清单 (已按 Manifest 对齐, 见维护规则注释)。
        val perms = PERMISSION_LABELS.toList()
        return ExecutionResult.ok(buildString {
            appendLine("| 权限 | 说明 | 状态 |")
            appendLine("|------|------|------|")
            perms.forEach { (perm, desc) ->
                val status = when {
                    perm in SETTINGS_PERMISSIONS -> "需单独申请"
                    Build.VERSION.SDK_INT < 23 -> "已授予 (API<23)"
                    perm == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33 -> "无需 (API<33)"
                    app.checkSelf(perm) -> "✅ 已授予"
                    else -> "⛔ 未授予"
                }
                appendLine("| $perm | $desc | $status |")
            }
            appendLine()
            if (Build.VERSION.SDK_INT >= 33 && !app.checkSelf(Manifest.permission.POST_NOTIFICATIONS)) {
                appendLine("💡 通知权限未授予。Agent 发送通知前请先执行: sys.permission.request POST_NOTIFICATIONS")
            }
        })
    }

    suspend fun permissionCheck(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = requireApp() ?: return ExecutionResult.fail("SysExecutor not initialized")
        val perm = args.firstOrNull() ?: return ExecutionResult.fail(
            "Usage: sys.permission.check <permission_name>\n示例: sys.permission.check POST_NOTIFICATIONS",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val desc = PERMISSION_LABELS[perm] ?: perm
        val status = when {
            perm in SETTINGS_PERMISSIONS -> "需单独申请（系统设置）"
            Build.VERSION.SDK_INT < 23 -> "已授予 (API<23, 安装时授权)"
            perm == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33 -> "无需 (Android 12-, 安装时授权)"
            app.checkSelf(perm) -> "✅ 已授予"
            else -> "⛔ 未授予"
        }
        val guide = PERMISSION_GUIDE[perm]
        val guideText = if (guide != null) "\n说明: $guide" else ""
        val actionText = if (!app.checkSelf(perm) && perm in DIALOG_PERMISSIONS) {
            "\n操作: sys.permission.request $perm"
        } else if (!app.checkSelf(perm) && perm in SETTINGS_PERMISSIONS) {
            "\n操作: sys.permission.request $perm (将打开系统设置页)"
        } else ""
        return ExecutionResult.ok("$desc ($perm): $status$guideText$actionText")
    }

    suspend fun permissionRequest(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = requireApp() ?: return ExecutionResult.fail("SysExecutor not initialized")
        val perm = args.firstOrNull() ?: return ExecutionResult.fail(
            "Usage: sys.permission.request <permission_name>\n" +
            "常用权限: POST_NOTIFICATIONS (通知), CAMERA (相机), ACCESS_FINE_LOCATION (定位)",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)

        if (perm !in SETTINGS_PERMISSIONS && Build.VERSION.SDK_INT >= 23 && app.checkSelf(perm)) {
            val desc = PERMISSION_LABELS[perm] ?: perm
            return ExecutionResult.ok("$desc ($perm): ✅ 已授予，无需再次申请")
        }

        if (perm == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33) {
            return ExecutionResult.ok("通知权限: Android 12- 安装时自动授予，无需申请")
        }

        fun openAppSettings(): ExecutionResult {
            return try {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${app.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                app.startActivity(intent)
                val desc = PERMISSION_LABELS[perm] ?: perm
                ExecutionResult.ok("已打开应用设置页。请手动授予 '$desc' ($perm) 权限。")
            } catch (e: Exception) {
                ExecutionResult.fail("无法打开设置页: ${e.message}")
            }
        }

        if (perm in DIALOG_PERMISSIONS) {
            val activity = SysExecutor.currentActivity?.get()
            if (activity == null) return openAppSettings()
            return try {
                ActivityCompat.requestPermissions(activity, arrayOf(perm), PERM_REQUEST_CODE)
                val desc = PERMISSION_LABELS[perm] ?: perm
                ExecutionResult.ok("已弹出系统权限对话框: $desc\n请在弹窗中选择'允许'。授权后可用 sys.permission.check $perm 确认。")
            } catch (e: Exception) {
                return try {
                    val intent = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS.let {
                        Intent(it).apply {
                            data = Uri.parse("package:${app.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                    app.startActivity(intent)
                    ExecutionResult.ok("无法弹出权限对话框 (${e.message})。已打开应用设置页，请手动授予 '$perm' 权限。")
                } catch (e2: Exception) {
                    ExecutionResult.fail("权限请求失败: ${e2.message}")
                }
            }
        }

        return openAppSettings()
    }
}
