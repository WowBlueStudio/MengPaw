// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mengpaw.shell.PluginRegistrar
import com.mengpaw.shell.ui.localization.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── 设置页数据预计算 — 拆自 AppRoot.kt AppRootContent (2026-08-06, >400 行文件拆分批次4) ──
// 插件/命令/技能/工作区列表: 全部 remember/LaunchedEffect/DisposableEffect 原样迁移,
// 状态归本 composable 所有, 刷新回调经 holder 暴露 (行为与拆分前逐行一致)。

/**
 * CLI 命令来源分类 (v0.34.1) — 全局工具面板标签语义: 核心/插件。
 * 内核命名空间 = PipelineManager 内置 (self/evolution/agent/plugin/security) + core 适配层 (sys);
 * 其余命名空间 (插件注册) 一律标插件 (未知来源防御, 缺省安全)。
 */
internal val CORE_TOOL_NAMESPACES = setOf("self", "evolution", "agent", "plugin", "security", "sys")

internal fun toolSourceFor(fullName: String): String {
    val ns = fullName.substringBefore(".")
    return if (ns in CORE_TOOL_NAMESPACES) "core" else "plugin"
}

/** 设置页六类列表 + 工作区刷新回调。 */
data class AppRootSettingsItems(
    val pluginItems: List<FrameworkItem>,
    val toolItems: List<FrameworkItem>,
    val skillItems: List<FrameworkItem>,
    val agentSkillItems: List<FrameworkItem>,
    val agentToolItems: List<FrameworkItem>,
    val workspaceItems: List<FrameworkItem>,
    val refreshWorkspace: () -> Unit
)

@Composable
internal fun rememberAppRootSettingsItems(
    showSettings: Boolean,
    activeAgent: String,
    agentViewModel: AgentViewModel,
    strings: AppStrings
): AppRootSettingsItems {
    var workspaceVersion by remember { mutableIntStateOf(0) }
    // 命令执行版本号: Agent 每完成一批命令 (bang/ReAct 循环) → +1 → 设置页
    // 全局工具/智能体工具/智能体技能/插件 列表实时重扫 (命令可能改文件/装插件/进化技能)
    var agentDataVersion by remember { mutableIntStateOf(0) }

    // Plugins: recomputed each time the settings screen opens (deferInit may finish later,
    // and downloaded plugins land anytime — never trust a one-shot list)
    var pluginItems by remember { mutableStateOf(emptyList<FrameworkItem>()) }
    LaunchedEffect(showSettings, agentDataVersion) {
        if (!showSettings) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val pm = com.mengpaw.kernel.plugin.PluginManager.globalInstance
            val installed = pm.listAll().map { (plugin, status) ->
                val enName = PluginRegistrar.PLUGIN_EN_NAMES[plugin.metadata.id]
                FrameworkItem(name = plugin.metadata.name, enName = enName,
                    isWowBlue = plugin.metadata.id in PluginRegistrar.WOWBLUE_PLUGIN_IDS,
                    category = if (plugin.metadata.id in PluginRegistrar.BUILTIN_PLUGIN_IDS) ItemCategory.BUILTIN else ItemCategory.OFFICIAL,
                    summary = plugin.metadata.description,
                    docMarkdown = "## ${enName?.let { "${plugin.metadata.name} ($it)" } ?: plugin.metadata.name}\n\n${plugin.metadata.description}\n\nID: ${plugin.metadata.id}\n版本: ${plugin.metadata.version}\n状态: ${status.name}\n命令数: ${plugin.commands.size}")
            }
            // Builtin plugins compiled into the APK but not yet installed — show them anyway,
            // so new bundled plugins are never invisible in the global list
            val missingBuiltins = PluginRegistrar.BUILTIN_PLUGIN_IDS
                .filter { id -> pm.get(id) == null }
                .mapNotNull { id -> PluginRegistrar.BUILTIN_PLUGIN_INFO[id]?.let { (name, desc) ->
                    val enName = PluginRegistrar.PLUGIN_EN_NAMES[id]
                    FrameworkItem(name = name, enName = enName, category = ItemCategory.BUILTIN, isWowBlue = id in PluginRegistrar.WOWBLUE_PLUGIN_IDS,
                        summary = "$desc — 内置，未安装",
                        docMarkdown = "## ${enName?.let { "$name ($it)" } ?: name}\n\n$desc\n\nID: $id\n状态: 未安装（内置插件，可在插件市场激活）")
                } }
            // Kernel namespaces are not plugins but surface as builtin capabilities
            val kernelNamespaces = listOf(
                FrameworkItem("self (内置)", ItemCategory.BUILTIN, "Agent 自我管理 — 状态/配置/统计/版本/头像/主题/通知/时间 (Agent self-management — status/config/stats/version/avatar/theme/notify/time)", ""),
    FrameworkItem("evolution (内置)", ItemCategory.BUILTIN, "Agent 进化 — 失败钩子/金字塔自问/错误四分法处置/绩效 (Agent Evolution — failure hooks/pyramid self-inquiry/error classification/performance)", "", isWowBlue = true),
                FrameworkItem("agent (内置)", ItemCategory.BUILTIN, "文档管理 — 记忆/CLI/档案/审计/梦境/存储 (Document management — memory/CLI/archive/audit/dream/storage)", ""),
                FrameworkItem("plugin (内置)", ItemCategory.BUILTIN, "插件管理 — 市场/搜索/安装/卸载/启停/升级 (Plugin management — market/search/install/uninstall/toggle/update)", ""),
                FrameworkItem("sys (内置)", ItemCategory.BUILTIN, "系统信息 — 电量/网络/CPU/存储/定位/剪贴板 (System info — battery/network/CPU/storage/location/clipboard)", ""),
            )
            val items = installed + missingBuiltins + kernelNamespaces
            withContext(Dispatchers.Main) { pluginItems = items }
        }
    }

    // ── CLI 命令: 全量动态列表 (v0.34.1 起不再手工精选)
    // 数据源 = CommandSearch 索引 (engine.listCommands, 内核+插件命令全部, ~150 条),
    // 标签按来源: 内核命名空间 → 核心 (core), 其余 (插件注册) → 插件 (plugin)。
    // 此前手工精选 40 条快照永远滞后于注册表, McpServer.listTools 与插件命令同源
    // (同为 ACTIVE 插件命令包装) 造成重复 — 两者均已移除。
    val toolItems = remember(activeAgent, agentViewModel.activeNamespaces().hashCode()) {
        val engine = agentViewModel.activeEngine() ?: return@remember emptyList()
        try {
            engine.listCommands().map { info ->
                FrameworkItem(
                    name = info.name,
                    category = ItemCategory.BUILTIN, // 徽标由 GlobalToolPoolPanel 按 source 渲染
                    summary = info.description,
                    source = toolSourceFor(info.name)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    // Skills: only real Skill files under /技能剧本/ (CLI commands like skill.ls are Tools,
    // not Skills — v0.19.5). Recomputed each time settings opens, because skill-plugin seeds
    // defaults asynchronously at startup and a one-shot snapshot would stay stale forever
    var skillItems by remember { mutableStateOf(emptyList<FrameworkItem>()) }
    LaunchedEffect(showSettings, agentDataVersion) {
        if (!showSettings) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val skillsDir = java.io.File(com.mengpaw.kernel.DataPaths.SKILLS)
            val skillFiles = if (skillsDir.exists()) {
                skillsDir.listFiles()?.filter { it.extension == "md" }
                    ?.map { file ->
                        val content = try { file.readText() } catch (_: Exception) { "" }
                        // docMarkdown = 技能 md 全文 — 展开区以 Markdown 渲染剧本
                        // source = frontmatter 来源标记 (core/plugin=预置不可删; 空=用户技能可删)
                        FrameworkItem(file.nameWithoutExtension, ItemCategory.BUILTIN,
                            summary = extractSummary(content), docMarkdown = content,
                            source = extractSkillSource(content))
                    }
                    ?: emptyList()
            } else emptyList()
            // Empty dir → empty list; the section renders its own "暂无条目" empty state.
            // CLI management commands (skill.ls/skill.run/...) are Tools, not Skills.
            withContext(Dispatchers.Main) { skillItems = skillFiles }
        }
    }

    // Per-agent local skills: Agent文档/{agent}/skills/*.md — NOT the global /技能剧本/ pool.
    // Global vs exclusive separation is enforced at the UI boundary (LESSONS 99).
    var agentSkillItems by remember { mutableStateOf(emptyList<FrameworkItem>()) }
    LaunchedEffect(showSettings, activeAgent, agentDataVersion) {
        if (!showSettings) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            // 一次性迁移: DataPaths 双重路径 bug(v0.19.7 修复)前, 本地技能落在
            // Agent文档/Agent文档/{agent}/skills 错误路径 — 迁到正确路径
            try {
                val legacy = java.io.File(com.mengpaw.kernel.DataPaths.BASE, "Agent文档/Agent文档/$activeAgent/skills")
                if (legacy.exists()) {
                    val target = java.io.File(com.mengpaw.kernel.DataPaths.agentSkillsDir(activeAgent))
                    if (target.listFiles().isNullOrEmpty()) legacy.copyRecursively(target, overwrite = false)
                    legacy.deleteRecursively()
                }
            } catch (_: Exception) {}
            val dir = java.io.File(com.mengpaw.kernel.DataPaths.agentSkillsDir(activeAgent))
            val items = if (dir.exists()) {
                // 技能形态全覆盖 (v0.34.1+): ① md 剧本 + 同名资源文件夹 (脚本/流程) 合并一条目;
                // ② 纯文件夹技能 (无同名 md); ③ 散资源文件 (.py/.sh/.json 等非 md 剧本)。
                // 全部可见可删 — 删除统一: 删 {name}.md + {name} 递归 (对三种形态幂等)。
                val mdNames = dir.listFiles { f -> f.isFile && f.extension == "md" }
                    ?.map { it.nameWithoutExtension }?.toSet() ?: emptySet()
                buildList {
                    dir.listFiles { f -> f.isFile && f.extension == "md" }?.sortedBy { it.name }?.forEach { file ->
                        val content = try { file.readText() } catch (_: Exception) { "" }
                        val hasFolder = java.io.File(dir, file.nameWithoutExtension).isDirectory
                        add(FrameworkItem(name = file.nameWithoutExtension, category = ItemCategory.BUILTIN,
                            summary = (if (hasFolder) "[含资源文件夹] " else "") + extractSummary(content),
                            docMarkdown = content))
                    }
                    dir.listFiles { f -> f.isDirectory && f.name !in mdNames }?.sortedBy { it.name }?.forEach { folder ->
                        val files = folder.listFiles()?.map { it.name } ?: emptyList()
                        add(FrameworkItem(name = folder.name, category = ItemCategory.BUILTIN,
                            summary = "资源文件夹 (${files.size} 个文件)",
                            docMarkdown = files.joinToString("\n", prefix = "资源文件夹 ${folder.name}/:\n")))
                    }
                    dir.listFiles { f -> f.isFile && f.extension != "md" }?.sortedBy { it.name }?.forEach { file ->
                        val content = try { file.readText() } catch (_: Exception) { "(二进制或不可读文件)" }
                        add(FrameworkItem(name = file.name, category = ItemCategory.BUILTIN,
                            summary = "资源文件 (${file.length()} 字节)",
                            docMarkdown = content))
                    }
                }
            } else emptyList()
            withContext(Dispatchers.Main) { agentSkillItems = items }
        }
    }

    // Agent 专属工具: Agent文档/{agent}/tools/*.json — 命令集注册清单（非全局共享，LESSONS 99）
    // 每组 = 一个命令集 (如 飞书 CLI): name=显示名, enName=权威名(文件定位/删除),
    // children=命令列表 (展开查看), summary=命令数+来源。删除整组: AgentToolsStore.remove。
    var agentToolItems by remember { mutableStateOf(emptyList<FrameworkItem>()) }
    LaunchedEffect(showSettings, activeAgent, agentDataVersion) {
        if (!showSettings) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val items = com.mengpaw.plugin.agenttools.AgentToolsStore.readAll(activeAgent).map { set ->
                FrameworkItem(
                    name = set.displayName.ifBlank { set.name },
                    enName = set.name,
                    category = ItemCategory.CUSTOM,
                    summary = "${set.commands.size} 条命令 · 来源: ${set.source.ifBlank { "手动粘贴" }}",
                    docMarkdown = com.mengpaw.plugin.agenttools.AgentToolsStore.toMarkdown(set),
                    children = set.commands.map { cmd ->
                        FrameworkItem(name = cmd.name, category = ItemCategory.CUSTOM,
                            summary = cmd.description,
                            docMarkdown = listOf(cmd.usage, cmd.description).filter { it.isNotBlank() }.joinToString("\n\n"))
                    })
            }
            withContext(Dispatchers.Main) { agentToolItems = items }
        }
    }

    var workspaceItems by remember { mutableStateOf(emptyList<FrameworkItem>()) }
    // 实时刷新：Agent 写工作区文档（onDocChanged 多播）→ 列表立即重扫。
    // 分屏聊天时，一边聊一边可见文档变动（写记忆/更新文档即时反映）。
    DisposableEffect(Unit) {
        val listener: (String, String?) -> Unit = { agentName, _ ->
            if (showSettings && agentName == activeAgent) workspaceVersion++
        }
        com.mengpaw.kernel.agent.AgentDocs.addDocListener(listener)
        onDispose { com.mengpaw.kernel.agent.AgentDocs.removeDocListener(listener) }
    }
    // 实时刷新：Agent 执行命令（bang "!" 或 ReAct 循环）→ 工具/技能/插件列表重扫。
    // 挂在当前 agent 的 engine 实例上（每 agent 一个 engine），切换 agent 时重挂。
    DisposableEffect(activeAgent) {
        val engine = agentViewModel.activeEngine()
        if (engine == null) {
            onDispose { }
        } else {
            val listener = { if (showSettings) agentDataVersion++ }
            engine.addCommandListener(listener)
            onDispose { engine.removeCommandListener(listener) }
        }
    }
    // 与 skill/agent-skill/agent-tool 列表一致：设置页打开时强制刷新 —
    // 缺 showSettings 键会导致列表停留在应用启动时的快照，之后 Agent 写入的
    // memory/ 记忆文件不会出现（工作区文件列表 memory 文件树不显示）
    LaunchedEffect(showSettings, activeAgent, workspaceVersion) {
        if (!showSettings) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val dir = java.io.File(com.mengpaw.kernel.DataPaths.AGENTS, activeAgent)
            val items = buildList {
                // 顶层 Markdown 文档（agents/soul/boost 等）
                dir.listFiles { f -> f.isFile && f.extension == "md" }?.sortedBy { it.name }?.forEach { file ->
                    val content = try { file.readText() } catch (_: Exception) { "" }
                    add(FrameworkItem(name = file.name, category = ItemCategory.BUILTIN,
                        summary = extractSummary(content), docMarkdown = content))
                }
                // memory 记忆目录节点 — 三重记忆各自成行: 长期 memory.md / 中期 memory_{date}.md / 项目 project_{name}_memory.md
                val memoryDir = java.io.File(dir, "memory")
                if (memoryDir.exists()) {
                    val memoryFiles = memoryDir.listFiles { f -> f.isFile && f.extension == "md" }
                        ?.sortedBy { it.name } ?: emptyList()
                    if (memoryFiles.isNotEmpty()) {
                        val longTerm = memoryFiles.count { it.name == "memory.md" }
                        val midTerm = memoryFiles.count { it.name.startsWith("memory_") }
                        val project = memoryFiles.count { it.name.startsWith("project_") }
                        add(FrameworkItem(
                            name = strings.workspaceMemoryFolder,
                            category = ItemCategory.BUILTIN,
                            isFolder = true,
                            summary = String.format(strings.workspaceMemorySummary, longTerm, midTerm, project, memoryFiles.size),
                            children = memoryFiles.map { f ->
                                FrameworkItem(
                                    name = f.name,
                                    category = ItemCategory.BUILTIN,
                                    docMarkdown = try { f.readText() } catch (_: Exception) { "" })
                            }
                        ))
                    }
                }
                // Notes 笔记目录节点 — 记忆之外的笔记 (如其他 Agent 知识信息)。
                // 目录预建于 AgentDocs.bootstrap; 始终显示 (空目录也显示摘要), 子行仅收 .md。
                val notesDir = java.io.File(dir, "Notes")
                if (notesDir.exists()) {
                    val notesFiles = notesDir.listFiles { f -> f.isFile && f.extension == "md" }
                        ?.sortedBy { it.name } ?: emptyList()
                    add(FrameworkItem(
                        name = strings.workspaceNotesFolder,
                        category = ItemCategory.BUILTIN,
                        isFolder = true,
                        summary = String.format(strings.workspaceNotesSummary, notesFiles.size),
                        children = notesFiles.map { f ->
                            FrameworkItem(
                                name = f.name,
                                category = ItemCategory.BUILTIN,
                                docMarkdown = try { f.readText() } catch (_: Exception) { "" })
                        }
                    ))
                }
                // evolution 进化档案目录节点 — 失败模式库/用户反应/框架反馈 (与 memory/Notes 同款)。
                // 动态生成目录: 有档案才显示 (防空目录噪音); 子行收全部文件 (failures.jsonl 等非 md 档案也可读)。
                val evolutionDir = java.io.File(dir, "evolution")
                if (evolutionDir.exists()) {
                    val evolutionFiles = evolutionDir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }
                        ?.sortedBy { it.name } ?: emptyList()
                    val failures = evolutionFiles.count { it.name == "failures.jsonl" }
                    val reactions = evolutionFiles.count { it.name == "reactions.md" }
                    val feedback = java.io.File(evolutionDir, "feedback").listFiles()?.count { it.isFile } ?: 0
                    if (evolutionFiles.isNotEmpty() || feedback > 0) {
                        // P0 结果可信度 (2026-08-08): evolution 节点摘要附加幻觉率 + 未沉淀红灯
                        val healthSuffix = try {
                            buildString {
                                val veracity = com.mengpaw.kernel.evolution.EvolutionStore.veracityStats(activeAgent)
                                if (veracity.startsWith("会话失败如实提及")) {
                                    append(" | ").append(veracity.lineSequence().first())
                                }
                                val stats = com.mengpaw.kernel.evolution.EvolutionStore.stats(activeAgent)
                                stats.lineSequence().firstOrNull { it.startsWith("复现模式:") }
                                    ?.let { append(" | ").append(it.substringBefore(" (")) }
                                if (stats.contains("红灯")) {
                                    append(" | ⚠️ 有失败未沉淀 (evolution.audit 查看)")
                                }
                            }
                        } catch (_: Exception) { "" }
                        add(FrameworkItem(
                            name = strings.workspaceEvolutionFolder,
                            category = ItemCategory.BUILTIN,
                            isFolder = true,
                            summary = String.format(strings.workspaceEvolutionSummary, failures, reactions, feedback, evolutionFiles.size + feedback) + healthSuffix,
                            children = evolutionFiles.map { f ->
                                FrameworkItem(
                                    name = f.name,
                                    category = ItemCategory.BUILTIN,
                                    docMarkdown = try { f.readText() } catch (_: Exception) { "" })
                            }
                        ))
                    }
                }
            }
            withContext(Dispatchers.Main) { workspaceItems = items }
        }
    }

    return AppRootSettingsItems(
        pluginItems = pluginItems,
        toolItems = toolItems,
        skillItems = skillItems,
        agentSkillItems = agentSkillItems,
        agentToolItems = agentToolItems,
        workspaceItems = workspaceItems,
        refreshWorkspace = { workspaceVersion++ }
    )
}
