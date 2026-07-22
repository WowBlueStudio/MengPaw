// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.plugin.PluginManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages the Agent's structured document system.
 *
 * Directory structure:
 *   /data/data/com.mengpaw/files/agents/{agent-id}/
 *   ├── Agents.md      # Security rules (system, read-only)
 *   ├── Soul.md        # Style & execution mode
 *   ├── Profile.md     # Identity & relationships
 *   ├── Memory.md      # Task records + auto-index
 *   ├── Memory.archive.md  # Archived old memories
 *   ├── CLI.md         # Auto-generated command reference
 *   └── memory.idx     # Binary index for fast search
 */
class AgentDocManager(
    private val agentId: String = "agent-001",
    private val baseDir: String = com.mengpaw.kernel.DataPaths.AGENTS,
    /** Plugin manager for CLI doc generation. Can be set after construction. */
    @Volatile var pluginManager: PluginManager? = null
) {
    private val agentDir: File get() = File(baseDir, agentId)

    // Memory constraints
    private val maxMemories = 50
    private val maxMemoryLines = 200
    private val archiveThreshold = 40

    // ── Initialization ────────────────────────────────────────────────

    /** Create all default documents for a new agent using pre-built .md templates. */
    fun initAgentDocs(profile: AgentProfile) {
        agentDir.mkdirs()

        // Copy all .md templates from assets via AgentDocs bootstrapper
        AgentDocs.bootstrap(profile.agentName)

        // Profile.md — always overwrite with dynamic identity (template is generic)
        val profileFile = file(AgentDocType.PROFILE)
        try { profileFile.atomicWriteText(profile.toMarkdown()) } catch (e: Exception) {
            ErrorCollector.report(e, "AgentDocManager.initAgentDocs")
        }

        // CLI.md — always regenerate from active plugin list
        regenerateCliDoc(pluginManager ?: PluginManager())
    }

    // ── Read ──────────────────────────────────────────────────────────

    fun getDoc(docType: AgentDocType): String {
        val f = file(docType)
        return if (f.exists()) try { f.readText() } catch (e: Exception) { ErrorCollector.report(e, "AgentDocManager.getDoc"); "" } else ""
    }

    fun getDocPath(docType: AgentDocType): String = file(docType).absolutePath

    fun listDocs(): List<String> = AgentDocType.entries.map { it.name.lowercase() + ".md" }

    // ── Memory management ─────────────────────────────────────────────

    /**
     * Append a memory record and rebuild the index.
     * Called automatically by AgentEngine after each task.
     */
    fun updateMemory(entry: MemoryRecord) {
        val memFile = file(AgentDocType.MEMORY)
        val content = if (memFile.exists()) try { memFile.readText() } catch (e: Exception) { ErrorCollector.report(e, "AgentDocManager.updateMemory"); "" } else ""

        // Build new entry
        val entryMd = """
## ${entry.id}: ${entry.title}
- **日期**: ${entry.date}
- **关键词**: ${entry.keywords.joinToString(", ")}
- **内容**: ${entry.content.take(maxMemoryLines * 80)}

---
""".trimIndent()

        // FIX: Preserve existing records when inserting new entry after index section.
        // split("---", limit=3) gives at most 3 parts; parts[2] holds all existing records.
        // Previous code used parts.drop(3) which was always empty → DATA LOSS on every insert.
        val parts = content.split("---", limit = 3)
        val newContent = if (parts.size >= 3) {
            parts[0] + "---" + parts[1] + "---\n\n" + entryMd + parts[2]
        } else {
            content + "\n" + entryMd
        }

        try { memFile.atomicWriteText(newContent) } catch (e: Exception) { ErrorCollector.report(e, "AgentDocManager.updateMemory"); return }
        rebuildIndex()
        enforceLimits()
    }

    /** Search memories by keyword (index-only, does NOT load full content into context). */
    fun searchMemory(query: String): List<MemoryRecord> {
        val q = query.lowercase()
        val memFile = file(AgentDocType.MEMORY)
        if (!memFile.exists()) return emptyList()

        val text = try { memFile.readText() } catch (e: Exception) { ErrorCollector.report(e, "AgentDocManager.searchMemory"); "" }
        val records = parseMemoryRecords(text)
        return records.filter {
            it.title.lowercase().contains(q) ||
            it.keywords.any { k -> k.lowercase().contains(q) }
        }.map { it.copy(content = it.content.take(200) + "...") } // Truncate for passive query
    }

    /** Get memory statistics without loading content. */
    fun getMemoryStats(): Pair<Int, Long> {
        val memFile = file(AgentDocType.MEMORY)
        if (!memFile.exists()) return 0 to 0L
        val records = parseMemoryRecords(try { memFile.readText() } catch (e: Exception) { ErrorCollector.report(e, "AgentDocManager.getMemoryStats"); "" })
        return records.size to memFile.length()
    }

    /** Get only the memory index (titles + keywords), not full content. */
    fun getMemoryIndex(): String {
        val memFile = file(AgentDocType.MEMORY)
        if (!memFile.exists()) return "(No memories)"
        val records = parseMemoryRecords(try { memFile.readText() } catch (e: Exception) { ErrorCollector.report(e, "AgentDocManager.getMemoryIndex"); "" })
        if (records.isEmpty()) return "(No memories)"
        return buildString {
            appendLine("| ID | 日期 | 标题 | 关键词 |")
            appendLine("|----|------|------|--------|")
            records.forEach { r ->
                appendLine("| ${r.id} | ${r.date} | ${r.title} | ${r.keywords.take(3).joinToString(", ")} |")
            }
        }
    }

    // ── CLI reference ─────────────────────────────────────────────────

    /** Regenerate CLI.md — Agent's primary command reference with permission guides & tutorials. */
    fun regenerateCliDoc(pluginManager: PluginManager) {
        try { file(AgentDocType.CLI).atomicWriteText(buildString {
            appendLine("# MengPaw CLI 命令参考")
            appendLine()
            appendLine("> 本文档是 Agent 的主要命令参考。Agent 在执行任何操作前应查阅本文档，")
            appendLine("> 了解所需命令、权限和前置条件。所有提醒义务由 Agent 承担。")
            appendLine("> 生成时间: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())}")
            appendLine("> 活跃插件: ${pluginManager.activeCount()}")
            appendLine()

            // ── Built-in Commands ──
            appendLine("## 内置命令 (始终可用)")
            appendLine()
            appendLine("### self — Agent 自省")
            appendLine("| 命令 | 用法 | 说明 | 权限 |")
            appendLine("|------|------|------|------|")
            appendLine("| self.status | self status | 运行状态 | 无 |")
            appendLine("| self.config | self config [key=value] | 查看/修改配置 | 无 |")
            appendLine("| self.stats | self stats | 系统统计 | 无 |")
            appendLine("| self.version | self version | 版本信息 | 无 |")
            appendLine("| self.avatar | self.avatar <path> | 设置头像 | 无 |")
            appendLine("| self.theme | self.theme primary=#0E4397 surface=#FFFFFF | 修改主题色 | 无 |")
            appendLine()

            appendLine("### plugin — 插件/工具/技能管理")
            appendLine("| 命令 | 用法 | 说明 | 权限 |")
            appendLine("|------|------|------|------|")
            appendLine("| plugin.marketplace | plugin.marketplace [--refresh] | 浏览插件市场 | 无 |")
            appendLine("| plugin.search | plugin.search <关键词> | 搜索插件 | 无 |")
            appendLine("| plugin.install | plugin.install <插件ID> | 下载+验证+安装+激活 | 无 |")
            appendLine("| plugin.uninstall | plugin.uninstall <插件ID> | 卸载插件 | 无 |")
            appendLine("| plugin.list | plugin.list | 已安装列表 | 无 |")
            appendLine("| plugin.info | plugin.info <插件ID> | 插件详情 | 无 |")
            appendLine("| plugin.enable | plugin.enable <插件ID> | 启用 | 无 |")
            appendLine("| plugin.disable | plugin.disable <插件ID> | 停用 | 无 |")
            appendLine("| plugin.update | plugin.update <插件ID> | 检查更新 | 无 |")
            appendLine("| plugin.upgrade | plugin.upgrade --all | 升级全部 | 无 |")
            appendLine()

            appendLine("### agent — Agent 文档")
            appendLine("| 命令 | 用法 | 说明 |")
            appendLine("|------|------|------|")
            appendLine("| agent.cli | agent.cli | 查看本文档 |")
            appendLine("| agent.docs | agent.docs | 列出所有文档 |")
            appendLine("| agent.memory | agent.memory [关键词] | 记忆索引/搜索 |")
            appendLine("| agent.audit | agent.audit [条数] | 命令审计日志 |")
            appendLine("| agent.profile | agent.profile | 身份档案 |")
            appendLine("| agent.browser-tools | agent.browser-tools | MP浏览器扩展能力 |")
            appendLine()

            appendLine("### sys — 系统信息")
            appendLine("| 命令 | 说明 | 权限 |")
            appendLine("|------|------|------|")
            appendLine("| sys.battery | 电量/充电/温度 | 无 |")
            appendLine("| sys.network | 网络类型/信号 | 无 |")
            appendLine("| sys.cpu | CPU 使用率/核心 | 无 |")
            appendLine("| sys.memory | 内存用量 | 无 |")
            appendLine("| sys.storage | 存储空间 | 无 |")
            appendLine("| sys.display | 屏幕参数 | 无 |")
            appendLine("| sys.sensors | 传感器列表 | 无 |")
            appendLine("| sys.clipboard | 剪贴板 | 无 |")
            appendLine("| sys.location | GPS 定位 | **需位置权限** |")
            appendLine("| sys.camera | 相机信息 | **需相机权限** |")
            appendLine("| sys.apps | 已安装应用 | **需应用列表权限** |")
            appendLine()

            // ── Plugin commands ──
            appendLine("## 插件命令 (按需安装)")
            appendLine()
            pluginManager.getActivePlugins().forEach { plugin ->
                val ns = plugin.metadata.id.removeSuffix("-plugin").removeSuffix("-ext")
                val perms = plugin.metadata.permissions.ifEmpty { listOf("无") }
                appendLine("### $ns — ${plugin.metadata.name}")
                appendLine("| 命令 | 用法 | 权限 |")
                appendLine("|------|------|------|")
                plugin.commands.keys.sorted().forEach { cmd ->
                    appendLine("| $ns.$cmd | $ns $cmd | ${perms.joinToString(", ")} |")
                }
                appendLine()
            }

            // ── Tutorials ──
            appendLine("---")
            appendLine()
            appendLine("## Agent 操作指南 (展示给用户)")
            appendLine()
            appendLine("Agent 负责提醒用户完成以下手动操作。当检测到所需能力缺失时，" +
                "Agent 应主动向用户展示对应的教程章节。")
            appendLine()

            appendLine("### 启用 USB 调试")
            appendLine("1. 打开 设置 → 关于手机")
            appendLine("2. 连续点击「版本号」7 次，直到提示「开发者模式已开启」")
            appendLine("3. 返回 设置 → 系统 → 开发者选项")
            appendLine("4. 开启「USB 调试」")
            appendLine("5. 连接电脑后，手机上点击「允许」")
            appendLine()

            appendLine("### 获取 Root 权限")
            appendLine("Root 权限允许 Agent 执行系统级操作（如 ui.click 真实触控）。")
            appendLine("**警告：Root 会增加安全风险。**")
            appendLine("1. 确认设备 Bootloader 已解锁")
            appendLine("2. 安装 Magisk Manager")
            appendLine("3. 通过 Magisk 修补 boot.img 并刷入")
            appendLine("4. 重启后在 Magisk 中授权 MengPaw")
            appendLine("5. root 后可使用 `proc.exec` 命令（需先解除沙箱禁用）")
            appendLine()

            appendLine("### 授予无障碍权限")
            appendLine("`ui.*` 命令需要无障碍服务才能执行真实触控：")
            appendLine("1. 打开 设置 → 无障碍 → 已安装的应用")
            appendLine("2. 找到 MengPaw，开启无障碍服务")
            appendLine("3. 确认授权对话框")
            appendLine()

            appendLine("### 安装插件/Tools/Skills")
            appendLine("Agent 会在需要时自动检测缺失的能力，并建议安装对应的插件：")
            appendLine("1. Agent 执行命令 → 返回 \"Unknown command: xxx\"")
            appendLine("2. Agent 调用 `plugin.search xxx` 查找匹配插件")
            appendLine("3. Agent 向用户展示插件信息和权限要求")
            appendLine("4. 用户确认后，Agent 执行 `plugin.install <插件ID>`")
            appendLine("5. 插件自动激活，命令即可用")
            appendLine()

            appendLine("### 切换搜索引擎 (MP浏览器)")
            appendLine("点击浏览器搜索框左侧的引擎图标循环切换，或在设置中勾选/排序。")
            appendLine()

            appendLine("### 插件省电管理 (Power-Aware Plugin)")
            appendLine("Agent 在后台静默运行时自动管理插件以降低功耗：")
            appendLine("- `plugin.auto sleep <id>` — 休眠指定插件，释放内存和线程")
            appendLine("- `plugin.auto wake <id>` — 唤醒插件（执行任务前自动调用）")
            appendLine("- `plugin.auto sleep-idle` — 休眠所有非核心插件（self/plugin/agent 除外）")
            appendLine("- `plugin.auto status` — 查看所有插件功耗状态")
            appendLine("- Agent 应在任务完成后休眠非必要插件，执行前自动唤醒所需插件")
            appendLine("- 后台空闲 > 10min 建议执行 `plugin.auto sleep-idle`")
            appendLine()

            appendLine("### 插件隔离 (Plugin Isolation)")
            appendLine("Agent 可以通过启停插件来隔离不同 Agent 的权限和能力：")
            appendLine("- `plugin.disable <插件ID>` — 停用插件，Agent 无法调用其命令")
            appendLine("- `plugin.enable <插件ID>` — 重新启用插件")
            appendLine("- 停用的插件仍保留已安装状态，配置和数据不丢失")
            appendLine("- Agent 可使用此机制限制其他 Agent 的权限范围")
            appendLine("- 多 Agent 场景：为每个 Agent 启用不同的插件组合，实现能力隔离")
            appendLine()

            appendLine("### 多Agent协作 (Hermes)")
            appendLine("通过 hermes-plugin 实现多 Agent 对话和任务委派：")
            appendLine("- `hermes.role <角色描述>` — 设定自身角色（如：研究员、代码审查员）")
            appendLine("- `hermes.discover` — 发现本地可用的其他 Agent")
            appendLine("- `hermes.team invite <id> <role>` — 邀请 Agent 加入团队")
            appendLine("- `hermes.delegate <agent> <任务>` — 委派任务给团队成员")
            appendLine("- `hermes.ask <agent> <问题>` — 向其他 Agent 提问")
            appendLine("- `hermes.memo [内容]` — 团队共享记忆")
            appendLine("- 委派的任务写入目标 Agent 的 inbox/ 目录")
            appendLine("- 团队信息存储在 Agent文档/team/ 目录")
            appendLine()

            appendLine("### 广告拦截 (MP浏览器)")
            appendLine("菜单 → 广告拦截开关，默认开启。拦截 40+ 常见广告/追踪域名。")
            appendLine()

            appendLine("---")
            appendLine()
            appendLine("## 可用的插件/Tools/Skills 列表")
            appendLine()
            appendLine("Agent 可按需安装以下插件。使用 `plugin.install <ID>` 安装。")
            appendLine()
            appendLine("| 插件ID | 名称 | 命令 | 权限 |")
            appendLine("|--------|------|------|------|")
            appendLine("| fs-plugin | 文件系统 | fs.* (8命令) | 无 |")
            appendLine("| net-plugin | 网络请求 | net.* (3命令) | INTERNET |")
            appendLine("| memory-plugin | 记忆系统 | memory.* (6命令) | 无 |")
            appendLine("| skill-plugin | 技能系统 | skill.* (4命令) | 无 |")
            appendLine("| ui-plugin | 界面操控 | ui.* (7命令) | 无障碍服务 |")
            appendLine("| proc-plugin | 进程管理 | proc.* (3命令) | 无 |")
            appendLine("| clipboard-plugin | 剪贴板 | clipboard.* (3命令) | 无 |")
            appendLine("| notification-plugin | 通知 | notification.* (3命令) | 通知权限 |")
            appendLine("| vision-plugin | 视觉识别 | vision.* (3命令) | 相机权限 |")
            appendLine("| audio-plugin | 听觉识别 | audio.* (4命令) | 麦克风权限 |")
            appendLine("| hermes-plugin | 多Agent协作 | hermes.* (6命令) | 无 |")
            appendLine("| tavily-plugin | AI搜索 | tavily.* (2命令) | TAVILY_API_KEY |")
            appendLine("| render-plugin | API生图 | render.* (4命令) | API Key |")
            appendLine("| comfy-plugin | ComfyUI工作流 | comfy.* (5命令) | ComfyUI Server |")
            appendLine()
            appendLine("### ⚠️ 生图参数准则 (Agent 必读)")
            appendLine("Agent 在设置生图参数前，**必须**查询社区公开信息，不可随意填写:")
            appendLine("1. 使用 tavily.search 或 net.curl 查询 Civitai (civitai.com) 上对应模型的推荐参数")
            appendLine("2. 参考 ComfyUI 官方文档 (docs.comfy.org)")
            appendLine("3. 常用安全范围: Steps=20~40, CFG=5~9, Sampler=euler_ancestral/dpm++_2m")
            appendLine("4. 无参考时使用保守值: Steps=25, CFG=7, Sampler=euler_ancestral")
            appendLine("5. API生图 (render.generate) 同样需要先查询模型推荐的 prompt 格式")
            appendLine()

            appendLine("## ComfyUI 工作流 CLI 参考")
            appendLine("| 命令 | 用法 | 说明 |")
            appendLine("|------|------|------|")
            appendLine("| comfy.nodes | comfy.nodes [category] | 查看可用节点（含参数警告） |")
            appendLine("| comfy.workflow create | comfy.workflow create <name> | 创建空工作流 |")
            appendLine("| comfy.workflow add | comfy.workflow add <wf> <id> <type> [params] | 添加节点 |")
            appendLine("| comfy.workflow connect | comfy.workflow connect <wf> <from:slot> <to:slot> | 连接节点 |")
            appendLine("| comfy.workflow show | comfy.workflow show <name> | 查看工作流JSON |")
            appendLine("| comfy.workflow list | comfy.workflow list | 列出所有工作流 |")
            appendLine("| comfy.run | comfy.run <wf> [api-url=...] | 提交到 ComfyUI API |")
            appendLine("| comfy.preview | comfy.preview <wf> | 在 MP浏览器预览结果 |")
            appendLine("| comfy.export | comfy.export <wf> json | 导出工作流 JSON |")
            appendLine()

            appendLine("## API 生图 CLI 参考")
            appendLine("| 命令 | 用法 | 说明 |")
            appendLine("|------|------|------|")
            appendLine("| render.models | render.models [replicate/stability/dalle] | 查看可用模型 |")
            appendLine("| render.generate | render.generate <backend> <model> prompt=... | 提交生图Job |")
            appendLine("| render.status | render.status [job-id] | 查询Job状态 |")
            appendLine("| render.preview | render.preview <job-id> | 在 MP浏览器预览结果 |")
            appendLine()
            appendLine("> 完整插件市场: `plugin.marketplace`")
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("*Agent CLI 参考 v0.2 · MengPaw*")
        })
        } catch (e: Exception) { ErrorCollector.report(e, "AgentDocManager.regenerateCliDoc") }
    }

    // ── Internal helpers ──────────────────────────────────────────────

    private fun file(docType: AgentDocType): File = File(agentDir, docType.name.lowercase() + ".md")

    /**
     * Write text to a file atomically: write to a .tmp sibling, then rename.
     * Prevents file corruption if the process crashes mid-write.
     */
    private fun File.atomicWriteText(text: String) {
        parentFile?.mkdirs()
        val tmp = File(parentFile, "$name.tmp")
        tmp.writeText(text)
        tmp.renameTo(this)
        if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }
    }

    private fun rebuildIndex() {
        val memFile = file(AgentDocType.MEMORY)
        if (!memFile.exists()) return
        val text = try { memFile.readText() } catch (e: Exception) { ErrorCollector.report(e, "AgentDocManager.rebuildIndex"); "" }
        val records = parseMemoryRecords(text)
        val idx = buildString {
            appendLine("# 记忆索引")
            appendLine()
            appendLine("> 索引更新: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())}")
            appendLine("> 总条目: ${records.size} | 总大小: ${memFile.length() / 1024}KB | 上限: $maxMemories 条")
            appendLine()
            appendLine("| ID | 日期 | 标题 | 关键词 |")
            appendLine("|----|------|------|--------|")
            records.forEach { r ->
                appendLine("| ${r.id} | ${r.date} | ${r.title} | ${r.keywords.take(3).joinToString(", ")} |")
            }
        }
        // Replace the index section (between first --- and second ---)
        val newText = text.replaceFirst(
            Regex("---\\n[\\s\\S]*?---"),
            "---\n$idx\n---"
        )
        try { memFile.atomicWriteText(newText) } catch (e: Exception) { ErrorCollector.report(e, "AgentDocManager.rebuildIndex") }
    }

    private fun enforceLimits() {
        val memFile = file(AgentDocType.MEMORY)
        if (!memFile.exists()) return
        val records = parseMemoryRecords(try { memFile.readText() } catch (e: Exception) { ErrorCollector.report(e, "AgentDocManager.enforceLimits"); "" })
        if (records.size > maxMemories) {
            // Archive oldest 10 entries (sorted by ID desc = newest first, so takeLast = oldest)
            val toArchive = records.takeLast(10)
            val archiveFile = File(agentDir, "Memory.archive.md")
            val archiveContent = toArchive.joinToString("\n---\n") { r ->
                "## ${r.id}: ${r.title}\n日期: ${r.date}\n关键词: ${r.keywords.joinToString(", ")}\n\n${r.content}"
            }
            try { archiveFile.appendText("\n---\n$archiveContent") } catch (e: Exception) { ErrorCollector.report(e, "AgentDocManager.enforceLimits") }

            // Rebuild with proper index section (preserving format that rebuildIndex expects)
            val remaining = records.dropLast(10)
            val idxSection = buildString {
                appendLine("# 记忆索引")
                appendLine()
                appendLine("> 索引更新: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())}")
                appendLine("> 总条目: ${remaining.size} | 总大小: ${memFile.length() / 1024}KB | 上限: $maxMemories 条")
                appendLine()
                appendLine("| ID | 日期 | 标题 | 关键词 |")
                appendLine("|----|------|------|--------|")
                remaining.forEach { r ->
                    appendLine("| ${r.id} | ${r.date} | ${r.title} | ${r.keywords.take(3).joinToString(", ")} |")
                }
            }
            val recordsSection = remaining.joinToString("\n") { r ->
                """
## ${r.id}: ${r.title}
- **日期**: ${r.date}
- **关键词**: ${r.keywords.joinToString(", ")}
- **内容**: ${r.content.take(maxMemoryLines * 80)}

---
""".trimIndent()
            }
            // Preserve the two-section format: index between --- markers, records after
            val newText = "---\n$idxSection\n---\n\n$recordsSection"
            try { memFile.atomicWriteText(newText) } catch (e: Exception) { ErrorCollector.report(e, "AgentDocManager.enforceLimits") }
        }
    }

    private fun parseMemoryRecords(text: String): List<MemoryRecord> {
        val records = mutableListOf<MemoryRecord>()
        // FIX: Use findAll to properly capture the actual ID from each section header.
        // The previous split() approach lost the captured group and reconstructed fake IDs.
        val headerPattern = Regex("## (mem-\\d+):\\s*(.+?)(?=\n-|$)", RegexOption.MULTILINE)
        val matches = headerPattern.findAll(text).toList()
        if (matches.isEmpty()) return records

        for (i in matches.indices) {
            val match = matches[i]
            val id = match.groupValues[1].trim()
            val title = match.groupValues[2].trim()
            // Extract section content: from this header to the next header (or end of text)
            val sectionStart = match.range.last + 1
            val sectionEnd = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
            val section = text.substring(sectionStart, sectionEnd)

            val date = Regex("日期[:\\s]*([^\n]+)").find(section)?.groupValues?.get(1)?.trim() ?: ""
            val keywords = Regex("关键词[:\\s]*([^\n]+)").find(section)?.groupValues?.get(1)
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val content = section.substringAfter("内容:").take(500).trim()
            records.add(MemoryRecord(id, date, title, keywords, content))
        }
        return records.sortedByDescending { it.id }
    }

    private fun appendNamespaceCommands(sb: StringBuilder, ns: String, desc: String, commands: List<Triple<String, String, String>>) {
        sb.appendLine("## $ns ($desc)")
        sb.appendLine("| 命令 | 用法 | 说明 |")
        sb.appendLine("|------|------|------|")
        commands.forEach { (cmd, usage, detail) ->
            sb.appendLine("| $ns.$cmd | $usage | $detail |")
        }
        sb.appendLine()
    }

    // ── Default document templates ────────────────────────────────────

    companion object {
        /** Built-in self.* commands for CLI.md generation. */
        val SELF_COMMANDS = listOf(
            Triple("status", "self status", "Agent 运行状态"),
            Triple("config", "self config [key=value]", "查看/设置配置"),
            Triple("stats", "self stats", "内存/CPU/线程统计"),
            Triple("version", "self version", "版本信息")
        )

        /** Built-in plugin.* commands. */
        val PLUGIN_COMMANDS = listOf(
            Triple("marketplace", "plugin.marketplace [--refresh]", "拉取插件市场索引"),
            Triple("search", "plugin.search <query>", "搜索插件"),
            Triple("install", "plugin.install <id>", "安装插件"),
            Triple("uninstall", "plugin.uninstall <id>", "卸载插件"),
            Triple("list", "plugin.list", "列出已安装插件"),
            Triple("info", "plugin.info <id>", "插件详情"),
            Triple("enable", "plugin.enable <id>", "启用插件"),
            Triple("disable", "plugin.disable <id>", "禁用插件"),
            Triple("update", "plugin.update <id>", "检查插件更新"),
            Triple("upgrade", "plugin.upgrade --all", "升级全部插件")
        )

        /** Built-in agent.* commands. */
        val AGENT_COMMANDS = listOf(
            Triple("docs", "agent.docs", "列出所有文档"),
            Triple("memory", "agent.memory", "查看记忆索引"),
            Triple("memory.record", "agent.memory.record <content>", "手动记录"),
            Triple("cli", "agent.cli", "查看 CLI 命令参考"),
            Triple("profile", "agent.profile", "查看 Agent 档案"),
            Triple("soul", "agent.soul", "查看 Agent 灵魂设定"),
            Triple("browser-tools", "agent.browser-tools", "MP浏览器插件开发能力")
        )

        /** Browser plugin development capabilities — readable by Agent via CLI. */
        val BROWSER_TOOLS_MD = """
# MP浏览器 插件开发能力 (Browser Plugin API)

> Agent 可以通过 `memory.read browser-tools` 查询浏览器插件开发接口。

## 可用的浏览器钩子

| 钩子 | 触发时机 | 用途 |
|------|---------|------|
| onPageStarted(url) | 页面开始加载 | 监听导航事件 |
| onPageFinished(url, title) | 页面加载完成 | 注入 JS/CSS |
| shouldIntercept(request) | 每个资源请求 | 广告拦截、请求修改 |
| injectScript(url) | 每页加载后 | Tampermonkey 风格用户脚本 |
| injectStyle(url) | 每页加载后 | 暗黑模式、自定义样式 |
| menuItems() | 浏览器菜单打开时 | 添加自定义菜单项 |
| onLongPress(element) | 长按页面元素 | 图片/视频/二维码处理 |

## 如何开发浏览器插件

1. 实现 BrowserPlugin 接口（继承 Plugin + 浏览器钩子）
2. 实现 commands map 提供 CLI 命令
3. 注册到 BrowserPluginRegistry.register(plugin)
4. 打包为 .jar，上传到插件市场

## 示例：视频下载插件
```
plugin.install video-downloader
→ 长按视频 → 菜单出现 "下载视频" → 调用 video.download
```

## 已安装的浏览器插件
查询方法: `plugin.list` 查看 browser- 前缀的插件
""".trimIndent()
    }
}
