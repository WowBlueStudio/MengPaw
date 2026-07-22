// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mengpaw.kernel.DataPaths
import android.content.IntentFilter
import com.mengpaw.kernel.trigger.TriggerEngine
import java.io.File

/**
 * 系统事件接收器 — 零功耗事件监听。
 *
 * 不轮询，不保活。Android 系统在事件发生时自动唤醒本接收器，
 * 执行对应 Agent 动作后立即返回休眠。
 *
 * 支持的事件:
 * - 接通电源 → 自动整理记忆 + 压缩会话上下文
 * - 电量充足 → 执行后台任务
 * - WiFi 连接 → 同步 ACP 消息
 * - 存储不足 → 提醒清理
 * - 安装/卸载应用 → 通知 Agent
 */
class EventReceiver : BroadcastReceiver() {

    companion object {
        @Volatile
        private var registered: EventReceiver? = null

        /** Register for all supported system events. Call once at app startup. */
        fun register(context: Context) {
            if (registered != null) return
            val receiver = EventReceiver()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_OKAY)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction("android.net.conn.CONNECTIVITY_CHANGE")
                addAction(Intent.ACTION_DEVICE_STORAGE_LOW)
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            // Android 14+ (API 34+) requires RECEIVER_NOT_EXPORTED flag for all dynamic receivers.
            // Without it, registerReceiver() throws IllegalArgumentException on targetSdk>=34.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            registered = receiver
        }

        fun unregister(context: Context) {
            registered?.let { try { context.unregisterReceiver(it) } catch (_: Exception) {} }
            registered = null
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        val ctx = context ?: return

        android.util.Log.d("EventReceiver", "Event: $action")

        when (action) {
            // ── 接通电源 → 整理记忆 + 压缩会话 ──
            Intent.ACTION_POWER_CONNECTED -> {
                TriggerEngine.onSystemWake()
                // 触发梦境模式 — 整理记忆、归档、摘要
                try {
                    val result = com.mengpaw.kernel.agent.DreamEngine.dream("MengPaw")
                    android.util.Log.d("EventReceiver", "Dream: reviewed=${result.memoriesReviewed} archived=${result.archived}")
                } catch (e: Exception) {
                    android.util.Log.w("EventReceiver", "Dream failed: ${e.message}")
                }
                context?.let { scheduleAgentTask(it, "power_connected",
                    "已接通电源。梦境整理完成。检查插件更新。") }
            }

            // ── 断开电源 → 进入省电模式 ──
            Intent.ACTION_POWER_DISCONNECTED -> {
                scheduleAgentTask(ctx, "power_disconnected",
                    "设备已断开电源。请休眠所有非必要插件 (plugin.auto sleep-idle)")
            }

            // ── 电量充足 → 可执行重任务 ──
            Intent.ACTION_BATTERY_OKAY -> {
                scheduleAgentTask(ctx, "battery_ok",
                    "电量已恢复充足。检查是否有待处理的后台任务。")
            }

            // ── 电量过低 → 紧急休眠 ──
            Intent.ACTION_BATTERY_LOW -> {
                scheduleAgentTask(ctx, "battery_low",
                    "电量过低！立即休眠所有非核心插件，降低唤醒频率。")
            }

            // ── WiFi 连接 → 同步 ACP ──
            "android.net.conn.CONNECTIVITY_CHANGE" -> {
                TriggerEngine.onSystemWake()
                scheduleAgentTask(ctx, "wifi_connected",
                    "WiFi 已连接。检查 ACP 收件箱是否有待处理任务。")
            }

            // ── 存储不足 ──
            Intent.ACTION_DEVICE_STORAGE_LOW -> {
                scheduleAgentTask(ctx, "storage_low",
                    "设备存储空间不足。清理旧截图、归档记忆、删除临时文件。")
            }

            // ── 应用安装/卸载 ──
            Intent.ACTION_PACKAGE_ADDED -> {
                val pkg = intent.data?.schemeSpecificPart ?: ""
                scheduleAgentTask(ctx, "pkg_added", "检测到新应用安装: $pkg")
            }
            Intent.ACTION_PACKAGE_REMOVED -> {
                val pkg = intent.data?.schemeSpecificPart ?: ""
                scheduleAgentTask(ctx, "pkg_removed", "应用已卸载: $pkg")
            }
        }
    }

    /** Write a task to Agent inbox — Agent picks it up on next trigger cycle. */
    private fun scheduleAgentTask(context: Context, eventId: String, task: String) {
        try {
            val inbox = File(DataPaths.AGENT_INBOX).also { it.mkdirs() }
            val taskFile = File(inbox, "event_${eventId}_${System.currentTimeMillis()}.md")
            taskFile.writeText("# 系统事件: $eventId\n- 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}\n\n$task\n")
            android.util.Log.d("EventReceiver", "Task queued: $eventId")
        } catch (e: Exception) {
            android.util.Log.w("EventReceiver", "Failed to queue task: ${e.message}")
        }
    }
}
