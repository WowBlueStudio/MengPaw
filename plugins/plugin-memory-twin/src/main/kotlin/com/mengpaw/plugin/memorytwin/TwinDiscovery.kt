// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.memorytwin

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * LAN discovery for Memory Twin peers via Android NSD (mDNS).
 *
 * Registers this device as a "_mengpaw-twin._tcp" service and discovers
 * other MengPaw twin peers on the same WiFi network automatically.
 *
 * ## Protocol
 * - Service type: `_mengpaw-twin._tcp`
 * - Each device advertises: deviceId, agentName, port
 * - Discovery is automatic on WiFi connect
 *
 * ## Usage
 * ```kotlin
 * val discovery = TwinDiscovery(context, deviceId, agentName)
 * discovery.start()
 * discovery.discoveredPeers.collect { peer ->
 *     syncEngine.updatePeers(peer)
 * }
 * ```
 */
class TwinDiscovery(
    private val context: Context,
    private val deviceId: String,
    private val agentName: String,
    private val port: Int = 9876
) {
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isRunning = false

    companion object {
        const val SERVICE_TYPE = "_mengpaw-twin._tcp"
        const val SERVICE_NAME_PREFIX = "MengPaw-Twin-"
    }

    /**
     * Flow of discovered twin peers. Emits lists of peer info as they are found.
     */
    val discoveredPeers: Flow<List<TwinPeerInfo>> = callbackFlow {
        val peers = mutableListOf<TwinPeerInfo>()

        // Resolve listener: called when a service is fully resolved (IP/port available)
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                try {
                    val peerId = serviceInfo.attributes["deviceId"]?.let { String(it) } ?: return
                    val peerName = serviceInfo.attributes["agentName"]?.let { String(it) } ?: "Unknown"
                    val peerPort = serviceInfo.port.takeIf { it > 0 } ?: 9876
                    val host = serviceInfo.host?.hostAddress ?: return

                    val existing = peers.find { it.peerId == peerId }
                    if (existing != null) {
                        existing.address = host
                        existing.port = peerPort
                        existing.lastSeen = System.currentTimeMillis()
                    } else {
                        peers.add(TwinPeerInfo(
                            peerId = peerId,
                            agentName = peerName,
                            address = host,
                            port = peerPort
                        ))
                    }
                    trySend(peers.toList())
                } catch (e: Exception) {
                    ErrorCollector.report(e, "TwinDiscovery.resolve")
                }
            }

            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                ErrorCollector.report(
                    Exception("NSD resolve failed: code=$errorCode for ${serviceInfo.serviceName}"),
                    "TwinDiscovery"
                )
            }
        }

        // Discovery listener: called when services appear/disappear
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                // Discovery started
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // Resolve the found service to get IP/port
                nsdManager?.resolveService(serviceInfo, resolveListener)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // Remove lost peer
                val lostName = serviceInfo.serviceName
                peers.removeAll { lostName.contains(it.peerId.take(8)) }
                trySend(peers.toList())
            }

            override fun onDiscoveryStopped(serviceType: String) {
                // Discovery stopped
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                ErrorCollector.report(
                    Exception("NSD start discovery failed: code=$errorCode"),
                    "TwinDiscovery"
                )
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                ErrorCollector.report(
                    Exception("NSD stop discovery failed: code=$errorCode"),
                    "TwinDiscovery"
                )
            }
        }

        // Registration listener: called when our service is registered/unregistered
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                ErrorCollector.report(
                    Exception("NSD registration failed: code=$errorCode"),
                    "TwinDiscovery"
                )
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
        }

        awaitClose {
            stop()
        }
    }

    /** Start advertising this device and discovering peers. */
    fun start() {
        if (isRunning) return
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return

        try {
            // Register our own twin service
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "$SERVICE_NAME_PREFIX${deviceId.take(8)}"
                serviceType = SERVICE_TYPE
                port = this@TwinDiscovery.port
                setAttribute("deviceId", deviceId)
                setAttribute("agentName", agentName)
            }
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)

            // Start discovering other twin services
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

            isRunning = true
        } catch (e: Exception) {
            ErrorCollector.report(e, "TwinDiscovery.start")
        }
    }

    /** Stop advertising and discovery. */
    fun stop() {
        if (!isRunning) return
        try {
            nsdManager?.unregisterService(registrationListener)
            nsdManager?.stopServiceDiscovery(discoveryListener)
        } catch (_: Exception) { /* best-effort cleanup */ }
        nsdManager = null
        isRunning = false
    }
}
