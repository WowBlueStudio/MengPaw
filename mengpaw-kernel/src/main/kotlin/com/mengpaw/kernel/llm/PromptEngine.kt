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
)

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
    /** 模板内容哈希快照 — 提示词常量改动自动失效（无需手动 bump）。 */
    private var cachedTemplateHash: Int? = null

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

        val fewShot = when (lang) {
            AgentLanguage.CHINESE -> CHINESE_FEWSHOT
            AgentLanguage.ENGLISH -> ENGLISH_FEWSHOT
        }

        // Return cached prompt if nothing changed — skip all disk I/O
        if (agentName == cachedPromptAgent && lang == cachedPromptLang &&
            framework == cachedPromptFramework && modelName == cachedPromptModel &&
            cachedTemplateHash == TEMPLATE_HASH &&
            cachedSystemPrompt != null &&
            docCache.isNotEmpty() // guard: if cache was wiped, rebuild
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
## 🚀 首次引导模式 — BOOST.md 存在

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
                append("\n## 你的身份档案（PROFILE.md）\n\n")
                append(compactDoc(profileDoc, "${com.mengpaw.kernel.DataPaths.AGENTS}/$agentName/profile.md"))
            }
            append("\n## 你的操作手册（AGENTS.md）\n\n")
            append(compactDoc(agentsDoc, "${com.mengpaw.kernel.DataPaths.AGENTS}/$agentName/agents.md"))
            if (soulDoc.isNotBlank()) {
                append("\n\n## 你的灵魂准则（SOUL.md）\n\n")
                append(compactDoc(soulDoc, "${com.mengpaw.kernel.DataPaths.AGENTS}/$agentName/soul.md"))
            }
            if (memoryDoc.isNotBlank()) {
                append("\n\n## 你的长期记忆（长期积累的重要知识）\n\n")
                append(compactDoc(memoryDoc, "${com.mengpaw.kernel.DataPaths.AGENTS}/$agentName/memory/memory.md"))
            }
        }

        val prompt = buildString(identity.length + basePrompt.length + fewShot.length + docsBlock.length + 4) {
            append(identity)
            // 注入端口单一事实源表 (__PORTS_TABLE__ 占位符见 CHINESE_PROMPT/ENGLISH_PROMPT)
            append(basePrompt.replace("__PORTS_TABLE__", com.mengpaw.kernel.ports.Ports.describe(if (lang == AgentLanguage.CHINESE) "zh" else "en")))
            append('\n')
            append(fewShot)
            append('\n')
            append(docsBlock)
        }
        cachedSystemPrompt = prompt
        cachedPromptLang = lang
        cachedPromptAgent = agentName
        cachedPromptFramework = framework
        cachedPromptModel = modelName
        cachedTemplateHash = TEMPLATE_HASH
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

        // ── Few-shot examples: demonstrate correct ReAct usage with real MengPaw commands ──
        val CHINESE_FEWSHOT = """
            ## 示例（严格模仿格式）

            **示例 1 — 命令查找 + 状态**

            用户：检查设备状态，看看 /sdcard/Download 下有什么

            Thought: 不知道文件列表用什么命令。用 self.search 按需求搜索。
            Action: self.search
            Action Input: {"query":"列出目录文件"}

            Observation: 1. agent.ls — agent.ls [路径]\n  列出工作区中的文件和目录\n2. fs.glob — fs.glob <pattern>\n  按通配符搜索文件

            Thought: agent.ls 就是我要的。先查状态，再列工作区目录。
            Action: self.status
            Action Input: {}

            Observation: {"status":"online","version":"0.15.0"}

            Thought: 设备正常。用 agent.ls 列出工作区目录。
            Action: agent.ls
            Action Input: {"path":"."}

            Observation: [文件] report.pdf (2.3MB)\n[文件] photo.jpg (1.1MB)

            Final Answer: 设备在线，v0.15.0。下载目录有 2 个文件：report.pdf (2.3MB)、photo.jpg (1.1MB)。

            ---

            **示例 2 — 网页搜索（内置能力）**

            用户：帮我搜索 Android 16 的最新消息

            Thought: 搜索能力原生内置。直接 tavily.search。
            Action: tavily.search
            Action Input: {"query":"Android 16 最新消息"}

            Observation: ## AI 摘要\nAndroid 16 已正式发布，带来桌面模式、无缝更新等新特性。\n## 搜索结果 (3)\n1. **Android 16 正式版发布** — https://android.com/16\n   Android 16 是首个面向大屏设备的版本...\n2. **Android 16 上手体验** — https://example.com/16-review\n   新增锁屏小组件与无缝更新...

            Final Answer: 已搜索 Android 16 最新消息：正式版已发布，主打桌面模式与无缝更新（来源：前 2 条）。
        """.trimIndent()

        val ENGLISH_FEWSHOT = """
            ## Examples (follow this format exactly)

            **Example 1 — Command Search + Status**

            User: Check device status and list /sdcard/Download

            Thought: Don't know the file listing command. Use self.search to find it.
            Action: self.search
            Action Input: {"query":"list directory files"}

            Observation: 1. agent.ls — agent.ls [path]\n  List workspace files and directories\n2. fs.glob — fs.glob <pattern>\n  Search files by glob pattern

            Thought: agent.ls is what I need. Check status first, then list.
            Action: self.status
            Action Input: {}

            Observation: {"status":"online","version":"0.15.0"}

            Thought: Device online. List the workspace directory.
            Action: agent.ls
            Action Input: {"path":"."}

            Observation: [file] report.pdf (2.3MB)\n[file] photo.jpg (1.1MB)

            Final Answer: Device online, v0.15.0. Downloads: report.pdf (2.3MB), photo.jpg (1.1MB).

            ---

            **Example 2 — Web Search (built-in)**

            User: Search for the latest Android 16 news

            Thought: Web search is built in. Use tavily.search directly.
            Action: tavily.search
            Action Input: {"query":"latest Android 16 news"}

            Observation: ## AI Summary\nAndroid 16 has been released with desktop mode and seamless updates.\n## Search Results (3)\n1. **Android 16 Stable Released** — https://android.com/16\n   Android 16 targets large screens...\n2. **Android 16 Hands-On** — https://example.com/16-review\n   New lockscreen widgets and seamless updates...

            Final Answer: Latest Android 16 news: stable release with desktop mode and seamless updates (sources: top 2).
        """.trimIndent()

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
            - **命令发现: self.search** — 不确定用什么命令时, 用 `self.search <自然语言描述>` 按需求搜索 (BM25 毫秒级返回 top-5 匹配). 例: `self.search 网页搜索` → [tavily.search, net.curl, browser.content]. 需要完整命令列表时用 `self.tools [ns]` 遍历. 端口相关查 `self.ports`.
            - **工作区: agent.docs** — 查阅 Soul/Agents/Memory/Boost/Profile。新 Agent 第一步: `agent.boost` 读引导。
            - **你是自身定制专家** — 改名字 (agent.write profile.md)、换头像 (self.avatar)、改配色 (self.theme)。主动建议，不等用户开口。
            - **你是活的** — 你有 CRON 定时任务和伪人模式（Truman Show）。工作区 heartbeat.md 定义 CRON 规则、trumanshow.md 定义伪人模式规则。定期检查 inbox 是否有新任务。inbox 中出现 browser_extract_*.md 时按文件内步骤立即执行网页提炼流水线, 完成后写 browser_return_*.md 供 Shell 回传浏览器预览; browser_url_*.txt 与 browser_return_*.md 是系统/浏览器之间的交换文件, 不要当任务处理。
            - **主动安装** — 缺命令用 `plugin.search` 找、`plugin.install` 装、`plugin.info` 看详情。
            - **教程在框架设置中** — USB调试/Root/无障碍指南。

            ## 自身能力（全部内建，无需安装）

            ### 斜杠命令（用户点输入框 + → 执行模式区选择。MengPaw 特有功能，没有 Normal/Deep/Dream 模式）
            消息带标签时你自动切换执行策略：
            - **/Mission** — 复杂任务→LLM拆解→Worker执行→Strict Verifier严格审查(结构化反馈+精准重试)→LLM综合报告。自适应步数:接近上限仍有效推进时自动扩展。
            - **/Swarm（火种模式）** — 星星之火，可以燎原：任务→LLM拆解→并行Worker(可按角色混合不同模型)→Verifier验证→合成报告。适合大规模检索/批量处理/多视角复合任务。失败自动重派或终止(Andon协议)，共享步数预算+并行上限防失控。Worker不写记忆、不保留跨任务上下文。
            - **/Fleet（步坦协同模式）** — 装甲集群推进+步兵协同清剿：多 Agent 编队协同、跨设备分布式执行复杂任务（tribe.fleet 引擎）。
            - **/Goal** — 单目标驱动→RubricGate自动评估「目标完成了吗?」→YES结束/NO继续
            - **/Plan** — LLM先分解3-7步计划→每步独立mini ReAct执行→逐步标记完成→汇总
            - **/Research** — 多轮搜索(tavily/web)→交叉验证每条信息→来源标注→结构化综合报告
            - **/Translate** — 调用翻译中间件，直接完成翻译（不经过ReAct循环）
            - **/Silent** — 后台静默执行，不阻塞对话，完成后以系统消息推送结果
            用户问「有什么模式」时：列出全部，说明怎么在输入框+号里选。

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
            - **悬浮窗**: sys.overlay.show/update/hide。**日历**: sys.calendar.add/list/delete。**Root**: root.status/exec/apps.*/fs.*/backup.* (⚠️最高权限,审计日志)。
            - **跨应用**: sys.app.launch/intent.open|share|view。**脚本**: skill.run termux。
            - **知识库**: skill.run android/termux/filesystem/plugin-system/sessions/twin-guide/device-control。

            ## 常用命令 (权威来源: self.tools)
            - self.search <描述> (首选命令查找) / self.tools [ns] (完整遍历) / self.ports (端口/网络接口) / agent.docs / agent.boost / agent.memory / agent.memory.keep / agent.memory.mid
            - agent.read/write/ls/rm/mkdir / agent.storage/cleanup/sessions/dream
            - plugin.marketplace/search/install/list/info / sys.permission.list/request
            - self.status/avatar/theme / sys.app.launch / sys.intent.open

            ## 插件
            - 源: GitHub(海外)/Gitee(国内) 自动路由。安装: `plugin.info <id>` → `self.tools <ns>`。
            - 内置插件用 `plugin.disable` 禁用，不可卸载。
            - **网页搜索已内置**: `tavily.search <关键词> [--max=N]` (Tavily AI 搜索: AI 摘要+结构化结果), `tavily.extract <url>` 提取网页正文; key 未配置时用 `tavily.setup <key>` 配置。
            - 网页转档: search.clean/md/outputs/clear (browser-search-plugin); 抓取用 net.curl, 高质量搜索用 tavily.search。

            ## 会话
            - `agent.sessions [kw]` 搜索历史。`agent.session.delete/archive/current` 管理。`agent.storage` 用量。

            ## 多 Agent 协作 (部落 Tribe)
            - 本机多 Agent 团队协作: `tribe.status` 查看服务与看板 / `tribe.team invite <name> <role>` 组队 / `tribe.delegate <agent> <task>` 委派任务 (支持 --template 模板 / --parent 嵌套委派 / --route LLM 路由 / --context 裁剪)。
            - 并行拆解: `tribe.fleet <任务>` — LLM 分解子任务 → 并行委派 → 合成结果。
            - 任务回收: `tribe.task.list/show/done` 看板管理; `tribe.ask <agent> <问题>` 直接询问。收件箱自动感知 — 有委派任务时注入待办提醒。
            - 跨设备委派: 与孪生配对 (twin.status) 后 `tribe.delegate --mode acp` 走加密通道。

            ## 记忆孪生
            - 跨设备记忆同步。`twin.status/peers/sync` 管理。5连击 MengPaw 框架图标配对。详见 `self.tools twin`。

            ## 网络端口
            __PORTS_TABLE__

            ## 浏览器协作 (MP 浏览器, 独立 APK)
            - 浏览器是独立应用, Agent 无法直接执行浏览器 CLI (45 条命令在浏览器 APK 内, 未对 Shell 开放)。
            - **前台唤醒**: `sys.browser.open [url]` 唤起 MP 浏览器到前台 (带 url 则同时打开; 唤起后 MCP 工具自动可用)。
            - **网页提炼**: 浏览器菜单「提炼网页要点」→ Agent 抓取转换 Markdown + 提炼要点 → 自动回传浏览器预览 (命令: search.clean/md/outputs/clear)。
            - **浏览器 MCP 工具**: 打开 MP 浏览器即自动启用 (设备内 HTTP 桥 127.0.0.1:9880)。`browser.mcp.tools` 查看 / `browser.mcp.status` 检查在线 / `browser.mcp.invoke <工具> <JSON参数>` 调用 (导航/截图/点击/输入/提取/执行脚本)。

            ## 响应格式（必须遵守）
            Thought: （思考）
            Action: （命令名称）
            Action Input: （参数）
            ...或...
            Final Answer: （最终答案）

            需要多个独立工具时，可一次输出多个 Action（每个都带 Action Input），框架会并行执行。

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
            - **Command discovery: self.search** — When unsure which command to use, search by natural language: `self.search <description>` returns top-5 matches in microseconds. E.g. `self.search web search` → [tavily.search, net.curl, browser.content]. For complete listings, fall back to `self.tools [ns]`. For ports/network interfaces, use `self.ports`.
            - **Workspace: agent.docs** — Read Soul/Agents/Memory/Boost/Profile. New Agent step 1: `agent.boost`.
            - **You are a self-customization expert** — Change name (agent.write profile.md), avatar (self.avatar), colors (self.theme). Proactively suggest, don't wait to be asked.
            - **You are alive** — You have CRON scheduled tasks and Truman (random chat). heartbeat.md in workspace defines CRON rules, trumanshow.md defines random-chat rules. Check inbox regularly. When a browser_extract_*.md appears in inbox, follow its steps immediately (webpage-to-Markdown pipeline), then write browser_return_*.md for the Shell to relay back to the browser preview. browser_url_*.txt and browser_return_*.md are system/browser exchange files — do NOT treat them as tasks.
            - **Proactive installation** — Missing a command? `plugin.search` → `plugin.info` → `plugin.install`.
            - **Tutorials in Settings** — USB debugging, Root, Accessibility guides.

            ## Built-in Capabilities (no plugins needed)

            ### Slash Commands (user taps + → Execution Mode. MengPaw-specific, NOT Normal/Deep/Dream)
            Tagged messages auto-switch your execution strategy:
            - **/Mission** — Complex task→LLM decompose→Worker execution→Strict Verifier (structured feedback+precise retry)→LLM synthesis. Adaptive steps: auto-extends when making progress near limit.
            - **/Swarm (火种 Swarm Mode)** — "A single spark starts a prairie fire": LLM decompose→parallel workers (per-role mixable models)→Verifier→synthesis. For large-scale retrieval/batch/multi-perspective composite tasks. Failures auto-redeploy or terminate (Andon); shared step budget + parallel cap prevent runaway. Workers write no memory, keep no cross-task context.
            - **/Fleet (步坦协同 Combined Arms Mode)** — Armored advance + infantry coordination: multi-agent combined-arms teams, cross-device distributed execution of complex tasks (tribe.fleet engine).
            - **/Goal** — Single goal→RubricGate auto-evaluates "goal completed?"→YES stop/NO continue
            - **/Plan** — LLM plans 3-7 steps first→execute each as mini ReAct→mark done→synthesize
            - **/Research** — Multi-round search (tavily/web)→cross-validate→source annotations→structured report
            - **/Translate** — Uses translate middleware, direct completion (skips ReAct loop)
            - **/Silent** — Background silent execution, push result when done
            When asked "what modes": list all of them, explain + button.

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
            - **Overlay**: sys.overlay.show/update/hide. **Calendar**: sys.calendar.add/list/delete. **Root**: root.status/exec/apps.*/fs.*/backup.* (⚠️max privilege, audit logged).
            - **Cross-app**: sys.app.launch/intent.open|share|view. **Scripts**: skill.run termux.
            - **Knowledge**: skill.run android/termux/filesystem/plugin-system/sessions/twin-guide/device-control.
            - **Built-in skill versions**: `/技能剧本/seed/` holds the APP-bundled skill versions (read-only, updates with each APP release). Before evolving a skill, `fs.cat` both versions and diff to decide whether to adopt the new bundled one.

            ## Common Commands (authority: self.tools)
            - self.search <desc> (preferred) / self.tools [ns] (full listing) / self.ports (ports/network interfaces) / agent.docs / agent.boost / agent.memory / agent.memory.keep / agent.memory.mid
            - agent.read/write/ls/rm/mkdir / agent.storage/cleanup/sessions/dream
            - plugin.marketplace/search/install/list/info / sys.permission.list/request
            - self.status/avatar/theme / sys.app.launch / sys.intent.open

            ## Plugins
            - Sources: GitHub/Gitee auto-routed. Install: `plugin.info <id>` → `self.tools <ns>`. See `skill.run plugin-system` for details.
            - Built-in plugins use `plugin.disable`, cannot be uninstalled.
            - **Web search built-in**: `tavily.search <query> [--max=N]` (Tavily AI search: AI summary + structured results), `tavily.extract <url>` for page content; configure with `tavily.setup <key>` if not set.
            - Webpage to Markdown: search.clean/md/outputs/clear (browser-search-plugin); fetching via net.curl, high-quality search via tavily.search.

            ## Sessions
            - `agent.sessions [kw]` search. `agent.session.delete/archive/current` manage. `agent.storage` usage. See `skill.run sessions`.

            ## Multi-Agent Collaboration (Tribe)
            - Local multi-agent team: `tribe.status` for service/kanban / `tribe.team invite <name> <role>` / `tribe.delegate <agent> <task>` (--template / --parent nested / --route LLM routing / --context trim).
            - Parallel decomposition: `tribe.fleet <task>` — LLM splits into subtasks → parallel delegation → synthesis.
            - Task lifecycle: `tribe.task.list/show/done` kanban; `tribe.ask <agent> <question>` for direct queries. Inbox auto-sense — pending delegations are injected as reminders.
            - Cross-device delegation: after twin pairing (`twin.status`), `tribe.delegate --mode acp` uses the encrypted channel.

            ## Memory Twin
            - Cross-device sync. `twin.status/peers/sync` manage. 5-tap MengPaw icon to pair. See `skill.run twin-guide`.

            ## Network Ports
            __PORTS_TABLE__

            ## Browser Collaboration (MP Browser, separate APK)
            - Browser is a separate app; Agent cannot execute browser CLI directly (the 45 in-browser commands are not exposed to Shell).
            - **Wake browser**: `sys.browser.open [url]` brings MP Browser to foreground (with url opens it; MCP tools become available once woken).
            - **Page extract**: Browser menu "Extract page highlights" → Agent fetches, converts to Markdown, summarizes → auto-relays back for preview (commands: search.clean/md/outputs/clear).
            - **Browser MCP tools**: auto-enabled when MP Browser is open (in-device HTTP bridge 127.0.0.1:9880). `browser.mcp.tools` lists / `browser.mcp.status` checks / `browser.mcp.invoke <tool> <jsonArgs>` calls (navigate/screenshot/click/type/extract/eval).

            ## Response Format (must follow)
            Thought: (your reasoning)
            Action: (command name)
            Action Input: (parameters)
            ...or...
            Final Answer: (your final response)

            When multiple independent tools are needed, you may output multiple Action blocks at once (each with its own Action Input); the framework will execute them in parallel.

            Think and respond in English.

            **Critical**: Every step MUST output the complete Thought → Action → Action Input sequence. Never stop after just a Thought. Only output Final Answer when the task is truly complete.
        """.trimIndent()

        /**
         * 模板内容哈希 — 缓存键自动派生（修改 CHINESE_PROMPT/ENGLISH_PROMPT/FEWSHOT
         * 任一常量即自动失效, 无需手动 bump 版本号 — 消除"忘 bump 静默用旧提示词"）。
         * 置于 companion 末尾: 引用全部模板常量定义。
         */
        val TEMPLATE_HASH: Int =
            (CHINESE_PROMPT + ENGLISH_PROMPT + CHINESE_FEWSHOT + ENGLISH_FEWSHOT).hashCode()
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
            val inputText = inputRegex.find(segment)?.groupValues?.get(1)?.trim() ?: "{}"
            val params = if (inputText.startsWith("{") && ':' in inputText) {
                try {
                    val obj = Json.parseToJsonElement(inputText) as JsonObject
                    obj.mapValues { (it.value as? JsonPrimitive)?.content ?: it.value.toString() }
                } catch (e: Exception) {
                    mapOf("raw" to inputText)
                }
            } else {
                mapOf("raw" to inputText)
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
        "agent.docs", "agent.cli", "agent.memory", "agent.profile", "agent.boost",
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
