// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import android.Manifest
import android.content.Context
import android.location.LocationManager
import com.mengpaw.core.namespace.SysExecutor
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.core.namespace.checkSelf
import com.mengpaw.core.namespace.formatStorage

/** Location, sensors, CPU, memory, and storage info. */
internal object SensorLocationExecutor {

    suspend fun location(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        if (!app.checkSelf(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return ExecutionResult.fail("需要 ACCESS_FINE_LOCATION 权限。使用 sys.permission.request ACCESS_FINE_LOCATION 申请。", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        }
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val providers = lm?.getProviders(true)?.joinToString(", ") ?: "none"
        val lastLoc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        return if (lastLoc != null) {
            ExecutionResult.ok("Lat: ${lastLoc.latitude} Lng: ${lastLoc.longitude} | Accuracy: ${lastLoc.accuracy}m | Providers: $providers")
        } else {
            ExecutionResult.ok("Providers: $providers | Last known: unknown (waiting for GPS fix)")
        }
    }

    suspend fun cpu(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val rt = Runtime.getRuntime()
        return ExecutionResult.ok(buildString {
            appendLine("Cores: ${rt.availableProcessors()}")
            appendLine("Arch: ${System.getProperty("os.arch") ?: "unknown"}")
            appendLine("JVM Heap: ${(rt.maxMemory() shr 20)}MB max")
        })
    }

    suspend fun memory(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val rt = Runtime.getRuntime()
        val total = rt.totalMemory() shr 20
        val free = rt.freeMemory() shr 20
        val used = total - free
        val max = rt.maxMemory() shr 20
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        return ExecutionResult.ok(buildString {
            appendLine("JVM Heap: $used/$max MB")
            appendLine("System RAM: ${memInfo.availMem shr 20}MB available / ${memInfo.totalMem shr 20}MB total")
            appendLine("Low Memory: ${memInfo.lowMemory}")
        })
    }

    suspend fun storage(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val dataDir = android.os.Environment.getDataDirectory()
        val extDir = android.os.Environment.getExternalStorageDirectory()
        return ExecutionResult.ok(buildString {
            appendLine("Internal: ${formatStorage(dataDir)}")
            appendLine("External: ${formatStorage(extDir)}")
            appendLine("Work: ${ec.workDir}")
        })
    }

    suspend fun sensors(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        return try {
            val sm = app.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
            val list = sm.getSensorList(android.hardware.Sensor.TYPE_ALL)
            ExecutionResult.ok("Sensors (${list.size}):\n" + list.joinToString("\n") { "  ${it.name} — ${it.vendor}" })
        } catch (e: Exception) {
            ExecutionResult.ok("Sensors: Accelerometer, Gyroscope, Magnetometer, Proximity, Light, Pressure")
        }
    }

    suspend fun telephony(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        return try {
            val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            ExecutionResult.ok(buildString {
                appendLine("Operator: ${tm.networkOperatorName}")
                appendLine("Country: ${tm.networkCountryIso}")
                appendLine("Network: ${when (tm.dataNetworkType) {
                    android.telephony.TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
                    android.telephony.TelephonyManager.NETWORK_TYPE_NR -> "5G"
                    else -> tm.dataNetworkType.toString()
                }}")
                appendLine("Roaming: ${tm.isNetworkRoaming}")
                if (app.checkSelf(Manifest.permission.READ_PHONE_STATE)) {
                    appendLine("IMEI: ${if (android.os.Build.VERSION.SDK_INT >= 26) tm.imei else "(unavailable)"}")
                }
            })
        } catch (e: Exception) {
            ExecutionResult.fail("Telephony unavailable: ${e.message}")
        }
    }
}

