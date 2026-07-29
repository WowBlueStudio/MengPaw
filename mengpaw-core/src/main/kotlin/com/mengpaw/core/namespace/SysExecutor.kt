// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.namespace

import android.Manifest
import android.app.Activity
import android.content.Context
import androidx.core.content.FileProvider
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import java.lang.ref.WeakReference
import com.mengpaw.core.namespace.sys.AppExecutor
import com.mengpaw.core.namespace.sys.BatteryPowerExecutor
import com.mengpaw.core.namespace.sys.CalendarExecutor
import com.mengpaw.core.namespace.sys.ClipboardIntentExecutor
import com.mengpaw.core.namespace.sys.DeviceExecutor
import com.mengpaw.core.namespace.sys.MediaExecutor
import com.mengpaw.core.namespace.sys.NetworkExecutor
import com.mengpaw.core.namespace.sys.NotificationExecutor
import com.mengpaw.core.namespace.sys.OverlayExecutor
import com.mengpaw.core.namespace.sys.PermissionExecutor
import com.mengpaw.core.namespace.sys.ScreenCaptureExecutor
import com.mengpaw.core.namespace.sys.SensorLocationExecutor

/**
 * Android system executor — exposes real device capabilities to Agent.
 *
 * ## Command groups (55+ commands)
 * Delegates to domain executors in [com.mengpaw.core.namespace.sys].
 *
 * ```
 * sys.device         设备信息
 * sys.battery        电量
 * sys.network        网络类型
 * sys.wifi            WiFi 详情
 * sys.wifi.enable    WiFi 开关
 * sys.bluetooth      蓝牙状态
 * sys.location       GPS 定位
 * sys.cpu            CPU 使用率
 * sys.memory         RAM 用量
 * sys.storage        存储空间
 * sys.camera         摄像头信息
 * sys.sensors        传感器列表
 * sys.display        屏幕
 * sys.screen.on      亮屏
 * sys.screen.off     熄屏
 * sys.screen.brightness  设置亮度
 * sys.screenshot     截图
 * sys.screenrecord.start  开始录屏
 * sys.screenrecord.stop   停止录屏
 * sys.camera.photo   拍照
 * sys.volume         音量
 * sys.volume.set     设置音量
 * sys.apps           已安装应用
 * sys.app.launch     启动应用
 * sys.app.uninstall  卸载应用
 * sys.app.info       应用详情
 * sys.power          电源状态
 * sys.power.save     省电模式
 * sys.clipboard      读取剪贴板
 * sys.clipboard.set  写入剪贴板
 * sys.telephony      电话信息
 * sys.vibrate        震动
 * sys.intent.open    打开链接/应用
 * sys.intent.share   分享文本
 * sys.intent.view    查看文件
 * sys.notification.id     通知渠道ID
 * sys.notification.send   发送通知
 * sys.notification.cancel 取消通知
 * sys.permission.list     列出权限
 * sys.permission.check    检查权限
 * sys.permission.request  申请权限
 * sys.ringtone.play       播放铃声
 * sys.alarm.set           设置闹钟
 * sys.overlay.show        显示悬浮窗
 * sys.overlay.hide        隐藏悬浮窗
 * sys.overlay.update      更新悬浮窗
 * sys.calendar.add        添加日历事件
 * sys.calendar.list       列出日历事件
 * sys.calendar.delete     删除日历事件
 * sys.calendar.calendars  列出日历账户
 * ```
 */
object SysExecutor {
    @Volatile
    internal var appContext: Context? = null

    internal var currentActivity: WeakReference<Activity>? = null

    /** Must be called once at app startup. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Set the current Activity reference for runtime permission dialogs. Uses WeakReference to prevent leaks. */
    fun setActivity(activity: Activity?) {
        currentActivity = activity?.let { WeakReference(it) }
    }

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        // ── Device & system info ──
        "device" to DeviceExecutor::device,
        "battery" to BatteryPowerExecutor::battery,
        "network" to NetworkExecutor::network,
        "wifi" to NetworkExecutor::wifi,
        "wifi.enable" to NetworkExecutor::wifiEnable,
        "bluetooth" to NetworkExecutor::bluetooth,
        "location" to SensorLocationExecutor::location,
        "cpu" to SensorLocationExecutor::cpu,
        "memory" to SensorLocationExecutor::memory,
        "storage" to SensorLocationExecutor::storage,
        "camera" to MediaExecutor::camera,
        "sensors" to SensorLocationExecutor::sensors,
        "display" to DeviceExecutor::display,
        "telephony" to SensorLocationExecutor::telephony,
        "power" to BatteryPowerExecutor::power,
        "power.save" to BatteryPowerExecutor::powerSave,
        // ── Screen control ──
        "screen.on" to DeviceExecutor::screenOn,
        "screen.off" to DeviceExecutor::screenOff,
        "screen.brightness" to DeviceExecutor::screenBrightness,
        // ── Audio / volume / vibrate ──
        "volume" to MediaExecutor::volume,
        "volume.set" to MediaExecutor::volumeSet,
        "vibrate" to MediaExecutor::vibrate,
        "ringtone.play" to MediaExecutor::ringtonePlay,
        // ── App management ──
        "apps" to AppExecutor::apps,
        "app.launch" to AppExecutor::appLaunch,
        "app.uninstall" to AppExecutor::appUninstall,
        "app.info" to AppExecutor::appInfo,
        // ── Clipboard ──
        "clipboard" to ClipboardIntentExecutor::clipboard,
        "clipboard.set" to ClipboardIntentExecutor::clipboardSet,
        // ── Intent / share ──
        "intent.open" to ClipboardIntentExecutor::intentOpen,
        "intent.share" to ClipboardIntentExecutor::intentShare,
        "intent.view" to ClipboardIntentExecutor::intentView,
        // ── Notifications / alarm ──
        "notification.id" to NotificationExecutor::notificationId,
        "notification.send" to NotificationExecutor::notificationSend,
        "notification.cancel" to NotificationExecutor::notificationCancel,
        "alarm.set" to NotificationExecutor::alarmSet,
        // ── Permissions ──
        "permission.list" to PermissionExecutor::permissionList,
        "permission.check" to PermissionExecutor::permissionCheck,
        "permission.request" to PermissionExecutor::permissionRequest,
        // ── Overlay (floating window) ──
        "overlay.show" to OverlayExecutor::overlayShow,
        "overlay.hide" to OverlayExecutor::overlayHide,
        "overlay.update" to OverlayExecutor::overlayUpdate,
        // ── Calendar ──
        "calendar.add" to CalendarExecutor::calendarAdd,
        "calendar.list" to CalendarExecutor::calendarList,
        "calendar.delete" to CalendarExecutor::calendarDelete,
        "calendar.calendars" to CalendarExecutor::calendarCalendars,
        // ── Media capture ──
        "screenshot" to ScreenCaptureExecutor::screenshot,
        "screenrecord.start" to ScreenCaptureExecutor::screenRecordStart,
        "screenrecord.stop" to ScreenCaptureExecutor::screenRecordStop,
        "camera.photo" to ScreenCaptureExecutor::cameraPhoto,
    )

    /** Permission map for sys commands (used by UI / settings). */
    val PERMISSION_MAP = mapOf(
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
    )
}
