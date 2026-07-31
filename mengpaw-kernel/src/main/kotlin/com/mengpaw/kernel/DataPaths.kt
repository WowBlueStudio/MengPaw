// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

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
 *   ├── Agent文档/             ← Agent document system
 *   │   └── {agent-id}/
 *   │       ├── dialog/         ← 压缩归档 (YYYY-MM-DD.jsonl)
 *   │       └── tool_results/   ← 工具结果外存
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
    fun longTermMemoryFile(agentName: String) = "$AGENTS/$agentName/memory/memory.md"
    /** Mid-term memory dir — dated files, NOT injected into prompt. */
    fun midTermMemoryDir(agentName: String) = "$AGENTS/$agentName/memory"
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
    /** Evolution data dir — failures / user reactions / framework feedback. */
    fun evolutionDir(agentName: String) = "${safeAgentDir(agentName)}/evolution"
    /** Failure pattern store (JSON-lines). */
    fun evolutionFailuresFile(agentName: String) = "${evolutionDir(agentName)}/failures.jsonl"
    /** User reaction archive (用户分身数据源) — appended markdown. */
    fun evolutionReactionsFile(agentName: String) = "${evolutionDir(agentName)}/reactions.md"
    /** Framework feedback reports written by Agent (evolution.report). */
    fun evolutionFeedbackDir(agentName: String) = "${evolutionDir(agentName)}/feedback"

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
