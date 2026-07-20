// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.pad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import com.mengpaw.core.plugin.Plugin
import com.mengpaw.core.plugin.PluginContext
import com.mengpaw.core.plugin.PluginMetadata
import com.mengpaw.core.plugin.PluginType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * PAD 悬浮窗插件 — 羽化边缘呼吸灯小圆点。
 *
 * 命令:
 *   pad.show     — 显示悬浮窗
 *   pad.hide     — 隐藏悬浮窗
 *
 * 状态:
 *   IDLE=灰色静止, WORKING=蓝色呼吸(1.5s缩放), ERROR=红色闪烁
 */
class PadPlugin : Plugin {

    override val metadata = PluginMetadata(
        id = "pad-plugin", name = "PAD 悬浮窗", version = "1.0.0",
        type = PluginType.NATIVE, author = "MengPaw",
        description = "PAD 模式悬浮窗 — 羽化边缘呼吸灯小圆点，直观显示 Agent 工作状态",
        permissions = listOf("SYSTEM_ALERT_WINDOW", "FOREGROUND_SERVICE"),
        minCoreVersion = "0.2.0",
        commands = listOf("pad.show", "pad.hide")
    )

    override val uiButtons: List<com.mengpaw.core.plugin.PluginUiButton> = listOf(
        com.mengpaw.core.plugin.PluginUiButton("show", "悬浮窗", "TouchApp", com.mengpaw.core.plugin.ButtonPlacement.BOTTOM_SHEET, "pad.show"),
        com.mengpaw.core.plugin.PluginUiButton("hide", "隐藏悬浮窗", "TouchApp", com.mengpaw.core.plugin.ButtonPlacement.HEADER_BAR, "pad.hide")
    )
    override val commands: Map<String, suspend (List<String>, com.mengpaw.core.cli.ExecutionContext) -> com.mengpaw.core.cli.ExecutionResult> = mapOf(
        "show" to { _, _ ->
            FloatingDotService.show()
            com.mengpaw.core.cli.ExecutionResult.ok("Floating dot shown")
        },
        "hide" to { _, _ ->
            FloatingDotService.hideDot()
            com.mengpaw.core.cli.ExecutionResult.ok("Floating dot hidden")
        }
    )

    override suspend fun onInstall(context: PluginContext) {
        // Context is injected via companion.init()
    }

    companion object {
        private var appContext: Context? = null

        /** Must be called from Application/Activity before using the plugin. */
        fun init(context: Context) {
            appContext = context.applicationContext
        }

        /** Update dot state from AgentViewModel. */
        fun updateState(state: DotState) {
            FloatingDotService.updateState(state)
        }

        /** Show the floating dot. */
        fun show() {
            appContext?.let { FloatingDotService.start(it) }
        }

        /** Hide the floating dot. */
        fun hide() {
            FloatingDotService.hideDot()
            appContext?.let { FloatingDotService.stop(it) }
        }

        fun isVisible(): Boolean = FloatingDotService.isRunning()
    }

    enum class DotState { IDLE, WORKING, ERROR }
}

// ── Foreground Service ────────────────────────────────────────────

class FloatingDotService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var dotHidden = false

    companion object {
        private val _state = MutableStateFlow(PadPlugin.DotState.IDLE)
        val state: StateFlow<PadPlugin.DotState> = _state.asStateFlow()
        private var instance: FloatingDotService? = null

        fun updateState(s: PadPlugin.DotState) { _state.value = s }
        fun isRunning() = instance != null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingDotService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingDotService::class.java))
        }
        fun show() { instance?.showDot() }
        fun hideDot() { instance?.hideDot() }
    }

    private val layoutParams by lazy {
        WindowManager.LayoutParams(
            (16 * 2.5f * resources.displayMetrics.density).toInt(),
            (16 * 2.5f * resources.displayMetrics.density).toInt(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 50; y = 200 }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(2001, createNotification())
        createDot()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        floatingView?.let { try { windowManager?.removeView(it) } catch (e: Exception) {} }
        super.onDestroy()
    }

    private fun createDot() {
        val cv = ComposeView(this).apply {
            setContent {
                val dotState by state.collectAsState()
                val density = androidx.compose.ui.platform.LocalDensity.current

                val breathScale by rememberInfiniteTransition(label = "b").animateFloat(
                    0.7f, 1.0f,
                    infiniteRepeatable(tween(1500, easing = EaseInOutCubic), RepeatMode.Reverse),
                    label = "bs"
                )
                // FIX U5: Use InfiniteTransition for ERROR blinking (not System.currentTimeMillis)
                val blinkScale by rememberInfiniteTransition(label = "blink").animateFloat(
                    1f, 0.25f,
                    infiniteRepeatable(tween(300), RepeatMode.Reverse),
                    label = "blink_s"
                )

                val dotColor = when (dotState) {
                    PadPlugin.DotState.IDLE -> Color(0xFF9E9E9E)
                    PadPlugin.DotState.WORKING -> Color(0xFF42A5F5)
                    PadPlugin.DotState.ERROR -> Color(0xFFEF5350)
                }
                val scale = when (dotState) {
                    PadPlugin.DotState.IDLE -> 1f
                    PadPlugin.DotState.WORKING -> breathScale
                    PadPlugin.DotState.ERROR -> blinkScale  // FIX U5: reactive blinking
                }
                val alpha = when (dotState) {
                    PadPlugin.DotState.IDLE -> 0.7f
                    PadPlugin.DotState.WORKING -> 0.9f
                    PadPlugin.DotState.ERROR -> 1f
                }

                Box(Modifier.fillMaxSize().alpha(alpha)
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { hideDot() }) },
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    // 羽化光晕
                    Box(Modifier.size((40f * scale).dp).shadow(8.dp, CircleShape, ambientColor = dotColor)
                        .background(Brush.radialGradient(listOf(dotColor.copy(alpha = 0.4f), dotColor.copy(alpha = 0f))), CircleShape))
                    // 核心圆点
                    Box(Modifier.size((16f * scale).dp).shadow(4.dp, CircleShape, ambientColor = dotColor)
                        .background(Brush.radialGradient(listOf(dotColor.copy(alpha = 0.9f), dotColor.copy(alpha = 0.6f))), CircleShape))
                }
            }
        }
        floatingView = cv
        windowManager?.addView(cv, layoutParams)
    }

    fun showDot() { dotHidden = false; floatingView?.visibility = View.VISIBLE }
    fun hideDot() { dotHidden = true; floatingView?.visibility = View.GONE }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("pad_dot", "PAD 悬浮窗", NotificationManager.IMPORTANCE_MIN)
                    .apply { description = "PAD 悬浮窗服务" })
        }
    }

    private fun createNotification(): Notification = NotificationCompat.Builder(this, "pad_dot")
        .setContentTitle("MengPaw PAD").setContentText("悬浮窗运行中")
        .setSmallIcon(android.R.drawable.ic_menu_manage).setOngoing(true)
        // FIX U6: Use setClassName (R8-safe) instead of Class.forName (crashes when R8 renames)
        .setContentIntent(PendingIntent.getActivity(this, 0,
            Intent().setClassName(this, "com.mengpaw.shell.MainActivity"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()
}
