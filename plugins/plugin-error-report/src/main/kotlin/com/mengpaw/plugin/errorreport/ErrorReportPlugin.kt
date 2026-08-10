// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.errorreport

import android.content.Context
import android.content.SharedPreferences
import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.kernel.plugin.PluginContext
import com.mengpaw.kernel.plugin.PluginMetadata
import com.mengpaw.kernel.plugin.PluginType
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Official error-report plugin for MengPaw.
 *
 * ## Privacy Notice
 * Installing this plugin means you agree to help improve MengPaw and its plugins
 * by sending anonymized error reports. No personal data or API keys are collected.
 *
 * ## Features
 * - Local error collection via ErrorCollector (always active in core)
 * - CLI commands for Agent to view/manage errors
 * - Auto-upload on WiFi to Gitee/GitHub
 * - Auto-clean error files after app update
 *
 * ## 职责拆分 (批次3)
 * 上传执行拆到 [ErrorReportUploader], WiFi 监视拆到 [ErrorReportWifiMonitor]
 * (构造参数传依赖闭包), 主类保留 CLI 命令与配置, 公开 API 零变化。
 */
class ErrorReportPlugin : Plugin {

    override val metadata = PluginMetadata(
        id = "error-report-plugin",
        name = "错误上报",
        version = "0.3.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "官方错误收集与上报。安装即同意帮助改进框架和插件。WiFi下自动上传到Gitee/GitHub。",
        permissions = listOf("INTERNET", "ACCESS_NETWORK_STATE"),
        minCoreVersion = "0.2.3",
        commands = listOf("error.list", "error.show", "error.clear", "error.export", "error.status", "error.upload")
    )

    override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = mapOf(
        "list" to ::listErrors,
        "show" to ::showError,
        "clear" to ::clearErrors,
        "export" to ::exportErrors,
        "status" to ::uploadStatus,
        "upload" to ::manualUpload,
    )

    private val client = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(15, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Configurable endpoint — Gitee issues API by default
    private var reportEndpoint = "https://gitee.com/api/v5/repos/WowBlueStudio/MengPaw/issues"
    private var reportToken = ""
    private var wifiOnly = true
    private var wifiConnected = false
    private var isUploading = false

    // ── 职责委托 (批次3 拆分) ──────────────────────────────────────────

    private val uploader = ErrorReportUploader(
        scope = scope, client = client,
        tokenProvider = { reportToken },
        endpointProvider = { reportEndpoint },
        wifiOnlyProvider = { wifiOnly },
        wifiConnectedProvider = { wifiConnected },
        uploadingProvider = { isUploading },
        setUploading = { isUploading = it }
    )

    private val wifiMonitor = ErrorReportWifiMonitor(
        appContextProvider = { appContext },
        setWifiConnected = { wifiConnected = it },
        onWifiConnected = { uploader.scheduleUpload() }
    )

    // ── Lifecycle ───────────────────────────────────────────────────────

    override suspend fun onInstall(ctx: PluginContext) {
        // Android context 由 Shell MainActivity.deferInit 注入 (companion.appContext)
        // 注入前安装则上传功能暂禁用, 本地收集不受影响

        // Check version for auto-clean
        checkAndCleanOnUpdate()

        // Register WiFi monitor
        wifiMonitor.register()

        // Load saved config
        loadConfig()

        ctx.log("错误上报插件已激活。安装即表示同意帮助改进 MengPaw。")
    }

    override suspend fun onUninstall() {
        wifiMonitor.unregister()
        uploader.cancelScheduledUpload()
        client.close()
        scope.cancel()
    }

    // ── CLI: error.list ─────────────────────────────────────────────────

    private suspend fun listErrors(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val n = args.firstOrNull()?.toIntOrNull() ?: 20
        val entries = ErrorCollector.list(n.coerceIn(1, 200))
        if (entries.isEmpty()) return ExecutionResult.ok("✅ 没有记录的错误。")
        val output = buildString {
            appendLine("## 最近 ${entries.size} 条错误")
            appendLine()
            appendLine("| ID | 时间 | 类型 | 来源 | 摘要 |")
            appendLine("|----|------|------|------|------|")
            entries.forEach { e ->
                val time = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(e.timestamp))
                val type = e.type.name.take(8)
                val src = e.source.take(15)
                val msg = e.message.take(40).replace("|", "/")
                val reported = if (e.reported) "✓" else ""
                appendLine("| ${e.id} | $time | $type | $src | $msg $reported |")
            }
        }
        return ExecutionResult.ok(output)
    }

    // ── CLI: error.show ─────────────────────────────────────────────────

    private suspend fun showError(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: error.show <error-id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val entry = ErrorCollector.show(args[0])
            ?: return ExecutionResult.fail("Error not found: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
        val output = buildString {
            appendLine("## ${entry.id}")
            appendLine("- **时间**: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(entry.timestamp))}")
            appendLine("- **类型**: ${entry.type.name}")
            appendLine("- **来源**: ${entry.source}")
            appendLine("- **会话**: ${entry.sessionId ?: "N/A"}")
            appendLine("- **Agent**: ${entry.agentName ?: "N/A"}")
            appendLine("- **已上报**: ${if (entry.reported) "是" else "否"}")
            if (entry.metadata.isNotEmpty()) {
                appendLine("- **元数据**: ${entry.metadata.map { "${it.key}=${it.value}" }.joinToString(", ")}")
            }
            appendLine()
            appendLine("### 错误信息")
            appendLine(entry.message)
            if (entry.stackTrace.isNotBlank()) {
                appendLine()
                appendLine("### 堆栈")
                appendLine("```")
                appendLine(entry.stackTrace.take(2000))
                appendLine("```")
            }
        }
        return ExecutionResult.ok(output)
    }

    // ── CLI: error.clear ────────────────────────────────────────────────

    private suspend fun clearErrors(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val count = ErrorCollector.list(Int.MAX_VALUE).size
        ErrorCollector.clear()
        return ExecutionResult.ok("已清空 $count 条错误记录。")
    }

    // ── CLI: error.export ───────────────────────────────────────────────

    private suspend fun exportErrors(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val json = ErrorCollector.exportJson()
        val exportFile = File(DataPaths.ERROR_LOG, "export_${System.currentTimeMillis()}.json")
        exportFile.parentFile?.mkdirs()
        return try {
            exportFile.writeText(json)
            ExecutionResult.ok("已导出到: ${exportFile.absolutePath}\n共 ${ErrorCollector.list(Int.MAX_VALUE).size} 条错误。")
        } catch (e: Exception) {
            ExecutionResult.fail("Export failed: ${e.message}", errorCode = ErrorCodes.ERR_IO)
        }
    }

    // ── CLI: error.status ───────────────────────────────────────────────

    private suspend fun uploadStatus(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val total = ErrorCollector.list(Int.MAX_VALUE).size
        val pending = ErrorCollector.pendingUploads().size
        val reported = total - pending
        val sb = StringBuilder()
        sb.appendLine("## 错误上报状态")
        sb.appendLine("- **总错误**: $total")
        sb.appendLine("- **已上报**: $reported")
        sb.appendLine("- **待上传**: $pending")
        sb.appendLine("- **上传中**: ${if (isUploading) "是" else "否"}")
        sb.appendLine("- **WiFi**: ${if (wifiConnected) "已连接 ✓" else "未连接"}")
        sb.appendLine("- **WiFi 限制**: ${if (wifiOnly) "仅 WiFi" else "任意网络"}")
        sb.appendLine("- **上报地址**: ${reportEndpoint.take(60)}")
        if (reportToken.isNotEmpty()) sb.appendLine("- **Token**: 已配置")
        return ExecutionResult.ok(sb.toString())
    }

    // ── CLI: error.upload ───────────────────────────────────────────────

    private suspend fun manualUpload(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (reportToken.isEmpty()) {
            return ExecutionResult.fail("未配置上报 Token。请在插件目录下创建 config.properties: token=<your-gitee-token>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        }
        val result = uploader.doUpload()
        return if (result.isSuccess) {
            val (uploaded, failed) = result.getOrDefault(0 to 0)
            ExecutionResult.ok("上传完成: $uploaded 条成功, $failed 条失败")
        } else {
            ExecutionResult.fail("上传失败: ${result.exceptionOrNull()?.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    // ── Version Auto-Clean ──────────────────────────────────────────────

    private fun checkAndCleanOnUpdate() {
        val ctx = appContext ?: return
        try {
            val pkgInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val currentVersion = pkgInfo.versionName ?: return
            val prefs = getPrefs(ctx)
            val lastVersion = prefs.getString(KEY_LAST_VERSION, "") ?: ""
            if (lastVersion.isNotEmpty() && lastVersion != currentVersion) {
                // Version changed — clean old errors
                ErrorCollector.clear()
            }
            prefs.edit().putString(KEY_LAST_VERSION, currentVersion).apply()
        } catch (_: Exception) { }
    }

    private fun loadConfig() {
        val ctx = appContext ?: return
        try {
            val configFile = File(DataPaths.pluginDir("error-report-plugin"), "config.properties")
            if (configFile.exists()) {
                val props = java.util.Properties()
                props.load(configFile.inputStream())
                reportToken = props.getProperty("token", "")
                props.getProperty("endpoint")?.let { reportEndpoint = it }
                props.getProperty("wifi_only")?.let { wifiOnly = it.toBoolean() }
            }
        } catch (_: Exception) { }
    }

    private fun getPrefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("mengpaw_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LAST_VERSION = "error_last_version"

        /** Android Context — 由 Shell MainActivity.deferInit 注入 (替代失效的 getAppContext 反射)。 */
        @Volatile var appContext: Context? = null
    }
}
