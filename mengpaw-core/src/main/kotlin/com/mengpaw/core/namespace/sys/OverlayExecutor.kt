// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.namespace.sys

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.mengpaw.core.namespace.SysExecutor
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult

/** Floating overlay window — show, hide, and update text. */
internal object OverlayExecutor {

    private var overlayView: TextView? = null
    private var overlayManager: WindowManager? = null

    suspend fun overlayShow(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(app)) {
            return ExecutionResult.fail(buildString {
                appendLine("悬浮窗权限未授予（系统设置类权限，需手动开启）。")
                appendLine()
                appendLine("请引导用户: 设置 → 应用 → MengPaw → 在其他应用上层显示 → 开启")
                appendLine("或让用户执行: sys.permission.request SYSTEM_ALERT_WINDOW")
            })
        }
        val text = args.takeWhile { !it.startsWith("--") }.joinToString(" ")
        if (text.isBlank()) return ExecutionResult.fail("用法: sys.overlay.show <文本> [--x 100] [--y 200] [--size 14] [--color #FFF]")
        val flags = args.dropWhile { !it.startsWith("--") }
        val x = flags.find { it.startsWith("--x") }?.substringAfter("--x")?.trim()?.toIntOrNull() ?: 100
        val y = flags.find { it.startsWith("--y") }?.substringAfter("--y")?.trim()?.toIntOrNull() ?: 500
        val size = flags.find { it.startsWith("--size") }?.substringAfter("--size")?.trim()?.toFloatOrNull() ?: 14f
        val colorStr = flags.find { it.startsWith("--color") }?.substringAfter("--color")?.trim() ?: "#FFFFFF"
        val color = try { android.graphics.Color.parseColor(colorStr) } catch (_: Exception) { android.graphics.Color.WHITE }

        overlayView?.let { try { overlayManager?.removeView(it) } catch (_: Exception) {} }

        val tv = TextView(app).apply {
            this.text = text
            setTextColor(color)
            textSize = size
            setPadding(16, 8, 16, 8)
            setBackgroundColor(android.graphics.Color.parseColor("#CC000000"))
            alpha = 0.9f
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply { this.x = x; this.y = y; gravity = Gravity.TOP or Gravity.START }

        overlayManager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayManager?.addView(tv, params)
        overlayView = tv
        return ExecutionResult.ok(buildString {
            appendLine("悬浮窗已显示 ✅")
            appendLine("- 内容: \"$text\"")
            appendLine("- 位置: ($x, $y)")
            appendLine()
            appendLine("更新内容: sys.overlay.update <新文本>")
            appendLine("隐藏: sys.overlay.hide")
        })
    }

    suspend fun overlayHide(args: List<String>, ec: ExecutionContext): ExecutionResult {
        if (overlayView == null) return ExecutionResult.ok("悬浮窗未在显示")
        try { overlayManager?.removeView(overlayView) } catch (_: Exception) {}
        overlayView = null
        overlayManager = null
        return ExecutionResult.ok("悬浮窗已隐藏")
    }

    suspend fun overlayUpdate(args: List<String>, ec: ExecutionContext): ExecutionResult {
        if (overlayView == null) return ExecutionResult.fail("悬浮窗未在显示。请先执行 sys.overlay.show")
        val text = args.joinToString(" ")
        if (text.isBlank()) return ExecutionResult.fail("用法: sys.overlay.update <文本>")
        overlayView?.text = text
        return ExecutionResult.ok("悬浮窗已更新: \"$text\"")
    }
}
