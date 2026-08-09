// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell

import android.content.Context
import com.mengpaw.core.AndroidLogger
import com.mengpaw.core.DataPathsInitializer
import com.mengpaw.kernel.KernelLog

/**
 * 应用启动关键路径初始化 — 从 MainActivity.kt 拆出 (2026-08-04, >40KB UI 文件拆分)。
 *
 * 设计不变量 (docs/lessons.md): 关键路径顺序不可重排 —
 * DataPaths → SysExecutor → UI 渲染。崩溃日志处理器最先注册。
 * Activity 专属调用 (SysExecutor.setActivity / enableEdgeToEdge / setContent) 留在 Activity。
 */
object AppInitializer {

    /** 必须在 UI 渲染前完成的初始化。 */
    fun initialize(context: Context) {
        // ── Global crash logger ──
        // Writes to both internal (for ADB on debug builds) and public Downloads
        // (for release builds, where /data/data is not ADB-readable on Android 10+)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            val entry = "\n=== $ts ===\nThread: ${thread.name}\n" +
                "Exception: ${throwable.javaClass.name}: ${throwable.message}\n" +
                throwable.stackTraceToString() + "\n"
            try {
                // Internal storage (ADB accessible on debug builds)
                val internal = java.io.File(context.filesDir, "crash.log")
                internal.parentFile?.mkdirs()
                internal.appendText(entry)
            } catch (_: Exception) {}
            try {
                // Public Downloads — accessible via file manager, no ADB needed
                val pub = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "MengPaw_crash.log")
                pub.parentFile?.mkdirs()
                pub.appendText(entry)
            } catch (_: Exception) {}
            // Pass to system default handler (crash dialog + logcat)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // ── 关键路径: 必须在 UI 渲染前完成的初始化 ──
        DataPathsInitializer.initialize(context)
        com.mengpaw.kernel.plugin.PluginManager.initializeGlobalInstance(
            com.mengpaw.kernel.AgentEngine.CORE_VERSION)
        // ── 插件注册源注入 (v0.34.3 P0-1): CLI.md 插件表单一事实源 —
        //    内置 = PluginRegistrar.BUILTIN_PLUGIN_INFO; 远程 = 类注册表非内置条目 ──
        com.mengpaw.kernel.plugin.BuiltinPluginRegistry.builtinBriefs =
            com.mengpaw.shell.PluginRegistrar.BUILTIN_PLUGIN_INFO.mapValues { it.value.second }
        com.mengpaw.shell.PluginRegistrar.REMOTE_PLUGIN_BRIEFS.filterKeys {
            it in com.mengpaw.shell.ui.screens.PluginClassRegistry.ALL_KNOWN_CLASSES
        }.let { com.mengpaw.kernel.plugin.BuiltinPluginRegistry.remoteBriefs = it }
        com.mengpaw.core.namespace.SysExecutor.init(context)
        com.mengpaw.core.security.IntegrityGuard.globalInstance.init(context)
        com.mengpaw.core.AgentTemplates.init(context)
        com.mengpaw.core.SkillSeeds.ensure(context)
        com.mengpaw.kernel.agent.AgentDocs.bootstrapper = { name, lang -> com.mengpaw.core.AgentTemplates.bootstrapAgent(name, lang) }
        KernelLog.setLogger(AndroidLogger())
        // P2 修复 (悬空链接线): IntegrityGuard.verify() 此前从未被调用 —
        // init() 只建基线, 路径保护走 IntegrityProvider.validateCommand (Pipeline),
        // 而签名完整性复核 verify() 无人调用。启动时复核一次并告警:
        // 失败不阻断启动 (阻断有 boot-loop 风险), 仅记录日志供审计。
        if (!com.mengpaw.core.security.IntegrityGuard.globalInstance.verify()) {
            KernelLog.w("IntegrityGuard",
                "APK 完整性校验失败 (fail-secure) — 签名基线缺失或不匹配, 详见 IntegrityGuard.getManifest()")
        }
    }
}
