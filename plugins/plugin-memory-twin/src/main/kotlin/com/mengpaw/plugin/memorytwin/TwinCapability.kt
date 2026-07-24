// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.memorytwin

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.llm.LlmProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * Device capability card — a snapshot of what this device can do.
 *
 * This card is exchanged between twin peers so that the MengPaw Twin Agent
 * can reason about "which body + brain is best for this task?"
 */
@Serializable
data class CapabilityCard(
    val deviceId: String,
    val deviceName: String,
    val deviceModel: String,
    val formFactor: FormFactor,
    val hardware: HardwareProfile,
    val model: ModelProfile,
    val software: SoftwareProfile,
    val runtime: RuntimeStatus
) {
    fun toJson(): String = Json.encodeToString(this)

    companion object {
        fun fromJson(json: String): CapabilityCard =
            Json { ignoreUnknownKeys = true }.decodeFromString(json)
    }
}

@Serializable
enum class FormFactor {
    PHONE, TABLET, TV, AUTO, WEAR, DESKTOP,
    /** Unknown / other form factor. Emitted when detection fails. */
    UNKNOWN
}

@Serializable
data class HardwareProfile(
    val cpuCores: Int,
    val ramTotalMB: Long,
    val storageFreeMB: Long,
    val hasCamera: Boolean,
    val cameraFacing: List<String>,
    val hasBluetooth: Boolean,
    val hasNfc: Boolean,
    val sensors: List<String>,
    val screenWidth: Int,
    val screenHeight: Int,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val networkType: String
)

@Serializable
data class ModelProfile(
    val providerName: String,
    val modelName: String,
    val providerType: String,
    val contextWindowTokens: Int,
    val supportsVision: Boolean,
    val supportsTools: Boolean,
    val estimatedQuality: ModelQuality
)

@Serializable
enum class ModelQuality { HIGH, MEDIUM, BASIC, UNKNOWN }

@Serializable
data class SoftwareProfile(
    val mengpawVersion: String,
    val installedPlugins: List<String>,
    val optionalCapabilities: List<String>,
    val grantedPermissions: List<String>
)

@Serializable
data class RuntimeStatus(
    val isOnline: Boolean,
    val uptimeSeconds: Long,
    val lastSeenAt: Long,
    val currentSessionId: String?,
    val isBusy: Boolean
)

/**
 * Collects a [CapabilityCard] from the current device.
 *
 * Usage:
 * ```kotlin
 * val collector = TwinCapabilityCollector(context, deviceId, deviceName)
 * val card = collector.collect(llmProvider, pluginManager)
 * ```
 */
class TwinCapabilityCollector(
    private val context: Context,
    private val deviceId: String,
    private val deviceName: String
) {
    private val startTime = System.currentTimeMillis()

    fun collect(
        llmProvider: LlmProvider? = null,
        pluginNames: List<String> = emptyList(),
        grantedPermissions: List<String> = emptyList()
    ): CapabilityCard {
        val hw = collectHardware()
        val model = collectModel(llmProvider)
        val sw = collectSoftware(pluginNames, grantedPermissions)
        val runtime = collectRuntime()

        return CapabilityCard(
            deviceId = deviceId,
            deviceName = deviceName,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            formFactor = detectFormFactor(),
            hardware = hw,
            model = model,
            software = sw,
            runtime = runtime
        )
    }

    // ── Hardware collection ───────────────────────────────────────

    private fun collectHardware(): HardwareProfile {
        val cores = Runtime.getRuntime().availableProcessors()
        val ramTotal = Runtime.getRuntime().maxMemory() / (1024 * 1024)

        // Storage
        val storageBytes = context.filesDir?.usableSpace ?: 0L
        val storageFreeMB = storageBytes / (1024 * 1024)

        // Camera
        var hasCamera = false
        val cameraFacing = mutableListOf<String>()
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            cm?.cameraIdList?.forEach { id ->
                hasCamera = true
                val characteristics = cm.getCameraCharacteristics(id)
                val facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                when (facing) {
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> cameraFacing.add("Front")
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> cameraFacing.add("Rear")
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL -> cameraFacing.add("External")
                }
            }
        } catch (_: Exception) { /* Camera info unavailable */ }

        // Bluetooth
        val hasBluetooth = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)

        // NFC
        val hasNfc = context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)

        // Sensors
        val sensors = mutableListOf<String>()
        try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            sm?.getSensorList(android.hardware.Sensor.TYPE_ALL)?.forEach { sensor ->
                sensors.add(sensor.name)
            }
        } catch (_: Exception) { /* Sensor info unavailable */ }

        // Display
        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        // Battery
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryLevel = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val isCharging = try {
            val status = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Exception) { false }

        // Network
        val networkType = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetworkInfo
            when (activeNetwork?.type) {
                ConnectivityManager.TYPE_WIFI -> "WiFi"
                ConnectivityManager.TYPE_MOBILE -> "Mobile"
                ConnectivityManager.TYPE_ETHERNET -> "Ethernet"
                else -> "Disconnected"
            }
        } catch (_: Exception) { "Unknown" }

        return HardwareProfile(
            cpuCores = cores,
            ramTotalMB = ramTotal,
            storageFreeMB = storageFreeMB,
            hasCamera = hasCamera,
            cameraFacing = cameraFacing.distinct(),
            hasBluetooth = hasBluetooth,
            hasNfc = hasNfc,
            sensors = sensors.distinct(),
            screenWidth = screenW,
            screenHeight = screenH,
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            networkType = networkType
        )
    }

    // ── Model collection ──────────────────────────────────────────

    private fun collectModel(llmProvider: LlmProvider?): ModelProfile {
        if (llmProvider == null) {
            return ModelProfile("unknown", "unknown", "UNKNOWN", 0, false, false, ModelQuality.UNKNOWN)
        }
        val info = llmProvider.info()
        // Estimate quality based on model name heuristics
        val quality = when {
            info.model.contains("pro", ignoreCase = true) -> ModelQuality.HIGH
            info.model.contains("flash", ignoreCase = true) -> ModelQuality.MEDIUM
            info.model.contains("turbo", ignoreCase = true) -> ModelQuality.MEDIUM
            info.model.contains("mini", ignoreCase = true) -> ModelQuality.BASIC
            info.model.contains("gpt-5", ignoreCase = true) -> ModelQuality.HIGH
            info.model.contains("gpt-4", ignoreCase = true) -> ModelQuality.HIGH
            info.model.contains("claude", ignoreCase = true) -> ModelQuality.HIGH
            info.model.contains("deepseek-v4", ignoreCase = true) -> ModelQuality.HIGH
            info.model.contains("deepseek-v3", ignoreCase = true) -> ModelQuality.HIGH
            info.model.contains("qwen", ignoreCase = true) -> ModelQuality.MEDIUM
            else -> ModelQuality.BASIC
        }
        // Estimate context window
        val ctxWindow = when {
            info.model.contains("128k", ignoreCase = true) || info.model.contains("128K") -> 128_000
            info.model.contains("32k", ignoreCase = true) || info.model.contains("32K") -> 32_000
            info.model.contains("1m", ignoreCase = true) || info.model.contains("1M") -> 1_000_000
            quality == ModelQuality.HIGH -> 128_000
            quality == ModelQuality.MEDIUM -> 32_000
            else -> 8_000
        }
        return ModelProfile(
            providerName = info.name,
            modelName = info.model,
            providerType = info.providerType.name,
            contextWindowTokens = ctxWindow,
            supportsVision = info.model.contains("vision", ignoreCase = true) ||
                info.model.contains("vl", ignoreCase = true) ||
                info.model.contains("gpt-4o", ignoreCase = true) ||
                info.model.contains("gpt-5", ignoreCase = true),
            supportsTools = quality != ModelQuality.BASIC,
            estimatedQuality = quality
        )
    }

    // ── Software collection ───────────────────────────────────────

    private fun collectSoftware(
        pluginNames: List<String>,
        grantedPermissions: List<String>
    ): SoftwareProfile {
        return SoftwareProfile(
            mengpawVersion = "0.12.12",
            installedPlugins = pluginNames,
            optionalCapabilities = buildList {
                if (pluginNames.any { it.contains("browser") }) add("browser")
                if (pluginNames.any { it.contains("tavily") }) add("tavily_search")
                if (pluginNames.any { it.contains("comfy") }) add("comfyui")
                if (pluginNames.any { it.contains("render") }) add("render")
                if (pluginNames.any { it.contains("workflow") }) add("workflow")
            },
            grantedPermissions = grantedPermissions
        )
    }

    // ── Runtime collection ────────────────────────────────────────

    private fun collectRuntime(): RuntimeStatus {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return RuntimeStatus(
            isOnline = true,
            uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000,
            lastSeenAt = System.currentTimeMillis(),
            currentSessionId = null,
            isBusy = false
        )
    }

    // ── Form factor detection ─────────────────────────────────────

    private fun detectFormFactor(): FormFactor {
        val pm = context.packageManager
        return when {
            pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION) -> FormFactor.TV
            pm.hasSystemFeature(PackageManager.FEATURE_WATCH) -> FormFactor.WEAR
            pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) -> FormFactor.AUTO
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) -> FormFactor.TV
            else -> {
                // Heuristic: tablet = large screen
                val metrics = context.resources.displayMetrics
                val density = metrics.density
                val widthDp = (metrics.widthPixels / density).toInt()
                val heightDp = (metrics.heightPixels / density).toInt()
                if (widthDp >= 600 || heightDp >= 600) FormFactor.TABLET
                else FormFactor.PHONE
            }
        }
    }
}
