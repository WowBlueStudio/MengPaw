// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core.namespace.sys

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.NumberPicker
import com.mengpaw.core.namespace.SysExecutor
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import java.util.Locale

/**
 * sys.dialog.* — 用户交互对话框 (对齐 Termux:API termux-dialog)。
 *
 * 全部命令为挂起式: 弹窗后等待用户确认/取消/超时 (默认 120s), 期间阻塞 Agent。
 * 并发互斥: 同一时间只允许一个对话框 (dialogLock), 重复调用直接失败。
 * 输出格式: "key: value" 纯文本, 取消/超时返回 ERR_TIMEOUT。
 */
internal object DialogExecutor {

    private const val DIALOG_TIMEOUT_MS = 120_000L

    private val dialogLock = Any()
    private var pending: CompletableDeferred<String?>? = null
    private var currentDialog: Dialog? = null

    /** 在 Activity 上弹对话框并挂起等待结果; null = 取消/超时/无前台 Activity/已有对话框在途。 */
    private suspend fun awaitUserInput(
        timeoutMs: Long,
        show: (Activity, (String?) -> Unit) -> Dialog?
    ): String? {
        val activity = SysExecutor.currentActivity?.get() ?: return null
        val deferred = CompletableDeferred<String?>()
        synchronized(dialogLock) {
            if (pending != null) return null
            pending = deferred
        }
        try {
            activity.runOnUiThread {
                try {
                    val dialog = show(activity) { value ->
                        synchronized(dialogLock) { currentDialog = null }
                        deferred.complete(value)
                    }
                    if (dialog != null) synchronized(dialogLock) { currentDialog = dialog }
                } catch (e: Exception) {
                    deferred.complete("error: ${e.message}")
                }
            }
            val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (result == null) {
                activity.runOnUiThread {
                    synchronized(dialogLock) { currentDialog?.dismiss() }
                }
            }
            return result
        } finally {
            synchronized(dialogLock) { pending = null }
        }
    }

    /** 解析逗号分隔选项列表 (LLM 友好, 支持 "a, b" 或 "a,b,c")。 */
    private fun parseOptions(raw: List<String>): List<String> =
        raw.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }

    suspend fun confirm(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val title = args.firstOrNull() ?: "确认"
        val result = awaitUserInput(DIALOG_TIMEOUT_MS) { activity, done ->
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage("请确认是否继续")
                .setPositiveButton("确定") { _, _ -> done("confirmed: true") }
                .setNegativeButton("取消") { _, _ -> done("confirmed: false") }
                .setOnCancelListener { done(null) }
                .show()
        }
        return result?.let { ExecutionResult.ok(it) } ?: failCanceled()
    }

    suspend fun text(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val prompt = args.firstOrNull() ?: "请输入"
        val def = args.getOrNull(1)
        val result = awaitUserInput(DIALOG_TIMEOUT_MS) { activity, done ->
            val input = EditText(activity).apply {
                setSingleLine(true)
                setText(def.orEmpty())
                hint = prompt
            }
            val container = LinearLayout(activity).apply {
                setPadding(48, 24, 48, 24)
                addView(input)
            }
            AlertDialog.Builder(activity)
                .setTitle(prompt)
                .setView(container)
                .setPositiveButton("确定") { _, _ ->
                    done("text: ${input.text?.toString().orEmpty()}")
                }
                .setNegativeButton("取消") { _, _ -> done(null) }
                .setOnCancelListener { done(null) }
                .show()
        }
        return result?.let { ExecutionResult.ok(it) } ?: failCanceled()
    }

    suspend fun radio(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val title = args.firstOrNull() ?: "请选择"
        val options = parseOptions(args.drop(1))
        if (options.isEmpty()) {
            return ExecutionResult.fail(
                "Usage: sys.dialog.radio <标题> <选项1> [选项2...] (逗号分隔)",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        }
        val result = awaitUserInput(DIALOG_TIMEOUT_MS) { activity, done ->
            var selected = -1
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), -1) { _, which -> selected = which }
                .setPositiveButton("确定") { _, _ ->
                    done(if (selected in options.indices) "selected: ${options[selected]}" else "selected: (未选择)")
                }
                .setNegativeButton("取消") { _, _ -> done(null) }
                .setOnCancelListener { done(null) }
                .show()
        }
        return result?.let { ExecutionResult.ok(it) } ?: failCanceled()
    }

    suspend fun checkbox(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val title = args.firstOrNull() ?: "请选择(可多选)"
        val options = parseOptions(args.drop(1))
        if (options.isEmpty()) {
            return ExecutionResult.fail(
                "Usage: sys.dialog.checkbox <标题> <选项1> [选项2...] (逗号分隔)",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        }
        val checked = BooleanArray(options.size)
        val result = awaitUserInput(DIALOG_TIMEOUT_MS) { activity, done ->
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setMultiChoiceItems(options.toTypedArray(), checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton("确定") { _, _ ->
                    val picked = options.filterIndexed { i, _ -> checked[i] }
                    done(if (picked.isEmpty()) "checked: (未选择)" else "checked: ${picked.joinToString(", ")}")
                }
                .setNegativeButton("取消") { _, _ -> done(null) }
                .setOnCancelListener { done(null) }
                .show()
        }
        return result?.let { ExecutionResult.ok(it) } ?: failCanceled()
    }

    suspend fun spinner(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val title = args.firstOrNull() ?: "请选择"
        val options = parseOptions(args.drop(1))
        if (options.isEmpty()) {
            return ExecutionResult.fail(
                "Usage: sys.dialog.spinner <标题> <选项1> [选项2...] (逗号分隔)",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        }
        val result = awaitUserInput(DIALOG_TIMEOUT_MS) { activity, done ->
            var selected = 0
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), 0) { _, which -> selected = which }
                .setPositiveButton("确定") { _, _ -> done("selected: ${options[selected]}") }
                .setNegativeButton("取消") { _, _ -> done(null) }
                .setOnCancelListener { done(null) }
                .show()
        }
        return result?.let { ExecutionResult.ok(it) } ?: failCanceled()
    }

    suspend fun sheet(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val title = args.firstOrNull() ?: "请选择"
        val options = parseOptions(args.drop(1))
        if (options.isEmpty()) {
            return ExecutionResult.fail(
                "Usage: sys.dialog.sheet <标题> <选项1> [选项2...] (逗号分隔)",
                errorCode = ErrorCodes.ERR_INVALID_INPUT
            )
        }
        val result = awaitUserInput(DIALOG_TIMEOUT_MS) { activity, done ->
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setItems(options.toTypedArray()) { _, which -> done("selected: ${options[which]}") }
                .setOnCancelListener { done(null) }
                .show()
        }
        return result?.let { ExecutionResult.ok(it) } ?: failCanceled()
    }

    suspend fun date(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val title = args.firstOrNull() ?: "选择日期"
        val cal = Calendar.getInstance()
        val result = awaitUserInput(DIALOG_TIMEOUT_MS) { activity, done ->
            val dialog = DatePickerDialog(
                activity,
                { _, y, m, d ->
                    done("date: ${String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)}")
                },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
            )
            dialog.setOnCancelListener { done(null) }
            dialog.show()
            dialog
        }
        return result?.let { ExecutionResult.ok(it) } ?: failCanceled()
    }

    suspend fun time(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val title = args.firstOrNull() ?: "选择时间"
        val cal = Calendar.getInstance()
        val result = awaitUserInput(DIALOG_TIMEOUT_MS) { activity, done ->
            val dialog = TimePickerDialog(
                activity,
                { _, h, min ->
                    done("time: ${String.format(Locale.US, "%02d:%02d", h, min)}")
                },
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true
            )
            dialog.setOnCancelListener { done(null) }
            dialog.show()
            dialog
        }
        return result?.let { ExecutionResult.ok(it) } ?: failCanceled()
    }

    suspend fun counter(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val title = args.firstOrNull() ?: "选择数值"
        val min = args.getOrNull(1)?.toIntOrNull() ?: 0
        val max = args.getOrNull(2)?.toIntOrNull() ?: 100
        val start = (args.getOrNull(3)?.toIntOrNull() ?: min).coerceIn(min, max)
        if (min > max) {
            return ExecutionResult.fail("参数错误: min($min) 不能大于 max($max)", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
        val result = awaitUserInput(DIALOG_TIMEOUT_MS) { activity, done ->
            val picker = NumberPicker(activity).apply {
                this.minValue = min
                this.maxValue = max
                value = start
            }
            val container = LinearLayout(activity).apply {
                setPadding(48, 24, 48, 24)
                addView(picker)
            }
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(container)
                .setPositiveButton("确定") { _, _ -> done("value: ${picker.value}") }
                .setNegativeButton("取消") { _, _ -> done(null) }
                .setOnCancelListener { done(null) }
                .show()
        }
        return result?.let { ExecutionResult.ok(it) } ?: failCanceled()
    }

    /** 预设色板 (Termux dialog color 为自由取色, MengPaw 简化为常用色)。 */
    suspend fun color(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val title = args.firstOrNull() ?: "选择颜色"
        val presets = listOf(
            "#FFFFFF 白色", "#000000 黑色", "#F5222D 红色", "#FA8C16 橙色",
            "#FAAD14 黄色", "#52C41A 绿色", "#13C2C2 青色", "#1677FF 蓝色",
            "#722ED1 紫色", "#EB2F96 粉色"
        )
        val result = awaitUserInput(DIALOG_TIMEOUT_MS) { activity, done ->
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setItems(presets.toTypedArray()) { _, which ->
                    done("color: ${presets[which].substringBefore(' ')}")
                }
                .setOnCancelListener { done(null) }
                .show()
        }
        return result?.let { ExecutionResult.ok(it) } ?: failCanceled()
    }

    /** 语音输入对话框 — 复用 SpeechExecutor 的 RecognizerIntent 桥。 */
    suspend fun speech(args: List<String>, ec: ExecutionContext): ExecutionResult {
        val activity = SysExecutor.currentActivity?.get()
            ?: return ExecutionResult.fail("当前无前台 Activity, 无法发起语音识别", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        val prompt = args.firstOrNull() ?: "请说出内容"
        val text = SpeechExecutor.speechToText(activity, prompt, DIALOG_TIMEOUT_MS)
        return if (text == null) {
            ExecutionResult.fail("语音识别取消/超时/无结果", errorCode = ErrorCodes.ERR_TIMEOUT)
        } else {
            ExecutionResult.ok("text: $text")
        }
    }

    private fun failCanceled(): ExecutionResult =
        ExecutionResult.fail("对话框已取消、超时或无前台 Activity", errorCode = ErrorCodes.ERR_TIMEOUT)
}
