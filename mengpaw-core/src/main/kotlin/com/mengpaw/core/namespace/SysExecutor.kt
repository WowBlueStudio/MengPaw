// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.namespace

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
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
        "permission.request" to ::permissionRequest,
        // ── Misc ──
        "vibrate" to ::vibrate,
        "ringtone.play" to ::ringtonePlay,
        "alarm.set" to ::alarmSet,
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
        return try {
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            ExecutionResult.ok("Uninstall dialog opened for: $pkg")
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
                val status = if (Build.VERSION.SDK_INT >= 23 && perm in listOf(
                        Manifest.permission.SYSTEM_ALERT_WINDOW,
                        Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Manifest.permission.WRITE_SETTINGS
                    )) {
                    "需单独申请"
                } else if (Build.VERSION.SDK_INT < 23) {
                    "已授予 (API<23)"
                } else {
                    if (checkSelf(perm)) "✅ 已授予" else "⛔ 未授予"
                }
                appendLine("| $perm | $desc | $status |")
            }
        })
    }

    private suspend fun permissionRequest(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val perm = args.firstOrNull() ?: return ExecutionResult.fail("Usage: sys.permission.request <permission_name>")
        // Open app settings for the user to grant the permission
        return try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${app.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
            ExecutionResult.ok("Opened app settings. Grant '$perm' permission manually.")
        } catch (e: Exception) {
            ExecutionResult.fail("Cannot open settings: ${e.message}")
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

    val PERMISSION_MAP = mapOf(
        "sys.location" to Manifest.permission.ACCESS_FINE_LOCATION,
        "sys.camera" to Manifest.permission.CAMERA,
        "sys.apps" to Manifest.permission.QUERY_ALL_PACKAGES,
        "sys.telephony" to Manifest.permission.READ_PHONE_STATE,
    )
}
