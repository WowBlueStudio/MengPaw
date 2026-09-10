// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

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
    val runtime: RuntimeStatus,
    /** Memory Twin protocol version for compatibility negotiation. */
    val protocolVersion: String = "0.2"
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
    val estimatedQuality: ModelQuality,
    // ── 2026-09-10 进化: 每个判定都带上"来源", 让路由知道自己凭什么 ──
    // 全字段带默认值 → 旧版对端能力卡 (无这些字段) 仍可解析, 协议向后兼容。
    /** 档位来源 (LEARNED 实测 / EXTERNAL 外置规则 / BUILTIN 内置规则 / UNKNOWN 未知)。 */
    val qualitySource: CapabilitySource = CapabilitySource.UNKNOWN,
    /** 上下文来源; GUESS = 由档位推断的兜底 (TwinRouter 只给部分分)。 */
    val ctxSource: CapabilitySource = CapabilitySource.UNKNOWN,
    /** 视觉判定来源; UNKNOWN = 不知道 (路由中性处理 — 不因"不确定"就扣分)。 */
    val visionSource: CapabilitySource = CapabilitySource.UNKNOWN,
    /** 工具判定来源。 */
    val toolsSource: CapabilitySource = CapabilitySource.UNKNOWN,
    /** 本机实测证据条数 (0 = 这份画像纯靠声明, 未经实测)。 */
    val evidenceCount: Int = 0,
    /** 命中的规则 id — 可追溯"这条判定出自哪条规则"。 */
    val matchedRuleId: String? = null
) {
    /** 未知能力不再被当作"弱": 路由据此中性处理 (见 [TwinRouter])。 */
    val qualityKnown: Boolean get() = estimatedQuality != ModelQuality.UNKNOWN

    /** 上下文是否已知 (GUESS 也算"猜到", 但已知数值)。 */
    val ctxKnown: Boolean get() = contextWindowTokens > 0

    /** 一句话摘要 — 供命令展示与能力卡对比表使用。 */
    fun summary(): String = buildString {
        append(modelName)
        append(" | 档=").append(estimatedQuality)
        if (qualitySource != CapabilitySource.UNKNOWN) append("(").append(qualitySource.shortLabel()).append(")")
        append(" | 上下文=")
        append(if (ctxKnown) "${contextWindowTokens / 1000}K(${ctxSource.shortLabel()})" else "未知")
        append(" | 视觉=")
        append(
            when {
                visionSource == CapabilitySource.UNKNOWN -> "未知"
                supportsVision -> "支持(${visionSource.shortLabel()})"
                else -> "不支持(${visionSource.shortLabel()})"
            }
        )
        if (evidenceCount > 0) append(" | 实测证据=").append(evidenceCount).append("条")
    }
}

/** 能力来源的中文短标 (命令输出用)。 */
fun CapabilitySource.shortLabel(): String = when (this) {
    CapabilitySource.LEARNED -> "实测"
    CapabilitySource.EXTERNAL -> "外置规则"
    CapabilitySource.BUILTIN -> "内置规则"
    CapabilitySource.GUESS -> "推断"
    CapabilitySource.UNKNOWN -> "未知"
}

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
    private val deviceName: String,
    /** P0.4/P3.5: Version sourced from AgentEngine.CORE_VERSION at construction time. */
    private val mengpawVersion: String = "unknown",
    /**
     * 工作区 Agent 名 (2026-09-10) — 用于定位该 Agent 的外置模型能力规则文件
     * `{agent}/twin-model-rules.json`。留空则只用内置规则 + 本机实测证据。
     */
    private val agentName: String = ""
) {
    private val startTime = System.currentTimeMillis()

    /**
     * Collect full capability card.
     * @param runtimeInjection Optional override for runtime state (isOnline auto-detected,
     *   currentSessionId/isBusy injected from AgentEngine). Fixes P0.3/P1.5 hardcoded nulls.
     */
    fun collect(
        llmProvider: LlmProvider? = null,
        pluginNames: List<String> = emptyList(),
        grantedPermissions: List<String> = emptyList(),
        currentSessionId: String? = null,
        isBusy: Boolean = false
    ): CapabilityCard {
        val hw = collectHardware()
        val model = collectModel(llmProvider)
        val sw = collectSoftware(pluginNames, grantedPermissions)
        val runtime = collectRuntime(currentSessionId, isBusy)

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

    /**
     * 模型能力画像 — 2026-09-10 进化: 判定逻辑整体迁到 [ModelProfileResolver]
     * (实测证据 > 外置规则 > 内置族级规则 > 档位推断 > 中性未知)。
     *
     * 这里曾是一串"名字里有没有 pro/flash/mini/gpt-5"的硬编码猜测, 型号一换代就静默判错,
     * 且判错直接改变 [TwinRouter] 的路由结论 (DeepSeek 更换规范 id 后视觉能力误判即实例)。
     */
    private fun collectModel(llmProvider: LlmProvider?): ModelProfile =
        ModelProfileResolver.resolve(llmProvider, agentName)

    // ── Software collection ───────────────────────────────────────

    private fun collectSoftware(
        pluginNames: List<String>,
        grantedPermissions: List<String>
    ): SoftwareProfile {
        return SoftwareProfile(
            mengpawVersion = mengpawVersion, // P0.4: Real version injected at construction time
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

    private fun collectRuntime(currentSessionId: String? = null, isBusy: Boolean = false): RuntimeStatus {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        // P0.3: Actually check network connectivity instead of hardcoding true
        val online = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetworkInfo
            activeNetwork?.isConnectedOrConnecting == true
        } catch (_: Exception) { true } // default to online if check fails
        return RuntimeStatus(
            isOnline = online,
            uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000,
            lastSeenAt = System.currentTimeMillis(),
            currentSessionId = currentSessionId, // P1.5: Injected from AgentEngine
            isBusy = isBusy // P1.5: Injected from AgentEngine
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

    // ── P1.4: Auto-collect on system state changes ─────────────────

    companion object {
        /**
         * Register Android system broadcast receivers for auto-recollection
         * when battery, connectivity, or charging state changes.
         *
         * @param context Application context
         * @param onCardChange Callback invoked with the new CapabilityCard on any change
         * @return The registered receiver (caller should keep reference to unregister)
         */
        fun registerAutoCollect(
            context: Context,
            onCardChange: (CapabilityCard) -> Unit
        ): android.content.BroadcastReceiver {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: android.content.Intent?) {
                    // Re-collect and emit on any matching system event
                    try {
                        val deviceId = try { com.mengpaw.kernel.acp.AcpCrypto.myFingerprint() }
                            catch (_: Exception) { "device-${System.currentTimeMillis()}" }
                        val deviceName = try { android.os.Build.MODEL } catch (_: Exception) { "Android" }
                        val collector = TwinCapabilityCollector(ctx, deviceId, deviceName,
                            agentName = MemoryTwinPlugin.agentName)
                        val card = collector.collect()
                        onCardChange(card)
                    } catch (e: Exception) {
                        com.mengpaw.kernel.error.ErrorCollector.report(e, "TwinCapability.autoCollect")
                    }
                }
            }
            val filter = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_BATTERY_CHANGED)
                addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION)
                addAction(android.content.Intent.ACTION_POWER_CONNECTED)
                addAction(android.content.Intent.ACTION_POWER_DISCONNECTED)
            }
            context.registerReceiver(receiver, filter)
            return receiver
        }
    }
}
