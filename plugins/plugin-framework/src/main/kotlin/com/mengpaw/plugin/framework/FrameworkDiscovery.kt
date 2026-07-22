// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.framework

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.mengpaw.kernel.KernelLog
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
        const val SERVICE_PORT = 9876

        @Volatile var instance: FrameworkDiscovery? = null
    }

    private val nsdManager: NsdManager? by lazy {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }
    private var registered = false
    private var discovering = false
    private var discoveryLoop: Job? = null

    /** 设备显示名称 */
    var deviceName: String = Build.MODEL ?: "MengPaw"
    /** 框架名称（软件名） */
    var frameworkName: String = "MengPaw"
    /** 框架版本 */
    var frameworkVersion: String = "0.9.1"
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
    }

    /** 启动持续发现循环 — 每 30s 重新扫描一次 */
    fun startContinuousDiscovery() {
        startDiscovery()
        discoveryLoop?.cancel()
        discoveryLoop = GlobalScope.launch(Dispatchers.IO) {
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
            // 解析详细信息
            nsdManager?.resolveService(info, resolveListener)
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

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
        override fun onServiceResolved(info: NsdServiceInfo) {
            val name = info.serviceName.removePrefix("MengPaw-")
            val addr = info.host?.hostAddress ?: return
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
