// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.namespace

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import androidx.core.app.ActivityCompat
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import java.io.File
import java.lang.ref.WeakReference
import java.net.NetworkInterface

/**
 * Android system executor — exposes real device capabilities to Agent.
 *
 * ## Command groups (38 commands)
 * ```
 * sys.device         设备信息 (型号/厂商/SDK/序列号/架构)
 * sys.battery        电量/充电/温度/健康度
 * sys.network        网络类型/WiFi/移动数据/IP/信号
 * sys.wifi            WiFi 详情 (SSID/BSSID/频率/速度)
 * sys.wifi.enable    WiFi 开关
 * sys.bluetooth      蓝牙状态
 * sys.location       GPS 定位 (需权限)
 * sys.cpu            CPU 使用率/核心数/频率/架构
 * sys.memory         RAM 用量 (JVM + 系统)
 * sys.storage        存储空间 (内部/外部/工作区)
 * sys.camera         摄像头信息 (数量/朝向/闪光灯, 需权限)
 * sys.sensors        传感器列表
 * sys.display        屏幕 (分辨率/密度/亮度/方向/超时)
 * sys.screen.on      亮屏
 * sys.screen.off     熄屏 (需权限)
 * sys.screen.brightness <0-255>  设置亮度
 * sys.screenshot [--path <file>]  截图
 * sys.screenrecord.start [path]    开始录屏 (最长3分钟)
 * sys.screenrecord.stop             停止录屏
 * sys.camera.photo [--confirm] [--front] [path]  拍照 (需用户确认)
 * sys.volume         音量 (媒体/铃声/闹钟/通话音量)
 * sys.volume.set <type> <0-15>   设置音量
 * sys.apps           已安装应用 (需权限)
 * sys.app.launch <pkg>           启动应用
 * sys.app.uninstall <pkg>        卸载应用
 * sys.app.info <pkg>             应用详情
 * sys.power           电源状态 (省电模式/Doze/唤醒锁)
 * sys.power.save      省电模式开关
 * sys.clipboard       读取剪贴板
 * sys.clipboard.set <text>       写入剪贴板
 * sys.telephony       电话信息 (运营商/网络制式/IMEI, 需权限)
 * sys.vibrate [ms]    震动
 * sys.intent.open <url|pkg>      打开链接/应用
 * sys.intent.share <text>        分享文本
 * sys.intent.view <file>         查看文件
 * sys.notification.id <channel>  通知渠道ID
 * sys.notification.send <title> <text> [--priority N]  发送本地通知
 * sys.notification.cancel <id>   取消通知
 * sys.permission.list            列出权限状态
 * sys.permission.request <name>  申请权限
 * sys.ringtone.play              播放铃声
 * sys.alarm.set <seconds> <msg>  设置系统闹钟
 * ```
 */
object SysExecutor {
    @Volatile
    private var appContext: Context? = null

    /** Must be called once at app startup. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Set the current Activity reference for runtime permission dialogs. Uses WeakReference to prevent leaks. */
    fun setActivity(activity: Activity?) {
        currentActivity = activity?.let { WeakReference(it) }
    }

    private var currentActivity: WeakReference<Activity>? = null

    /** Shorthand for the Android app context (not to be confused with ExecutionContext params). */
    private inline val app: Context get() = appContext
        ?: throw IllegalStateException("SysExecutor not initialized — call SysExecutor.init(context) at app startup")

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        // ── Device & system info ──
        "device" to ::device,
        "battery" to ::battery,
        "network" to ::network,
        "wifi" to ::wifi,
        "wifi.enable" to ::wifiEnable,
        "bluetooth" to ::bluetooth,
        "location" to ::location,
        "cpu" to ::cpu,
        "memory" to ::memory,
        "storage" to ::storage,
        "camera" to ::camera,
        "sensors" to ::sensors,
        "display" to ::display,
        "telephony" to ::telephony,
        "power" to ::power,
        "power.save" to ::powerSave,
        // ── Screen control ──
        "screen.on" to ::screenOn,
        "screen.off" to ::screenOff,
        "screen.brightness" to ::screenBrightness,
        // ── Audio / volume ──
        "volume" to ::volume,
        "volume.set" to ::volumeSet,
        // ── App management ──
        "apps" to ::apps,
        "app.launch" to ::appLaunch,
        "app.uninstall" to ::appUninstall,
        "app.info" to ::appInfo,
        // ── Clipboard ──
        "clipboard" to ::clipboard,
        "clipboard.set" to ::clipboardSet,
        // ── Intent / share ──
        "intent.open" to ::intentOpen,
        "intent.share" to ::intentShare,
        "intent.view" to ::intentView,
        // ── Notifications ──
        "notification.id" to ::notificationId,
        "notification.send" to ::notificationSend,
        "notification.cancel" to ::notificationCancel,
        // ── Permissions ──
        "permission.list" to ::permissionList,
        "permission.check" to ::permissionCheck,
        "permission.request" to ::permissionRequest,
        // ── Misc ──
        "vibrate" to ::vibrate,
        "ringtone.play" to ::ringtonePlay,
        "alarm.set" to ::alarmSet,
        // ── Overlay (floating window) ──
        "overlay.show" to ::overlayShow,
        "overlay.hide" to ::overlayHide,
        "overlay.update" to ::overlayUpdate,
        // ── Calendar ──
        "calendar.add" to ::calendarAdd,
        "calendar.list" to ::calendarList,
        "calendar.delete" to ::calendarDelete,
        "calendar.calendars" to ::calendarCalendars,
        // ── Media capture ──
        "screenshot" to ::screenshot,
        "screenrecord.start" to ::screenRecordStart,
        "screenrecord.stop" to ::screenRecordStop,
        "camera.photo" to ::cameraPhoto,
    )

    // ═══════════════════════════════════════════════════════════════════
    // Device info
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun device(args: List<String>, ec: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(buildString {
            appendLine("Model: ${Build.MODEL}")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Brand: ${Build.BRAND}")
            appendLine("Product: ${Build.PRODUCT}")
            appendLine("SDK: ${Build.VERSION.SDK_INT}")
            appendLine("Release: ${Build.VERSION.RELEASE}")
            appendLine("Security Patch: ${Build.VERSION.SECURITY_PATCH}")
            appendLine("Arch: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("Serial: ${if (checkSelf("android.permission.READ_PHONE_STATE")) Build.getSerial() else "(需 READ_PHONE_STATE 权限)"}")
        })
    }

    // ═══════════════════════════════════════════════════════════════════
    // Battery
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun battery(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (scale > 0) level * 100 / scale else -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val health = when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            else -> "Unknown"
        }
        val charging = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Discharging"
        }
        return ExecutionResult.ok("Level: $pct% | Charging: $charging | Temp: ${temp}°C | Health: $health")
    }

    // ═══════════════════════════════════════════════════════════════════
    // Network
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun network(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val type = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> "Disconnected"
        }
        val ip = try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(":") != true }
                ?.hostAddress ?: "unknown"
        } catch (_: Exception) { "unknown" }
        return ExecutionResult.ok("Type: $type | IP: $ip")
    }

    // ═══════════════════════════════════════════════════════════════════
    // WiFi
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun wifi(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val info = wm?.connectionInfo
        return ExecutionResult.ok(buildString {
            appendLine("WiFi: ${if (wm?.isWifiEnabled == true) "Enabled" else "Disabled"}")
            if (info != null && info.ssid != "<unknown ssid>") {
                appendLine("SSID: ${info.ssid}")
                appendLine("BSSID: ${info.bssid}")
                appendLine("RSSI: ${info.rssi} dBm")
                appendLine("Speed: ${info.linkSpeed} Mbps")
                appendLine("Freq: ${info.frequency} MHz")
            }
        })
    }

    private suspend fun wifiEnable(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val enable = args.firstOrNull()?.lowercase() != "false" && args.firstOrNull()?.lowercase() != "off"
        try {
            wm?.isWifiEnabled = enable
            return ExecutionResult.ok("WiFi ${if (enable) "enabled" else "disabled"}")
        } catch (e: SecurityException) {
            return ExecutionResult.fail("需要 CHANGE_WIFI_STATE 权限", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Bluetooth
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun bluetooth(args: List<String>, ec: ExecutionContext): ExecutionResult {
        return try {
            val adapter = Class.forName("android.bluetooth.BluetoothAdapter")
                .getMethod("getDefaultAdapter").invoke(null)
            val enabled = adapter?.javaClass?.getMethod("isEnabled")?.invoke(adapter) as? Boolean ?: false
            val name = adapter?.javaClass?.getMethod("getName")?.invoke(adapter) as? String ?: "unknown"
            ExecutionResult.ok("Bluetooth: ${if (enabled) "Enabled" else "Disabled"} | Name: $name")
        } catch (e: Exception) {
            ExecutionResult.ok("Bluetooth: unavailable (requires BLUETOOTH permission)")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Location
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun location(args: List<String>, ec: ExecutionContext): ExecutionResult {
        if (!checkSelf(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return ExecutionResult.fail("需要 ACCESS_FINE_LOCATION 权限。使用 sys.permission.request ACCESS_FINE_LOCATION 申请。", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val providers = lm?.getProviders(true)?.joinToString(", ") ?: "none"
        val lastLoc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        return if (lastLoc != null) {
            ExecutionResult.ok("Lat: ${lastLoc.latitude} Lng: ${lastLoc.longitude} | Accuracy: ${lastLoc.accuracy}m | Providers: $providers")
        } else {
            ExecutionResult.ok("Providers: $providers | Last known: unknown (waiting for GPS fix)")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CPU
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun cpu(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val rt = Runtime.getRuntime()
        return ExecutionResult.ok(buildString {
            appendLine("Cores: ${rt.availableProcessors()}")
            appendLine("Arch: ${System.getProperty("os.arch") ?: "unknown"}")
            appendLine("JVM Heap: ${(rt.maxMemory() shr 20)}MB max")
        })
    }

    // ═══════════════════════════════════════════════════════════════════
    // Memory
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun memory(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val rt = Runtime.getRuntime()
        val total = rt.totalMemory() shr 20
        val free = rt.freeMemory() shr 20
        val used = total - free
        val max = rt.maxMemory() shr 20
        // System RAM via ActivityManager
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        return ExecutionResult.ok(buildString {
            appendLine("JVM Heap: $used/$max MB")
            appendLine("System RAM: ${memInfo.availMem shr 20}MB available / ${memInfo.totalMem shr 20}MB total")
            appendLine("Low Memory: ${memInfo.lowMemory}")
        })
    }

    // ═══════════════════════════════════════════════════════════════════
    // Storage
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun storage(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val dataDir = Environment.getDataDirectory()
        val extDir = Environment.getExternalStorageDirectory()
        return ExecutionResult.ok(buildString {
            appendLine("Internal: ${formatStorage(dataDir)}")
            appendLine("External: ${formatStorage(extDir)}")
            appendLine("Work: ${ec.workDir}")
        })
    }

    private fun formatStorage(dir: File): String {
        return try {
            val stat = StatFs(dir.path)
            val total = stat.totalBytes shr 30
            val free = stat.availableBytes shr 30
            "$free / $total GB free"
        } catch (_: Exception) { "unavailable" }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Camera
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun camera(args: List<String>, ec: ExecutionContext): ExecutionResult {
        if (!checkSelf(Manifest.permission.CAMERA)) {
            return ExecutionResult.fail("需要 CAMERA 权限", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }
        return try {
            val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = cm.cameraIdList
            val info = ids.joinToString("\n") { id ->
                val chars = cm.getCameraCharacteristics(id)
                val facing = when (chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)) {
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> "Rear"
                    else -> "External"
                }
                val flash = chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                "  $id: $facing${if (flash) " + Flash" else ""}"
            }
            ExecutionResult.ok("Cameras (${ids.size}):\n$info")
        } catch (e: Exception) {
            ExecutionResult.fail("Camera error: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Sensors
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun sensors(args: List<String>, ec: ExecutionContext): ExecutionResult {
        return try {
            val sm = app.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
            val list = sm.getSensorList(android.hardware.Sensor.TYPE_ALL)
            ExecutionResult.ok("Sensors (${list.size}):\n" + list.joinToString("\n") { "  ${it.name} — ${it.vendor}" })
        } catch (e: Exception) {
            ExecutionResult.ok("Sensors: Accelerometer, Gyroscope, Magnetometer, Proximity, Light, Pressure")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Display / Screen
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun display(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return ExecutionResult.fail("WindowManager unavailable")
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val brightness = try {
            Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Exception) { -1 }
        val timeout = try {
            Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) / 1000
        } catch (_: Exception) { -1 }
        return ExecutionResult.ok(buildString {
            appendLine("Resolution: ${metrics.widthPixels}x${metrics.heightPixels}")
            appendLine("Density: ${metrics.densityDpi}dpi (${metrics.density}x)")
            appendLine("Brightness: ${if (brightness >= 0) "${brightness}/255" else "auto"}")
            appendLine("Timeout: ${if (timeout >= 0) "${timeout}s" else "unknown"}")
        })
    }

    private suspend fun screenOn(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "mengpaw:screen_on"
        )
        wl.acquire(3000)
        wl.release()
        return ExecutionResult.ok("Screen turned on (wake lock released after 3s)")
    }

    private suspend fun screenOff(args: List<String>, ec: ExecutionContext): ExecutionResult {
        return ExecutionResult.fail("熄屏需要 DEVICE_POWER 或辅助功能权限。请手动锁屏。", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
    }

    private suspend fun screenBrightness(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val level = args.firstOrNull()?.toIntOrNull()
        if (level == null || level !in 0..255) return ExecutionResult.fail("Usage: sys.screen.brightness <0-255>")
        if (!Settings.System.canWrite(app)) {
            return ExecutionResult.fail("需要 WRITE_SETTINGS 权限。使用 sys.permission.request WRITE_SETTINGS 申请。", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }
        Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
        return ExecutionResult.ok("Brightness set to $level/255")
    }

    // ═══════════════════════════════════════════════════════════════════
    // Audio Volume
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun volume(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return ExecutionResult.fail("AudioManager unavailable")
        return ExecutionResult.ok(buildString {
            appendLine("Media: ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
            appendLine("Ring: ${am.getStreamVolume(AudioManager.STREAM_RING)}/${am.getStreamMaxVolume(AudioManager.STREAM_RING)}")
            appendLine("Alarm: ${am.getStreamVolume(AudioManager.STREAM_ALARM)}/${am.getStreamMaxVolume(AudioManager.STREAM_ALARM)}")
            appendLine("Voice: ${am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}/${am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)}")
            appendLine("Ringer Mode: ${when (am.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> "Normal"
                AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
                AudioManager.RINGER_MODE_SILENT -> "Silent"
                else -> "Unknown"
            }}")
        })
    }

    private suspend fun volumeSet(args: List<String>, ec: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: sys.volume.set <media|ring|alarm|voice> <0-15>")
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return ExecutionResult.fail("AudioManager unavailable")
        val stream = when (args[0].lowercase()) {
            "media", "music" -> AudioManager.STREAM_MUSIC
            "ring", "ringtone" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "voice", "call" -> AudioManager.STREAM_VOICE_CALL
            else -> return ExecutionResult.fail("Unknown stream: ${args[0]}. Valid: media, ring, alarm, voice")
        }
        val level = args[1].toIntOrNull() ?: return ExecutionResult.fail("Volume must be a number")
        am.setStreamVolume(stream, level.coerceIn(0, am.getStreamMaxVolume(stream)), 0)
        return ExecutionResult.ok("${args[0]} volume set to $level")
    }

    // ═══════════════════════════════════════════════════════════════════
    // App management
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun apps(args: List<String>, ec: ExecutionContext): ExecutionResult {
        if (!checkSelf(Manifest.permission.QUERY_ALL_PACKAGES) && Build.VERSION.SDK_INT >= 30) {
            return ExecutionResult.fail("需要 QUERY_ALL_PACKAGES 权限", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }
        val pm = app.packageManager
        val query = args.firstOrNull()
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { query == null || it.packageName.contains(query, ignoreCase = true) || it.loadLabel(pm).contains(query, ignoreCase = true) }
            .take(30)
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        return ExecutionResult.ok(buildString {
            appendLine("Installed apps${if (query != null) " matching '$query'" else ""} (showing ${apps.size}):")
            apps.forEach { appendLine("  ${it.loadLabel(pm)} — ${it.packageName}") }
            if (apps.size >= 30) appendLine("  ... use sys.apps <keyword> to filter")
        })
    }

    private suspend fun appLaunch(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val pkg = args.firstOrNull() ?: return ExecutionResult.fail("Usage: sys.app.launch <package>")
        return try {
            val intent = app.packageManager.getLaunchIntentForPackage(pkg)
                ?: return ExecutionResult.fail("App not found or not launchable: $pkg", errorCode = ErrorCodes.ERR_NOT_FOUND)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            ExecutionResult.ok("Launched: $pkg")
        } catch (e: Exception) {
            ExecutionResult.fail("Launch failed: ${e.message}")
        }
    }

    private suspend fun appUninstall(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val pkg = args.firstOrNull() ?: return ExecutionResult.fail("Usage: sys.app.uninstall <package>")
        // Prevent Agent from uninstalling MengPaw itself or system packages
        if (pkg == app.packageName) {
            return ExecutionResult.fail("Cannot uninstall MengPaw itself", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }
        return try {
            // ACTION_DELETE opens the system uninstall dialog — user must confirm manually
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            ExecutionResult.ok("Uninstall dialog opened for: $pkg (user confirmation required)")
        } catch (e: Exception) {
            ExecutionResult.fail("Uninstall failed: ${e.message}")
        }
    }

    private suspend fun appInfo(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val pkg = args.firstOrNull() ?: return ExecutionResult.fail("Usage: sys.app.info <package>")
        return try {
            val pm = app.packageManager
            val ai = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
            val pi = pm.getPackageInfo(pkg, 0)
            ExecutionResult.ok(buildString {
                appendLine("Package: $pkg")
                appendLine("Name: ${pm.getApplicationLabel(ai)}")
                appendLine("Version: ${pi.versionName} (${pi.versionCode})")
                appendLine("Target SDK: ${ai.targetSdkVersion}")
                appendLine("System: ${(ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0}")
                appendLine("Installed: ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(pi.firstInstallTime))}")
            })
        } catch (e: PackageManager.NameNotFoundException) {
            ExecutionResult.fail("App not found: $pkg", errorCode = ErrorCodes.ERR_NOT_FOUND)
        } catch (e: Exception) {
            ExecutionResult.fail("Error: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Power
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun power(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        return ExecutionResult.ok(buildString {
            appendLine("Power Save: ${pm.isPowerSaveMode}")
            @Suppress("DEPRECATION")
            appendLine("Interactive: ${pm.isInteractive}")
            if (Build.VERSION.SDK_INT >= 23) appendLine("Doze: ${pm.isDeviceIdleMode}")
        })
    }

    private suspend fun powerSave(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val enable = args.firstOrNull()?.lowercase() != "false" && args.firstOrNull()?.lowercase() != "off"
        if (!checkSelf(Manifest.permission.WRITE_SETTINGS)) {
            // Can only open settings page, not directly toggle
            val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            return ExecutionResult.ok("Opened battery saver settings. Toggle manually or grant WRITE_SETTINGS permission.")
        }
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        // Power save mode requires system-level permission; open settings as fallback
        val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
        return ExecutionResult.ok("Opened battery saver settings (direct toggle requires system app).")
    }

    // ═══════════════════════════════════════════════════════════════════
    // Clipboard
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun clipboard(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return ExecutionResult.ok("Clipboard empty")
        val text = (0 until clip.itemCount).joinToString(" | ") { i ->
            clip.getItemAt(i)?.text?.toString() ?: ""
        }
        return ExecutionResult.ok(text.ifBlank { "Clipboard empty" })
    }

    private suspend fun clipboardSet(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val text = args.joinToString(" ")
        if (text.isBlank()) return ExecutionResult.fail("Usage: sys.clipboard.set <text>")
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("MengPaw", text))
        return ExecutionResult.ok("Clipboard set: ${text.take(50)}...")
    }

    // ═══════════════════════════════════════════════════════════════════
    // Telephony
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun telephony(args: List<String>, ec: ExecutionContext): ExecutionResult {
        return try {
            val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            ExecutionResult.ok(buildString {
                appendLine("Operator: ${tm.networkOperatorName}")
                appendLine("Country: ${tm.networkCountryIso}")
                appendLine("Network: ${when (tm.dataNetworkType) {
                    TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
                    TelephonyManager.NETWORK_TYPE_NR -> "5G"
                    else -> tm.dataNetworkType.toString()
                }}")
                appendLine("Roaming: ${tm.isNetworkRoaming}")
                if (checkSelf(Manifest.permission.READ_PHONE_STATE)) {
                    appendLine("IMEI: ${if (Build.VERSION.SDK_INT >= 26) tm.imei else "(unavailable)"}")
                }
            })
        } catch (e: Exception) {
            ExecutionResult.fail("Telephony unavailable: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Vibrate
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun vibrate(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val ms = args.firstOrNull()?.toLongOrNull() ?: 200L
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                val vm = app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            }
            ExecutionResult.ok("Vibrated ${ms}ms")
        } catch (e: Exception) {
            ExecutionResult.fail("Vibrate failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Intents
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun intentOpen(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val target = args.firstOrNull() ?: return ExecutionResult.fail("Usage: sys.intent.open <url|package>")
        return try {
            val intent = if (target.startsWith("http://") || target.startsWith("https://")) {
                Intent(Intent.ACTION_VIEW, Uri.parse(target))
            } else {
                app.packageManager.getLaunchIntentForPackage(target)
                    ?: return ExecutionResult.fail("Package not found: $target", errorCode = ErrorCodes.ERR_NOT_FOUND)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            ExecutionResult.ok("Opened: $target")
        } catch (e: Exception) {
            ExecutionResult.fail("Open failed: ${e.message}")
        }
    }

    private suspend fun intentShare(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val text = args.joinToString(" ")
        if (text.isBlank()) return ExecutionResult.fail("Usage: sys.intent.share <text>")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(Intent.createChooser(intent, "Share via"))
        return ExecutionResult.ok("Share sheet opened")
    }

    private suspend fun intentView(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val path = args.firstOrNull() ?: return ExecutionResult.fail("Usage: sys.intent.view <file_path>")
        val file = File(path)
        if (!file.exists()) return ExecutionResult.fail("File not found: $path", errorCode = ErrorCodes.ERR_NOT_FOUND)
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                app, "${app.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeTypeFor(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
            return ExecutionResult.ok("Viewing: $path")
        } catch (e: Exception) {
            // Fallback: use file URI directly
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.fromFile(file), mimeTypeFor(file.name))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                app.startActivity(intent)
                return ExecutionResult.ok("Viewing (direct): $path")
            } catch (e2: Exception) {
                return ExecutionResult.fail("View failed: ${e2.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Notifications
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun notificationId(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val channel = args.firstOrNull() ?: "mengpaw_agent"
        return ExecutionResult.ok("Notification channel: $channel (id: 1001)")
    }

    private suspend fun notificationSend(args: List<String>, ec: ExecutionContext): ExecutionResult {
        // Parse: sys.notification.send <title> <text> [--priority N]
        var priority = NotificationCompat.PRIORITY_DEFAULT
        val parts = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            if (args[i] == "--priority" && i + 1 < args.size) {
                priority = args[i + 1].toIntOrNull() ?: NotificationCompat.PRIORITY_DEFAULT
                i += 2
            } else {
                parts.add(args[i])
                i++
            }
        }
        if (parts.size < 2) return ExecutionResult.fail("Usage: sys.notification.send <title> <text> [--priority N]")

        val title = parts[0]
        val text = parts.drop(1).joinToString(" ")
        val channelId = "mengpaw_agent"

        // Android 13+: proactively check POST_NOTIFICATIONS before attempting
        if (Build.VERSION.SDK_INT >= 33 && !checkSelf(Manifest.permission.POST_NOTIFICATIONS)) {
            return ExecutionResult.fail(
                "⛔ 需要 POST_NOTIFICATIONS 权限（Android 13+ 通知权限）\n" +
                "当前状态: 未授予\n" +
                "操作: sys.permission.request POST_NOTIFICATIONS\n" +
                "说明: 将弹出系统权限对话框，请用户在弹窗中选择'允许'",
                errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }

        try {
            // Create channel if needed
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    "MengPaw Agent",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                )
                val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(app, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(priority)
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(app).notify(1002, notification)
            return ExecutionResult.ok("Notification sent: $title")
        } catch (e: SecurityException) {
            return ExecutionResult.fail("需要 POST_NOTIFICATIONS 权限 (Android 13+)", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        } catch (e: Exception) {
            return ExecutionResult.fail("Notification failed: ${e.message}")
        }
    }

    private suspend fun notificationCancel(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val id = args.firstOrNull()?.toIntOrNull() ?: 1002
        NotificationManagerCompat.from(app).cancel(id)
        return ExecutionResult.ok("Notification #$id cancelled")
    }

    // ═══════════════════════════════════════════════════════════════════
    // Permissions
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun permissionList(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val perms = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION to "GPS 定位",
            Manifest.permission.CAMERA to "相机",
            Manifest.permission.RECORD_AUDIO to "录音",
            Manifest.permission.READ_EXTERNAL_STORAGE to "读取存储",
            Manifest.permission.WRITE_EXTERNAL_STORAGE to "写入存储",
            Manifest.permission.SYSTEM_ALERT_WINDOW to "悬浮窗",
            Manifest.permission.POST_NOTIFICATIONS to "通知",
            Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS to "忽略电池优化",
            Manifest.permission.QUERY_ALL_PACKAGES to "查询应用列表",
            Manifest.permission.READ_PHONE_STATE to "读取手机状态",
            Manifest.permission.SEND_SMS to "发送短信",
            Manifest.permission.READ_CONTACTS to "读取联系人",
        )
        return ExecutionResult.ok(buildString {
            appendLine("| 权限 | 说明 | 状态 |")
            appendLine("|------|------|------|")
            perms.forEach { (perm, desc) ->
                val status = when {
                    perm in SETTINGS_PERMISSIONS -> "需单独申请"
                    Build.VERSION.SDK_INT < 23 -> "已授予 (API<23)"
                    perm == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33 -> "无需 (API<33)"
                    checkSelf(perm) -> "✅ 已授予"
                    else -> "⛔ 未授予"
                }
                appendLine("| $perm | $desc | $status |")
            }
            appendLine()
            if (Build.VERSION.SDK_INT >= 33 && !checkSelf(Manifest.permission.POST_NOTIFICATIONS)) {
                appendLine("💡 通知权限未授予。Agent 发送通知前请先执行: sys.permission.request POST_NOTIFICATIONS")
            }
        })
    }

    private suspend fun permissionCheck(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val perm = args.firstOrNull() ?: return ExecutionResult.fail(
            "Usage: sys.permission.check <permission_name>\n示例: sys.permission.check POST_NOTIFICATIONS",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)
        // Find the human-readable description
        val desc = PERMISSION_LABELS[perm] ?: perm
        val status = when {
            perm in SETTINGS_PERMISSIONS -> "需单独申请（系统设置）"
            Build.VERSION.SDK_INT < 23 -> "已授予 (API<23, 安装时授权)"
            perm == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33 -> "无需 (Android 12-, 安装时授权)"
            checkSelf(perm) -> "✅ 已授予"
            else -> "⛔ 未授予"
        }
        val guide = PERMISSION_GUIDE[perm]
        val guideText = if (guide != null) "\n说明: $guide" else ""
        val actionText = if (!checkSelf(perm) && perm in DIALOG_PERMISSIONS) {
            "\n操作: sys.permission.request $perm"
        } else if (!checkSelf(perm) && perm in SETTINGS_PERMISSIONS) {
            "\n操作: sys.permission.request $perm (将打开系统设置页)"
        } else ""
        return ExecutionResult.ok("$desc ($perm): $status$guideText$actionText")
    }

    private suspend fun permissionRequest(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val perm = args.firstOrNull() ?: return ExecutionResult.fail(
            "Usage: sys.permission.request <permission_name>\n" +
            "常用权限: POST_NOTIFICATIONS (通知), CAMERA (相机), ACCESS_FINE_LOCATION (定位)",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)

        // Already granted?
        if (perm !in SETTINGS_PERMISSIONS && Build.VERSION.SDK_INT >= 23 && checkSelf(perm)) {
            val desc = PERMISSION_LABELS[perm] ?: perm
            return ExecutionResult.ok("$desc ($perm): ✅ 已授予，无需再次申请")
        }

        // POST_NOTIFICATIONS on Android 12- : auto-granted
        if (perm == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33) {
            return ExecutionResult.ok("通知权限: Android 12- 安装时自动授予，无需申请")
        }

        // Dialog-based runtime permissions → show system dialog
        if (perm in DIALOG_PERMISSIONS) {
            val activity = currentActivity?.get()
            if (activity == null) {
                // Fallback: open app settings
                return try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${app.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    app.startActivity(intent)
                    ExecutionResult.ok("已打开应用设置页。请在权限列表中手动授予 '$perm' 权限。")
                } catch (e: Exception) {
                    ExecutionResult.fail("无法打开设置页: ${e.message}")
                }
            }
            return try {
                ActivityCompat.requestPermissions(activity, arrayOf(perm), PERM_REQUEST_CODE)
                val desc = PERMISSION_LABELS[perm] ?: perm
                ExecutionResult.ok("已弹出系统权限对话框: $desc\n请在弹窗中选择'允许'。授权后可用 sys.permission.check $perm 确认。")
            } catch (e: Exception) {
                // Fallback: open app settings
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${app.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    app.startActivity(intent)
                    ExecutionResult.ok("无法弹出权限对话框 (${e.message})。已打开应用设置页，请手动授予 '$perm' 权限。")
                } catch (e2: Exception) {
                    ExecutionResult.fail("权限请求失败: ${e2.message}")
                }
            }
        }

        // Special permissions → open app settings
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
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

    // ═══════════════════════════════════════════════════════════════════
    // Ringtone
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun ringtonePlay(args: List<String>, ec: ExecutionContext): ExecutionResult {
        return try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(app, uri)
            ringtone.play()
            ExecutionResult.ok("Ringtone played")
        } catch (e: Exception) {
            ExecutionResult.fail("Ringtone failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Alarm
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun alarmSet(args: List<String>, ec: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: sys.alarm.set <seconds> <message>")
        val secs = args[0].toLongOrNull() ?: return ExecutionResult.fail("Seconds must be a number")
        val msg = args.drop(1).joinToString(" ")
        return try {
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(app, Class.forName("com.mengpaw.shell.service.WakeReceiver"))
            intent.putExtra("wake_reason", "alarm")
            intent.putExtra("message", msg)
            val pi = PendingIntent.getBroadcast(
                app, 2001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + secs * 1000, pi)
            ExecutionResult.ok("Alarm set: ${secs}s → '$msg'")
        } catch (e: Exception) {
            ExecutionResult.fail("Alarm failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Overlay (floating window)
    // ═══════════════════════════════════════════════════════════════════

    private var overlayView: android.widget.TextView? = null
    private var overlayManager: android.view.WindowManager? = null

    private suspend fun overlayShow(args: List<String>, ec: ExecutionContext): ExecutionResult {
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(app)) {
            return ExecutionResult.fail(buildString {
                appendLine("悬浮窗权限未授予（系统设置类权限，需手动开启）。")
                appendLine()
                appendLine("请引导用户: 设置 → 应用 → MengPaw → 在其他应用上层显示 → 开启")
                appendLine("或让用户执行: sys.permission.request SYSTEM_ALERT_WINDOW")
            })
        }
        val text = args.takeWhile { !it.startsWith("--") }.joinToString(" ")
        if (text.isBlank()) return ExecutionResult.fail("用法: sys.overlay.show <文本> [--x 100] [--y 200] [--size 14] [--color #FFF]")
        val flags = args.dropWhile { !it.startsWith("--") }
        val x = flags.find { it.startsWith("--x") }?.substringAfter("--x")?.trim()?.toIntOrNull() ?: 100
        val y = flags.find { it.startsWith("--y") }?.substringAfter("--y")?.trim()?.toIntOrNull() ?: 500
        val size = flags.find { it.startsWith("--size") }?.substringAfter("--size")?.trim()?.toFloatOrNull() ?: 14f
        val colorStr = flags.find { it.startsWith("--color") }?.substringAfter("--color")?.trim() ?: "#FFFFFF"
        val color = try { android.graphics.Color.parseColor(colorStr) } catch (_: Exception) { android.graphics.Color.WHITE }

        // Remove existing overlay
        overlayView?.let { try { overlayManager?.removeView(it) } catch (_: Exception) {} }

        val tv = android.widget.TextView(app).apply {
            this.text = text
            setTextColor(color)
            textSize = size
            setPadding(16, 8, 16, 8)
            setBackgroundColor(android.graphics.Color.parseColor("#CC000000"))
            alpha = 0.9f
        }
        val params = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else android.view.WindowManager.LayoutParams.TYPE_PHONE,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply { this.x = x; this.y = y; gravity = android.view.Gravity.TOP or android.view.Gravity.START }

        overlayManager = app.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        overlayManager?.addView(tv, params)
        overlayView = tv
        return ExecutionResult.ok(buildString {
            appendLine("悬浮窗已显示 ✅")
            appendLine("- 内容: \"$text\"")
            appendLine("- 位置: ($x, $y)")
            appendLine()
            appendLine("更新内容: sys.overlay.update <新文本>")
            appendLine("隐藏: sys.overlay.hide")
        })
    }

    private suspend fun overlayHide(args: List<String>, ec: ExecutionContext): ExecutionResult {
        if (overlayView == null) return ExecutionResult.ok("悬浮窗未在显示")
        try { overlayManager?.removeView(overlayView) } catch (_: Exception) {}
        overlayView = null
        overlayManager = null
        return ExecutionResult.ok("悬浮窗已隐藏")
    }

    private suspend fun overlayUpdate(args: List<String>, ec: ExecutionContext): ExecutionResult {
        if (overlayView == null) return ExecutionResult.fail("悬浮窗未在显示。请先执行 sys.overlay.show")
        val text = args.joinToString(" ")
        if (text.isBlank()) return ExecutionResult.fail("用法: sys.overlay.update <文本>")
        overlayView?.text = text
        return ExecutionResult.ok("悬浮窗已更新: \"$text\"")
    }

    // ═══════════════════════════════════════════════════════════════════
    // Calendar
    // ═══════════════════════════════════════════════════════════════════

    /** Cached writable calendar ID. Refreshed when add fails or on explicit refresh. */
    private var cachedCalendarId: Long? = null

    /** Get a writable calendar ID. Returns the first visible, non-read-only calendar. */
    private fun resolveCalendarId(): Long? {
        cachedCalendarId?.let { return it }
        return try {
            val projection = arrayOf(
                android.provider.CalendarContract.Calendars._ID,
                android.provider.CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                android.provider.CalendarContract.Calendars.VISIBLE
            )
            val cursor = app.contentResolver.query(
                android.provider.CalendarContract.Calendars.CONTENT_URI, projection,
                null, null, null
            ) ?: return null
            var id: Long? = null
            while (cursor.moveToNext()) {
                val access = cursor.getInt(1)
                val visible = cursor.getInt(2) != 0
                // Prefer owner-level writable visible calendar
                if (visible && access >= android.provider.CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    id = cursor.getLong(0)
                    if (access >= android.provider.CalendarContract.Calendars.CAL_ACCESS_OWNER) break
                }
            }
            cursor.close()
            cachedCalendarId = id
            id
        } catch (_: Exception) { null }
    }

    /** List all available calendars. */
    private suspend fun calendarCalendars(args: List<String>, ec: ExecutionContext): ExecutionResult {
        if (!checkSelf(Manifest.permission.READ_CALENDAR)) {
            return ExecutionResult.fail("需要日历读取权限。请执行: sys.permission.request READ_CALENDAR")
        }
        return try {
            val projection = arrayOf(
                android.provider.CalendarContract.Calendars._ID,
                android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                android.provider.CalendarContract.Calendars.ACCOUNT_NAME,
                android.provider.CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                android.provider.CalendarContract.Calendars.VISIBLE
            )
            val cursor = app.contentResolver.query(
                android.provider.CalendarContract.Calendars.CONTENT_URI, projection,
                null, null, null
            ) ?: return ExecutionResult.fail("无法读取日历列表")
            if (cursor.count == 0) { cursor.close(); return ExecutionResult.ok("(无可用日历账户)") }
            val sb = StringBuilder("## 可用日历\n\n| ID | 名称 | 账户 | 权限 |\n|----|------|------|------|\n")
            val accessLabels = mapOf(0 to "无", 100 to "只读", 500 to "贡献", 600 to "编辑", 700 to "拥有者")
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1) ?: "?"
                val account = cursor.getString(2) ?: "本地"
                val access = accessLabels[cursor.getInt(3)] ?: "?"
                val visible = if (cursor.getInt(4) != 0) "" else " (隐藏)"
                sb.appendLine("| $id | $name | $account | $access$visible |")
            }
            cursor.close()
            // Refresh cached ID
            cachedCalendarId = null
            val resolved = resolveCalendarId()
            if (resolved != null) sb.appendLine("\n默认写入日历: ID=$resolved")
            ExecutionResult.ok(sb.toString().trimEnd())
        } catch (e: Exception) {
            ExecutionResult.fail("日历查询异常: ${e.message}")
        }
    }

    private suspend fun calendarAdd(args: List<String>, ec: ExecutionContext): ExecutionResult {
        if (!checkSelf(Manifest.permission.WRITE_CALENDAR)) {
            return ExecutionResult.fail("需要日历写入权限。请执行: sys.permission.request WRITE_CALENDAR")
        }
        if (args.isEmpty()) return ExecutionResult.fail(
            "用法: sys.calendar.add <标题> <开始时间> [--end <结束时间>] [--desc <描述>] [--cal <日历ID>]\n" +
            "时间格式: yyyy-MM-dd HH:mm 或 Unix毫秒。查看日历列表: sys.calendar.calendars"
        )
        val parts = args.takeWhile { !it.startsWith("--") }
        val title = parts.getOrNull(0) ?: return ExecutionResult.fail("缺少标题")
        val startStr = parts.getOrNull(1) ?: return ExecutionResult.fail("缺少开始时间")
        val flags = args.dropWhile { !it.startsWith("--") }
        val endStr = flags.find { it.startsWith("--end") }?.substringAfter("--end")?.trim()
        val desc = flags.find { it.startsWith("--desc") }?.substringAfter("--desc")?.trim()
        val calId = flags.find { it.startsWith("--cal") }?.substringAfter("--cal")?.trim()?.toLongOrNull()

        val cal = calId ?: resolveCalendarId()
            ?: return ExecutionResult.fail("未找到可写入的日历。请先执行 sys.calendar.calendars 查看可用日历，然后用 --cal <ID> 指定。")
        val startMillis = parseTime(startStr) ?: return ExecutionResult.fail("时间格式无效: $startStr。使用 yyyy-MM-dd HH:mm 或 Unix毫秒")
        val endMillis = endStr?.let { parseTime(it) } ?: (startMillis + 3600_000L)

        return try {
            val values = android.content.ContentValues().apply {
                put(android.provider.CalendarContract.Events.CALENDAR_ID, cal)
                put(android.provider.CalendarContract.Events.TITLE, title)
                put(android.provider.CalendarContract.Events.DTSTART, startMillis)
                put(android.provider.CalendarContract.Events.DTEND, endMillis)
                if (desc != null) put(android.provider.CalendarContract.Events.DESCRIPTION, desc)
                put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            }
            val uri = app.contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                val id = uri.lastPathSegment ?: "?"
                ExecutionResult.ok(buildString {
                    appendLine("日历事件已创建 ✅")
                    appendLine("- 标题: $title")
                    appendLine("- ID: $id (用于 sys.calendar.delete $id)")
                    appendLine("- 时间: ${formatTime(startMillis)} → ${formatTime(endMillis)}")
                    appendLine("- 日历: $cal")
                    appendLine()
                    appendLine("查看: sys.calendar.list --days 7")
                    appendLine("删除: sys.calendar.delete $id")
                })
            } else ExecutionResult.fail("日历写入失败。尝试 sys.calendar.calendars 查看可用日历，用 --cal <ID> 指定。")
        } catch (e: SecurityException) {
            ExecutionResult.fail("日历权限被拒绝: ${e.message}")
        } catch (e: Exception) {
            ExecutionResult.fail("日历异常: ${e.message}")
        }
    }

    private suspend fun calendarList(args: List<String>, ec: ExecutionContext): ExecutionResult {
        if (!checkSelf(Manifest.permission.READ_CALENDAR)) {
            return ExecutionResult.fail("需要日历读取权限。请执行: sys.permission.request READ_CALENDAR")
        }
        val days = args.find { it.startsWith("--days") }?.substringAfter("--days")?.trim()?.toIntOrNull() ?: 7
        val start = System.currentTimeMillis()
        val end = start + days * 86_400_000L

        return try {
            val projection = arrayOf(
                android.provider.CalendarContract.Events._ID,
                android.provider.CalendarContract.Events.TITLE,
                android.provider.CalendarContract.Events.DTSTART,
                android.provider.CalendarContract.Events.DTEND,
                android.provider.CalendarContract.Events.CALENDAR_ID
            )
            val cursor = app.contentResolver.query(
                android.provider.CalendarContract.Events.CONTENT_URI, projection,
                "${android.provider.CalendarContract.Events.DTSTART} >= ? AND ${android.provider.CalendarContract.Events.DTSTART} <= ?",
                arrayOf(start.toString(), end.toString()),
                "${android.provider.CalendarContract.Events.DTSTART} ASC"
            )
            if (cursor == null || cursor.count == 0) {
                cursor?.close()
                return ExecutionResult.ok("(未来 $days 天无日历事件)\n\n添加: sys.calendar.add \"标题\" \"yyyy-MM-dd HH:mm\"")
            }
            val sb = StringBuilder("## 未来 $days 天日历事件 (${cursor.count}个)\n\n| 时间 | 标题 | ID | 日历 |\n|------|------|----|------|\n")
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val title = cursor.getString(1) ?: "(无标题)"
                val dtStart = cursor.getLong(2)
                val calId = cursor.getLong(4)
                sb.appendLine("| ${formatTime(dtStart)} | ${title.take(30)} | $id | $calId |")
            }
            cursor.close()
            sb.appendLine("\n删除: sys.calendar.delete <ID>")
            ExecutionResult.ok(sb.toString().trimEnd())
        } catch (e: Exception) {
            ExecutionResult.fail("日历查询异常: ${e.message}")
        }
    }

    /** Delete a calendar event by ID. */
    private suspend fun calendarDelete(args: List<String>, ec: ExecutionContext): ExecutionResult {
        if (!checkSelf(Manifest.permission.WRITE_CALENDAR)) {
            return ExecutionResult.fail("需要日历写入权限。请执行: sys.permission.request WRITE_CALENDAR")
        }
        val idStr = args.firstOrNull() ?: return ExecutionResult.fail(
            "用法: sys.calendar.delete <ID>\n先用 sys.calendar.list 查看事件ID。"
        )
        val id = idStr.toLongOrNull() ?: return ExecutionResult.fail("无效的事件ID: $idStr")
        return try {
            val uri = android.content.ContentUris.withAppendedId(
                android.provider.CalendarContract.Events.CONTENT_URI, id
            )
            val deleted = app.contentResolver.delete(uri, null, null)
            if (deleted > 0) ExecutionResult.ok("日历事件已删除: ID=$id")
            else ExecutionResult.fail("未找到事件 ID=$id。用 sys.calendar.list 查看有效ID。")
        } catch (e: SecurityException) {
            ExecutionResult.fail("权限被拒绝: ${e.message}")
        } catch (e: Exception) {
            ExecutionResult.fail("删除异常: ${e.message}")
        }
    }

    private fun parseTime(s: String): Long? {
        return try {
            if (s.matches(Regex("\\d{13,}"))) s.toLong() // Unix ms
            else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).parse(s)?.time
        } catch (_: Exception) { null }
    }

    private fun formatTime(millis: Long): String =
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(millis))

    // ═══════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════

    private fun checkSelf(permission: String): Boolean =
        if (Build.VERSION.SDK_INT >= 23) {
            ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
        } else true

    private fun mimeTypeFor(filename: String): String = when {
        filename.endsWith(".pdf") -> "application/pdf"
        filename.endsWith(".png") -> "image/png"
        filename.endsWith(".jpg") || filename.endsWith(".jpeg") -> "image/jpeg"
        filename.endsWith(".gif") -> "image/gif"
        filename.endsWith(".mp4") -> "video/mp4"
        filename.endsWith(".mp3") -> "audio/mpeg"
        filename.endsWith(".apk") -> "application/vnd.android.package-archive"
        filename.endsWith(".txt") || filename.endsWith(".md") -> "text/plain"
        filename.endsWith(".html") -> "text/html"
        else -> "*/*"
    }

    // ═══════════════════════════════════════════════════════════════════
    // Permission map for sys commands (used by UI / settings)
    // ═══════════════════════════════════════════════════════════════════

    /** Request code for ActivityCompat.requestPermissions. */
    private const val PERM_REQUEST_CODE = 9001

    /** Permissions that can be requested via system dialog (ActivityCompat.requestPermissions). */
    private val DIALOG_PERMISSIONS = setOf(
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    /** Permissions that require opening system settings (cannot use standard dialog). */
    private val SETTINGS_PERMISSIONS = setOf(
        Manifest.permission.SYSTEM_ALERT_WINDOW,
        Manifest.permission.WRITE_SETTINGS,
        Manifest.permission.REQUEST_INSTALL_PACKAGES,
        Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
    )

    /** Human-readable labels for permissions. */
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
        Manifest.permission.READ_PHONE_STATE to "读取手机状态",
        Manifest.permission.SEND_SMS to "发送短信",
        Manifest.permission.READ_CONTACTS to "读取联系人",
        Manifest.permission.WRITE_SETTINGS to "修改系统设置",
        Manifest.permission.REQUEST_INSTALL_PACKAGES to "安装应用",
        Manifest.permission.READ_CALENDAR to "读取日历",
        Manifest.permission.WRITE_CALENDAR to "写入日历",
    )

    /** Actionable guidance for each permission — shown to Agent on check/deny. */
    private val PERMISSION_GUIDE = mapOf(
        Manifest.permission.POST_NOTIFICATIONS to "Android 13+ 通知权限。安装后默认禁止，必须手动授权。" +
            "使用 sys.permission.request POST_NOTIFICATIONS 弹出授权对话框。",
        Manifest.permission.CAMERA to "相机权限。使用 sys.permission.request CAMERA 申请。",
        Manifest.permission.ACCESS_FINE_LOCATION to "GPS 精确定位。使用 sys.permission.request ACCESS_FINE_LOCATION 申请。",
        Manifest.permission.RECORD_AUDIO to "录音权限。使用 sys.permission.request RECORD_AUDIO 申请。",
        Manifest.permission.READ_PHONE_STATE to "读取手机状态（IMEI/运营商）。使用 sys.permission.request READ_PHONE_STATE 申请。",
    )

    // ═══════════════════════════════════════════════════════════════════
    // Screenshot
    // ═══════════════════════════════════════════════════════════════════

    private var screenRecordProcess: Process? = null

    private suspend fun screenshot(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val flags = args.filter { it.startsWith("--") }
        val pathArg = args.filter { !it.startsWith("--") }.firstOrNull()
        val outputPath = if (pathArg != null) {
            java.io.File(pathArg).absolutePath
        } else {
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            "${com.mengpaw.kernel.DataPaths.SCREENSHOTS}/screenshot_${timestamp}.png"
        }
        java.io.File(outputPath).parentFile?.mkdirs()
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("screencap", "-p", outputPath))
            proc.waitFor()
            if (proc.exitValue() == 0) {
                val size = java.io.File(outputPath).length()
                ExecutionResult.ok("截图已保存: $outputPath (${size} bytes)")
            } else {
                val err = proc.errorStream.bufferedReader().readText().trim()
                ExecutionResult.fail("截图失败${if (err.isNotEmpty()) ": $err" else " — screencap 不可用（可能需要 root 或 ADB 权限）"}")
            }
        } catch (e: Exception) {
            ExecutionResult.fail("截图失败: ${e.message}。screencap 需要 root 或 shell 权限。备选: 使用 browser.screenshot 截取浏览器内容。")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Screen recording
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun screenRecordStart(args: List<String>, ec: ExecutionContext): ExecutionResult {
        if (screenRecordProcess != null && screenRecordProcess!!.isAlive) {
            return ExecutionResult.fail("录屏已在运行。先执行 sys.screenrecord.stop 停止。")
        }
        val pathArg = args.firstOrNull()
        val outputPath = pathArg ?: run {
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            "${com.mengpaw.kernel.DataPaths.SCREENSHOTS}/record_${timestamp}.mp4"
        }
        java.io.File(outputPath).parentFile?.mkdirs()
        return try {
            // --time-limit 180: max 3 min to prevent runaway recordings
            val proc = Runtime.getRuntime().exec(arrayOf("screenrecord", "--time-limit", "180", outputPath))
            screenRecordProcess = proc
            ExecutionResult.ok("录屏已开始 (最长 3 分钟)\n输出: $outputPath\n停止: sys.screenrecord.stop")
        } catch (e: Exception) {
            ExecutionResult.fail("录屏启动失败: ${e.message}。screenrecord 需要 root 或 shell 权限。")
        }
    }

    private suspend fun screenRecordStop(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val proc = screenRecordProcess
        if (proc == null || !proc.isAlive) {
            screenRecordProcess = null
            return ExecutionResult.fail("没有正在运行的录屏")
        }
        return try {
            // Send signal to stop recording gracefully (screenrecord finalizes mp4 on SIGINT/SIGTERM)
            proc.destroy()  // SIGTERM — screenrecord handles this to finalize output
            Thread.sleep(1500) // wait for screenrecord to finalize the mp4
            if (proc.isAlive) proc.destroyForcibly()
            screenRecordProcess = null
            ExecutionResult.ok("录屏已停止。文件在 ${com.mengpaw.kernel.DataPaths.SCREENSHOTS}/ 目录下。")
        } catch (e: Exception) {
            try { proc.destroyForcibly() } catch (_: Exception) {}
            screenRecordProcess = null
            ExecutionResult.fail("停止录屏异常: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Camera photo capture
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun cameraPhoto(args: List<String>, ec: ExecutionContext): ExecutionResult {
        if (!checkSelf(Manifest.permission.CAMERA)) {
            return ExecutionResult.fail("需要 CAMERA 权限。使用 sys.permission.request CAMERA 申请。", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }
        // ── User confirmation gate ──
        if (!args.contains("--confirm")) {
            return ExecutionResult.fail(buildString {
                appendLine("📸 即将使用摄像头拍照")
                appendLine()
                appendLine("⚠️ 摄像头涉及隐私。请先告知用户并获取确认，然后执行:")
                appendLine("  sys.camera.photo --confirm")
                appendLine()
                appendLine("可选参数: sys.camera.photo --confirm --front (使用前置摄像头)")
                appendLine("          sys.camera.photo --confirm <输出路径>")
            })
        }
        return try {
            val pathArg = args.filter { !it.startsWith("--") }.firstOrNull()
            val outputPath = pathArg ?: run {
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                "${com.mengpaw.kernel.DataPaths.SCREENSHOTS}/photo_${timestamp}.jpg"
            }
            val useFront = args.contains("--front")
            java.io.File(outputPath).parentFile?.mkdirs()

            // Use Camera2 API to capture
            val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = if (useFront) {
                cm.cameraIdList.firstOrNull { id ->
                    val chars = cm.getCameraCharacteristics(id)
                    chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                }
            } else {
                cm.cameraIdList.firstOrNull { id ->
                    val chars = cm.getCameraCharacteristics(id)
                    chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                }
            } ?: cm.cameraIdList.firstOrNull()
                ?: return ExecutionResult.fail("未找到可用摄像头")

            // Open camera and capture a single frame
            val latch = java.util.concurrent.CountDownLatch(1)
            var captureError: String? = null
            var capturedPath: String? = null

            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            cm.openCamera(cameraId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(device: android.hardware.camera2.CameraDevice) {
                    try {
                        val reader = android.media.ImageReader.newInstance(1920, 1080, android.graphics.ImageFormat.JPEG, 1)
                        reader.setOnImageAvailableListener({ r ->
                            val image = r.acquireLatestImage()
                            if (image != null) {
                                try {
                                    val buffer = image.planes[0].buffer
                                    val bytes = ByteArray(buffer.remaining())
                                    buffer.get(bytes)
                                    java.io.File(outputPath).writeBytes(bytes)
                                    capturedPath = outputPath
                                } finally {
                                    image.close()
                                }
                            }
                            latch.countDown()
                        }, handler)

                        val surfaces = listOf(reader.surface)
                        device.createCaptureSession(surfaces,
                            object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                                    val req = device.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE)
                                    req.addTarget(reader.surface)
                                    session.capture(req.build(), null, null)
                                }
                                override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) {
                                    captureError = "Camera session config failed"
                                    latch.countDown()
                                }
                            }, handler)
                    } catch (e: Exception) {
                        captureError = e.message
                        latch.countDown()
                    }
                }
                override fun onDisconnected(d: android.hardware.camera2.CameraDevice) { d.close(); latch.countDown() }
                override fun onError(d: android.hardware.camera2.CameraDevice, e: Int) { d.close(); captureError = "Camera error: $e"; latch.countDown() }
            }, handler)

            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)

            if (capturedPath != null) {
                val size = java.io.File(outputPath).length()
                ExecutionResult.ok("照片已保存: $outputPath (${size} bytes)")
            } else {
                ExecutionResult.fail("拍照失败: ${captureError ?: "超时"}")
            }
        } catch (e: Exception) {
            ExecutionResult.fail("拍照失败: ${e.message}")
        }
    }

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
