// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel

import com.mengpaw.kernel.harness.BaseDirPathResolver
import com.mengpaw.kernel.harness.HarnessFileSystem
import com.mengpaw.kernel.harness.HarnessPathResolver
import com.mengpaw.kernel.harness.JvmHarnessFileSystem

/**
 * Unified data directory paths — 过渡期双 API 门面。
 *
 * **架构地位 (A 阶段改造, 2026-08-21)**: 本对象是 ReAct 核心平台上抽象的**旧入口**,
 * 现已成为 [HarnessPathResolver] 的门面。真实路径解析逻辑在
 * [com.mengpaw.kernel.harness.BaseDirPathResolver] — 该实现无任何平台类型,
 * 可整体搬入 harness 独立仓库。
 *
 * 双 API 并存的原因: 现有 152 个调用点使用上方常量/函数形态, 一次性改签名风险过高;
 * 故保留旧形态 (委托到 [resolver]), 新代码一律走 [resolver] / [fs]。
 * 待 ReAct 主链路完成注入式改造后, 旧常量形态可逐个下线。
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

    /** 数据根目录。Set by the app on startup. Falls back to `/sdcard/MengPaw` if not initialized. */
    @Volatile
    var BASE: String = "/sdcard/MengPaw"
        private set

    /** 宿主可写输出目录 (外部可访问) — Android 传 getExternalFilesDir("output")。 */
    @Volatile
    var OUTPUT: String = "$BASE/输出"
        private set

    /**
     * 逻辑路径提供者 — A 阶段新增的**唯一推荐入口**。
     * 与 [BASE] 同步重建: BASE 仅在启动时 initialize 一次, 之后的实例保持有效。
     */
    @Volatile
    var resolver: HarnessPathResolver = BaseDirPathResolver(BASE)
        private set

    /** 文件系统抽象 — 新代码访问磁盘一律走此 (旧代码的 java.io.File 逐步迁移)。 */
    val fs: HarnessFileSystem get() = JvmHarnessFileSystem

    /** Must be called at app startup with the platform-specific base path. */
    fun initialize(basePath: String) {
        BASE = basePath
        resolver = BaseDirPathResolver(basePath)
        OUTPUT = resolver.outputDir
    }

    // ── 旧常量形态 (委托 resolver, 行为与改造前逐字一致) ──────────────

    val CONFIG get() = resolver.configDir
    val SKILLS get() = resolver.skillsDir
    val CHECKPOINTS get() = resolver.checkpointDir
    val SCREENSHOTS get() = "$BASE/截图存档"
    val PLUGIN_CACHE get() = resolver.pluginDir
    val AGENTS get() = resolver.agentsDir
    /** 无主进化档案目录 (agentName=null 时 EvolutionStore 写入处) —
     *  与 Agent文档 分离, 防被 Agent 发现逻辑误判为 Agent (v0.34.x 修复)。 */
    val EVOLUTION get() = resolver.evolutionDir
    // ── 语音录制 (v0.33.0+) ──
    val RECORDINGS get() = resolver.recordingDir
    /** Fleet 局域网互传共享目录 (v0.36) — 所有格式文件可互传, 非孪生同步范围。 */
    val FLEET_SHARE get() = resolver.fleetShareDir
    val AGENT_TEMPLATES get() = resolver.agentTemplatesDir
    val SOCKET get() = resolver.socketPath
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
    val ERROR_LOG get() = resolver.errorDir
    val ERROR_QUEUE get() = "$ERROR_LOG/queue"

    fun initializeOutput(outputPath: String) {
        OUTPUT = outputPath
        fs.mkdirs(OUTPUT)
    }

    // ── Conversation context archive (QwenPaw-style no-data-loss) ──
    /** Sanitize agent name for filesystem use — prevent path traversal. */
    private fun safeAgentDir(agentName: String): String = resolver.agentDir(agentName)

    /** Archived raw dialog before compaction. Agent can read_file to recall. */
    fun dialogArchiveDir(agentName: String) = resolver.dialogArchiveDir(agentName)
    /** Long tool outputs offloaded to disk. Agent references snippet + path. */
    fun toolResultsDir(agentName: String) = resolver.toolResultsDir(agentName)

    // ── Memory Twin (v0.22.0: 工作区文件同步, 账本与独立梦境目录已移除) ──
    val TWIN_AUDIT get() = "$AGENTS/twin/audit.log"

    // ── Per-agent Skills & Tools partitions ─────────────────────────
    /** Agent's local skills directory — pulled from global pool or created locally. */
    fun agentSkillsDir(agentName: String) = resolver.agentSkillsDir(agentName)
    /** Agent's local tools directory — agent-specific CLI commands. */
    fun agentToolsDir(agentName: String) = resolver.agentToolsDir(agentName)

    // ── Two-tier memory ────────────────────────────────────────────
    /** Long-term memory file — injected into system prompt. Curated content only. */
    // P1 修复: agentName 可能含路径分隔符/穿越段 — 统一走 safeAgentDir 消毒
    fun longTermMemoryFile(agentName: String) = "${safeAgentDir(agentName)}/memory/memory.md"
    /** Mid-term memory dir — dated files, NOT injected into prompt. */
    fun midTermMemoryDir(agentName: String) = resolver.memoryDir(agentName)
    /** Mid-term memory file for a specific date. */
    fun midTermMemoryFile(agentName: String, date: String) = "${midTermMemoryDir(agentName)}/memory_$date.md"
    /** Project memory file — reusable project completion patterns. */
    fun projectMemoryFile(agentName: String, projectName: String) = "${midTermMemoryDir(agentName)}/project_${projectName}_memory.md"
    /** List all project memory files for an agent. */
    fun projectMemoryFiles(agentName: String): List<String> =
        fs.listFiltered(midTermMemoryDir(agentName), prefix = "project_", suffix = "_memory.md")
            .map { it.removePrefix("project_").removeSuffix("_memory.md") }
            .sorted()

    // ── Evolution (Agent 进化系统) ─────────────────────────────────
    /**
     * 有主 Agent 的进化数据目录 (失败模式库 / 用户反应 / 框架反馈)。
     * 无主 (agentName=null/空白, 如后台 Pipeline 错误) → 归 `{BASE}/进化档案/`,
     * 绝不落 Agent文档/ 下 — 否则被 Agent 发现逻辑识别为假 Agent (v0.34.x 教训)。
     * "default" (EvolutionStore.DEFAULT_AGENT 保留字, 非真 Agent) 同样归进化档案/。
     */
    fun evolutionDir(agentName: String?) = resolver.agentEvolutionDir(agentName)
    /** Failure pattern store (JSON-lines). */
    fun evolutionFailuresFile(agentName: String?) = "${evolutionDir(agentName)}/failures.jsonl"
    /** 会话幻觉率统计文件 (P0, 2026-08-08): 每行一条会话记录, 与 failures.jsonl 同模式。 */
    fun evolutionVeracityFile(agentName: String?) = "${evolutionDir(agentName)}/veracity.jsonl"
    /** User reaction archive (用户分身数据源) — appended markdown. */
    fun evolutionReactionsFile(agentName: String?) = "${evolutionDir(agentName)}/reactions.md"
    /** Framework feedback reports written by Agent (evolution.report). */
    fun evolutionFeedbackDir(agentName: String?) = "${evolutionDir(agentName)}/feedback"
    /** 用户学习登记的指令集 (evolution.learn.command 持久化, v2 2026-08-09) — 全局共享。 */
    fun evolutionCommandsFile() = "${EVOLUTION}/commands.json"

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

    fun pluginDir(pluginId: String): String = "$PLUGIN_CACHE/${pluginFolderName(pluginId)}"

    /** Human-readable folder name from plugin ID. */
    fun pluginFolderName(pluginId: String): String = when (pluginId) {
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
