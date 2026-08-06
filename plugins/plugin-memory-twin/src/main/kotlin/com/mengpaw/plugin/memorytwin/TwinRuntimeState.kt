// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.memorytwin

import android.content.BroadcastReceiver
import com.mengpaw.kernel.acp.AcpCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 孪生插件运行时状态 — 从 MemoryTwinPlugin 拆出, 供各命令组共享。
 *
 * 引擎/处理器/发现器在 cmdStart 初始化, 停止时清空; deviceId/deviceName
 * 保持惰性计算 (首次访问), 与拆分前的 lazy 语义一致。
 */
internal class TwinRuntimeState {

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val deviceId: String by lazy {
        try { AcpCrypto.myFingerprint() } catch (_: Exception) { "device-${System.currentTimeMillis()}" }
    }
    val deviceName: String by lazy {
        try { android.os.Build.MODEL ?: "Android Device" } catch (_: Exception) { "Android Device" }
    }

    lateinit var syncEngine: TwinSyncEngine
    lateinit var acpHandler: TwinAcpHandler
    /** P1 修复: handler 绑定的 engine — 引擎切换时需重建 handler 并重新注册。 */
    var handlerEngine: TwinSyncEngine? = null
    var discovery: TwinDiscovery? = null
    var isRunning = false
    /** P1.4: Auto-collect broadcast receiver (registered in cmdStart, unregistered in stopTwinService). */
    var autoCollectReceiver: BroadcastReceiver? = null

    // lateinit 后备字段仅声明类可访问 (Kotlin 限制) — 命令组经此方法读写。
    fun assignSyncEngine(engine: TwinSyncEngine) { syncEngine = engine }
    fun assignAcpHandler(handler: TwinAcpHandler) { acpHandler = handler }
    fun isSyncEngineReady(): Boolean = ::syncEngine.isInitialized
    fun isAcpHandlerReady(): Boolean = ::acpHandler.isInitialized
}
