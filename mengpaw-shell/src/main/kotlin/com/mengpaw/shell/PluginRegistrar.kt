// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell

import com.mengpaw.kernel.plugin.Plugin
import com.mengpaw.plugin.clipboard.ClipboardPlugin
import com.mengpaw.plugin.dev.DevPlugin
import com.mengpaw.plugin.framework.FrameworkPlugin
import com.mengpaw.plugin.memorytwin.MemoryTwinPlugin
import com.mengpaw.plugin.net.NetPlugin
import com.mengpaw.plugin.skill.SkillPlugin
import com.mengpaw.plugin.tavily.TavilyPlugin
import com.mengpaw.plugin.update.UpdatePlugin
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
     * framework / skill / dev / net / clipboard /
     * memory-twin / root / hermes(Tribe) / tavily. (memory 已融入内核 agent.memory.*)
     * 注意: root-plugin 与 tribe-plugin 随 APK 编译但不在 bundledPlugins() 自动激活名单 —
     * 默认未激活, 需用户在插件市场安装/启用 (系统提示词「插件」节同步此语义)。
     */
    val BUILTIN_PLUGIN_IDS = setOf(
        "framework-plugin", "skill-plugin", "dev-plugin",
        "net-plugin", "clipboard-plugin",
        "memory-twin-plugin", "root-plugin", "tribe-plugin", "tools-plugin",
        "dream-plugin", "evolution-plugin", "concise-plugin", "termux-plugin",
        "tavily-plugin", "update-plugin"
    )

    /**
     * Plugins that lead similar functionality in other agent frameworks (WowBlue 原创标识).
     * 判定标准: 领先于同类框架功能的原创插件 — 记忆三轨 / 记忆孪生 / 双层技能池 /
     * mDNS 框架发现 / 插件开发工具链 / 部落协作. 基础能力(fs/net/self/clipboard)
     * 与系统级能力(root)不标.
     */
    val WOWBLUE_PLUGIN_IDS = setOf(
        "memory-twin-plugin", "skill-plugin",
        "framework-plugin", "dev-plugin", "tribe-plugin", "tools-plugin",
        "evolution-plugin", "concise-plugin"
    )

    /**
     * 插件英文名映射 — UI 统一显示「中文名 (English)」（中英对照卖点）。
     * 只对中文名插件设置; 中文名已含英文（部落协作 (Tribe) 改回部落协作 + 本映射）或
     * 纯英文名（Agent Loop）不设。
     */
    val PLUGIN_EN_NAMES = mapOf(
        // 内置
        "concise-plugin" to "Concise",
        "dream-plugin" to "Dream",
        "evolution-plugin" to "Agent Evolution",
        "framework-plugin" to "Framework Discovery",
        "net-plugin" to "Network",
        "memory-twin-plugin" to "Memory Twin",
        "skill-plugin" to "Skills",
        "clipboard-plugin" to "Clipboard",
        "dev-plugin" to "Plugin Dev Tools",
        "root-plugin" to "Root Access",
        "tools-plugin" to "Agent Tools",
        "tribe-plugin" to "Tribe",
        "termux-plugin" to "Termux Bridge",
        "tavily-plugin" to "AI Search",
        "update-plugin" to "Auto Update",
        // remote
        "update-plugin" to "Auto Update",
        "translate-plugin" to "Translation Engine",
        "error-report-plugin" to "Error Reporting",
        "render-plugin" to "Image Render API",
        "comfy-plugin" to "ComfyUI Workflows",
        "browser-push-plugin" to "Cross-Device Push",
        "browser-search-plugin" to "Page Archiving",
        "browser-mcp-plugin" to "Browser MCP",
        // connectors
        "connector-openclaw-plugin" to "OpenClaw Connector",
        "connector-qwenpaw-plugin" to "QwenPaw Connector",
        "connector-claude-code-plugin" to "Claude Code Connector",
        "connector-reasonix-plugin" to "Reasonix Connector",
        "connector-trae-plugin" to "Trae IDE Connector"
    )

    /** Builtin plugin display info (名称/描述), 用于内置但未安装时在全局插件列表兜底显示. */
    val BUILTIN_PLUGIN_INFO = mapOf(
        "framework-plugin" to ("框架发现" to "局域网 MengPaw 框架发现 — mDNS 注册与扫描、指纹记录、信任管理 (LAN MengPaw framework discovery — mDNS register/scan, fingerprint, trust management)"),
        "skill-plugin" to ("技能系统" to "可复用的 Agent 剧本系统（YAML+Markdown），含默认 Skill (Reusable Agent skill system (YAML+Markdown) with default skills)"),
        "dev-plugin" to ("插件开发" to "插件开发工具链 — create/audit/share/examples (Plugin dev toolchain — create/audit/share/examples)"),
        "net-plugin" to ("网络请求" to "HTTP 请求：GET/POST，支持自定义 Header 和超时 (HTTP requests: GET/POST with custom headers and timeouts)"),
        "clipboard-plugin" to ("剪贴板" to "剪贴板操作：copy, paste, clear (Clipboard ops: copy, paste, clear)"),
        "memory-twin-plugin" to ("记忆孪生" to "跨设备工作区同步 — ACP P2P 文件同步 + 心跳保活 + QoS自适应 + 手动IP发现 (Cross-device workspace sync — ACP P2P file sync + heartbeat + adaptive QoS + manual IP discovery)"),
        "root-plugin" to ("Root 权限" to "Root 权限管理 — su 命令执行/应用管理/文件系统/系统修改/备份恢复/审计日志 (Root access management — su exec/apps/fs/system/backup/audit)"),
        "tribe-plugin" to ("部落协作" to "多 Agent 部落协作：LAN 自动组队、Kanban 委派、LLM 路由、任务模板、Fleet 并行、广播讨论、ACP 实时、心跳 (Multi-agent tribe collaboration: LAN teams, Kanban delegation, LLM routing, task templates, Fleet parallel, broadcast, ACP realtime, heartbeat)"),
        "tools-plugin" to ("Agent 命令集" to "Agent 命令集注册 — 导入外部 CLI 命令集(gh/飞书等)，摘要注入系统提示词快速调用 (Agent toolset import — external CLI sets (gh/Feishu), summary injected into system prompt)"),
        "dream-plugin" to ("梦境模式" to "梦境模式内置默认实现 (不可移除) — 记忆整理管道; 第三方可实现 DreamProvider 覆盖 (Dream mode built-in (non-removable) — memory consolidation; third-party DreamProvider can override)"),
        "evolution-plugin" to ("智能体进化" to "智能体进化内置默认实现 (不可移除) — 失败模式库/省察引导/框架反馈; 第三方可实现 EvolutionProvider 覆盖 (Agent Evolution built-in (non-removable) — failure library/reflection guides/framework feedback; third-party EvolutionProvider can override)"),
        "concise-plugin" to ("言简意赅" to "去除系统提示词中的结构性输出干扰（强制 Thought/Action 样板、Markdown 装饰），让模型回答更简洁 (Removes structural-output noise from the system prompt (forced Thought/Action boilerplate, Markdown decoration) for cleaner answers)"),
        "termux-plugin" to ("Termux 桥" to "通过 Termux 登录 ubuntu 执行命令与 conda 环境 Python — 逐层探测/脚本执行/输出回传 (Termux bridge — run commands/Python inside Termux+ubuntu+miniconda, with layer detection and output retrieval)"),
        "tavily-plugin" to ("AI 搜索" to "Tavily AI 优化搜索引擎 — 结构化搜索结果 + 网页正文提取，Agent 原生搜索能力 (Tavily AI-optimized search — structured results + web content extraction, Agent's native search)"),
        "update-plugin" to ("自动更新" to "WiFi 环境自动检测更新，可选自动下载安装 — 检查 GitHub/Gitee Releases，安装 APK (Auto update: check/download/install/auto)")
    )

    /** 远程/按需安装插件候选描述 (v0.34.3 P0-1) — CLI.md 远程插件表数据源,
     *  与 PluginClassRegistry.BUILTIN_CLASSES 中非内置条目对齐 (新增远程插件在此登记)。 */
    val REMOTE_PLUGIN_BRIEFS = mapOf(
        "translate-plugin" to "翻译",
        "error-report-plugin" to "错误上报",
        "render-plugin" to "API 生图 (需 API Key)",
        "comfy-plugin" to "ComfyUI 工作流 (默认端口 8188)",
        "browser-push-plugin" to "浏览器推送",
        "browser-search-plugin" to "网页转档/提炼",
        "browser-mcp-plugin" to "浏览器 MCP 工具 (9880 桥)"
    )

    /** PluginViewModel 类注册 — 使内置插件类可被反射实例化 (install 时用类名加载). */
    fun registerPluginClasses() {
        PluginViewModel.registerPluginClass("net-plugin", "com.mengpaw.plugin.net.NetPlugin")
        PluginViewModel.registerPluginClass("framework-plugin", "com.mengpaw.plugin.framework.FrameworkPlugin")
        PluginViewModel.registerPluginClass("skill-plugin", "com.mengpaw.plugin.skill.SkillPlugin")
        PluginViewModel.registerPluginClass("clipboard-plugin", "com.mengpaw.plugin.clipboard.ClipboardPlugin")
        PluginViewModel.registerPluginClass("dev-plugin", "com.mengpaw.plugin.dev.DevPlugin")
        PluginViewModel.registerPluginClass("memory-twin-plugin", "com.mengpaw.plugin.memorytwin.MemoryTwinPlugin")
        PluginViewModel.registerPluginClass("root-plugin", "com.mengpaw.plugin.root.RootPlugin")
        PluginViewModel.registerPluginClass("termux-plugin", "com.mengpaw.plugin.termux.TermuxPlugin")
        PluginViewModel.registerPluginClass("tribe-plugin", "com.mengpaw.plugin.hermes.TribePlugin")
        PluginViewModel.registerPluginClass("tools-plugin", "com.mengpaw.plugin.agenttools.AgentToolsPlugin")
        PluginViewModel.registerPluginClass("dream-plugin", "com.mengpaw.plugin.dream.DreamPlugin")
        PluginViewModel.registerPluginClass("evolution-plugin", "com.mengpaw.plugin.evolution.EvolutionPlugin")
        PluginViewModel.registerPluginClass("concise-plugin", "com.mengpaw.plugin.concise.ConcisePlugin")
        PluginViewModel.registerPluginClass("tavily-plugin", "com.mengpaw.plugin.tavily.TavilyPlugin")
        PluginViewModel.registerPluginClass("update-plugin", "com.mengpaw.plugin.update.UpdatePlugin")
    }

    /** 捆绑插件实例清单 — 随 APK 编译进壳, 首次启动自动 install + activate. */
    fun bundledPlugins(): List<Pair<String, Plugin>> = listOf(
        "framework-plugin" to FrameworkPlugin(),
        "skill-plugin" to SkillPlugin(),
        "dev-plugin" to DevPlugin(),
        "net-plugin" to NetPlugin(),
        "clipboard-plugin" to ClipboardPlugin(),
        "memory-twin-plugin" to MemoryTwinPlugin(),
        "tools-plugin" to com.mengpaw.plugin.agenttools.AgentToolsPlugin(),
        "dream-plugin" to com.mengpaw.plugin.dream.DreamPlugin(),
        "evolution-plugin" to com.mengpaw.plugin.evolution.EvolutionPlugin(),
        "concise-plugin" to com.mengpaw.plugin.concise.ConcisePlugin(),
        "termux-plugin" to com.mengpaw.plugin.termux.TermuxPlugin(),
        "tavily-plugin" to TavilyPlugin(),
        "update-plugin" to UpdatePlugin(),
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
