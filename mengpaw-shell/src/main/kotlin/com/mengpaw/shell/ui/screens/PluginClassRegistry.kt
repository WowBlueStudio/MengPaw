// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

// ── 插件类注册表 — 拆自 PluginViewModel.kt companion (2026-08-06, 批次4) ──
// PluginViewModel.companion 经委托保持公开 API 不变 (PluginRegistrar/AgentViewModel 引用点)。

internal object PluginClassRegistry {
    /**
     * "Unknown command: <namespace>." 解析正则 — 预编译共享常量 (P2 修复).
     * AgentViewModel.checkMissingPlugin 与 suggestPluginForCommand 共用, 避免每消息重复编译.
     */
    val UNKNOWN_COMMAND_REGEX = Regex("Unknown command: (\\w+)\\.")

    /**
     * Registry mapping plugin IDs to their fully-qualified class names.
     * Populated at app startup and auto-registered for builtin plugins from marketplace.
     */
    val pluginClassRegistry = mutableMapOf<String, String>()

    /** Register a known plugin class for instantiation. */
    fun registerPluginClass(pluginId: String, className: String) {
        pluginClassRegistry[pluginId] = className
    }

    /** Mapping from plugin ID to known builtin class name. */
    private val BUILTIN_CLASSES = mapOf(
        "net-plugin" to "com.mengpaw.plugin.net.NetPlugin",
        "framework-plugin" to "com.mengpaw.plugin.framework.FrameworkPlugin",
        "skill-plugin" to "com.mengpaw.plugin.skill.SkillPlugin",
        "clipboard-plugin" to "com.mengpaw.plugin.clipboard.ClipboardPlugin",
        "tavily-plugin" to "com.mengpaw.plugin.tavily.TavilyPlugin",
        "tribe-plugin" to "com.mengpaw.plugin.hermes.TribePlugin",
        "hermes-plugin" to "com.mengpaw.plugin.hermes.TribePlugin",
        "render-plugin" to "com.mengpaw.plugin.render.RenderPlugin",
        "comfy-plugin" to "com.mengpaw.plugin.comfy.ComfyPlugin",
        "translate-plugin" to "com.mengpaw.plugin.translate.TranslatePlugin",
        "dev-plugin" to "com.mengpaw.plugin.dev.DevPlugin",
        "error-report-plugin" to "com.mengpaw.plugin.errorreport.ErrorReportPlugin",
        "browser-push-plugin" to "com.mengpaw.plugin.browserpush.BrowserPushPlugin",
        "browser-search-plugin" to "com.mengpaw.plugin.browsersearch.BrowserSearchPlugin",
        "browser-mcp-plugin" to "com.mengpaw.plugin.browsermcp.BrowserMcpPlugin",
        "update-plugin" to "com.mengpaw.plugin.update.UpdatePlugin",
        "office-plugin" to "com.mengpaw.plugin.office.OfficePlugin",
        "memory-twin-plugin" to "com.mengpaw.plugin.memorytwin.MemoryTwinPlugin",
        "root-plugin" to "com.mengpaw.plugin.root.RootPlugin",
        "dream-plugin" to "com.mengpaw.plugin.dream.DreamPlugin",
        "evolution-plugin" to "com.mengpaw.plugin.evolution.EvolutionPlugin",
        "concise-plugin" to "com.mengpaw.plugin.concise.ConcisePlugin"
    )

    /** 已知插件类注册全量 (v0.34.3 P0-1) — 内置 + 远程类映射公开,
     *  AppInitializer 注入 BuiltinPluginRegistry 时用来求远程候选集合。 */
    val ALL_KNOWN_CLASSES: Map<String, String> get() = BUILTIN_CLASSES

    /** Look up the class name for a builtin plugin by its ID. */
    fun builtinPluginClass(pluginId: String): String? = BUILTIN_CLASSES[pluginId]
}
