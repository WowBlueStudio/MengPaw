// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import com.mengpaw.core.namespace.SysExecutor
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult

/**
 * 无障碍命令执行器 — sys.accessibility.* (v0.42.2)。
 *
 * 依赖系统『无障碍』服务手动开启; 未开启时返回引导文案 (设置 → 无障碍 → MengPaw)。
 * 安全分级: status LOW / dump MID (读屏) / click·swipe·input·back·home·recents HIGH
 * (模拟用户操作, 弹窗确认), 见 CommandRiskLevels。
 *
 * 参数风格对齐 OverlayExecutor: 位置参数 + `--flag` 可选参数。
 */
internal object AccessibilityExecutor {

    private const val DEFAULT_MAX_NODES = 150
    private const val DEFAULT_MAX_DEPTH = 6
    private const val DEFAULT_SWIPE_DURATION_MS = 300L

    suspend fun status(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val app = SysExecutor.appContext ?: return ExecutionResult.fail("SysExecutor not initialized")
        val enabled = MengPawAccessibilityService.isAuthorized(app)
        val connected = MengPawAccessibilityService.isConnected()
        return if (enabled && connected) {
            ExecutionResult.ok("无障碍服务已开启 ✅ (系统设置已授权, 服务已连接)")
        } else if (enabled) {
            ExecutionResult.ok("无障碍服务已授权, 但服务尚未连接 — 请重启应用或稍后重试 (状态: 授权=true 连接=false)")
        } else {
            ExecutionResult.fail(buildString {
                appendLine("无障碍服务未开启 ⛔ (系统设置类权限, 需手动开启)。")
                appendLine()
                appendLine("请引导用户: 设置 → 无障碍 / 辅助功能 → MengPaw → 打开服务开关并确认")
                appendLine("开启后执行 sys.accessibility.status 确认。")
            })
        }
    }

    suspend fun dump(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val flags = args.filter { it.startsWith("--") }
        val maxNodes = flags.find { it.startsWith("--max") }
            ?.substringAfter("--max")?.trim()?.toIntOrNull()?.coerceIn(20, 500) ?: DEFAULT_MAX_NODES
        val maxDepth = flags.find { it.startsWith("--depth") }
            ?.substringAfter("--depth")?.trim()?.toIntOrNull()?.coerceIn(2, 12) ?: DEFAULT_MAX_DEPTH
        val json = MengPawAccessibilityService.snapshot(maxDepth, maxNodes)
            ?: return ExecutionResult.fail(buildString {
                appendLine("无法读取屏幕控件树。")
                appendLine("- 服务未连接: 先 sys.accessibility.status, 未开启请到系统设置开启")
                appendLine("- 当前无活动窗口 (如锁屏/无界面): 稍后重试")
            })
        return ExecutionResult.ok(json)
    }

    suspend fun click(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val target = parseClick(args)
            ?: return ExecutionResult.fail(
                "用法: sys.accessibility.click <x> <y> | --text <文本> | --id <viewId>"
            )
        val ok = when {
            target.text != null -> MengPawAccessibilityService.clickText(target.text)
            target.viewId != null -> MengPawAccessibilityService.clickViewId(target.viewId)
            else -> MengPawAccessibilityService.click(target.x, target.y)
        }
        return if (ok) {
            val desc = when {
                target.text != null -> "文本=\"${target.text}\""
                target.viewId != null -> "viewId=${target.viewId}"
                else -> "(${target.x}, ${target.y})"
            }
            ExecutionResult.ok("点击完成 ✅ 目标: $desc")
        } else {
            ExecutionResult.fail(buildString {
                appendLine("点击失败。")
                if (target.text != null || target.viewId != null) {
                    appendLine("未找到匹配控件 — 先 sys.accessibility.dump 查看实际文本/viewId")
                } else {
                    appendLine("手势被系统取消或服务未连接 — 先 sys.accessibility.status")
                }
            })
        }
    }

    suspend fun swipe(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val spec = parseSwipe(args)
            ?: return ExecutionResult.fail(
                "用法: sys.accessibility.swipe <x1> <y1> <x2> <y2> [--duration ms]"
            )
        val ok = MengPawAccessibilityService.swipe(
            spec.x1, spec.y1, spec.x2, spec.y2, spec.durationMs
        )
        return if (ok) {
            ExecutionResult.ok(
                "滑动完成 ✅ (${spec.x1},${spec.y1}) → (${spec.x2},${spec.y2}) " +
                    "时长 ${spec.durationMs}ms"
            )
        } else {
            ExecutionResult.fail("滑动失败 — 手势被系统取消或服务未连接 (先 sys.accessibility.status)")
        }
    }

    suspend fun input(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val text = args.takeWhile { !it.startsWith("--") }.joinToString(" ")
        if (text.isBlank()) return ExecutionResult.fail("用法: sys.accessibility.input <文本>")
        val ok = MengPawAccessibilityService.inputText(text)
        return if (ok) {
            ExecutionResult.ok("输入完成 ✅ \"$text\"")
        } else {
            ExecutionResult.fail(
                "输入失败 — 无聚焦的可编辑控件。先点击目标输入框 (sys.accessibility.click) 再重试"
            )
        }
    }

    suspend fun back(args: List<String>, ec: ExecutionContext): ExecutionResult =
        globalAction("返回") { MengPawAccessibilityService.back() }

    suspend fun home(args: List<String>, ec: ExecutionContext): ExecutionResult =
        globalAction("回到桌面") { MengPawAccessibilityService.home() }

    suspend fun recents(args: List<String>, ec: ExecutionContext): ExecutionResult =
        globalAction("打开最近任务") { MengPawAccessibilityService.recents() }

    private suspend fun globalAction(label: String, action: suspend () -> Boolean): ExecutionResult {
        return if (action()) ExecutionResult.ok("$label ✅") else {
            ExecutionResult.fail("$label 失败 — 服务未连接 (先 sys.accessibility.status)")
        }
    }

    // ── 参数解析 (纯逻辑, 供 JVM 单测) ──

    internal data class ClickTarget(
        val x: Int = 0,
        val y: Int = 0,
        val text: String? = null,
        val viewId: String? = null,
    )

    internal data class SwipeSpec(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val durationMs: Long = DEFAULT_SWIPE_DURATION_MS,
    )

    internal fun parseClick(args: List<String>): ClickTarget? {
        val flags = args.filter { it.startsWith("--") }
        flagValue(args, flags, "--text")?.let { return ClickTarget(text = it) }
        flagValue(args, flags, "--id")?.let { return ClickTarget(viewId = it) }
        val coords = args.filter { !it.startsWith("--") }
        if (coords.size < 2) return null
        val x = coords[0].toIntOrNull() ?: return null
        val y = coords[1].toIntOrNull() ?: return null
        return ClickTarget(x = x, y = y)
    }

    internal fun parseSwipe(args: List<String>): SwipeSpec? {
        val coords = args.filter { !it.startsWith("--") }
        if (coords.size < 4) return null
        val x1 = coords[0].toIntOrNull() ?: return null
        val y1 = coords[1].toIntOrNull() ?: return null
        val x2 = coords[2].toIntOrNull() ?: return null
        val y2 = coords[3].toIntOrNull() ?: return null
        val duration = flagValue(args, args.filter { it.startsWith("--") }, "--duration")
            ?.toLongOrNull()?.coerceIn(50, 5000)
            ?: DEFAULT_SWIPE_DURATION_MS
        return SwipeSpec(x1, y1, x2, y2, duration)
    }

    /** 支持 `--name value` 与 `--name=value` 两种写法; 无值返回 null。 */
    private fun flagValue(args: List<String>, flags: List<String>, name: String): String? {
        flags.find { it.startsWith("$name=") }?.let {
            return it.substringAfter("=").trim().takeIf { v -> v.isNotEmpty() }
        }
        val idx = args.indexOfFirst { it == name }
        if (idx >= 0 && idx + 1 < args.size && !args[idx + 1].startsWith("--")) {
            return args[idx + 1].trim().takeIf { it.isNotEmpty() }
        }
        return null
    }
}
