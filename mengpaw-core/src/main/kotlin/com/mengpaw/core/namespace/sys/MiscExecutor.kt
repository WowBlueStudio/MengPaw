// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.ConsumerIrManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.mengpaw.core.namespace.SysExecutor
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 杂项设备命令 — toast / wakelock / 红外 / USB
 * (对齐 Termux:API termux-toast / termux-wake-lock / termux-infrared-transmit / termux-usb)。
 *
 * toast/wakelock/ir 无需新权限; usb.request 弹系统授权 (需 USB_HOST 硬件),
 * 命令风险等级 MID — 默认拒绝, 仅 TRUSTED 放行。
 */
internal object MiscExecutor {

    private const val USB_PERM_ACTION = "com.mengpaw.action.USB_PERMISSION"
    private val usbLock = Any()
    private var usbPending: CompletableDeferred<Boolean>? = null
    private var wakelock: PowerManager.WakeLock? = null
    private val wakelockLock = Any()

    suspend fun toast(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext
            ?: return ExecutionResult.fail("SysExecutor not initialized", errorCode = ErrorCodes.ERR_INTERNAL)
        val text = args.joinToString(" ").trim()
        if (text.isEmpty()) {
            return ExecutionResult.fail("Usage: sys.toast <消息文本>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
        return try {
            val long = args.any { it.equals("long", ignoreCase = true) }
            Toast.makeText(app, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            ExecutionResult.ok("toast: 已显示")
        } catch (e: Exception) {
            ExecutionResult.fail("toast 失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    suspend fun wakelockAcquire(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext
            ?: return ExecutionResult.fail("SysExecutor not initialized", errorCode = ErrorCodes.ERR_INTERNAL)
        synchronized(wakelockLock) {
            if (wakelock?.isHeld == true) return ExecutionResult.ok("已处于唤醒状态 (wakelock)")
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mengpaw:sys_wakelock")
            wl.acquire(30 * 60 * 1000L)
            wakelock = wl
        }
        return ExecutionResult.ok("已获取唤醒锁 (30 分钟自动释放)。释放: sys.wakelock.release")
    }

    suspend fun wakelockRelease(args: List<String>, ec: ExecutionContext): ExecutionResult {
        synchronized(wakelockLock) {
            val wl = wakelock
            if (wl != null) {
                try {
                    if (wl.isHeld) wl.release()
                } catch (_: Exception) {}
                wakelock = null
                return ExecutionResult.ok("唤醒锁已释放")
            }
        }
        return ExecutionResult.ok("当前未持有唤醒锁")
    }

    suspend fun irTransmit(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext
            ?: return ExecutionResult.fail("SysExecutor not initialized", errorCode = ErrorCodes.ERR_INTERNAL)
        val ir = app.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
            ?: return ExecutionResult.fail("设备不支持红外 (ConsumerIrManager 不可用)", errorCode = ErrorCodes.ERR_INTERNAL)
        if (!ir.hasIrEmitter()) {
            return ExecutionResult.fail("设备无红外发射器", errorCode = ErrorCodes.ERR_INTERNAL)
        }
        val freq = args.firstOrNull()?.toIntOrNull() ?: 38000
        val pattern = args.drop(1).mapNotNull { it.toIntOrNull() }
        if (pattern.isEmpty()) {
            return ExecutionResult.fail(
                "Usage: sys.ir.transmit <频率Hz> <时长1> <间隔1> <时长2>... (微秒)",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        }
        return try {
            ir.transmit(freq, pattern.toIntArray())
            ExecutionResult.ok("红外信号已发送 (${freq}Hz, ${pattern.size} 段)")
        } catch (e: Exception) {
            ExecutionResult.fail("红外发送失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    suspend fun usbList(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext
            ?: return ExecutionResult.fail("SysExecutor not initialized", errorCode = ErrorCodes.ERR_INTERNAL)
        val um = app.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return ExecutionResult.fail("UsbManager 不可用 (设备无 USB Host 支持)", errorCode = ErrorCodes.ERR_INTERNAL)
        val devices = um.deviceList.values
        if (devices.isEmpty()) return ExecutionResult.ok("(未连接 USB 设备)")
        return ExecutionResult.ok(devices.map { d ->
            "name=${d.deviceName} | vendor=${d.vendorId} | product=${d.productId} | class=${d.deviceClass} | " +
                "permission=${um.hasPermission(d)}"
        }.joinToString("\n"))
    }

    suspend fun usbRequest(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext
            ?: return ExecutionResult.fail("SysExecutor not initialized", errorCode = ErrorCodes.ERR_INTERNAL)
        val name = args.firstOrNull()
            ?: return ExecutionResult.fail(
                "Usage: sys.usb.request <设备名> (用 sys.usb.list 查看)",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        val um = app.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return ExecutionResult.fail("UsbManager 不可用", errorCode = ErrorCodes.ERR_INTERNAL)
        val device = um.deviceList[name]
            ?: return ExecutionResult.fail("USB 设备不存在: $name (先执行 sys.usb.list)", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        if (um.hasPermission(device)) return ExecutionResult.ok("已具备访问权限: $name")

        val deferred = CompletableDeferred<Boolean>()
        synchronized(usbLock) {
            if (usbPending != null) {
                return ExecutionResult.fail("已有 USB 授权请求在途", errorCode = ErrorCodes.ERR_INTERNAL)
            }
            usbPending = deferred
        }
        val filter = IntentFilter(USB_PERM_ACTION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != USB_PERM_ACTION) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                synchronized(usbLock) {
                    usbPending?.complete(granted)
                    usbPending = null
                }
                try { context?.unregisterReceiver(this) } catch (_: Exception) {}
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                app, 0,
                Intent(USB_PERM_ACTION).setPackage(app.packageName),
                flags or PendingIntent.FLAG_UPDATE_CURRENT
            )
            um.requestPermission(device, pi)
        } catch (e: Exception) {
            synchronized(usbLock) {
                usbPending?.complete(false)
                usbPending = null
            }
            try { app.unregisterReceiver(receiver) } catch (_: Exception) {}
            return ExecutionResult.fail("USB 授权请求失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
        // P2 修复: 超时后立即注销 receiver 并清理在途标记, 避免接收器悬挂到用户下次响应。
        val granted = withTimeoutOrNull(30_000L) { deferred.await() }
        if (granted == null) {
            synchronized(usbLock) {
                usbPending?.complete(false)
                usbPending = null
            }
            try { app.unregisterReceiver(receiver) } catch (_: Exception) {}
            return ExecutionResult.fail("USB 授权等待超时 (30s)", errorCode = ErrorCodes.ERR_TIMEOUT)
        }
        if (granted) {
            return ExecutionResult.ok("已获得 USB 设备访问权限: $name")
        }
        return ExecutionResult.fail("用户拒绝或超时未授权 USB 设备: $name", errorCode = ErrorCodes.ERR_TIMEOUT)
    }
}
