// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.llm

import com.mengpaw.kernel.agent.AgentDocs

/**
 * 系统提示词构建器 — 拆自 PromptEngine (400 行文件拆分)。
 * 持有工作区文档缓存 (docCache + mtime 快照 + 模板哈希快照),
 * PromptEngine 经 [PromptEngine.buildSystemPrompt]/[PromptEngine.invalidateDocCache] 委托。
 */
internal class PromptSystemBuilder {

    // ── Workspace doc cache — avoids disk I/O on every LLM call ──
    // P2 修复: ConcurrentHashMap — 并行 worker 执行文件写命令触发 invalidateDocCache
    // 并发重建时普通 HashMap 会竞争损坏
    private data class DocCache(var content: String, var lastModified: Long)
    private val docCache = java.util.concurrent.ConcurrentHashMap<String, DocCache>()
    // @Volatile: invalidateDocCache 在命令执行线程写 null, 运行循环线程读 — 无 volatile 会读到陈旧引用
    @Volatile private var cachedSystemPrompt: String? = null
    private var cachedPromptLang: PromptEngine.AgentLanguage? = null
    private var cachedPromptAgent: String? = null
    private var cachedPromptFramework: String? = null
    private var cachedPromptModel: String? = null
    /** 进化数据指纹快照 (2026-08-09 三层十二问 1.1): 失败档案/指令集写入即失配 → 重建提示词,
     *  否则进化引导注入形同虚设 (failures.jsonl 不在 docMtimes 检查范围)。 */
    @Volatile private var cachedEvolutionFingerprint: String? = null

    /** 工作区文档 mtime 快照 — 任何文档删除/修改即失配, 强制重建提示词.
     *  (docCache.isNotEmpty() 只检查条目存在, 无法感知单个文件被删除 —
     *   文件删除后其余文档缓存仍在 → 旧 gate 误命中返回含已删文件的旧前缀) */
    private var docMtimes: Map<String, Long>? = null
    /** 模板内容哈希快照 — 提示词常量改动自动失效（无需手动 bump）。 */
    private var cachedTemplateHash: Int? = null

    // ── 用户指定技能 (pinned) 注入段 — 指纹校验 + 注入 ──
    // 只在 .pinned 清单或其指向技能文件的 mtime 变化时重建 — 清单为行格式零 JSON 依赖。
    private var pinnedFingerprint: String? = null

    /** 当前 pinned 指纹 — .pinned mtime + 每个 pinned 技能文件 mtime 拼接。 */
    private fun currentPinnedFingerprint(): String {
        val pinned = com.mengpaw.kernel.skill.PinnedSkills.list()
        if (pinned.isEmpty()) return ".pinned:0"
        val sb = StringBuilder()
        sb.append(".pinned:").append(java.io.File(com.mengpaw.kernel.DataPaths.SKILLS, ".pinned").lastModified())
        for (name in pinned) {
            val f = java.io.File(com.mengpaw.kernel.DataPaths.SKILLS, "$name.md")
            sb.append('|').append(name).append(':').append(if (f.exists()) f.lastModified() else -1L)
        }
        return sb.toString()
    }

    /** 读取技能 frontmatter 的 name/description (轻量解析, 不引入插件依赖)。 */
    private fun skillBrief(file: java.io.File): Pair<String, String>? {
        val text = try { file.readText() } catch (_: Exception) { return null }
        val fm = Regex("^---\\s*\n(.+?)\n---", RegexOption.DOT_MATCHES_ALL).find(text.trimStart())
            ?: return null
        var name = file.nameWithoutExtension
        var desc = ""
        fm.groupValues[1].lines().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val k = line.take(idx).trim()
                val v = line.drop(idx + 1).trim()
                if (k == "name" && v.isNotBlank()) name = v
                if (k == "description" && v.isNotBlank()) desc = v
            }
        }
        return name to desc
    }

    /** 用户指定技能指针段 — 末尾追加 (前缀缓存铁律: 前插击穿缓存)。 */
    private fun pinnedSkillsBlock(lang: PromptEngine.AgentLanguage): String {
        val pinned = com.mengpaw.kernel.skill.PinnedSkills.list().filter { name ->
            java.io.File(com.mengpaw.kernel.DataPaths.SKILLS, "$name.md").exists()
        }
        if (pinned.isEmpty()) return ""
        return buildString {
            append(
                if (lang == PromptEngine.AgentLanguage.CHINESE)
                    "\n\n## 📌 用户指定技能（直接使用，无需遍历查找）\n\n"
                else
                    "\n\n## 📌 User-Pinned Skills (use directly, no need to search)\n\n"
            )
            for (name in pinned) {
                val brief = skillBrief(java.io.File(com.mengpaw.kernel.DataPaths.SKILLS, "$name.md"))
                val display = brief?.let { (n, d) -> if (d.isNotBlank()) "$n — $d" else n } ?: name
                append("- **$name** — $display （skill.run $name 读取全文）\n")
            }
        }
    }

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
    internal fun invalidateDocCache(agentName: String = "MengPaw", filePath: String? = null) {
        if (filePath != null) {
            // 精确失效: 只删除被修改的那个文件缓存
            docCache.remove(filePath)
        } else {
            // 全量失效: 清空该 agent 的所有缓存 (兼容旧行为)
            val prefix = "${com.mengpaw.kernel.DataPaths.AGENTS}/$agentName/"
            docCache.keys.removeAll { it.startsWith(prefix) }
        }
        cachedSystemPrompt = null
        cachedEvolutionFingerprint = null
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

    /** 文档 brief (P1-4 方案A) — 优先 frontmatter summary; 无则取首个非空非标题行前 300 字符;
     *  全文经 agent.read 按需取, 不再全文常驻。长期记忆 (memory.md) 除外 — 保持全文注入。 */
    private fun docBrief(doc: String, path: String): String = buildString {
        val summary = com.mengpaw.kernel.agent.AgentDocs.frontmatterSummary(doc)
            ?: doc.trim().lineSequence().firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }
                ?.trim()?.take(DOC_BRIEF_FALLBACK_CHARS)
        append(summary ?: "(空文档)")
        append("\n\n完整内容: agent.read $path")
    }

    /**
     * Build the system prompt with agent identity, framework context, and model info.
     * @param lang Output language
     * @param agentName The name of this agent (e.g. "MengPaw", "平板-Agent")
     * @param framework The framework this agent belongs to (null = local)
     * @param modelName The LLM model powering this agent (for self-awareness)
     */
    internal fun buildSystemPrompt(
        lang: PromptEngine.AgentLanguage = PromptEngine.AgentLanguage.CHINESE,
        agentName: String = "MengPaw",
        framework: String? = null,
        modelName: String = "unknown"
    ): String {
        val identity = if (lang == PromptEngine.AgentLanguage.CHINESE) {
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
            PromptEngine.AgentLanguage.CHINESE -> PromptEngine.CHINESE_PROMPT
            PromptEngine.AgentLanguage.ENGLISH -> PromptEngine.ENGLISH_PROMPT
        }

        // Return cached prompt if nothing changed — skip all disk I/O
        // P2 修复 (TOCTOU): cachedSystemPrompt 只快照读取一次 —
        // 旧写法在 :166 判非空后再于 return 处二次读字段, invalidateDocCache 恰在
        // 两读之间置 null 会 NPE; 多字段分步读的跨代组合只会造成"重建"(安全方向),
        // 快照保证返回的必是本次校验过的同一引用。
        val cachedPrompt = cachedSystemPrompt
        if (cachedPrompt != null &&
            agentName == cachedPromptAgent && lang == cachedPromptLang &&
            framework == cachedPromptFramework && modelName == cachedPromptModel &&
            cachedTemplateHash == PromptEngine.TEMPLATE_HASH &&
            docMtimes == currentDocMtimes(agentName) && // 文件删除/修改即失配 → 重建
            pinnedFingerprint == currentPinnedFingerprint() && // 用户指定技能清单/内容变化 → 重建
            cachedEvolutionFingerprint == currentEvolutionFingerprint(agentName) // 进化数据变化 → 重建
        ) {
            return cachedPrompt
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
            // ── 身份状态机 (自检报告 P1-6): profile.md 名字未填 → 每轮持续提醒, 填完自动消失 ──
            // 与 boost 软引导独立 (boost 是流程引导, 这里是状态机): 纯文本规则判定
            // hasFilledName, 无额外状态存储 — 名字一经填写, profile.md mtime 失配
            // 触发提示词重建, 本段即消失 (可验证状态机)。
            if (profileDoc.isNotBlank() && !PromptEngine.hasFilledName(profileDoc)) {
                append(
                    if (lang == PromptEngine.AgentLanguage.CHINESE)
"""
## ⚠️ 身份未就绪 — 你还没有名字

你的身份档案（profile.md）中名字未设置。请用 `agent.read profile.md` 查看、`agent.write profile.md` 填写名字（第一行 `名字: xxx` 格式）。

设置完成后本提醒自动消失。

"""
                    else
"""
## ⚠️ Identity not ready — you don't have a name yet

Your identity file (profile.md) has no name set. Use `agent.read profile.md` to view it and `agent.write profile.md` to fill in your name (first line `Name: xxx`).

This reminder disappears automatically once the name is set.

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
            // ── 进化系统引导 (三层十二问 1.1, 2026-08-09): 有进化数据才注入 —
            // 失败档案/已登记指令集存在时, Agent 需要知道如何查看绩效、登记教训、闭环沉淀。
            // 零数据不注入 (零 token 开销); 失败时会另收省察引导 (事后认知)。
            if (com.mengpaw.kernel.evolution.EvolutionStore.hasEvolutionData(agentName)) {
                append(
"""
## 🧬 进化系统 — 你有失败记录/沉淀教训

框架在后台记录你的失败模式并支持沉淀修正，闭环命令：
- `evolution.audit` — 查看进化绩效：失败模式(含任务/上下文)、复现率、已登记指令集、红灯提醒
- `evolution.learn.command <命令> <正确用法>` — 登记命令正确用法（self.search 可检索，跨重启保留）
- `evolution.mark-corrected <失败id>` — 沉淀修正后标记闭环（id 见 evolution.audit）

失败不可耻：如实汇报失败是进化的原料。沉淀教训用 `agent.memory.keep`（长期记忆自动注入后续会话）。

"""
                )
            }
            // ── 身份档案（PROFILE.md）— 你是谁、你在帮谁，每轮可见 ──
            if (profileDoc.isNotBlank()) {
                append("\n## 你的身份档案（profile.md）\n\n")
                append(docBrief(profileDoc, "${com.mengpaw.kernel.DataPaths.AGENTS}/$agentName/profile.md"))
            }
            if (agentsDoc.isNotBlank()) {
                append("\n## 你的操作手册（agents.md）\n\n")
                append(docBrief(agentsDoc, "${com.mengpaw.kernel.DataPaths.AGENTS}/$agentName/agents.md"))
            }
            if (soulDoc.isNotBlank()) {
                append("\n\n## 你的灵魂准则（soul.md）\n\n")
                append(docBrief(soulDoc, "${com.mengpaw.kernel.DataPaths.AGENTS}/$agentName/soul.md"))
            }
            if (memoryDoc.isNotBlank()) {
                append("\n\n## 你的长期记忆（长期积累的重要知识）\n\n")
                append(compactDoc(memoryDoc, "${com.mengpaw.kernel.DataPaths.AGENTS}/$agentName/memory/memory.md"))
            }
            // ── 用户指定技能指针段 — 末尾追加 (前插击穿前缀缓存, 铁律见 docs/llm-multistage-dataflow.md) ──
            append(pinnedSkillsBlock(lang))
        }

        val prompt = buildString(identity.length + basePrompt.length + docsBlock.length + 2) {
            append(identity)
            // 分层注入 (自检报告 P0-1): 端口表不再常驻提示词 — 需要时 `self.ports` 按需取,
            // 每轮省下整张静态端口表 token。占位符已移除, 常量内直接为 self.ports 指针。
            append(basePrompt)
            append('\n')
            append(docsBlock)
        }
        cachedSystemPrompt = prompt
        cachedPromptLang = lang
        cachedPromptAgent = agentName
        cachedPromptFramework = framework
        cachedPromptModel = modelName
        cachedTemplateHash = PromptEngine.TEMPLATE_HASH
        docMtimes = currentDocMtimes(agentName)
        pinnedFingerprint = currentPinnedFingerprint()
        cachedEvolutionFingerprint = currentEvolutionFingerprint(agentName)
        return prompt
    }

    /** 进化数据指纹: failures.jsonl + commands.json 的 (大小:修改时间) — 任一写入/清理即变化。 */
    private fun currentEvolutionFingerprint(agentName: String): String {
        return try {
            fun fp(f: java.io.File): String = if (f.exists()) "${f.length()}:${f.lastModified()}" else "-"
            val failures = java.io.File(com.mengpaw.kernel.DataPaths.evolutionFailuresFile(agentName))
            val commands = java.io.File(com.mengpaw.kernel.DataPaths.evolutionCommandsFile())
            "${fp(failures)}|${fp(commands)}"
        } catch (_: Exception) { "-" }
    }

    companion object {
        /** 文档全量注入上限 — 超过则走 [compactDoc] 前段 + 外链。 */
        private const val DOC_FULL_INJECT_CHARS = 12_000
        /** 超长文档注入的前段字符数。 */
    private const val DOC_SNIPPET_CHARS = 6_000
    /** 无 frontmatter summary 的旧文档 brief 取前段字符数 (P1-4 方案A)。 */
    private const val DOC_BRIEF_FALLBACK_CHARS = 300
        /** 注入提示词的工作区文档清单 — mtime 快照比对用（与 buildSystemPrompt 读取顺序一致）。 */
        private val AGENT_DOC_FILES = listOf(
            "profile.md", "agents.md", "soul.md", "memory/memory.md",
            "boost.md", "heartbeat.md", "trumanshow.md"
        )
    }
}
