// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell

import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.plugin.clipboard.ClipboardPlugin
import com.mengpaw.plugin.dev.DevPlugin
import com.mengpaw.plugin.framework.FrameworkPlugin
import com.mengpaw.plugin.fs.FsPlugin
import com.mengpaw.plugin.memorytwin.MemoryTwinPlugin
import com.mengpaw.plugin.net.NetPlugin
import com.mengpaw.plugin.skill.SkillPlugin
import com.mengpaw.shell.ui.screens.PluginViewModel

/**
 * 捆绑插件注册表 — 微内核的"插件武装"装配清单。
 *
 * 从 MainActivity.kt 拆出 (2026-08-01, ≥50KB 文件拆分): 内置插件 ID 集合 /
 * WowBlue 标识 / 显示信息 / 类注册 / 自动安装 五份装配数据集中于此,
 * MainActivity 只保留生命周期与 UI。新增捆绑插件时只需改本文件。
 */
object PluginRegistrar {

    /**
     * Plugin IDs compiled into the shell APK (显示为"内置"分类).
     * 必须与 mengpaw-shell/build.gradle.kts 中 implementation(project(":plugin-*")) 对齐:
     * framework / skill / dev / fs / net / clipboard /
     * memory-twin / root / hermes(Tribe). (memory 已融入内核 agent.memory.*)
     */
    val BUILTIN_PLUGIN_IDS = setOf(
        "framework-plugin", "skill-plugin", "dev-plugin",
        "fs-plugin", "net-plugin", "clipboard-plugin",
        "memory-twin-plugin", "root-plugin", "tribe-plugin", "tools-plugin",
        "dream-plugin", "evolution-plugin"
    )

    /**
     * Plugins that lead similar functionality in other agent frameworks (WowBlue 原创标识).
     * 判定标准: 领先于同类框架功能的原创插件 — 记忆三轨 / 记忆孪生 / 双层技能池 /
     * mDNS 框架发现 / 插件开发工具链 / 部落协作. 基础能力(fs/net/self/clipboard)
     * 与系统级能力(root)不标.
     */
    val WOWBLUE_PLUGIN_IDS = setOf(
        "memory-twin-plugin", "skill-plugin",
        "framework-plugin", "dev-plugin", "tribe-plugin", "tools-plugin"
    )

    /** Builtin plugin display info (名称/描述), 用于内置但未安装时在全局插件列表兜底显示. */
    val BUILTIN_PLUGIN_INFO = mapOf(
        "framework-plugin" to ("框架发现" to "局域网 MengPaw 框架发现 — mDNS 注册与扫描、指纹记录、信任管理"),
        "skill-plugin" to ("技能系统" to "可复用的 Agent 剧本系统（YAML+Markdown），含默认 Skill"),
        "dev-plugin" to ("插件开发" to "插件开发工具链 — create/audit/share/examples"),
        "fs-plugin" to ("文件系统" to "文件系统增量操作：cp, mv, stat, grep, glob (读写用内核 agent.read/write/ls/rm/mkdir)"),
        "net-plugin" to ("网络请求" to "HTTP 请求：GET/POST，支持自定义 Header 和超时"),
        "clipboard-plugin" to ("剪贴板" to "剪贴板操作：copy, paste, clear"),
        "memory-twin-plugin" to ("记忆孪生" to "跨设备工作区同步 — ACP P2P 文件同步 + 心跳保活 + QoS自适应 + 手动IP发现"),
        "root-plugin" to ("Root 权限" to "Root 权限管理 — su 命令执行/应用管理/文件系统/系统修改/备份恢复/审计日志"),
        "tribe-plugin" to ("部落协作 (Tribe)" to "多 Agent 部落协作：LAN 自动组队、Kanban 委派、LLM 路由、任务模板、Fleet 并行、广播讨论、ACP 实时、心跳"),
        "tools-plugin" to ("Agent 命令集" to "Agent 命令集注册 — 导入外部 CLI 命令集(gh/飞书等)，摘要注入系统提示词快速调用"),
        "dream-plugin" to ("梦境模式" to "梦境模式内置默认实现 (不可移除) — 记忆整理管道; 第三方可实现 DreamProvider 覆盖"),
        "evolution-plugin" to ("智能体进化" to "智能体进化内置默认实现 (不可移除) — 失败模式库/省察引导/框架反馈; 第三方可实现 EvolutionProvider 覆盖")
    )

    /** PluginViewModel 类注册 — 使内置插件类可被反射实例化 (install 时用类名加载). */
    fun registerPluginClasses() {
        PluginViewModel.registerPluginClass("fs-plugin", "com.mengpaw.plugin.fs.FsPlugin")
        PluginViewModel.registerPluginClass("net-plugin", "com.mengpaw.plugin.net.NetPlugin")
        PluginViewModel.registerPluginClass("framework-plugin", "com.mengpaw.plugin.framework.FrameworkPlugin")
        PluginViewModel.registerPluginClass("skill-plugin", "com.mengpaw.plugin.skill.SkillPlugin")
        PluginViewModel.registerPluginClass("clipboard-plugin", "com.mengpaw.plugin.clipboard.ClipboardPlugin")
        PluginViewModel.registerPluginClass("dev-plugin", "com.mengpaw.plugin.dev.DevPlugin")
        PluginViewModel.registerPluginClass("memory-twin-plugin", "com.mengpaw.plugin.memorytwin.MemoryTwinPlugin")
        PluginViewModel.registerPluginClass("root-plugin", "com.mengpaw.plugin.root.RootPlugin")
        PluginViewModel.registerPluginClass("tribe-plugin", "com.mengpaw.plugin.hermes.TribePlugin")
        PluginViewModel.registerPluginClass("tools-plugin", "com.mengpaw.plugin.agenttools.AgentToolsPlugin")
        PluginViewModel.registerPluginClass("dream-plugin", "com.mengpaw.plugin.dream.DreamPlugin")
        PluginViewModel.registerPluginClass("evolution-plugin", "com.mengpaw.plugin.evolution.EvolutionPlugin")
    }

    /** 捆绑插件实例清单 — 随 APK 编译进壳, 首次启动自动 install + activate. */
    fun bundledPlugins(): List<Pair<String, Plugin>> = listOf(
        "framework-plugin" to FrameworkPlugin(),
        "skill-plugin" to SkillPlugin(),
        "dev-plugin" to DevPlugin(),
        "fs-plugin" to FsPlugin(),
        "net-plugin" to NetPlugin(),
        "clipboard-plugin" to ClipboardPlugin(),
        "memory-twin-plugin" to MemoryTwinPlugin(),
        "tools-plugin" to com.mengpaw.plugin.agenttools.AgentToolsPlugin(),
        "dream-plugin" to com.mengpaw.plugin.dream.DreamPlugin(),
        "evolution-plugin" to com.mengpaw.plugin.evolution.EvolutionPlugin(),
    )

    /** 捆绑插件自动安装 — 已安装跳过, 逐个容错 (单插件失败不影响其余). */
    suspend fun autoInstallBundled() {
        try {
            val pm = com.mengpaw.kernel.plugin.PluginManager.globalInstance
            for ((id, plugin) in bundledPlugins()) {
                try {
                    if (pm.get(id) == null) {
                        pm.install(plugin).fold(
                            onSuccess = {
                                pm.activate(id).fold(
                                    onSuccess = { android.util.Log.i("MengPaw", "Bundled plugin $id installed + activated") },
                                    onFailure = { android.util.Log.w("MengPaw", "Bundled plugin $id installed but activate failed: ${it.message}", it) }
                                )
                            },
                            onFailure = { android.util.Log.w("MengPaw", "Bundled plugin $id install failed: ${it.message}", it) }
                        )
                    } else { android.util.Log.d("MengPaw", "Bundled plugin $id already installed, skipping") }
                } catch (e: Exception) { android.util.Log.w("MengPaw", "Auto-install $id panicked: ${e.message}", e) }
            }
            android.util.Log.i("MengPaw", "Bundled auto-install done: ${pm.count()} installed, ${pm.activeCount()} active")
        } catch (_: Exception) {}
    }
}
