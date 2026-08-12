// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import android.Manifest

/**
 * sys 命令权限映射 (供 UI / 设置展示)。从 SysExecutor 拆出以守单文件 ≤400 行红线。
 * 维护规则: 与 mengpaw-shell/AndroidManifest.xml 的 <uses-permission> 保持一一对应。
 */
internal val SYS_PERMISSION_MAP: Map<String, String> = mapOf(
    "sys.location" to Manifest.permission.ACCESS_FINE_LOCATION,
    "sys.camera" to Manifest.permission.CAMERA,
    "sys.apps" to Manifest.permission.QUERY_ALL_PACKAGES,
    "sys.camera.photo" to Manifest.permission.CAMERA,
    "sys.telephony" to Manifest.permission.READ_PHONE_STATE,
    "sys.notification.send" to Manifest.permission.POST_NOTIFICATIONS,
    "sys.notification.id" to Manifest.permission.POST_NOTIFICATIONS,
    "sys.overlay.show" to Manifest.permission.SYSTEM_ALERT_WINDOW,
    "sys.calendar.add" to Manifest.permission.WRITE_CALENDAR,
    "sys.calendar.delete" to Manifest.permission.WRITE_CALENDAR,
    "sys.calendar.list" to Manifest.permission.READ_CALENDAR,
    "sys.calendar.calendars" to Manifest.permission.READ_CALENDAR,
    "sys.dialog.speech" to Manifest.permission.RECORD_AUDIO,
    "sys.stt.listen" to Manifest.permission.RECORD_AUDIO,
    "sys.mic.record" to Manifest.permission.RECORD_AUDIO,
    "sys.mic.stop" to Manifest.permission.RECORD_AUDIO,
    "sys.wifi.scan" to Manifest.permission.ACCESS_FINE_LOCATION,
    "sys.contacts.list" to Manifest.permission.READ_CONTACTS,
    "sys.sms.send" to Manifest.permission.SEND_SMS,
    "sys.sms.list" to Manifest.permission.READ_SMS,
    "sys.calllog.list" to Manifest.permission.READ_CALL_LOG,
    "sys.phone.call" to Manifest.permission.CALL_PHONE,
    "sys.wallpaper.set" to Manifest.permission.SET_WALLPAPER
)
