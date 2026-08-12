// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace

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
import com.mengpaw.core.namespace.sys.DialogExecutor
import com.mengpaw.core.namespace.sys.DownloadWallpaperExecutor
import com.mengpaw.core.namespace.sys.ContactsSmsExecutor
import com.mengpaw.core.namespace.sys.MediaExecutor
import com.mengpaw.core.namespace.sys.MicExecutor
import com.mengpaw.core.namespace.sys.MiscExecutor
import com.mengpaw.core.namespace.sys.NetworkExecutor
import com.mengpaw.core.namespace.sys.NotificationExecutor
import com.mengpaw.core.namespace.sys.OverlayExecutor
import com.mengpaw.core.namespace.sys.PermissionExecutor
import com.mengpaw.core.namespace.sys.ScreenCaptureExecutor
import com.mengpaw.core.namespace.sys.SensorLocationExecutor
import com.mengpaw.core.namespace.sys.SpeechExecutor
import com.mengpaw.core.namespace.sys.SYS_KEYWORDS_ZH
import com.mengpaw.core.namespace.sys.SYS_PERMISSION_MAP
import com.mengpaw.core.namespace.sys.TorchExecutor
import com.mengpaw.core.namespace.sys.TtsExecutor

/**
 * Android system executor — exposes real device capabilities to Agent.
 *
 * ## Command groups (85 commands)
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
 * sys.dialog.confirm 确认对话框
 * sys.dialog.text    文本输入对话框
 * sys.dialog.radio   单选对话框
 * sys.dialog.checkbox 多选对话框
 * sys.dialog.spinner 下拉选择对话框
 * sys.dialog.sheet   底部选项列表
 * sys.dialog.date    日期选择
 * sys.dialog.time    时间选择
 * sys.dialog.counter 数值选择
 * sys.dialog.color   颜色选择
 * sys.dialog.speech  语音输入对话框
 * sys.tts.speak      文字转语音朗读
 * sys.tts.engines    列出 TTS 引擎
 * sys.stt.listen     语音转文字
 * sys.mic.record     麦克风录音
 * sys.mic.stop       停止录音
 * sys.torch.on       打开手电筒
 * sys.torch.off      关闭手电筒
 * sys.notification.list 读取系统通知
 * sys.contacts.list  联系人列表
 * sys.sms.send       发送短信
 * sys.sms.list       短信收件箱
 * sys.calllog.list   通话记录
 * sys.phone.call     拨打电话
 * sys.download       下载文件
 * sys.download.status  查询下载任务状态
 * sys.wallpaper.set  设置壁纸
 * sys.toast          气泡提示
 * sys.wakelock.acquire  获取唤醒锁
 * sys.wakelock.release  释放唤醒锁
 * sys.ir.transmit    红外发射
 * sys.usb.list       USB 设备列表
 * sys.usb.request    USB 访问授权
 * sys.wifi.scan      WiFi 热点扫描
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

    /** Set the current Activity reference for runtime permission dialogs. Uses WeakReference to prevent leaks. */
    fun setActivity(activity: Activity?) {
        currentActivity = activity?.let { WeakReference(it) }
    }

    // ── MediaProjection 授权桥 (P1 修复, 9d 审查) ──
    // sys.screenshot/screenrecord 走 MediaProjection (免 root): MainActivity 在
    // onCreate 注册 ActivityResultLauncher 并经 [setProjectionLauncher] 注入;
    // 命令协程经 [requestProjection] 挂起等待用户授权结果, 超时默认拒绝。
    @Volatile
    private var projectionLauncher: ((android.content.Intent) -> Unit)? = null

    private var projectionRequest: kotlinx.coroutines.CompletableDeferred<Pair<Int, android.content.Intent?>>? = null
    private val projectionLock = Any()

    /** MainActivity 注入投影授权 launcher (ActivityResultLauncher 必须在 Activity 启动前注册). */
    fun setProjectionLauncher(launcher: (android.content.Intent) -> Unit) {
        projectionLauncher = launcher
    }

    /** MainActivity launcher 回调转发 — 完成等待中的授权请求. */
    fun onProjectionResult(resultCode: Int, data: android.content.Intent?) {
        synchronized(projectionLock) {
            projectionRequest?.complete(resultCode to data)
            projectionRequest = null
        }
    }

    /** 发起屏幕捕获授权并等待用户结果. @return (resultCode, data) 或 null (超时/无 launcher/已在途). */
    internal suspend fun requestProjection(
        intent: android.content.Intent, timeoutMs: Long
    ): Pair<Int, android.content.Intent?>? {
        val launcher = projectionLauncher ?: return null
        val deferred = kotlinx.coroutines.CompletableDeferred<Pair<Int, android.content.Intent?>>()
        synchronized(projectionLock) {
            if (projectionRequest != null) return null // 已有授权请求在途, 拒绝并发
            projectionRequest = deferred
        }
        launcher(intent)
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { deferred.await() }
    }

    // ── 语音识别授权桥 (sys.stt.listen / sys.dialog.speech) ──
    // 与 projection 桥同模式: MainActivity 注册 ActivityResultLauncher 并经
    // [setSpeechLauncher] 注入; 命令协程经 [requestSpeech] 挂起等待识别结果。
    @Volatile
    private var speechLauncher: ((android.content.Intent) -> Unit)? = null

    private var speechRequest: kotlinx.coroutines.CompletableDeferred<Pair<Int, android.content.Intent?>>? = null
    private val speechLock = Any()

    /** MainActivity 注入语音识别 launcher (ActivityResultLauncher 必须在 Activity 启动前注册). */
    fun setSpeechLauncher(launcher: (android.content.Intent) -> Unit) {
        speechLauncher = launcher
    }

    /** MainActivity launcher 回调转发 — 完成等待中的识别请求. */
    fun onSpeechResult(resultCode: Int, data: android.content.Intent?) {
        synchronized(speechLock) {
            speechRequest?.complete(resultCode to data)
            speechRequest = null
        }
    }

    /** 发起系统语音识别并等待用户结果. @return (resultCode, data) 或 null (超时/无 launcher/已在途). */
    internal suspend fun requestSpeech(
        intent: android.content.Intent, timeoutMs: Long
    ): Pair<Int, android.content.Intent?>? {
        val launcher = speechLauncher ?: return null
        val deferred = kotlinx.coroutines.CompletableDeferred<Pair<Int, android.content.Intent?>>()
        synchronized(speechLock) {
            if (speechRequest != null) return null // 已有识别请求在途, 拒绝并发
            speechRequest = deferred
        }
        launcher(intent)
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { deferred.await() }
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
        // ── Dialog (user interaction) ──
        "dialog.confirm" to DialogExecutor::confirm,
        "dialog.text" to DialogExecutor::text,
        "dialog.radio" to DialogExecutor::radio,
        "dialog.checkbox" to DialogExecutor::checkbox,
        "dialog.spinner" to DialogExecutor::spinner,
        "dialog.sheet" to DialogExecutor::sheet,
        "dialog.date" to DialogExecutor::date,
        "dialog.time" to DialogExecutor::time,
        "dialog.counter" to DialogExecutor::counter,
        "dialog.color" to DialogExecutor::color,
        "dialog.speech" to DialogExecutor::speech,
        // ── Speech / TTS / Mic / Torch ──
        "stt.listen" to SpeechExecutor::sttListen,
        "tts.speak" to TtsExecutor::ttsSpeak,
        "tts.engines" to TtsExecutor::ttsEngines,
        "mic.record" to MicExecutor::record,
        "mic.stop" to MicExecutor::stop,
        "torch.on" to TorchExecutor::torchOn,
        "torch.off" to TorchExecutor::torchOff,
        // ── Sensitive data (短信/联系人/通话/拨号) ──
        "contacts.list" to ContactsSmsExecutor::contactsList,
        "sms.send" to ContactsSmsExecutor::smsSend,
        "sms.list" to ContactsSmsExecutor::smsList,
        "calllog.list" to ContactsSmsExecutor::callLogList,
        "phone.call" to ContactsSmsExecutor::phoneCall,
        // ── Misc device (通知列表/下载/壁纸/toast/wakelock/红外/USB/WiFi 扫描) ──
        "notification.list" to NotificationExecutor::notificationList,
        "download" to DownloadWallpaperExecutor::download,
        "download.status" to DownloadWallpaperExecutor::downloadStatus,
        "wallpaper.set" to DownloadWallpaperExecutor::wallpaperSet,
        "toast" to MiscExecutor::toast,
        "wakelock.acquire" to MiscExecutor::wakelockAcquire,
        "wakelock.release" to MiscExecutor::wakelockRelease,
        "ir.transmit" to MiscExecutor::irTransmit,
        "usb.list" to MiscExecutor::usbList,
        "usb.request" to MiscExecutor::usbRequest,
        "wifi.scan" to NetworkExecutor::wifiScan,
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
    val PERMISSION_MAP: Map<String, String> = SYS_PERMISSION_MAP
}
