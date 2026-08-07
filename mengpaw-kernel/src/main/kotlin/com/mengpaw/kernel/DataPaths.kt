// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

/**
 * Unified data directory paths — platform-independent constants.
 *
 * All data is stored under BASE, which must be initialized at app startup.
 * Android: DataPaths.initialize(context.filesDir.absolutePath)
 * JVM/Desktop: DataPaths.initialize("/path/to/data")
 *
 * Directory structure:
 *   {BASE}/
 *   ├── 技能剧本/              ← skill-plugin data
 *   ├── 会话检查点/            ← session checkpoints
 *   ├── 截图存档/              ← UI screenshots
 *   ├── 插件仓库/              ← plugin cache + downloaded JARs
 *   ├── Agent文档/             ← Agent document system (仅真 Agent 工作区)
 *   │   └── {agent-id}/
 *   │       ├── dialog/         ← 压缩归档 (YYYY-MM-DD.jsonl)
 *   │       ├── tool_results/   ← 工具结果外存
 *   │       └── evolution/      ← 有主 Agent 的进化档案
 *   ├── 进化档案/              ← 无主进化档案 (agentName=null, 不入 Agent文档 防误判)
 *   └── mengpaw.sock           ← Unix Socket (Termux IPC)
 */
object DataPaths {
    /** Set by the app on startup. Falls back to `/sdcard/MengPaw` if not initialized. */
    @Volatile
    var BASE: String = "/sdcard/MengPaw"
        private set

    /** Must be called at app startup with the platform-specific base path. */
    fun initialize(basePath: String) {
        BASE = basePath
    }

    val CONFIG get() = "$BASE/配置"
    val SKILLS get() = "$BASE/技能剧本"
    val CHECKPOINTS get() = "$BASE/会话检查点"
    val SCREENSHOTS get() = "$BASE/截图存档"
    val PLUGIN_CACHE get() = "$BASE/插件仓库"
    val AGENTS get() = "$BASE/Agent文档"
    /** 无主进化档案目录 (agentName=null 时 EvolutionStore 写入处) —
     *  与 Agent文档 分离, 防被 Agent 发现逻辑误判为 Agent (v0.34.x 修复)。 */
    val EVOLUTION get() = "$BASE/进化档案"
    // ── 语音录制 (v0.33.0+) ──
    val RECORDINGS get() = "$BASE/录音"
    val AGENT_TEMPLATES get() = "$BASE/agent-templates"
    val SOCKET get() = "$BASE/mengpaw.sock"
    val AGENT_INBOX get() = "$AGENTS/inbox"
    val TEAM get() = "$AGENTS/team"
    val TEAM_INBOX get() = "$TEAM/inbox"
    val TEAM_MEMOS get() = "$TEAM/memos"
    val INCUBATOR get() = "$AGENTS/incubator"
    val ACP_TRUSTED get() = "$AGENTS/acp/trusted"
    val COMFY_WORKFLOWS get() = "$PLUGIN_CACHE/comfy/workflows"
    val COMFY_OUTPUTS get() = "$PLUGIN_CACHE/comfy/outputs"
    val RENDER_OUTPUTS get() = "$PLUGIN_CACHE/renders"
    val SEARCH_OUTPUTS get() = "$PLUGIN_CACHE/search/outputs"
    val WORKFLOW_DIR get() = "$PLUGIN_CACHE/workflows"
    val WORKFLOW_OUTPUTS get() = "$PLUGIN_CACHE/workflows/outputs"
    val ERROR_LOG get() = "$BASE/错误报告"
    val ERROR_QUEUE get() = "$ERROR_LOG/queue"

    // ── User-facing output — accessible via system file manager ──────
    /** User-facing output directory — HTML/MD/PDF exports.
     *  Initialized separately via [initializeOutput] with getExternalFilesDir("output"). */
    @Volatile
    var OUTPUT: String = "$BASE/输出"
        private set

    // ── Conversation context archive (QwenPaw-style no-data-loss) ──
    /** Sanitize agent name for filesystem use — prevent path traversal. */
    private fun safeAgentDir(agentName: String): String =
        "$AGENTS/${agentName.replace(Regex("[/\\\\]"), "_")}"

    /** Archived raw dialog before compaction. Agent can read_file to recall. */
    fun dialogArchiveDir(agentName: String) = "${safeAgentDir(agentName)}/dialog"
    /** Long tool outputs offloaded to disk. Agent references snippet + path. */
    fun toolResultsDir(agentName: String) = "${safeAgentDir(agentName)}/tool_results"

    fun initializeOutput(outputPath: String) {
        OUTPUT = outputPath
        java.io.File(OUTPUT).mkdirs()
    }

    // ── Memory Twin (v0.22.0: 工作区文件同步, 账本与独立梦境目录已移除) ──
    val TWIN_AUDIT get() = "$AGENTS/twin/audit.log"

    // ── Per-agent Skills & Tools partitions ─────────────────────────
    /** Agent's local skills directory — pulled from global pool or created locally. */
    fun agentSkillsDir(agentName: String) = "${safeAgentDir(agentName)}/skills"
    /** Agent's local tools directory — agent-specific CLI commands. */
    fun agentToolsDir(agentName: String) = "${safeAgentDir(agentName)}/tools"

    // ── Two-tier memory ────────────────────────────────────────────
    /** Long-term memory file — injected into system prompt. Curated content only. */
    // P1 修复: agentName 可能含路径分隔符/穿越段 — 统一走 safeAgentDir 消毒
    fun longTermMemoryFile(agentName: String) = "${safeAgentDir(agentName)}/memory/memory.md"
    /** Mid-term memory dir — dated files, NOT injected into prompt. */
    fun midTermMemoryDir(agentName: String) = "${safeAgentDir(agentName)}/memory"
    /** Mid-term memory file for a specific date. */
    fun midTermMemoryFile(agentName: String, date: String) = "${midTermMemoryDir(agentName)}/memory_$date.md"
    /** Project memory file — reusable project completion patterns. */
    fun projectMemoryFile(agentName: String, projectName: String) = "${midTermMemoryDir(agentName)}/project_${projectName}_memory.md"
    /** List all project memory files for an agent. */
    fun projectMemoryFiles(agentName: String): List<String> {
        val dir = java.io.File(midTermMemoryDir(agentName))
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.name.startsWith("project_") && it.name.endsWith("_memory.md") }
            ?.map { it.name.removePrefix("project_").removeSuffix("_memory.md") }
            ?.sorted()
            ?: emptyList()
    }

    // ── Evolution (Agent 进化系统) ─────────────────────────────────
    /**
     * 有主 Agent 的进化数据目录 (失败模式库 / 用户反应 / 框架反馈)。
     * 无主 (agentName=null/空白, 如后台 Pipeline 错误) → 归 `{BASE}/进化档案/`,
     * 绝不落 Agent文档/ 下 — 否则被 Agent 发现逻辑识别为假 Agent (v0.34.x 教训)。
     * "default" (EvolutionStore.DEFAULT_AGENT 保留字, 非真 Agent) 同样归进化档案/。
     */
    fun evolutionDir(agentName: String?) =
        if (agentName.isNullOrBlank() || agentName == "default") EVOLUTION
        else "${safeAgentDir(agentName)}/evolution"
    /** Failure pattern store (JSON-lines). */
    fun evolutionFailuresFile(agentName: String?) = "${evolutionDir(agentName)}/failures.jsonl"
    /** User reaction archive (用户分身数据源) — appended markdown. */
    fun evolutionReactionsFile(agentName: String?) = "${evolutionDir(agentName)}/reactions.md"
    /** Framework feedback reports written by Agent (evolution.report). */
    fun evolutionFeedbackDir(agentName: String?) = "${evolutionDir(agentName)}/feedback"

    // ── Agent 工作区判定 (Agent 发现/列表的唯一事实源) ─────────────
    /** Agent文档/ 下的系统目录 — 不是 Agent, 不得出现在任何 Agent 列表。
     * (v0.34.x: 统一散落名单 — MainActivity/SidebarContent/DreamWorker/
     *  BrowserTheme/TribeInbox/TribeTeam 此前各写各的, default/twin 漏排除
     *  导致假 Agent 混入列表)。 */
    val AGENT_SYSTEM_DIRS: Set<String> =
        setOf("inbox", "team", "acp", "incubator", "agent-001", "default", "twin")

    /** 该目录名是否构成一个 Agent 工作区 (真 Agent 或框架托管的 Agent)。 */
    fun isAgentWorkspaceDir(name: String): Boolean =
        name.isNotBlank() && name !in AGENT_SYSTEM_DIRS && !name.startsWith(".")

    // ── Plugin-specific storage ───────────────────────────────────

    fun pluginDir(pluginId: String): String = "${PLUGIN_CACHE}/${pluginFolderName(pluginId)}"

    /** Human-readable folder name from plugin ID. */
    fun pluginFolderName(pluginId: String): String = when (pluginId) {
        "fs-plugin" -> "文件系统插件-fs"
        "net-plugin" -> "网络插件-net"
        "skill-plugin" -> "技能系统插件-skill"
        "ui-plugin" -> "界面操控插件-ui"
        "proc-plugin" -> "进程管理插件-proc"
        "clipboard-plugin" -> "剪贴板插件-clipboard"
        "notification-plugin" -> "通知插件-notification"
        "vision-plugin" -> "视觉识别插件-vision"
        "audio-plugin" -> "听觉识别插件-audio"
        else -> pluginId
    }
}
