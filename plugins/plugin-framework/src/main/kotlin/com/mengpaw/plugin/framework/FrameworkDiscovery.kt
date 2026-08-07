// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.framework

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.ports.Ports
import kotlinx.coroutines.*

/**
 * 局域网框架发现 — 基于 Android NsdManager (mDNS)。
 *
 * 注册服务类型: _mengpaw._tcp
 * 每 30s 自动扫描，发现后写入 FrameworkPeerStore。
 */
class FrameworkDiscovery(private val context: Context) {
    companion object {
        const val SERVICE_TYPE = "_mengpaw._tcp"
        const val SERVICE_PORT = Ports.ACP

        @Volatile var instance: FrameworkDiscovery? = null
    }

    private val nsdManager: NsdManager? by lazy {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }
    private var registered = false
    private var discovering = false
    private var discoveryLoop: Job? = null
    private val discoveryScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    /** 设备显示名称 */
    var deviceName: String = Build.MODEL ?: "MengPaw"

    /** 判断地址是否为本机 — mDNS 自发现过滤用（实例名改名后名字比对不可靠） */
    private fun isLocalAddress(addr: String?): Boolean {
        if (addr.isNullOrBlank()) return false
        return try {
            val target = java.net.InetAddress.getByName(addr).hostAddress
            java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .any { it.hostAddress == target }
        } catch (_: Exception) { false }
    }
    /** 框架名称（软件名） */
    var frameworkName: String = "MengPaw"
    /** 框架版本 */
    var frameworkVersion: String = com.mengpaw.kernel.AgentEngine.CORE_VERSION
    /** 能力列表 */
    var capabilities: List<String> = listOf("goal", "mission", "research")
    /** Agent 列表 */
    var agentNames: List<String> = emptyList()

    // ── 注册本机服务 ──
    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            KernelLog.i("FrameworkDiscovery", "Registered: ${info.serviceName}")
            registered = true
        }
        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
            KernelLog.w("FrameworkDiscovery", "Registration failed: $errorCode")
            registered = false
        }
        override fun onServiceUnregistered(info: NsdServiceInfo) { registered = false }
        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
    }

    fun register() {
        val nsd = nsdManager ?: return
        if (registered) return
        val info = NsdServiceInfo().apply {
            serviceName = "MengPaw-$deviceName"
            serviceType = SERVICE_TYPE
            port = SERVICE_PORT
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            info.setAttribute("fwname", frameworkName)
            info.setAttribute("version", frameworkVersion)
            info.setAttribute("capabilities", capabilities.joinToString(","))
            info.setAttribute("agents", agentNames.joinToString(","))
        }
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener) }
        catch (e: Exception) { KernelLog.w("FrameworkDiscovery", "register failed: ${e.message}") }
        // 清理历史遗留的本机自条目: mDNS 实例名冲突时系统会把本机注册名改成 "... (2)"，
        // 名字比对过滤失效 → 自己被解析并写入 store，这里按 IP 兜底清除（幂等，每次启动一次）
        try {
            FrameworkPeerStore.loadAll()
                .filter { isLocalAddress(it.address) }
                .forEach {
                    FrameworkPeerStore.remove(it.fingerprint)
                    KernelLog.i("FrameworkDiscovery", "purged self peer: ${it.name} @ ${it.address}")
                }
        } catch (e: Exception) {
            KernelLog.w("FrameworkDiscovery", "purge self peers failed: ${e.message}")
        }
    }

    /** 启动持续发现循环 — 每 30s 重新扫描一次 */
    fun startContinuousDiscovery() {
        startDiscovery()
        discoveryLoop?.cancel()
        discoveryLoop = discoveryScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(30_000)
                // 停止旧扫描 → 重启，确保发现列表持续刷新
                stopDiscovery()
                delay(500)
                startDiscovery()
            }
        }
    }

    fun unregister() {
        val nsd = nsdManager ?: return
        if (!registered) return
        try { nsd.unregisterService(registrationListener); registered = false }
        catch (_: Exception) {}
    }

    // ── 发现其他框架 ──
    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) { discovering = true }
        override fun onDiscoveryStopped(type: String) { discovering = false }
        override fun onServiceFound(info: NsdServiceInfo) {
            // 忽略本机服务（名称相同）
            if (info.serviceName == "MengPaw-$deviceName") return
            // 解析详细信息 — 每次 new listener: 共享 listener 并发 resolve 会触发 NsdManager
            // "listener already in use" 崩溃 (Android 14 严格校验; 荣耀平板 v0.34.0 启动即闪退根因)
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceResolved(info: NsdServiceInfo) = handleResolved(info)
            }
            try { nsdManager?.resolveService(info, listener) }
            catch (e: Exception) { KernelLog.w("FrameworkDiscovery", "resolve failed: ${e.message}") }
        }
        override fun onServiceLost(info: NsdServiceInfo) {
            val fp = FrameworkPeerStore.computeFingerprint(
                info.serviceName.removePrefix("MengPaw-"),
                info.host?.hostAddress ?: ""
            )
            KernelLog.i("FrameworkDiscovery", "Lost: ${info.serviceName}")
        }
        override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
            KernelLog.w("FrameworkDiscovery", "Discovery start failed: $errorCode")
        }
        override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}
    }

    /** 解析成功处理 — 提取自共享 resolveListener (v0.34.1: 每次发现 new listener 防并发竞态) */
    private fun handleResolved(info: NsdServiceInfo) {
        val name = info.serviceName.removePrefix("MengPaw-")
        val addr = info.host?.hostAddress ?: return
        // 自发现过滤（IP 比对兜底）: 实例名可能被系统改名，名字比对不可靠
        if (isLocalAddress(addr)) return
        // 属性读取 API 33+
        val fwName = if (android.os.Build.VERSION.SDK_INT >= 33)
            info.attributes["fwname"]?.let { String(it) } ?: "MengPaw"
        else "MengPaw"
        val version = if (android.os.Build.VERSION.SDK_INT >= 33)
            info.attributes["version"]?.let { String(it) } ?: "?"
        else "?"
        val caps = if (android.os.Build.VERSION.SDK_INT >= 33)
            info.attributes["capabilities"]?.let { String(it) }?.split(",") ?: emptyList()
        else emptyList()
        val agents = if (android.os.Build.VERSION.SDK_INT >= 33)
            info.attributes["agents"]?.let { String(it) }?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        else emptyList()
        val fp = FrameworkPeerStore.computeFingerprint(name, addr)
        val now = System.currentTimeMillis()
        val peer = FrameworkPeerStore.FrameworkPeer(
            fingerprint = fp, name = name, version = version,
            frameworkName = fwName,
            address = addr, port = info.port,
            capabilities = caps, agents = agents,
            lastSeen = now,
            trusted = FrameworkPeerStore.findByFingerprint(fp)?.trusted ?: false
        )
        FrameworkPeerStore.save(peer)
        KernelLog.i("FrameworkDiscovery", "Found: $name ($fwName v$version) @ $addr:$info.port agents=${agents.size}")
    }

    fun startDiscovery() {
        val nsd = nsdManager ?: return
        if (discovering) return
        try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
        catch (e: Exception) { KernelLog.w("FrameworkDiscovery", "discover failed: ${e.message}") }
    }

    fun stopDiscovery() {
        val nsd = nsdManager ?: return
        if (!discovering) return
        try { nsd.stopServiceDiscovery(discoveryListener); discovering = false }
        catch (_: Exception) {}
    }

    /** 存活检测 — 向指定地址 ping */
    fun ping(address: String, port: Int = SERVICE_PORT): Boolean {
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(address, port), 2000)
            socket.close()
            true
        } catch (_: Exception) { false }
    }
}
