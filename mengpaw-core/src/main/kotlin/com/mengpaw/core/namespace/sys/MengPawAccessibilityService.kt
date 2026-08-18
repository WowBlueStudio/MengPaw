// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 无障碍服务 — Agent 屏幕级操控的数据源 (sys.accessibility.*, v0.42.2)。
 *
 * 需用户在系统『无障碍』手动开启 (设置 → 无障碍 → MengPaw), 非 Manifest 运行时权限。
 * 能力:
 * - [snapshot] 读取当前窗口控件树 (JSON, 经 [AccessibilitySnapshot] 截断序列化)
 * - [click] / [swipe] 基于坐标的模拟手势 (dispatchGesture)
 * - [inputText] 向聚焦控件写入文本 (ACTION_SET_TEXT)
 * - [back] / [home] / [recents] 全局导航操作 (performGlobalAction)
 *
 * 安全: 本服务只按命令被动响应, 不主动监听/记录事件; 命令侧由 CommandRiskLevels
 * 分级管控 (dump MID, 模拟操作 HIGH 需弹窗确认)。服务导出受系统
 * BIND_ACCESSIBILITY_SERVICE 权限保护, 第三方无法直接绑定。
 */
internal class MengPawAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 被动服务: 事件类型仅用于维持系统连接, 不做任何记录/转发
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** 当前窗口控件树 → 平台无关数据 (全量转换, 截断由序列化层负责)。 */
    private fun readRoot(): AccessibilityNodeData? {
        val root = rootInActiveWindow ?: return null
        return try {
            toData(root, 0)
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    private fun toData(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeData {
        val rect = Rect()
        try { node.getBoundsInScreen(rect) } catch (_: Exception) {}
        val children = mutableListOf<AccessibilityNodeData>()
        // 深度护栏: 嵌套过深 (异常 UI) 直接截断, 防止转换递归失控
        if (depth < MAX_CONVERT_DEPTH) {
            val childCount = try { node.childCount } catch (_: Exception) { 0 }
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                val data = try {
                    toData(child, depth + 1)
                } finally {
                    try { child.recycle() } catch (_: Exception) {}
                }
                children.add(data)
            }
        }
        return AccessibilityNodeData(
            text = safeText(node.text),
            contentDescription = safeText(node.contentDescription),
            className = try { node.className?.toString() } catch (_: Exception) { null },
            packageName = try { node.packageName?.toString() } catch (_: Exception) { null },
            viewId = try { node.viewIdResourceName } catch (_: Exception) { null },
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            clickable = try { node.isClickable } catch (_: Exception) { false },
            longClickable = try { node.isLongClickable } catch (_: Exception) { false },
            scrollable = try { node.isScrollable } catch (_: Exception) { false },
            checked = try { node.isChecked } catch (_: Exception) { false },
            selected = try { node.isSelected } catch (_: Exception) { false },
            focusable = try { node.isFocusable } catch (_: Exception) { false },
            children = children,
        )
    }

    private fun safeText(cs: CharSequence?): String? =
        cs?.toString()?.takeIf { it.isNotBlank() }

    /** 按文本/描述查找第一个匹配节点, 返回其屏幕中心 1x1 Rect; 未找到返回 null。 */
    private fun findClickableBoundsByText(text: String): Rect? {
        val root = rootInActiveWindow ?: return null
        val result = findBounds(root, text, byViewId = false)
        try { root.recycle() } catch (_: Exception) {}
        return result
    }

    /** 按 viewId (如 pkg:id/button_ok) 查找匹配节点, 返回屏幕中心坐标。 */
    private fun findClickableBoundsByViewId(viewId: String): Rect? {
        val root = rootInActiveWindow ?: return null
        val result = findBounds(root, viewId, byViewId = true)
        try { root.recycle() } catch (_: Exception) {}
        return result
    }

    private fun findBounds(
        root: AccessibilityNodeInfo,
        target: String,
        byViewId: Boolean,
    ): Rect? {
        var result: Rect? = null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty() && result == null) {
            val node = stack.removeLast()
            val match = try {
                if (byViewId) node.viewIdResourceName == target
                else node.text?.toString() == target || node.contentDescription?.toString() == target
            } catch (_: Exception) {
                false
            }
            if (match) {
                val rect = Rect()
                try { node.getBoundsInScreen(rect) } catch (_: Exception) {}
                if (!rect.isEmpty) result = centerRect(rect)
            }
            if (result == null) {
                val count = try { node.childCount } catch (_: Exception) { 0 }
                val children = ArrayList<AccessibilityNodeInfo>(count)
                for (i in 0 until count) {
                    try { node.getChild(i)?.let { children.add(it) } } catch (_: Exception) {}
                }
                for (i in children.indices.reversed()) stack.addLast(children[i])
            }
            if (node !== root) try { node.recycle() } catch (_: Exception) {}
        }
        while (stack.isNotEmpty()) {
            try { stack.removeLast().recycle() } catch (_: Exception) {}
        }
        // root 由调用方 (findClickableBoundsByText/ViewId) 统一回收, 避免双重回收
        return result
    }

    private fun centerRect(rect: Rect): Rect {
        val cx = rect.left + rect.width() / 2
        val cy = rect.top + rect.height() / 2
        return Rect(cx, cy, cx + 1, cy + 1)
    }

    /** 向当前聚焦的可编辑控件写入文本 (ACTION_SET_TEXT); 无聚焦控件返回 false。 */
    private fun setText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = findFocusedEditable(root)
        try { root.recycle() } catch (_: Exception) {}
        if (focused == null) return false
        return try {
            val bundle = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        } catch (_: Exception) {
            false
        } finally {
            try { focused.recycle() } catch (_: Exception) {}
        }
    }

    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var focused: AccessibilityNodeInfo? = null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty() && focused == null) {
            val node = stack.removeLast()
            val isTarget = try { node.isFocused && node.isEditable } catch (_: Exception) { false }
            if (isTarget) {
                focused = node
            } else {
                val count = try { node.childCount } catch (_: Exception) { 0 }
                val children = ArrayList<AccessibilityNodeInfo>(count)
                for (i in 0 until count) {
                    try { node.getChild(i)?.let { children.add(it) } } catch (_: Exception) {}
                }
                for (i in children.indices.reversed()) stack.addLast(children[i])
            }
            if (node !== root) try { node.recycle() } catch (_: Exception) {}
        }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n !== focused) try { n.recycle() } catch (_: Exception) {}
        }
        return focused
    }

    /** 派发手势 — dispatchGesture 必须在主线程调用, 挂起等待完成/取消回调。 */
    private suspend fun dispatch(path: Path, durationMs: Long): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                val callback = object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                val accepted = try {
                    dispatchGesture(gesture, callback, null)
                } catch (_: Exception) {
                    false
                }
                if (!accepted && cont.isActive) cont.resume(false)
            }
        }

    companion object {
        private const val MAX_CONVERT_DEPTH = 16

        @Volatile
        private var instance: MengPawAccessibilityService? = null

        /** 系统无障碍服务是否已授权本应用 (设置开关, 非运行时权限)。 */
        fun isAuthorized(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return false
            return try {
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
            } catch (_: Exception) {
                false
            }
        }

        fun isConnected(): Boolean = instance != null

        /** 读取当前窗口控件树 JSON; 服务未连接/无活动窗口返回 null。 */
        suspend fun snapshot(maxDepth: Int, maxNodes: Int): String? {
            val svc = instance ?: return null
            val root = withMainThread { svc.readRoot() } ?: return null
            return AccessibilitySnapshot.serialize(root, maxDepth = maxDepth, maxNodes = maxNodes)
        }

        /** 坐标点击。 */
        suspend fun click(x: Int, y: Int): Boolean {
            val svc = instance ?: return false
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            return svc.dispatch(path, CLICK_DURATION_MS)
        }

        /** 按文本 (text/desc 精确匹配) 点击; 未找到可点击节点返回 false。 */
        suspend fun clickText(text: String): Boolean {
            val svc = instance ?: return false
            val bounds = withMainThread { svc.findClickableBoundsByText(text) } ?: return false
            val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
            return svc.dispatch(path, CLICK_DURATION_MS)
        }

        /** 按 viewId 点击 (精确匹配); 未找到可点击节点返回 false。 */
        suspend fun clickViewId(viewId: String): Boolean {
            val svc = instance ?: return false
            val bounds = withMainThread { svc.findClickableBoundsByViewId(viewId) } ?: return false
            val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
            return svc.dispatch(path, CLICK_DURATION_MS)
        }

        /** 滑动手势。 */
        suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
            val svc = instance ?: return false
            val path = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            }
            return svc.dispatch(path, durationMs)
        }

        /** 向聚焦控件写入文本 (无聚焦可编辑控件时返回 false)。 */
        suspend fun inputText(text: String): Boolean = withMainThread {
            val svc = instance ?: return@withMainThread false
            svc.setText(text)
        }

        suspend fun back(): Boolean = globalAction(GLOBAL_ACTION_BACK)
        suspend fun home(): Boolean = globalAction(GLOBAL_ACTION_HOME)
        suspend fun recents(): Boolean = globalAction(GLOBAL_ACTION_RECENTS)

        private suspend fun globalAction(action: Int): Boolean = withMainThread {
            val svc = instance ?: return@withMainThread false
            try { svc.performGlobalAction(action) } catch (_: Exception) { false }
        }

        private const val CLICK_DURATION_MS = 1L

        private suspend fun <T> withMainThread(block: () -> T): T =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { block() }
    }
}
