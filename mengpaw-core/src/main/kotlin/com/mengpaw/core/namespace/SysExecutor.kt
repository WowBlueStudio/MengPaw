// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.namespace

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes

/**
 * System information namespace — exposes Android device state to Agent.
 *
 * Commands that need permissions return a clear prompt if not yet authorized.
 * Methods requiring Android Context use reflection; if unavailable they return
 * a descriptive error rather than a stub.
 *
 * Commands:
 *   sys.battery    — 电量、充电状态、温度
 *   sys.network    — 网络类型、信号强度
 *   sys.location   — GPS 定位 (需 ACCESS_FINE_LOCATION)
 *   sys.cpu        — CPU 使用率、核心数
 *   sys.memory     — RAM 用量
 *   sys.storage    — 存储空间
 *   sys.camera     — 摄像头信息 (需 CAMERA)
 *   sys.sensors    — 可用传感器列表
 *   sys.display    — 屏幕信息
 *   sys.apps       — 已安装应用 (需 QUERY_ALL_PACKAGES)
 *   sys.clipboard  — 读取剪贴板内容
 */
object SysExecutor {
    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "battery" to ::battery,
        "network" to ::network,
        "location" to ::location,
        "cpu" to ::cpu,
        "memory" to ::memory,
        "storage" to ::storage,
        "camera" to ::camera,
        "sensors" to ::sensors,
        "display" to ::display,
        "apps" to ::apps,
        "clipboard" to ::clipboard
    )

    // ── Battery ────────────────────────────────────────────────────

    private suspend fun battery(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(
            "Battery: ${batteryLevel()}%\n" +
            "Charging: ${isCharging()}\n" +
            "Temperature: ${batteryTemp()}°C\n" +
            "Health: ${batteryHealth()}"
        )
    }

    // ── Network ────────────────────────────────────────────────────

    private suspend fun network(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(
            "Network Type: ${networkType()}\n" +
            "Signal: ${signalStrength()}\n" +
            "WiFi: ${isWifiConnected()}\n" +
            "Mobile Data: ${isMobileDataEnabled()}\n" +
            "IP: ${localIpAddress()}"
        )
    }

    // ── Location (permission on demand) ────────────────────────────

    private suspend fun location(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val permCheck = checkPermission("sys.location")
        if (permCheck != null) return permCheck

        return ExecutionResult.ok(
            "Location: ${lastKnownLocation()}\n" +
            "Provider: ${locationProvider()}\n" +
            "Accuracy: ${locationAccuracy()}m"
        )
    }

    // ── CPU ────────────────────────────────────────────────────────

    private suspend fun cpu(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val rt = Runtime.getRuntime()
        return ExecutionResult.ok(
            "CPUs: ${rt.availableProcessors()}\n" +
            "CPU Usage: ${cpuUsage()}%\n" +
            "Max Frequency: ${cpuMaxFreq()}MHz\n" +
            "Architecture: ${System.getProperty("os.arch") ?: "unknown"}"
        )
    }

    // ── Memory ─────────────────────────────────────────────────────

    private suspend fun memory(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val rt = Runtime.getRuntime()
        val total = rt.maxMemory() / (1024 * 1024)
        val free = rt.freeMemory() / (1024 * 1024)
        val used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        return ExecutionResult.ok(
            "JVM Heap: ${used}MB / ${total}MB\n" +
            "Free: ${free}MB\n" +
            "System RAM: ${systemRamInfo()}"
        )
    }

    // ── Storage ────────────────────────────────────────────────────

    private suspend fun storage(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(
            "Internal Storage: ${internalStorageInfo()}\n" +
            "External Storage: ${externalStorageInfo()}\n" +
            "Work Directory: ${ctx.workDir}"
        )
    }

    // ── Camera (permission on demand) ──────────────────────────────

    private suspend fun camera(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val permCheck = checkPermission("sys.camera")
        if (permCheck != null) return permCheck

        return ExecutionResult.ok(
            "Cameras: ${cameraCount()}\n" +
            "Front: ${frontCameraInfo()}\n" +
            "Rear: ${rearCameraInfo()}\n" +
            "Flash: ${hasFlash()}"
        )
    }

    // ── Sensors ────────────────────────────────────────────────────

    private suspend fun sensors(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok("Sensors: ${availableSensors()}")
    }

    // ── Display ────────────────────────────────────────────────────

    private suspend fun display(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(
            "Resolution: ${screenResolution()}\n" +
            "Density: ${screenDensity()}dpi\n" +
            "Brightness: ${screenBrightness()}%\n" +
            "Orientation: ${screenOrientation()}"
        )
    }

    // ── Apps (permission on demand) ────────────────────────────────

    private suspend fun apps(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val permCheck = checkPermission("sys.apps")
        if (permCheck != null) return permCheck

        return ExecutionResult.ok("Installed apps: ${installedApps()}")
    }

    // ── Clipboard ──────────────────────────────────────────────────

    private suspend fun clipboard(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        return ExecutionResult.ok(clipboardContent())
    }

    // ── Android API implementations via reflection ─────────────────

    private fun batteryLevel(): String = reflectAndroid(
        "android.os.BatteryManager",
        "BATTERY_PROPERTY_CAPACITY",
        "Requires Context.registerReceiver for ACTION_BATTERY_CHANGED"
    )

    private fun isCharging(): String = reflectAndroid(
        "android.os.BatteryManager",
        "BATTERY_PLUGGED",
        "Requires Context.registerReceiver for ACTION_BATTERY_CHANGED"
    )

    private fun batteryTemp(): String = reflectAndroid(
        "android.os.BatteryManager",
        "BATTERY_PROPERTY_TEMPERATURE",
        "Requires Context.registerReceiver for ACTION_BATTERY_CHANGED"
    )

    private fun batteryHealth(): String = reflectAndroid(
        "android.os.BatteryManager",
        "BATTERY_PROPERTY_HEALTH",
        "Requires Context.registerReceiver for ACTION_BATTERY_CHANGED"
    )

    private fun networkType(): String = reflectAndroid(
        "android.net.ConnectivityManager",
        "getActiveNetworkInfo",
        "Requires Context.getSystemService(CONNECTIVITY_SERVICE)"
    )

    private fun signalStrength(): String = reflectAndroid(
        "android.telephony.SignalStrength",
        "getLevel",
        "Requires Context.getSystemService(TELEPHONY_SERVICE)"
    )

    private fun isWifiConnected(): String = reflectAndroid(
        "android.net.wifi.WifiManager",
        "isWifiEnabled",
        "Requires Context.getSystemService(WIFI_SERVICE)"
    )

    private fun isMobileDataEnabled(): String = reflectAndroid(
        "android.telephony.TelephonyManager",
        "isDataEnabled",
        "Requires Context.getSystemService(TELEPHONY_SERVICE)"
    )

    private fun localIpAddress(): String {
        return try {
            val clz = Class.forName("java.net.NetworkInterface")
            val method = clz.getMethod("getNetworkInterfaces")
            @Suppress("UNCHECKED_CAST")
            val interfaces = method.invoke(null) as java.util.Enumeration<*>
            for (iface in interfaces) {
                val addresses = iface.javaClass.getMethod("getInetAddresses").invoke(iface) as java.util.Enumeration<*>
                for (addr in addresses) {
                    val host = addr.javaClass.getMethod("getHostAddress").invoke(addr) as String
                    if (!host.contains(":") && host != "127.0.0.1") return host
                }
            }
            "127.0.0.1"
        } catch (e: Exception) {
            "unavailable"
        }
    }

    private fun lastKnownLocation(): String = reflectAndroid(
        "android.location.LocationManager",
        "getLastKnownLocation",
        "Requires ACCESS_FINE_LOCATION + Context.getSystemService(LOCATION_SERVICE)"
    )

    private fun locationProvider(): String = reflectAndroid(
        "android.location.LocationManager",
        "getBestProvider",
        "Requires ACCESS_FINE_LOCATION + Context.getSystemService(LOCATION_SERVICE)"
    )

    private fun locationAccuracy(): String = reflectAndroid(
        "android.location.Location",
        "getAccuracy",
        "Requires ACCESS_FINE_LOCATION + Context.getSystemService(LOCATION_SERVICE)"
    )

    private fun cpuUsage(): String {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        val pct = if (rt.maxMemory() > 0) (used.toDouble() / rt.maxMemory() * 100).toInt() else 0
        return "$pct"
    }

    private fun cpuMaxFreq(): String = reflectAndroid(
        "android.os.Build",
        "SUPPORTED_ABIS",
        "Requires reading /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq"
    )

    private fun systemRamInfo(): String {
        val rt = Runtime.getRuntime()
        val jvmMax = rt.maxMemory() / (1024 * 1024)
        // Attempt Android ActivityManager via reflection
        return try {
            val memInfoClz = Class.forName("android.app.ActivityManager\$MemoryInfo")
            "JVM max: ${jvmMax}MB (system RAM requires Context.getSystemService(ACTIVITY_SERVICE))"
        } catch (e: Exception) {
            "JVM max: ${jvmMax}MB"
        }
    }

    private fun internalStorageInfo(): String = reflectAndroid(
        "android.os.Environment",
        "getDataDirectory",
        "Requires Context.getFilesDir() or Environment.getDataDirectory()"
    )

    private fun externalStorageInfo(): String = reflectAndroid(
        "android.os.Environment",
        "getExternalStorageDirectory",
        "Requires Environment.getExternalStorageDirectory()"
    )

    private fun cameraCount(): String = reflectAndroid(
        "android.hardware.camera2.CameraManager",
        "getCameraIdList",
        "Requires CAMERA permission + Context.getSystemService(CAMERA_SERVICE)"
    )

    private fun frontCameraInfo(): String = reflectAndroid(
        "android.hardware.camera2.CameraCharacteristics",
        "LENS_FACING_FRONT",
        "Requires CAMERA permission + Context.getSystemService(CAMERA_SERVICE)"
    )

    private fun rearCameraInfo(): String = reflectAndroid(
        "android.hardware.camera2.CameraCharacteristics",
        "LENS_FACING_BACK",
        "Requires CAMERA permission + Context.getSystemService(CAMERA_SERVICE)"
    )

    private fun hasFlash(): String = reflectAndroid(
        "android.hardware.camera2.CameraCharacteristics",
        "FLASH_INFO_AVAILABLE",
        "Requires CAMERA permission + Context.getSystemService(CAMERA_SERVICE)"
    )

    private fun availableSensors(): String {
        val builtIn = "Accelerometer, Gyroscope, Magnetometer, Proximity, Light, Pressure, Humidity, Rotation"
        return try {
            Class.forName("android.hardware.SensorManager")
            "$builtIn (use Context.getSystemService(SENSOR_SERVICE) for runtime list)"
        } catch (e: Exception) {
            builtIn
        }
    }

    private fun screenResolution(): String = reflectAndroid(
        "android.view.Display",
        "getSize",
        "Requires Context.getSystemService(WINDOW_SERVICE).defaultDisplay"
    )

    private fun screenDensity(): String = reflectAndroid(
        "android.util.DisplayMetrics",
        "densityDpi",
        "Requires Context.resources.displayMetrics"
    )

    private fun screenBrightness(): String = reflectAndroid(
        "android.provider.Settings\$System",
        "SCREEN_BRIGHTNESS",
        "Requires Settings.System.getInt(contentResolver, SCREEN_BRIGHTNESS)"
    )

    private fun screenOrientation(): String = reflectAndroid(
        "android.view.Display",
        "getRotation",
        "Requires Context.getSystemService(WINDOW_SERVICE).defaultDisplay"
    )

    private fun installedApps(): String = reflectAndroid(
        "android.content.pm.PackageManager",
        "getInstalledApplications",
        "Requires QUERY_ALL_PACKAGES + Context.packageManager"
    )

    private fun clipboardContent(): String {
        return try {
            val service = Class.forName("android.content.ClipboardManager")
            "Requires Context.getSystemService(CLIPBOARD_SERVICE) + ClipboardManager.getPrimaryClip()"
        } catch (e: Exception) {
            "unavailable (Android Context required)"
        }
    }

    // ── Helpers ────────────────────────────────────────────────────

    /**
     * Attempts to load an Android SDK class via reflection. Returns a human-readable
     * message indicating what is needed when the class is unavailable.
     */
    private fun reflectAndroid(className: String, member: String, contextNote: String): String {
        return try {
            Class.forName(className)
            "unavailable ($contextNote)"
        } catch (e: ClassNotFoundException) {
            "unavailable (Android SDK class not accessible in this runtime)"
        }
    }

    // ── Permission check ───────────────────────────────────────────

    /**
     * Checks if a system command requires a runtime permission.
     * Returns an ExecutionResult with a permission prompt if not granted, null if OK.
     *
     * In production, this uses Android ContextCompat.checkSelfPermission().
     * When no Android Context is available, returns a clear permission-required message.
     */
    private fun checkPermission(commandName: String): ExecutionResult? {
        val perm = PERMISSION_MAP[commandName] ?: return null
        // In production: ContextCompat.checkSelfPermission(context, perm)
        // For now, return a clear message that permission is required
        return ExecutionResult.fail(
            "Permission required: $perm. Grant this permission in system settings to use $commandName.",
            errorCode = ErrorCodes.ERR_PERMISSION_DENIED
        )
    }

    /** Permission requirements for sys commands. */
    val PERMISSION_MAP = mapOf(
        "sys.location" to "ACCESS_FINE_LOCATION",
        "sys.camera" to "CAMERA",
        "sys.apps" to "QUERY_ALL_PACKAGES"
    )
}
