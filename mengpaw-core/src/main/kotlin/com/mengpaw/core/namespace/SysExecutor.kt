// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

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
 * ## Command groups (51 commands)
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
 * sys.browser.open   打开浏览器/网页
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

    /** sys.* 命令中文同义词表 — 补 BM25 搜索索引用 (kernel 静态种子无 sys, 反射 Android API)。
     *  缺失项回退命令名分词。 */
    private val SYS_KEYWORDS_ZH = mapOf(
        "device" to listOf("设备", "型号", "厂商", "系统", "安卓"),
        "battery" to listOf("电量", "电池", "充电", "温度", "续航"),
        "power" to listOf("电源", "省电", "功耗", "充电"),
        "power.save" to listOf("省电", "省电模式", "功耗", "节能"),
        "network" to listOf("网络", "信号", "数据", "蜂窝"),
        "wifi" to listOf("WIFI", "无线", "热点"),
        "wifi.enable" to listOf("开WIFI", "关WIFI", "切换无线"),
        "bluetooth" to listOf("蓝牙", "配对"),
        "location" to listOf("定位", "位置", "GPS", "坐标"),
        "cpu" to listOf("CPU", "处理器", "占用", "性能"),
        "memory" to listOf("内存", "RAM", "占用"),
        "storage" to listOf("存储", "空间", "磁盘"),
        "camera" to listOf("相机", "摄像头", "拍照"),
        "sensors" to listOf("传感器", "陀螺仪", "加速计"),
        "display" to listOf("屏幕", "分辨率", "亮度", "显示"),
        "telephony" to listOf("电话", "运营商", "SIM", "信号"),
        "screen.on" to listOf("亮屏", "唤醒屏幕", "开屏"),
        "screen.off" to listOf("熄屏", "关屏", "锁屏"),
        "screen.brightness" to listOf("亮度", "调亮度", "屏幕亮度"),
        "volume" to listOf("音量", "声音", "媒体音量"),
        "volume.set" to listOf("调音量", "设置音量", "静音"),
        "vibrate" to listOf("震动", "振动", "马达"),
        "ringtone.play" to listOf("铃声", "播放铃声", "响铃"),
        "apps" to listOf("应用", "应用列表", "已安装", "APP"),
        "app.launch" to listOf("打开应用", "启动", "启动应用", "运行"),
        "app.uninstall" to listOf("卸载应用", "卸载"),
        "app.info" to listOf("应用详情", "应用信息"),
        "browser.open" to listOf("打开浏览器", "浏览器", "网页", "唤起"),
        "clipboard" to listOf("剪贴板", "复制", "粘贴", "内容"),
        "clipboard.set" to listOf("写剪贴板", "复制内容"),
        "intent.open" to listOf("打开链接", "intent", "跳转", "协议"),
        "intent.share" to listOf("分享", "分享文本", "转发"),
        "intent.view" to listOf("查看", "打开文件"),
        "notification.id" to listOf("通知ID", "通知标识"),
        "notification.send" to listOf("发通知", "通知", "推送消息"),
        "notification.cancel" to listOf("取消通知", "清除通知"),
        "alarm.set" to listOf("闹钟", "定时提醒", "设置闹钟"),
        "permission.list" to listOf("权限列表", "权限", "已授权"),
        "permission.check" to listOf("检查权限", "权限状态"),
        "permission.request" to listOf("申请权限", "请求权限", "授权"),
        "overlay.show" to listOf("悬浮窗", "显示悬浮窗", "弹窗"),
        "overlay.hide" to listOf("隐藏悬浮窗", "关闭悬浮窗"),
        "overlay.update" to listOf("更新悬浮窗", "刷新悬浮窗"),
        "calendar.add" to listOf("日历", "添加事件", "日程", "提醒"),
        "calendar.list" to listOf("日历列表", "日程", "查看事件"),
        "calendar.delete" to listOf("删除事件", "删除日程"),
        "calendar.calendars" to listOf("日历账户", "日历列表"),
        "screenshot" to listOf("截图", "截屏", "屏幕快照"),
        "screenrecord.start" to listOf("录屏", "屏幕录制", "录视频", "开始录屏"),
        "screenrecord.stop" to listOf("停止录屏", "结束录屏", "保存视频"),
        "camera.photo" to listOf("拍照", "拍摄", "照片", "相机拍照")
    )

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
        "browser.open" to AppExecutor::browserOpen,
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

    init {
        // FIX(自检报告 P0-1): sys.* 命令补搜索索引 — kernel 静态种子无 sys (Android 反射实现),
        // 此前"日历/屏幕/录音"等自然语言搜不到真实存在的命令, Agent 只能盲猜。
        // 可用性过滤由 self.search 按注册表执行 (过滤层保证停用插件命令不外泄)。
        try {
            commands.keys.forEach { name ->
                com.mengpaw.kernel.cli.CommandSearch.registerOrUpdate(
                    com.mengpaw.kernel.cli.CommandIndex(
                        fullName = "sys.$name",
                        namespace = "sys",
                        description = "系统命令 (Android 设备能力)",
                        usage = "sys.$name",
                        zhKeywords = SYS_KEYWORDS_ZH[name] ?: name.split("."),
                        enKeywords = name.split(".")
                    )
                )
            }
        } catch (_: Exception) { /* 索引注册尽力而为 */ }
    }

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
