// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.framework

import com.mengpaw.kernel.DataPaths
import org.json.JSONObject
import java.io.File

/**
 * 本机框架名片 (v0.34.3 框架发现调整) — 持久化 {BASE}/配置/framework_identity.json。
 *
 * - displayName: 自定义框架名 (空 = 缺省, 其他设备显示指纹码)
 * - fingerprint: 本机指纹 = SHA256(mac) — 绑 MAC, 换 IP 不变; 显示为 6 位短码 (xxx-xxx)
 */
object FrameworkIdentity {

    private val file: File get() = File(DataPaths.CONFIG, "framework_identity.json")

    @Volatile var displayName: String = ""
        private set

    /** 本机指纹 (完整 16 hex) — 由 MAC 派生, 首次访问计算并缓存。 */
    @Volatile var fingerprint: String = ""
        private set

    /** 显示用短码 — 前 6 位 hex, 格式 xxx-xxx。 */
    val shortCode: String
        get() {
            val fp = fingerprint
            return if (fp.length >= 6) "${fp.take(3)}-${fp.drop(3).take(3)}" else fp
        }

    /** 加载身份 (启动时调用) — 懒加载。 */
    fun load() {
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

    /** 本机指纹 = SHA256("self|MAC") — 绑 MAC, 换 IP 不变; 无 MAC (低版本/模拟器) 回退 Build 指纹。 */
    private fun computeLocalFingerprint(): String {
        val mac = localMacAddress() ?: "no-mac"
        return FrameworkPeerStore.computeFingerprint("self", mac)
    }

    /** 本机 WiFi MAC — NetworkInterface 遍历取非回环硬件地址 (无需权限)。
     *  internal 供 FrameworkDiscovery 注册 mDNS mac 属性复用。 */
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
        file.delete()
    }
}
