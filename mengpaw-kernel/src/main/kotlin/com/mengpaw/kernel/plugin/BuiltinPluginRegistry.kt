// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.plugin

/**
 * 内置/远程插件注册源 (v0.34.3 P0-1) — CLI.md 插件表从本注册源动态生成,
 * 消除 CliDocGenerator 硬编码插件表 (幻影条目如已删除的 notification-plugin)。
 *
 * 单一事实源在 shell 层 PluginRegistrar (BUILTIN_PLUGIN_IDS/BUILTIN_PLUGIN_INFO)
 * 与 PluginClassRegistry (BUILTIN_CLASSES), AppInitializer 启动时注入。
 * map: 插件 ID → 用途描述 (CLI.md 用途列)。
 */
object BuiltinPluginRegistry {
    /** 内置插件 (随 APK 预装, 仅可禁用) — id → 用途描述。 */
    @Volatile var builtinBriefs: Map<String, String> = emptyMap()

    /** 远程/按需安装插件候选 — id → 用途描述。 */
    @Volatile var remoteBriefs: Map<String, String> = emptyMap()

    /** 测试隔离用。 */
    fun resetForTest() {
        builtinBriefs = emptyMap()
        remoteBriefs = emptyMap()
    }
}
