// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import com.mengpaw.kernel.device.DeviceCollector
import com.mengpaw.kernel.device.DeviceSnapshot
import com.mengpaw.kernel.error.ErrorCollector

/**
 * Android 设备采集器实现 (P4, docs/memory-git-sync-design.md §7.4)。
 *
 * 在 `MainActivity.deferInit` 注册到 [com.mengpaw.kernel.device.DeviceCollectors] (仿
 * `FleetPlatform.capabilityProvider` 注入模式) — kernel 保持零 Android 依赖。
 *
 * 只采集**库存字段** (§7.3): 型号/形态/OS/CPU/RAM/磁盘/屏幕/传感器/相机。
 * 电量属运行时, 只作为 [DeviceSnapshot.batteryLevel] 供 `device.self` 展示, 不落库存。
 * 所有系统服务读取都包 try/catch —— 任一服务不可用只让该字段缺失, 不能让采集整体失败。
 */
class DeviceCollectorImpl(private val context: Context) : DeviceCollector {

    override fun collect(): DeviceSnapshot? = try {
        DeviceSnapshot(
            deviceName = deviceName(),
            formFactor = detectFormFactor(),
            os = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            cpuCores = Runtime.getRuntime().availableProcessors(),
            ramMB = totalRamMB(),
            storageFreeMB = freeStorageMB(),
            screen = screenSpec(),
            sensors = sensors(),
            camera = cameras(),
            batteryLevel = batteryLevel(),
            isCharging = isCharging()
        )
    } catch (e: Exception) {
        ErrorCollector.report(e, "DeviceCollectorImpl.collect")
        null
    }

    private fun deviceName(): String = try {
        "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android" }
    } catch (_: Exception) {
        "Android"
    }

    /** 形态判定 — 与 `TwinCapabilityCollector.detectFormFactor` 同口径 (大屏阈值 600dp)。 */
    private fun detectFormFactor(): String {
        return try {
            val pm = context.packageManager
            when {
                pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION) -> "TV"
                pm.hasSystemFeature(PackageManager.FEATURE_WATCH) -> "WEAR"
                pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) -> "AUTO"
                pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) -> "TV"
                else -> {
                    val metrics = context.resources.displayMetrics
                    val widthDp = (metrics.widthPixels / metrics.density).toInt()
                    val heightDp = (metrics.heightPixels / metrics.density).toInt()
                    if (widthDp >= 600 || heightDp >= 600) "TABLET" else "PHONE"
                }
            }
        } catch (_: Exception) {
            "UNKNOWN"
        }
    }

    private fun totalRamMB(): Long = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        info.totalMem / (1024 * 1024)
    } catch (_: Exception) {
        Runtime.getRuntime().maxMemory() / (1024 * 1024)
    }

    private fun freeStorageMB(): Long = try {
        (context.filesDir?.usableSpace ?: 0L) / (1024 * 1024)
    } catch (_: Exception) {
        0L
    }

    /** `宽x高@dpi`, 例 `2560x1600@320`。 */
    private fun screenSpec(): String = try {
        val m = context.resources.displayMetrics
        "${m.widthPixels}x${m.heightPixels}@${m.densityDpi}"
    } catch (_: Exception) {
        ""
    }

    /** 传感器 → 短码 (accel/gyro/light/...), 去重保序; 取不到 → 空表。 */
    private fun sensors(): List<String> = try {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sm?.getSensorList(Sensor.TYPE_ALL).orEmpty()
            .map { shortSensorName(it.type) }
            .distinct()
    } catch (_: Exception) {
        emptyList()
    }

    private fun shortSensorName(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER -> "accel"
        Sensor.TYPE_GYROSCOPE -> "gyro"
        Sensor.TYPE_LIGHT -> "light"
        Sensor.TYPE_MAGNETIC_FIELD -> "magnet"
        Sensor.TYPE_PROXIMITY -> "proximity"
        Sensor.TYPE_PRESSURE -> "pressure"
        Sensor.TYPE_STEP_COUNTER -> "step"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "humidity"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "temperature"
        Sensor.TYPE_ROTATION_VECTOR -> "rotation"
        else -> "sensor-$type"
    }

    /** 相机朝向 → rear/front/external, 去重; 无相机 → 空表。 */
    private fun cameras(): List<String> = try {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        cm?.cameraIdList.orEmpty().mapNotNull { id ->
            val facing = cm?.getCameraCharacteristics(id)
                ?.get(CameraCharacteristics.LENS_FACING)
            when (facing) {
                CameraCharacteristics.LENS_FACING_BACK -> "rear"
                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                else -> null
            }
        }.distinct()
    } catch (_: Exception) {
        emptyList()
    }

    private fun batteryLevel(): Int = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    } catch (_: Exception) {
        -1
    }

    private fun isCharging(): Boolean = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val status = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    } catch (_: Exception) {
        false
    }
}
