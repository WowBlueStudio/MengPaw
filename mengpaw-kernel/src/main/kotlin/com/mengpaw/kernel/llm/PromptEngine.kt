// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import com.mengpaw.kernel.agent.AgentDocs
import kotlinx.serialization.json.*

/**
 * Parsed ReAct response from LLM output.
 */
data class ReActResponse(
    val thought: String,
    val action: ToolCall?,
    val isFinal: Boolean,
    /** Model output Thought but no Action — loop should inject a continue prompt. */
    val needsContinue: Boolean = false,
    /** Multiple tool calls from one LLM output (parallel execution). Empty when only [action] set. */
    val actions: List<ToolCall> = emptyList()
)

data class ToolCall(
    val name: String,
    val parameters: Map<String, String>
) {
    /**
     * JSON 双轨制门卫: 检测参数是否为 JSON 形态。
     * PromptEngine 的 tolerant JSON 解析成功时丢弃 key 只取值 — 单 key 碰巧兼容,
     * 多 key 会参数错位 ({"force":true,"id":"x"} → "true x"); 解析失败时 raw 兜底
     * 会把整个 JSON 串当参数。两种情况都应返回 PARAM_FORMAT_ERROR, 不执行命令。
     * @return 错误描述文本, 或 null (参数格式正常, 可执行)
     */
    fun paramFormatError(): String? {
        val raw = parameters["raw"]
        val looksLikeJson = raw != null && raw.trim().startsWith("{")
        val multiValueJson = raw == null && parameters.size > 1
        return when {
            looksLikeJson || multiValueJson ->
                "参数格式错误: 命令 '$name' 收到 JSON/多字段参数, 但命令期望 CLI 纯文本。" +
                "正确示例: $name <参数1> [参数2]。多字段 JSON 会因 key 被丢弃导致参数错位。"
            else -> null
        }
    }
}

/**
 * ReAct prompt templates and parsing engine.
 */
class PromptEngine {

    private val recentCommands = java.util.LinkedList<String>()

    // ── Workspace doc cache — avoids disk I/O on every LLM call ──
    // P2 修复: ConcurrentHashMap — 并行 worker 执行文件写命令触发 invalidateDocCache
    // 并发重建时普通 HashMap 会竞争损坏
    private data class DocCache(var content: String, var lastModified: Long)
    private val docCache = java.util.concurrent.ConcurrentHashMap<String, DocCache>()
    private var cachedSystemPrompt: String? = null
    private var cachedPromptLang: AgentLanguage? = null
    private var cachedPromptAgent: String? = null
    private var cachedPromptFramework: String? = null
    private var cachedPromptModel: String? = null

    /** 工作区文档 mtime 快照 — 任何文档删除/修改即失配, 强制重建提示词.
     *  (docCache.isNotEmpty() 只检查条目存在, 无法感知单个文件被删除 —
     *   文件删除后其余文档缓存仍在 → 旧 gate 误命中返回含已删文件的旧前缀) */
    private var docMtimes: Map<String, Long>? = null
    /** 模板内容哈希快照 — 提示词常量改动自动失效（无需手动 bump）。 */
    private var cachedTemplateHash: Int? = null

    /** 全部工作区文档的 mtime 快照（仅 stat, 不读内容）。 */
    private fun currentDocMtimes(agentName: String): Map<String, Long> =
        AGENT_DOC_FILES.associateWith { f ->
            val file = java.io.File("${com.mengpaw.kernel.DataPaths.AGENTS}/$agentName/$f")
            if (file.exists()) file.lastModified() else 0L
        }

    /** Read a workspace doc with file-system cache. Re-reads only if file changed. */
    private fun cachedRead(agentName: String, fileName: String, reader: (String) -> String): String {
        val path = "${com.mengpaw.kernel.DataPaths.AGENTS}/$agentName/$fileName"
        val file = java.io.File(path)
        val mtime = if (file.exists()) file.lastModified() else 0L
        if (mtime == 0L && !file.exists()) {
            docCache.remove(path)
        }
        val cached = docCache[path]
        if (cached != null && cached.lastModified == mtime && mtime > 0) {
            return cached.content
        }
        val content = reader(agentName)
        docCache[path] = DocCache(content, mtime)
        return content
    }

    /** Invalidate the workspace doc cache — call when Agent modifies files.
     *  @param agentName 被修改的 Agent
     *  @param filePath  被修改文件的完整路径; null 则全量失效 (兼容旧调用) */
    fun invalidateDocCache(agentName: String = "MengPaw", filePath: String? = null) {
        if (filePath != null) {
            // 精确失效: 只删除被修改的那个文件缓存
            docCache.remove(filePath)
        } else {
            // 全量失效: 清空该 agent 的所有缓存 (兼容旧行为)
            val prefix = "${com.mengpaw.kernel.DataPaths.AGENTS}/$agentName/"
            docCache.keys.removeAll { it.startsWith(prefix) }
        }
        cachedSystemPrompt = null
    }

    /**
     * 文档注入瘦身 — 超长文档只注入前段 + agent.read 外链（AgentToolsSummary 模式）。
     * ≤12K 字符全量注入（现状）；>12K 注入前 6K，避免大文档拖慢每轮 LLM 输入。
     */
    private fun compactDoc(doc: String, path: String): String {
        if (doc.length <= DOC_FULL_INJECT_CHARS) return doc
        return doc.take(DOC_SNIPPET_CHARS) +
            "\n\n…[文档过长 (${doc.length} 字符)，完整内容: agent.read $path]"
    }

    /**
     * Build the system prompt with agent identity, framework context, and model info.
     * @param lang Output language
     * @param agentName The name of this agent (e.g. "MengPaw", "平板-Agent")
     * @param framework The framework this agent belongs to (null = local)
     * @param modelName The LLM model powering this agent (for self-awareness)
     */
    fun buildSystemPrompt(
        lang: AgentLanguage = AgentLanguage.CHINESE,
        agentName: String = "MengPaw",
        framework: String? = null,
        modelName: String = "unknown"
    ): String {
        val identity = if (lang == AgentLanguage.CHINESE) {
            buildString {
                append("你是 **$agentName**，MengPaw 智能体系统中的一员。\n")
                if (framework != null) {
                    append("你运行在远程框架「**$framework**」上，通过网络与该框架协作。你的操作会传递给该框架执行。\n")
                } else {
                    append("你运行在**本地设备**上，可以直接操控本设备。\n")
                }
                append("你当前由 **$modelName** 模型驱动。\n")
                append("\n")
            }
        } else {
            buildString {
                append("You are **$agentName**, a member of the MengPaw agent system.\n")
                if (framework != null) {
                    append("You run on the remote framework \"**$framework**\" and collaborate over the network. Your actions are forwarded to that framework for execution.\n")
                } else {
                    append("You run on the **local device** and can control it directly.\n")
                }
                append("You are currently powered by the **$modelName** model.\n")
                append("\n")
            }
        }

        val basePrompt = when (lang) {
            AgentLanguage.CHINESE -> CHINESE_PROMPT
            AgentLanguage.ENGLISH -> ENGLISH_PROMPT
        }

        // Return cached prompt if nothing changed — skip all disk I/O
        if (agentName == cachedPromptAgent && lang == cachedPromptLang &&
            framework == cachedPromptFramework && modelName == cachedPromptModel &&
            cachedTemplateHash == TEMPLATE_HASH &&
            cachedSystemPrompt != null &&
            docMtimes == currentDocMtimes(agentName) // 文件删除/修改即失配 → 重建
        ) {
            return cachedSystemPrompt!!
        }

        // Read workspace docs with file-system cache (re-reads only when file changed)
        val profileDoc = cachedRead(agentName, "profile.md") { AgentDocs.readProfileDoc(it) }
        val agentsDoc = cachedRead(agentName, "agents.md") { AgentDocs.readAgentsDoc(it) }
        val soulDoc = cachedRead(agentName, "soul.md") { AgentDocs.readSoulDoc(it) }
        // Only LONG-TERM memory goes into system prompt
        val memoryDoc = cachedRead(agentName, "memory/memory.md") { AgentDocs.readLongTermMemory(it) }
        // Read boost.md — if present, Agent hasn't completed first-run setup
        val boostDoc = cachedRead(agentName, "boost.md") { AgentDocs.readBoostDoc(it) }
        // Read heartbeat.md — CRON task rules. Non-empty = scheduled tasks configured.
        val heartbeatDoc = cachedRead(agentName, "heartbeat.md") { AgentDocs.readHeartbeatDoc(it) }
        // Read trumanshow.md — Truman (random chat) rules. Non-empty = custom topics/guidance.
        val trumanShowDoc = cachedRead(agentName, "trumanshow.md") { AgentDocs.readTrumanShowDoc(it) }

        val docsBlock = buildString {
            // ── BOOTSTRAP: boost.md exists → inject first-run guidance ──
            if (boostDoc.isNotBlank()) {
                append(
"""
## 🚀 首次引导模式 — boost.md 存在

你的工作区中有 boost.md 引导文件，说明你还没有完成初始化设置。

1. 主动和用户打招呼，介绍自己
2. 阅读 `agent.boost` 获取完整的引导步骤
3. 引导用户完成四件事：身份（profile.md）、头像（self.avatar）、配色（self.theme）、灵魂准则（soul.md）
4. 全部完成后执行 `agent.boost.delete` 自毁引导文件——你就不再是新人了

如果用户说跳过或不需要，直接执行 agent.boost.delete 即可。

"""
                )
            }
            // ── HEARTBEAT: non-empty heartbeat.md → inject CRON task guidance ──
            if (heartbeatDoc.isNotBlank()) {
                append(
"""
## ⏰ CRON 定时任务 — heartbeat.md 存在

你的工作区中有 heartbeat.md 定时任务规则文件。

- 当 CRON 触发器触发（`[触发器任务 · CRON]`）时，阅读 heartbeat.md 了解该做什么
- 使用 `self.trigger` 管理触发器（添加/查看/删除）
- 定时任务在后台执行，不要阻塞用户对话
- 留空 heartbeat.md = 跳过所有定时任务

"""
                )
            }
            // ── TRUEMAN: non-empty trumanshow.md → inject random-chat guidance ──
            if (trumanShowDoc.isNotBlank()) {
                append(
"""
## 🎭 伪人模式 — trumanshow.md 存在

你的工作区中有 trumanshow.md 伪人模式规则文件。

- 当伪人模式（SCHEDULE/Truman Show）触发器触发（`[触发器任务 · SCHEDULE]`）时，阅读 trumanshow.md 了解聊什么
- 伪人模式是"真人感"聊天，不是任务——轻开场、看情况收、别硬聊
- 留空 trumanshow.md = 只用内置话题池

"""
                )
            }
            // ── SKILLS 双层架构引导 ──
            append(
"""
## 📋 Skills 双层池

Skills 分为两层：
- **全局池** (`/技能剧本/`): 所有 Agent 共享，通过 `skill.ls` 浏览
- **本地池** (`Agent文档/{name}/skills/`): 当前 Agent 专属

`skill.run <name>` **优先查本地，找不到再查全局池**。
`skill.pull <name>` — 从全局池复制到本地。
`skill.push <name>` — 从本地上传到全局池。
`skill.create <name>` — 在本地创建新 Skill。

`/技能剧本/seed/` 保存 APP 内置技能版本（随 APP 更新，只读参考）：进化技能前先 `fs.cat` 对比 seed 与全局池版本的差异，再决定是否采纳新内置版。

"""
            )
            // ── 身份档案（PROFILE.md）— 你是谁、你在帮谁，每轮可见 ──
            if (profileDoc.isNotBlank()) {
                append("\n## 你的身份档案（profile.md）\n\n")
                append(compactDoc(profileDoc, "${com.mengpaw.kernel.DataPaths.AGENTS}/$agentName/profile.md"))
            }
            if (agentsDoc.isNotBlank()) {
                append("\n## 你的操作手册（agents.md）\n\n")
                append(compactDoc(agentsDoc, "${com.mengpaw.kernel.DataPaths.AGENTS}/$agentName/agents.md"))
            }
            if (soulDoc.isNotBlank()) {
                append("\n\n## 你的灵魂准则（soul.md）\n\n")
                append(compactDoc(soulDoc, "${com.mengpaw.kernel.DataPaths.AGENTS}/$agentName/soul.md"))
            }
            if (memoryDoc.isNotBlank()) {
                append("\n\n## 你的长期记忆（长期积累的重要知识）\n\n")
                append(compactDoc(memoryDoc, "${com.mengpaw.kernel.DataPaths.AGENTS}/$agentName/memory/memory.md"))
            }
        }

        val prompt = buildString(identity.length + basePrompt.length + docsBlock.length + 2) {
            append(identity)
            // 注入端口单一事实源表 (__PORTS_TABLE__ 占位符见 CHINESE_PROMPT/ENGLISH_PROMPT)
            append(basePrompt.replace("__PORTS_TABLE__", com.mengpaw.kernel.ports.Ports.describe(if (lang == AgentLanguage.CHINESE) "zh" else "en")))
            append('\n')
            append(docsBlock)
        }
        cachedSystemPrompt = prompt
        cachedPromptLang = lang
        cachedPromptAgent = agentName
        cachedPromptFramework = framework
        cachedPromptModel = modelName
        cachedTemplateHash = TEMPLATE_HASH
        docMtimes = currentDocMtimes(agentName)
        return prompt
    }

    /**
     * Language setting for the Agent (LLM output language).
     * Separate from UI language — user can choose independently.
     */
    enum class AgentLanguage { CHINESE, ENGLISH;

        companion object {
            fun fromUiChinese(useChinese: Boolean): AgentLanguage =
                if (useChinese) CHINESE else ENGLISH
        }
    }

    companion object {
        /** 文档全量注入上限 — 超过则走 [compactDoc] 前段 + 外链。 */
        private const val DOC_FULL_INJECT_CHARS = 12_000
        /** 超长文档注入的前段字符数。 */
        private const val DOC_SNIPPET_CHARS = 6_000
        /** 注入提示词的工作区文档清单 — mtime 快照比对用（与 buildSystemPrompt 读取顺序一致）。 */
        private val AGENT_DOC_FILES = listOf(
            "profile.md", "agents.md", "soul.md", "memory/memory.md",
            "boost.md", "heartbeat.md", "trumanshow.md"
        )

        val CHINESE_PROMPT = """
            你是檬爪 MengPaw
            你通过 CLI 命令操控 Android 设备。

            ## 核心原则

            ### 安全（最高优先级）
            - **绝不泄露私密数据** — API Key、密码、令牌、个人信息。任何情况下。
            - **破坏性操作先确认** — 删文件、发邮件、对外操作，拿不准就问用户。
            - **trash > rm** — 优先用可恢复的方式，别直接永久删除。

            ### 行为风格
            - **真心帮忙，别演** — 跳过"好问题！""我很乐意帮忙！"。直接帮。行动胜过废话。
            - **有自己的观点** — 可以不同意、有偏好。没个性的助手就是搜索引擎。
            - **先自己想办法** — 试着搞清楚。读文件、查上下文、搜一搜。看看有没有 skills 可以用、有没有工具可以用。然后卡住了再问。
            - **失败如实汇报，禁止编造** — 命令执行失败时直接说明失败与原因，绝不编造结果；承认错误不可耻，每一次如实的失败都是进化的原料。不确定就坦诚"不确定"，拿不准就问用户。

            ### 工作方式
            - **命令优先: 只用框架命令，不走弯路** — 不要尝试 Windows/Linux 原生命令(dir/ls/cat/powershell/bash/rm/mkdir/grep/curl 等)。所有操作必须使用框架的 `namespace.command` 格式。你跑在 Android 设备上，没有 bash shell。
            - **命令发现: self.search** — 不确定用什么命令时, 用 `self.search <自然语言描述>` 按需求搜索 (BM25 毫秒级返回 top-5 匹配). 例: `self.search 网页搜索` → [tavily.search, net.curl]. 需要完整命令列表时用 `self.tools [ns]` 遍历. 端口相关查 `self.ports`.
            - **工作区: agent.docs** — 查阅 Soul/Agents/Memory/Boost/Profile。新 Agent 第一步: `agent.boost` 读引导。
            - **你是自身定制专家** — 改名字 (agent.write profile.md)、换头像 (self.avatar)、改配色 (self.theme)。主动建议，不等用户开口。
            - **你是活的** — 你有 CRON 定时任务和伪人模式（Truman Show）。工作区 heartbeat.md 定义 CRON 规则、trumanshow.md 定义伪人模式规则。定期检查 inbox 是否有新任务。inbox 中出现 browser_extract_*.md 时按文件内步骤立即执行网页提炼流水线, 完成后写 browser_return_*.md 供 Shell 回传浏览器预览; browser_url_*.txt 与 browser_return_*.md 是系统/浏览器之间的交换文件, 不要当任务处理。
            - **主动安装** — 缺命令用 `plugin.search` 找、`plugin.install` 装、`plugin.info` 看详情。
            - **教程在框架设置中** — USB调试/Root/无障碍指南。

            ## 自身能力（全部内建，无需安装）

            ### 斜杠命令（用户点输入框 + → 执行模式区选择。MengPaw 特有功能，没有 Normal/Deep/Dream 模式）
            消息带标签时你自动切换执行策略，无需额外处理。8 种模式的完整说明在工作区 `modes.md`——用户问「有什么模式」时，用 `agent.modes` 读取后列出全部，并说明怎么在输入框 + 号里选。

            ### 记忆系统 (三轨制)
            三层记忆，防止上下文膨胀导致你降智。每层都有完整的增删改查。
            - **长期记忆** (已注入上方提示词，最重要): 三种来源 — 用户说记住 / 你判断重要 / agent.dream 整理。永远精简。
            - **项目记忆** (按项目名分片): 里程碑或闭环后总结完整经验。项目级方法论。
            - **中期记忆** (按日期分片, 不注入提示词): 日常对话摘要。需要时查阅。
            - **核心操作**: agent.memory(看长期) / agent.memory.keep(写长期) / agent.memory.record(写中期) / agent.memory.mid(看中期) / agent.memory.project(看项目)。详细增删改命令见下方常用命令区。

            ### 文件 & 设备操控
            - **输出目录**: agent.output 查看。HTML/MD/PDF 等用户文档写到输出目录，用户可在文件管理器找到。例: `agent.write <输出路径>/report.html <内容>`。
            - **文件**: agent.ls/read/write/rm/mkdir (工作区) + agent.storage/cleanup。禁止写 /system/。
            - **截图录屏**: sys.screenshot / sys.screenrecord.start/stop。**拍照**: sys.camera.photo --confirm (⚠️需告知用户并获取确认)。
            - **悬浮窗**: sys.overlay.show/update/hide。**日历**: sys.calendar.add/list/delete。**Root（需先安装 root-plugin）**: 安装后可用 root.status/exec/apps.*/fs.*/backup.* (⚠️最高权限,审计日志)。
            - **跨应用**: sys.app.launch/intent.open|share|view。**脚本**: skill.run termux。
            - **知识库**: skill.run android/termux/filesystem/plugin-system/sessions/twin-guide/device-control。

            ## 工作区边界（哪里是你的，哪里是用户的）
            - **你的家（用户看不到）**: `Agent文档/{name}/` — 你的文档/记忆/技能/工具全在这。soul.md/agents.md/memory/ 随意读写; `dialog/` 与 `tool_results/` 是系统归档, 只读。
            - **内部交换（用户看不到）**: `Agent文档/inbox/` — 任务队列与浏览器交换文件 (browser_extract_*/browser_return_*)。处理完即走, 不驻留。
            - **与用户共享（用户可见）**: `agent.output` — 给用户看的文档 (HTML/MD/PDF) 一律写这里, 用户可在文件管理器找到。**禁止把用户文档写进工作区**。
            - **全局技能池（用户看不到）**: `技能剧本/` — 所有 Agent 共享, 以读为主; 只有沉淀为通用技能才写 (skill.push)。
            - **系统内部目录（用户看不到）**: `配置/`、`会话检查点/`、`截图存档/`、`插件仓库/`、`错误报告/` — 系统自管, 非必要不动。

            ## 常用命令 (权威来源: self.tools)
            - self.search <描述> (首选命令查找) / self.tools [ns] (完整遍历) / self.ports (端口/网络接口) / agent.docs / agent.boost / agent.memory / agent.memory.keep / agent.memory.mid
            - agent.read/write/ls/rm/mkdir / agent.storage/cleanup/sessions/dream
            - plugin.marketplace/search/install/list/info / sys.permission.list/request
            - self.status/avatar/theme / sys.app.launch / sys.intent.open

            ## 插件
            - 源: GitHub(海外)/Gitee(国内) 自动路由。安装: `plugin.info <id>` → `self.tools <ns>`。
            - 已安装的内置插件用 `plugin.disable` 禁用，不可卸载；root-plugin、tribe-plugin 等未捆绑插件需 `plugin.install` 安装后才可用。
            - **网页搜索已内置**: `tavily.search <关键词> [--max=N]` (Tavily AI 搜索: AI 摘要+结构化结果), `tavily.extract <url>` 提取网页正文; key 未配置时用 `tavily.setup <key>` 配置。
            - 网页转档（需安装 browser-search-plugin）: 安装后可用 search.clean/md/outputs/clear; 抓取用 net.curl, 高质量搜索用 tavily.search。

            ## 会话
            - `agent.sessions [kw]` 搜索历史。`agent.session.delete/archive/current` 管理。`agent.storage` 用量。

            ## 多 Agent 协作 (部落 Tribe)
            - 需安装 tribe-plugin（默认未安装）。安装后可用 `tribe.status` 查看服务与看板 / `tribe.team invite <name> <role>` 组队 / `tribe.delegate <agent> <task>` 委派任务 / `tribe.task.*` 看板 / `tribe.ask <agent> <问题>` 直接询问。
            - 并行拆解: `tribe.fleet <任务>` — LLM 分解子任务 → 并行委派 → 合成结果。
            - 收件箱自动感知 — 有委派任务时注入待办提醒。
            - 跨设备委派: 与孪生配对 (twin.status) 后 `tribe.delegate --mode acp` 走加密通道。

            ## 记忆孪生
            - 跨设备记忆同步。`twin.status/peers/sync` 管理。5连击 MengPaw 框架图标配对。详见 `self.tools twin`。

            ## 网络端口
            __PORTS_TABLE__

            ## 浏览器协作 (MP 浏览器, 独立 APK)
            - 浏览器是独立应用, Agent 无法直接执行浏览器 CLI (45 条命令在浏览器 APK 内, 未对 Shell 开放)。
            - **前台唤醒**: `sys.browser.open [url]` 唤起 MP 浏览器到前台 (带 url 则同时打开; 唤起后 MCP 工具自动可用)。
            - **网页提炼**（需安装 browser-search-plugin）: 浏览器菜单「提炼网页要点」→ Agent 抓取转换 Markdown + 提炼要点 → 自动回传浏览器预览 (命令: search.clean/md/outputs/clear)。
            - **浏览器 MCP 工具**（需安装 browser-mcp-plugin, 默认未安装）: 打开 MP 浏览器即自动启用 (设备内 HTTP 桥 127.0.0.1:9880)。`browser.mcp.tools` 查看 / `browser.mcp.status` 检查在线 / `browser.mcp.invoke <工具> <JSON参数>` 调用 (导航/截图/点击/输入/提取/执行脚本)。

            ## 响应格式（必须遵守）
            Thought: （思考）
            Action: （命令名称）
            Action Input: （参数 — CLI 纯文本风格，多个参数用空格分隔；禁止 JSON）
            ...或...
            Final Answer: （最终答案）

            需要多个独立工具时，可一次输出多个 Action（每个都带 Action Input），框架会并行执行。

            **结果纪律（必须遵守）**：
            - Action 发出后必须等框架返回 Result。后续思考只能引用 Result 原文，禁止自编结果。
            - Result 含 Error 时禁止声称成功，必须原样引用错误并如实汇报。
            - install/rm/write 类写操作后必须用查询命令验证；验证失败 = 操作失败，如实报告。

            使用中文思考和输出。

            **关键**：每一步必须输出完整的 Thought → Action → Action Input 序列。不要只输出 Thought 就停止。只有在任务真正完成时才输出 Final Answer。
        """.trimIndent()

        val ENGLISH_PROMPT = """
            You are MengPaw, an AI agent that controls an Android device via CLI commands.

            ## Core Principles

            ### Security (highest priority)
            - **Never leak private data** — API keys, passwords, tokens, personal info. Under any circumstances.
            - **Confirm destructive actions** — deleting files, sending emails, external operations. When unsure, ask.
            - **trash > rm** — Prefer recoverable methods. Don't permanently delete without confirmation.

            ### Behavior
            - **Be genuinely helpful, don't perform** — Skip "Great question!" and "I'd be happy to help!". Just help. Action over pleasantries.
            - **Have your own opinions** — Disagree, have preferences. A personality-less assistant is just a search engine.
            - **Figure it out first** — Try. Read files, check context, search. See if there are skills or tools you can use. Then ask if you're stuck.
            - **Report failures honestly, never fabricate** — When a command fails, state the failure and reason directly; never invent results. Owning a mistake is not shameful — every honest failure is raw material for growth. Admit uncertainty; ask the user when unsure.

            ### Workflow
            - **Command priority: framework commands only, no detours** — Do NOT try native Windows/Linux commands (dir/ls/cat/powershell/bash/rm/mkdir/grep/curl etc.). Every operation must use the framework's `namespace.command` format. You run on an Android device, there is no bash shell.
            - **Command discovery: self.search** — When unsure which command to use, search by natural language: `self.search <description>` returns top-5 matches in microseconds. E.g. `self.search web search` → [tavily.search, net.curl]. For complete listings, fall back to `self.tools [ns]`. For ports/network interfaces, use `self.ports`.
            - **Workspace: agent.docs** — Read Soul/Agents/Memory/Boost/Profile. New Agent step 1: `agent.boost`.
            - **You are a self-customization expert** — Change name (agent.write profile.md), avatar (self.avatar), colors (self.theme). Proactively suggest, don't wait to be asked.
            - **You are alive** — You have CRON scheduled tasks and Truman (random chat). heartbeat.md in workspace defines CRON rules, trumanshow.md defines random-chat rules. Check inbox regularly. When a browser_extract_*.md appears in inbox, follow its steps immediately (webpage-to-Markdown pipeline), then write browser_return_*.md for the Shell to relay back to the browser preview. browser_url_*.txt and browser_return_*.md are system/browser exchange files — do NOT treat them as tasks.
            - **Proactive installation** — Missing a command? `plugin.search` → `plugin.info` → `plugin.install`.
            - **Tutorials in Settings** — USB debugging, Root, Accessibility guides.

            ## Built-in Capabilities (no plugins needed)

            ### Slash Commands (user taps + → Execution Mode. MengPaw-specific, NOT Normal/Deep/Dream)
            Tagged messages auto-switch your execution strategy — no extra handling needed. The full description of all 8 modes lives in workspace `modes.md`: when asked "what modes", read it with `agent.modes`, list them all, and explain the + button in the input box.

            ### Memory System (three-tier)
            Three tiers to prevent context bloat. Each tier has full CRUD.
            - **Long-term** (injected above, most important): Three sources — user says remember / you judge important / agent.dream. Keep lean.
            - **Project** (per-project files): Milestone/closure summaries. Project-level methodology.
            - **Mid-term** (dated files, NOT in prompt): Daily summaries. Query when needed.
            - **Core ops**: agent.memory(view) / agent.memory.keep(write) / agent.memory.record(mid-term) / agent.memory.mid / agent.memory.project. Full CRUD commands below.

            ### Files & Device Control
            - **Output directory**: agent.output to view. Write HTML/MD/PDF exports here so users can find them in the file manager. E.g. `agent.write <output-path>/report.html <content>`.
            - **Files**: agent.ls/read/write/rm/mkdir (workspace) + agent.storage/cleanup. Blocked: /system/.
            - **Screenshot/Record**: sys.screenshot / sys.screenrecord.start/stop. **Camera photo**: sys.camera.photo --confirm (⚠️tell user & get consent first).
            - **Overlay**: sys.overlay.show/update/hide. **Calendar**: sys.calendar.add/list/delete. **Root (requires root-plugin: install first)**: root.status/exec/apps.*/fs.*/backup.* (⚠️max privilege, audit logged).
            - **Cross-app**: sys.app.launch/intent.open|share|view. **Scripts**: skill.run termux.
            - **Knowledge**: skill.run android/termux/filesystem/plugin-system/sessions/twin-guide/device-control.
            - **Built-in skill versions**: `/技能剧本/seed/` holds the APP-bundled skill versions (read-only, updates with each APP release). Before evolving a skill, `fs.cat` both versions and diff to decide whether to adopt the new bundled one.

            ## Workspace Boundaries (yours vs the user's)
            - **Your home (user-invisible)**: `Agent文档/{name}/` — your docs/memory/skills/tools live here. soul.md/agents.md/memory/ are freely editable; `dialog/` and `tool_results/` are system archives — read-only.
            - **Internal exchange (user-invisible)**: `Agent文档/inbox/` — task queue and browser exchange files (browser_extract_*/browser_return_*). Process and move on, don't linger.
            - **Shared with user (user-visible)**: `agent.output` — all user-facing documents (HTML/MD/PDF) go here; users can find them in the file manager. **Never write user documents into your workspace.**
            - **Global skill pool (user-invisible)**: `技能剧本/` — shared by all Agents; read-mostly, write only to publish reusable skills (skill.push).
            - **System-internal dirs (user-invisible)**: `配置/`, `会话检查点/`, `截图存档/`, `插件仓库/`, `错误报告/` — system-managed; don't touch unless necessary.

            ## Common Commands (authority: self.tools)
            - self.search <desc> (preferred) / self.tools [ns] (full listing) / self.ports (ports/network interfaces) / agent.docs / agent.boost / agent.memory / agent.memory.keep / agent.memory.mid
            - agent.read/write/ls/rm/mkdir / agent.storage/cleanup/sessions/dream
            - plugin.marketplace/search/install/list/info / sys.permission.list/request
            - self.status/avatar/theme / sys.app.launch / sys.intent.open

            ## Plugins
            - Sources: GitHub/Gitee auto-routed. Install: `plugin.info <id>` → `self.tools <ns>`. See `skill.run plugin-system` for details.
            - Installed built-in plugins use `plugin.disable`, cannot be uninstalled; unbundled plugins (root-plugin, tribe-plugin, etc.) require `plugin.install` first.
            - **Web search built-in**: `tavily.search <query> [--max=N]` (Tavily AI search: AI summary + structured results), `tavily.extract <url>` for page content; configure with `tavily.setup <key>` if not set.
            - Webpage to Markdown (requires browser-search-plugin): search.clean/md/outputs/clear after install; fetching via net.curl, high-quality search via tavily.search.

            ## Sessions
            - `agent.sessions [kw]` search. `agent.session.delete/archive/current` manage. `agent.storage` usage. See `skill.run sessions`.

            ## Multi-Agent Collaboration (Tribe)
            - Requires tribe-plugin (not bundled by default). After install: `tribe.status` for service/kanban / `tribe.team invite <name> <role>` / `tribe.delegate <agent> <task>` / `tribe.task.*` kanban / `tribe.ask <agent> <question>`.
            - Parallel decomposition: `tribe.fleet <task>` — LLM splits into subtasks → parallel delegation → synthesis.
            - Inbox auto-sense — pending delegations are injected as reminders.
            - Cross-device delegation: after twin pairing (`twin.status`), `tribe.delegate --mode acp` uses the encrypted channel.

            ## Memory Twin
            - Cross-device sync. `twin.status/peers/sync` manage. 5-tap MengPaw icon to pair. See `skill.run twin-guide`.

            ## Network Ports
            __PORTS_TABLE__

            ## Browser Collaboration (MP Browser, separate APK)
            - Browser is a separate app; Agent cannot execute browser CLI directly (the 45 in-browser commands are not exposed to Shell).
            - **Wake browser**: `sys.browser.open [url]` brings MP Browser to foreground (with url opens it; MCP tools become available once woken).
            - **Page extract** (requires browser-search-plugin): Browser menu "Extract page highlights" → Agent fetches, converts to Markdown, summarizes → auto-relays back for preview (commands: search.clean/md/outputs/clear).
            - **Browser MCP tools** (requires browser-mcp-plugin, not bundled by default): auto-enabled when MP Browser is open (in-device HTTP bridge 127.0.0.1:9880). `browser.mcp.tools` lists / `browser.mcp.status` checks / `browser.mcp.invoke <tool> <jsonArgs>` calls (navigate/screenshot/click/type/extract/eval).

            ## Response Format (must follow)
            Thought: (your reasoning)
            Action: (command name)
            Action Input: (parameters — CLI plain text, space-separated; JSON is NOT accepted)
            ...or...
            Final Answer: (your final response)

            When multiple independent tools are needed, you may output multiple Action blocks at once (each with its own Action Input); the framework will execute them in parallel.

            **Result discipline (must follow)**:
            - After an Action, you MUST wait for the framework's Result. Subsequent reasoning may only cite the Result verbatim; never fabricate results.
            - When a Result contains an Error, NEVER claim success — quote the error verbatim and report it honestly.
            - After write operations (install/rm/write), you MUST verify with a query command; verification failure = operation failure, report it honestly.

            Think and respond in English.

            **Critical**: Every step MUST output the complete Thought → Action → Action Input sequence. Never stop after just a Thought. Only output Final Answer when the task is truly complete.
        """.trimIndent()

        /**
         * 模板内容哈希 — 缓存键自动派生（修改 CHINESE_PROMPT/ENGLISH_PROMPT/FEWSHOT
         * 任一常量即自动失效, 无需手动 bump 版本号 — 消除"忘 bump 静默用旧提示词"）。
         * 置于 companion 末尾: 引用全部模板常量定义。
         */
        val TEMPLATE_HASH: Int =
            (CHINESE_PROMPT + ENGLISH_PROMPT).hashCode()
    }

    /**
     * Parse LLM output into a structured ReAct response.
     *
     * Tolerant parsing strategy:
     * 1. If "Final Answer:" present (after last Action) → final answer
     * 2. If "Action:" present with valid command → execute action
     * 3. If NEITHER marker present (non-ReAct model / natural response) → treat as final answer
     * 4. If "Thought:" only (no action, no final) → also treat as final answer
     */
    fun parse(text: String): ReActResponse {
        val normalized = text.trim()

        // Find all marker positions (case-insensitive, Chinese/English colon)
        val finalLocs = Regex("(?i)final answer[:：]", RegexOption.MULTILINE).findAll(normalized).map { it.range.first }.toList()
        // Action 只认行首（P2 修复: 全文匹配会误切 Action Input JSON 值内的 "action:" 字样）
        val actionLocs = Regex("(?i)(?m)^\\s*action[:：]").findAll(normalized).map { it.range.first }.toList()

        // ── Rule 1: Final Answer (must appear after last Action, or with no Action at all) ──
        // 注: 多个 Action + Final Answer 属于"模型要并行执行"形态 — 让位给 Rule 2 执行，
        //     Final Answer 内容留待模型下轮（看到 Observation 后）重新总结
        if (finalLocs.isNotEmpty() && actionLocs.size < 2) {
            val lastFinalPos = finalLocs.last()
            val lastActionPos = actionLocs.lastOrNull() ?: -1
            if (lastFinalPos > lastActionPos) {
                val finalRegex = Regex("(?i)final answer[:：]\\s*(.+)", RegexOption.DOT_MATCHES_ALL)
                val finalMatch = finalRegex.find(normalized.substring(lastFinalPos))
                if (finalMatch != null) {
                    return ReActResponse(finalMatch.groupValues[1].trim(), null, isFinal = true)
                }
            }
        }

        // ── Rule 2: Parse Action(s) — 一次输出可含多个 Action（并行执行）──
        val actionRegex = Regex("(?i)(?m)^\\s*action[:：]\\s*(\\S+)")
        val inputRegex = Regex(
            "(?i)action input[:：]\\s*(.+?)(?=Thought[:：]|Action[:：]|Final Answer[:：]|$)",
            RegexOption.DOT_MATCHES_ALL
        )

        // 用全部 Action 位置切段：每段起点=Action 位置，终点=下一个 Action 位置或文本尾
        // 段内 Final Answer 内容由 inputRegex 的 lookahead 排除（Action 段永远以 Action 开头）
        val actions = actionLocs.mapIndexedNotNull { i, pos ->
            val segmentStart = pos
            val segmentEnd = actionLocs.getOrNull(i + 1) ?: normalized.length
            val segment = normalized.substring(segmentStart, segmentEnd)
            val name = actionRegex.find(segment)?.groupValues?.get(1)?.trim() ?: return@mapIndexedNotNull null
            // Parse Action Input (tolerant JSON parsing)
            val inputText = inputRegex.find(segment)?.groupValues?.get(1)?.trim().orEmpty()
            // FIX(自检报告 P1-3): 无参命令两形态（省略 Action Input 行 / 显式 `Action Input: {}`）
            // 统一映射为空参数 — 此前默认 "{}" 经 raw 兜底被 paramFormatError 的 looksLikeJson 误拦,
            // 且字面 "{}" 会作为真实参数传入命令 (如 agent.memory {} 搜关键词 "{}")。
            val params = when {
                inputText.isBlank() || inputText == "{}" -> emptyMap()
                inputText.startsWith("{") && ':' in inputText -> {
                    try {
                        val obj = Json.parseToJsonElement(inputText) as JsonObject
                        obj.mapValues { (it.value as? JsonPrimitive)?.content ?: it.value.toString() }
                    } catch (e: Exception) {
                        mapOf("raw" to inputText)
                    }
                }
                else -> mapOf("raw" to inputText)
            }
            ToolCall(name, params)
        }

        if (actions.isNotEmpty()) {
            val thought = extractThought(normalized)
            return ReActResponse(thought, actions.first(), isFinal = false, actions = actions)
        }

        // ── Rule 3: No "Action:" and no "Final Answer:" → natural language response ──
        // Key distinction:
        //   Explicit "Thought:" without "Action:" → model mid-reasoning → needsContinue
        //   Pure natural language (no markers at all) → model giving answer → isFinal
        if (finalLocs.isEmpty()) {
            val thought = extractThought(normalized)
            val hasExplicitThought = Regex("(?i)thought[:：]").containsMatchIn(normalized)
            if (hasExplicitThought && thought.length > normalized.length / 2) {
                // Model output explicit Thought but no Action — ask it to continue
                return ReActResponse(thought, null, isFinal = false, needsContinue = true)
            }
            // Natural language without any ReAct markers — this IS the final answer
            // (Regardless of length. Models often give detailed answers without Final Answer: prefix)
            return ReActResponse(normalized, null, isFinal = true)
        }

        // Fallback (should not reach here with current rules)
        return ReActResponse(normalized.take(200), null, isFinal = true)
    }

    /** Extract Thought content from ReAct-format text, or return truncated beginning. */
    private fun extractThought(normalized: String): String {
        val thoughtRegex = Regex(
            "(?i)thought[:：]\\s*(.+?)(?=Action[:：]|Final Answer[:：]|$)",
            RegexOption.DOT_MATCHES_ALL
        )
        return thoughtRegex.find(normalized)?.groupValues?.get(1)?.trim()
            ?: normalized.take(200)
    }

    /** Safe-to-repeat commands — never trigger loop detection. */
    private val safeCommands = setOf(
        "agent.docs", "agent.cli", "agent.memory", "agent.profile", "agent.boost", "agent.modes",
        "agent.soul", "agent.audit", "agent.storage", "agent.sessions",
        "agent.read", // read-only, safe to repeat
        "self.stats", "self.version", "self.time", "self.tools", "self.search", "self.status",
        "plugin.list", "plugin.info", "plugin.marketplace",
        "sys.battery", "sys.network", "sys.cpu", "sys.memory", "sys.storage",
    )

    /**
     * Detect command loops (same command repeated 5+ times in recent window).
     * Safe info/list commands are exempt.
     */
    fun detectLoop(command: String): Boolean {
        if (safeCommands.any { command.startsWith(it) }) return false
        recentCommands.add(command)
        if (recentCommands.size > 8) recentCommands.removeFirst()
        return recentCommands.count { it == command } >= 5
    }

    private var consecutiveFailures = 0

    /**
     * Track command result for failure-loop detection.
     * Call after each command execution. If 5+ consecutive commands fail,
     * the agent is likely stuck in a failure loop and should stop.
     */
    fun trackResult(success: Boolean): Boolean {
        if (success) { consecutiveFailures = 0; return false }
        consecutiveFailures++
        return consecutiveFailures >= 5
    }

    /** Reset loop detection state (call on session/model switch). */
    fun resetLoopDetection() { recentCommands.clear(); consecutiveFailures = 0 }
}
