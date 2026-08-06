// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.error.ErrorCollector
import com.mengpaw.kernel.namespace.SelfExecutor
import com.mengpaw.kernel.plugin.PluginManager
import com.mengpaw.kernel.plugin.pluginNamespaceFor
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
 *   ├── memory/        # Single-track memory: memory.md (long) + memory_{date}.md (mid) + project_*_memory.md
 *   └── CLI.md         # Auto-generated command reference
 */
class AgentDocManager(
    agentId: String = "agent-001",
    private val baseDir: String = com.mengpaw.kernel.DataPaths.AGENTS,
    /** Plugin manager for CLI doc generation. Can be set after construction. */
    @Volatile var pluginManager: PluginManager? = null
) {
    // FIX(自检报告 P0-2): 原硬编码 "agent-001" — 模板写入 {AGENTS}/{name}/ 而命令层读
    // {AGENTS}/agent-001/, 引导文件永不可见。生产会话经 AgentEngine.setAgentIdentity → bindAgent 绑定。
    @Volatile
    private var agentId: String = agentId

    /**
     * 实际注册的 agent.* 命令键 (AgentExecutor 构造时注入) — CLI.md agent 表按此
     * 运行时生成, 与实现永不漂移 (此前硬编码 8/40 行, Agent 查 agent.cli 看不到
     * read/write/ls 等 32 个命令 — 发现性铁律 v0.31.0)。
     */
    @Volatile
    var registeredAgentCommands: List<String> = emptyList()

    private val agentDir: File get() = File(baseDir, agentId)

    /** 将文档系统绑定到指定 Agent 工作区目录（生产会话在 setAgentIdentity 时调用）。 */
    fun bindAgent(agentName: String) { agentId = agentName }

    /**
     * CLI.md 是否缺失/过期 — 读文件头 "活跃插件: N" 与当前激活数比对。
     * 插件 install/disable 改变 activeCount 后下次查询即自愈, 零写开销。
     */
    fun cliDocStale(pluginManager: PluginManager?): Boolean {
        val f = file(AgentDocType.CLI)
        if (!f.exists()) return true
        if (pluginManager == null) return false
        val header = try { f.readText().take(200) } catch (_: Exception) { return true }
        val stored = Regex("活跃插件:\\s*(\\d+)").find(header)?.groupValues?.get(1)?.toIntOrNull()
        return stored == null || stored != pluginManager.activeCount()
    }

    /** 预热 CLI.md — 幂等 (计数比对, 配置反复 apply 不重复写盘)。 */
    fun ensureCliDoc() {
        val pm = pluginManager
        if (pm != null && cliDocStale(pm)) regenerateCliDoc(pm)
    }

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

        // CLI.md — always regenerate from active plugin list (skip if no plugin manager yet)
        val pm = pluginManager
        if (pm != null) regenerateCliDoc(pm)
    }

    // ── Read ──────────────────────────────────────────────────────────

    fun getDoc(docType: AgentDocType): String {
        val f = file(docType)
        return if (f.exists()) try { f.readText() } catch (e: Exception) { ErrorCollector.report(e, "AgentDocManager.getDoc"); "" } else ""
    }

    fun getDocPath(docType: AgentDocType): String = file(docType).absolutePath

    fun listDocs(): List<String> = AgentDocType.entries.map { it.name.lowercase() + ".md" }

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
            appendLine("### self — Agent 自我管理")
            appendLine("| 命令 | 用法 | 说明 | 权限 |")
            appendLine("|------|------|------|------|")
            SelfExecutor.commands.keys.sorted().forEach { name ->
                val full = "self.$name"
                val d = SELF_COMMANDS.firstOrNull { it.first == name }
                if (d != null) appendLine("| $full | ${d.second} | ${d.third} | 无 |")
                else appendLine("| $full | $full | 见 self.search 获取用法 | 无 |")
            }
            appendLine()
            appendLine("### evolution — Agent 进化 (从失败中学习)")
            appendLine("| 命令 | 用法 | 说明 | 权限 |")
            appendLine("|------|------|------|------|")
            appendLine("| evolution.audit | evolution.audit | 进化绩效: 失败分布/复现率/教训 | 无 |")
            appendLine("| evolution.report | evolution.report <描述> | 框架缺陷反馈给开发者 (落盘+推送) | 无 |")
            appendLine("| evolution.learn.command | evolution.learn.command <命令> <描述> [--keywords 词,词] | 丰富指令集 (登记正确用法/同义词) | 无 |")
            appendLine("| evolution.reactions | evolution.reactions | 查看用户反应档案 (用户分身数据源) | 无 |")
            appendLine("| evolution.mark-corrected | evolution.mark-corrected <id> | 标记失败模式已沉淀修正 | 无 |")
            appendLine()
            appendLine("> 失败后系统自动注入金字塔省察引导 (L1 事实 → L2 归因 → L3 用户视角 → L4 进化), 处置动作: 常识错用 agent.memory.keep, 行为错改 soul.md (agent.write), 指令错用 evolution.learn.command 或 self.search。")
            appendLine()
            appendLine("## 网络端口参考")
            appendLine()
            appendLine(com.mengpaw.kernel.ports.Ports.describe("zh"))
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
            appendLine("| plugin.verify | plugin.verify <插件ID> \\| plugin.verify --all | 校验插件文件完整性 | 无 |")
            appendLine("| plugin.auto | plugin.auto <wake\\|sleep\\|status\\|sleep-idle> | 插件省电管理 | 无 |")
            appendLine()

            appendLine("### agent — Agent 文档")
            appendLine("| 命令 | 用法 | 说明 |")
            appendLine("|------|------|------|")
            registeredAgentCommands.forEach { name ->
                val full = "agent.$name"
                val d = AGENT_COMMANDS.firstOrNull { it.first == name }
                if (d != null) appendLine("| $full | ${d.second} | ${d.third} |")
                else appendLine("| $full | $full | 见 self.search $full 获取用法 |")
            }
            appendLine()

            appendLine("### sys — 系统信息 (Android 设备能力)")
            appendLine("| 命令 | 说明 | 权限 |")
            appendLine("|------|------|------|")
            appendLine("| sys.device | 设备信息 (型号/厂商/SDK) | 无 |")
            appendLine("| sys.battery | 电量/充电/温度 | 无 |")
            appendLine("| sys.network | 网络类型/信号 | 无 |")
            appendLine("| sys.wifi | WiFi 详情 | 无 |")
            appendLine("| sys.wifi.enable | WiFi 开关 | 无 |")
            appendLine("| sys.bluetooth | 蓝牙状态 | 无 |")
            appendLine("| sys.location | GPS 定位 | **需位置权限** |")
            appendLine("| sys.cpu | CPU 使用率/核心 | 无 |")
            appendLine("| sys.memory | 内存用量 | 无 |")
            appendLine("| sys.storage | 存储空间 | 无 |")
            appendLine("| sys.camera | 摄像头信息 | **需相机权限** |")
            appendLine("| sys.sensors | 传感器列表 | 无 |")
            appendLine("| sys.display | 屏幕参数 | 无 |")
            appendLine("| sys.telephony | 电话信息/运营商 | **需电话权限** |")
            appendLine("| sys.power | 电源状态 | 无 |")
            appendLine("| sys.power.save | 省电模式 | 无 |")
            appendLine("| sys.screen.on | 亮屏/唤醒 | 无 |")
            appendLine("| sys.screen.off | 熄屏 | 无 |")
            appendLine("| sys.screen.brightness | 设置亮度 | 无 |")
            appendLine("| sys.volume | 音量 | 无 |")
            appendLine("| sys.volume.set | 设置音量 | 无 |")
            appendLine("| sys.vibrate | 震动 | 无 |")
            appendLine("| sys.ringtone.play | 播放铃声 | 无 |")
            appendLine("| sys.apps | 已安装应用 | **需应用列表权限** |")
            appendLine("| sys.app.launch | 启动应用 | 无 |")
            appendLine("| sys.app.uninstall | 卸载应用 | 无 |")
            appendLine("| sys.app.info | 应用详情 | 无 |")
            appendLine("| sys.browser.open | 打开浏览器 | 无 |")
            appendLine("| sys.clipboard | 读取剪贴板 | 无 |")
            appendLine("| sys.clipboard.set | 写入剪贴板 | 无 |")
            appendLine("| sys.intent.open | 打开链接/应用 | 无 |")
            appendLine("| sys.intent.share | 分享文本 | 无 |")
            appendLine("| sys.intent.view | 查看文件 | 无 |")
            appendLine("| sys.notification.id | 通知渠道 ID | **需通知权限** |")
            appendLine("| sys.notification.send | 发送通知 | **需通知权限** |")
            appendLine("| sys.notification.cancel | 取消通知 | 无 |")
            appendLine("| sys.alarm.set | 设置闹钟 | 无 |")
            appendLine("| sys.permission.list | 列出权限 | 无 |")
            appendLine("| sys.permission.check | 检查权限 | 无 |")
            appendLine("| sys.permission.request | 申请权限 | 无 |")
            appendLine("| sys.overlay.show | 显示悬浮窗 | **需悬浮窗权限** |")
            appendLine("| sys.overlay.hide | 隐藏悬浮窗 | 无 |")
            appendLine("| sys.overlay.update | 更新悬浮窗 | 无 |")
            appendLine("| sys.calendar.add | 添加日历事件 | **需日历权限** |")
            appendLine("| sys.calendar.list | 列出日历事件 | **需日历权限** |")
            appendLine("| sys.calendar.delete | 删除日历事件 | **需日历权限** |")
            appendLine("| sys.calendar.calendars | 列出日历账户 | **需日历权限** |")
            appendLine("| sys.screenshot | 截图 | 无 |")
            appendLine("| sys.screenrecord.start | 开始录屏 | 无 |")
            appendLine("| sys.screenrecord.stop | 停止录屏 | 无 |")
            appendLine("| sys.camera.photo | 拍照 (需用户确认) | **需相机权限** |")
            appendLine()

            // ── Plugin commands ──
            appendLine("## 插件命令 (按需安装)")
            appendLine()
            pluginManager.getActivePlugins().forEach { plugin ->
                val ns = pluginNamespaceFor(plugin.metadata.id)
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
            appendLine("完整命令与权限见 `plugin.info <ID>` 或 `self.tools <命名空间>`。")
            appendLine()
            appendLine("### 内置 (随 APK 预装, 仅可禁用)")
            appendLine()
            appendLine("| 插件ID | 命令命名空间 | 用途 |")
            appendLine("|--------|------------|------|")
            appendLine("| framework-plugin | framework.* | 外部框架接入/发现 |")
            appendLine("| fs-plugin | fs.* | 文件系统 |")
            appendLine("| net-plugin | net.* | 网络请求 |")
            appendLine("| memory-twin-plugin | twin.* | 记忆孪生/跨设备同步 |")
            appendLine("| skill-plugin | skill.* | 技能系统 |")
            appendLine("| clipboard-plugin | clipboard.* | 剪贴板 |")
            appendLine("| notification-plugin | notification.* | 通知 |")
            appendLine("| dev-plugin | dev.plugin.* | 插件开发工具 (create/audit/share) |")
            appendLine("| root-plugin | root.* | Root 权限 (最高权限) |")
            appendLine("| tools-plugin | tools.* | Agent 命令集 (import/ls/remove/search) |")
            appendLine("| dream-plugin | agent.dream | 梦境模式 — 记忆整理管道 (中期→长期) |")
            appendLine("| evolution-plugin | evolution.* | 智能体进化 — 失败模式库/省察引导/框架反馈 |")
            appendLine("| tavily-plugin | tavily.* | AI 搜索 (内置, 需 TAVILY_API_KEY 或 tavily.setup 配置) |")
            appendLine()
            appendLine("### 远程/按需安装")
            appendLine()
            appendLine("| 插件ID | 用途 |")
            appendLine("|--------|------|")
            appendLine("| tribe-plugin (hermes 兼容) | 部落协作/多Agent团队 |")
            appendLine("| update-plugin | 自动更新 |")
            appendLine("| translate-plugin | 翻译 |")
            appendLine("| error-report-plugin | 错误上报 |")
            appendLine("| render-plugin | API 生图 (需 API Key) |")
            appendLine("| comfy-plugin | ComfyUI 工作流 (默认端口 8188) |")
            appendLine("| workflow-plugin | 工作流编排 |")
            appendLine("| incubator-plugin | Agent 孵化器 |")
            appendLine("| browser-push/search/mcp/cdp/inspector-plugin | 浏览器扩展能力 |")
            appendLine()
            appendLine("### 嵌入 (UI 绑定, 不可安装/卸载)")
            appendLine()
            appendLine("| 插件ID | 用途 |")
            appendLine("|--------|------|")
            appendLine("| agent-mission-plugin | Mission 模式 |")
            appendLine("| agent-loop-plugin | Agent Loop 模式 |")
            appendLine()
            appendLine("端口与网络接口一览: `self.ports`。")
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
     * 标准原子写: 先写同目录 `.tmp`，再 Files.move(REPLACE_EXISTING) 覆盖。
     * 不再"先删目标再 rename" — 失败时原文件保持完好，残留 tmp 清理后向上抛
     * (调用方已各自 try/catch + ErrorCollector.report)。
     */
    private fun File.atomicWriteText(text: String) {
        parentFile?.mkdirs()
        val tmp = File(parentFile, "$name.tmp")
        try {
            tmp.writeText(text)
            java.nio.file.Files.move(
                tmp.toPath(), this.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            try { tmp.delete() } catch (_: Exception) {}
            throw e
        }
    }

    // ── Default document templates ────────────────────────────────────

    companion object {
        /** Built-in self.* commands for CLI.md generation. */
        internal val SELF_COMMANDS = listOf(
            Triple("status", "self status", "Agent 运行状态"),
            Triple("config", "self config [key=value]", "查看/设置配置"),
            Triple("stats", "self stats", "内存/CPU/线程统计"),
            Triple("version", "self version", "版本信息"),
            Triple("avatar", "self.avatar [name|file]", "切换头像"),
            Triple("theme", "self.theme [light|dark|system]", "切换主题配色"),
            Triple("mcp", "self.mcp", "MCP 协议配置/服务列表"),
            Triple("trigger", "self.trigger [ls|add|rm]", "触发器管理 (Cron/Lifetime)"),
            Triple("acp", "self.acp", "ACP 框架通讯状态/配对设备"),
            Triple("tools", "self.tools [namespace]", "列出可用命令 (完整遍历)"),
            Triple("ports", "self.ports [--json]", "端口/网络接口一览"),
            Triple("search", "self.search <描述> [--top N]", "自然语言搜索命令 (BM25)"),
            Triple("search.stats", "self.search.stats", "搜索索引统计"),
            Triple("time", "self.time [format]", "当前日期时间"),
            Triple("notify.message", "self.notify.message <text>", "推送消息给用户"),
            Triple("notify.banner", "self.notify.banner <text> [--level]", "顶部横幅通知")
        )

        /** Built-in plugin.* commands. */
        internal val PLUGIN_COMMANDS = listOf(
            Triple("marketplace", "plugin.marketplace [--refresh]", "拉取插件市场索引"),
            Triple("search", "plugin.search <query>", "搜索插件"),
            Triple("install", "plugin.install <id>", "安装插件"),
            Triple("uninstall", "plugin.uninstall <id>", "卸载插件"),
            Triple("list", "plugin.list", "列出已安装插件"),
            Triple("info", "plugin.info <id>", "插件详情"),
            Triple("enable", "plugin.enable <id>", "启用插件"),
            Triple("disable", "plugin.disable <id>", "禁用插件"),
            Triple("update", "plugin.update <id>", "检查插件更新"),
            Triple("upgrade", "plugin.upgrade --all", "升级全部插件"),
            Triple("verify", "plugin.verify <id> | plugin.verify --all", "校验插件文件完整性"),
            Triple("auto", "plugin.auto <wake|sleep|status|sleep-idle>", "插件省电管理")
        )

        /** Built-in agent.* commands. */
        internal val AGENT_COMMANDS = listOf(
            Triple("docs", "agent.docs", "列出所有文档"),
            Triple("cli", "agent.cli", "查看 CLI 命令参考"),
            Triple("modes", "agent.modes", "斜杠命令模式菜单 (modes.md)"),
            Triple("boost", "agent.boost", "阅读初始化引导 (新 Agent 第一步)"),
            Triple("boost.delete", "agent.boost.delete", "删除引导加速文件"),
            Triple("profile", "agent.profile", "查看身份档案"),
            Triple("soul", "agent.soul", "查看灵魂设定"),
            Triple("audit", "agent.audit [条数]", "命令审计日志"),
            Triple("browser-tools", "agent.browser-tools", "MP浏览器扩展能力"),
            Triple("dream", "agent.dream", "梦境整理 (中期→洞察→长期)"),
            Triple("cleanup", "agent.cleanup", "清理过期文件"),
            Triple("storage", "agent.storage", "存储占用/限额"),
            Triple("sessions", "agent.sessions [keyword]", "搜索历史会话"),
            Triple("session.delete", "agent.session.delete <id>", "删除历史会话"),
            Triple("session.archive", "agent.session.archive <id>", "归档会话"),
            Triple("session.current", "agent.session.current", "当前会话状态"),
            Triple("read", "agent.read <路径>", "读取工作区文件 (只读)"),
            Triple("write", "agent.write <路径> <内容>", "写入工作区文件 (原子)"),
            Triple("ls", "agent.ls [路径]", "列出工作区目录"),
            Triple("rm", "agent.rm <路径>", "删除工作区文件"),
            Triple("mkdir", "agent.mkdir <路径>", "创建工作区目录"),
            Triple("output", "agent.output", "输出目录管理 (HTML/MD/PDF)"),
            Triple("memory", "agent.memory [关键词]", "记忆索引/搜索"),
            Triple("memory.record", "agent.memory.record <内容>", "写中期记忆"),
            Triple("memory.keep", "agent.memory.keep <内容>", "写长期记忆"),
            Triple("memory.read", "agent.memory.read <id>", "按 ID 读一条 (三轨)"),
            Triple("memory.search", "agent.memory.search <关键词> [--track long|mid|project]", "跨轨搜索记忆"),
            Triple("memory.stats", "agent.memory.stats", "记忆统计"),
            Triple("memory.write", "agent.memory.write <id> <内容>", "按 ID 写长期 (存在则更新)"),
            Triple("memory.mid", "agent.memory.mid [日期]", "查看中期记忆"),
            Triple("memory.project", "agent.memory.project [项目名]", "查看项目记忆"),
            Triple("memory.project.save", "agent.memory.project.save <项目名> <内容>", "项目经验总结"),
            Triple("memory.project.delete", "agent.memory.project.delete <项目名>", "删除项目分片"),
            Triple("memory.mid.delete", "agent.memory.mid.delete <日期>", "删除中期分片"),
            Triple("memory.rm", "agent.memory.rm <时间戳>", "删长期条目"),
            Triple("memory.edit", "agent.memory.edit <时间戳> <内容>", "改长期条目"),
            Triple("memory.mid.rm", "agent.memory.mid.rm <日期> <时间戳>", "删中期条目"),
            Triple("memory.mid.edit", "agent.memory.mid.edit <日期> <时间戳> <内容>", "改中期条目"),
            Triple("memory.project.rm", "agent.memory.project.rm <项目名> <时间戳>", "删项目条目"),
            Triple("memory.project.edit", "agent.memory.project.edit <项目名> <时间戳> <内容>", "改项目条目")
        )

        /** 浏览器协作能力 — readable by Agent via CLI (v0.22.1 重写: 真实三通道, 移除未接线的 45 命令手册). */
        val BROWSER_TOOLS_MD = """
# MP浏览器 协作能力 (v0.22.1)

> 浏览器是独立 APK。Agent 可用的三通道: 唤醒打开 / MCP 工具 / 网页转档。
> 完整手册: `skill.run browser-control`。

## 1. 前台唤醒与打开
- `sys.browser.open [url]` — 唤起 MP 浏览器到前台; 带 url 同时打开。唤起后 MCP 桥自动启动。

## 2. 浏览器 MCP 工具 (设备内 HTTP 桥 127.0.0.1:9880, 打开浏览器即自动启用)
- `browser.mcp.status` — 检查桥在线/离线
- `browser.mcp.tools` — 列出 6 个工具及参数
- `browser.mcp.invoke <工具> <JSON参数>` — 调用:
  - `browser_navigate` {"url": "..."} — 导航
  - `browser_screenshot` {} — 当前页截图
  - `browser_click` {"selector": "css"} — 点击元素
  - `browser_type` {"selector": "css", "text": "..."} — 输入文本
  - `browser_extract` {} — 提取页面结构 (标题/链接/表单/文本)
  - `browser_eval` {"script": "js"} — 执行任意 JS

## 3. 网页转档与提炼 (不依赖浏览器在线)
- `search.md <url> [--name x]` — 抓取转 Markdown 存 SEARCH_OUTPUTS
- `search.clean <url|路径> [--save]` — 提取正文去噪
- `search.outputs` / `search.clear` — 输出管理
- 浏览器菜单「提炼网页要点」→ Agent 处理 → 自动回传浏览器预览

## 浏览器扩展 (2026-08-06: BrowserPluginRegistry 死代码已删除)
- 浏览器进程内插件注册机制 (BrowserPlugin/BrowserPluginRegistry) 已移除 — register() 零调用, 插件与浏览器跨进程不可达
- 浏览器能力统一经 9880 MCP 桥: `browser.mcp.tools` 列全部工具 + 内置 browser.* 命令 (44 条)

## Agent Skills
- `skill.run browser-control` — 完整协作手册
""".trimIndent()
    }
}
