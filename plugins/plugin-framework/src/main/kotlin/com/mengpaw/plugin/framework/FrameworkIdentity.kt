// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.framework

import android.content.Context
import android.provider.Settings
import com.mengpaw.kernel.DataPaths
import org.json.JSONObject
import java.io.File

/**
 * 本机框架名片 (v0.34.3 框架发现调整) — 持久化 {BASE}/配置/framework_identity.json。
 *
 * - displayName: 自定义框架名 (空 = 缺省, 其他设备显示指纹码)
 * - fingerprint: 本机绑定标识 = "mengpaw|设备标识" — 换 IP 不变; 显示短码 = 设备标识尾 6 位 (xxx-xxx)
 * - 设备标识 (v0.35.1): Android 10+ 普通应用拿不到真实 Wi-Fi MAC (NetworkInterface.getHardwareAddress
 *   返回 null, WifiManager 返回 02:00:00:00:00:00) — 优先真实 MAC (Android 9-), 否则 ANDROID_ID 兜底
 *   (免权限、每设备唯一、卸载重装不变), 双端都无则 no-device-id (理论不出现)。
 */
object FrameworkIdentity {

    private val file: File get() = File(DataPaths.CONFIG, "framework_identity.json")

    /** load(context) 缓存 — 设备标识 (ANDROID_ID) 需要 ContentResolver。 */
    private var appContext: Context? = null

    @Volatile var displayName: String = ""
        private set

    /** 本机指纹 (完整 16 hex) — 由 MAC 派生, 首次访问计算并缓存。 */
    @Volatile var fingerprint: String = ""
        private set

    /** 显示用短码 — 前 6 位 hex, 格式 xxx-xxx。 */
    val shortCode: String
        get() = FrameworkPeerStore.shortCodeOf(fingerprint)

    /** 加载身份 (启动时调用) — 懒加载。 */
    fun load(context: Context) {
        appContext = context.applicationContext
        try {
            if (!file.exists()) { fingerprint = computeLocalFingerprint(); return }
            val obj = JSONObject(file.readText())
            displayName = obj.optString("displayName", "")
            fingerprint = obj.optString("fingerprint", "").ifEmpty { computeLocalFingerprint() }
        } catch (_: Exception) {
            fingerprint = computeLocalFingerprint()
        }
    }

    /** 设置自定义框架名 (空 = 缺省)。持久化 + 更新指纹缓存 (指纹绑 MAC, 名称不影响指纹)。 */
    fun setDisplayName(name: String) {
        displayName = name.trim()
        if (fingerprint.isEmpty()) fingerprint = computeLocalFingerprint()
        persist()
    }

    /** 本机绑定标识 = "mengpaw|设备标识" — 换 IP 不变。 */
    private fun computeLocalFingerprint(): String {
        return FrameworkPeerStore.computeFingerprint("mengpaw", deviceId())
    }

    /**
     * 设备标识 — 真实 MAC 优先 (Android 9- 可拿); Android 10+ 拿不到 →
     * ANDROID_ID 兜底 (免权限, 每设备唯一, 卸载重装不变)。mDNS 广播与配对请求复用。
     */
    fun deviceId(): String {
        localMacAddress()?.let { return it }
        val ctx = appContext ?: return "no-device-id"
        return try {
            val aid = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            if (aid.isNullOrBlank() || aid == "9774d56d682e549c") "no-device-id" else "android-id-$aid"
        } catch (_: Exception) { "no-device-id" }
    }

    /** 设备标识原文 (Android ID / 低版本 MAC) — 名片第二行展示用, 无前缀。 */
    fun deviceRawId(): String {
        localMacAddress()?.let { return it }
        val ctx = appContext ?: return ""
        return try {
            val aid = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            if (aid.isNullOrBlank() || aid == "9774d56d682e549c") "" else aid
        } catch (_: Exception) { "" }
    }

    /** 本机真实 MAC — NetworkInterface 遍历取非回环硬件地址; Android 10+ 恒 null (系统限制)。 */
    internal fun localMacAddress(): String? {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { !it.isLoopback && !it.isVirtual }
                ?.mapNotNull { ni ->
                    ni.hardwareAddress?.takeIf { it.size >= 6 }?.joinToString(":") { "%02x".format(it) }
                }
                ?.firstOrNull { it.startsWith("wlan") || it.contains(":") } // wlan 优先, 其余兜底
        } catch (_: Exception) { null }
    }

    private fun persist() {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(JSONObject().apply {
                put("displayName", displayName)
                put("fingerprint", fingerprint)
            }.toString(2))
            tmp.renameTo(file)
            if (tmp.exists()) try { tmp.delete() } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    /** 测试隔离。 */
    fun resetForTest() {
        displayName = ""
        fingerprint = ""
        appContext = null
        file.delete()
    }
}
