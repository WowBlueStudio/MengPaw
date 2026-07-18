package com.mengpaw.core

/**
 * Unified data directory paths — 傻瓜式目录结构。
 *
 * 所有数据存储在 /Android/data/com.mengpaw/ 下，卸载自动清除。
 * 目录名使用人类可读的命名，用户凭文件夹名即可识别用途，避免误删。
 *
 * 目录结构:
 *   /Android/data/com.mengpaw/
 *   ├── 记忆数据/              ← memory-plugin 数据
 *   ├── 技能剧本/              ← skill-plugin 数据
 *   ├── 会话检查点/            ← session checkpoints
 *   ├── 截图存档/              ← UI screenshots
 *   ├── 插件仓库/              ← 插件缓存 + 已下载 JAR
 *   │   ├── 文件系统插件-fs/
 *   │   ├── 网络插件-net/
 *   │   └── ...
 *   ├── Agent文档/             ← Agent 文档系统
 *   │   └── {agent-id}/
 *   │       ├── 安全规则.md
 *   │       ├── 灵魂设定.md
 *   │       ├── 关系档案.md
 *   │       ├── 记忆索引.md
 *   │       └── 命令参考.md
 *   └── mengpaw.sock           ← Unix Socket (Termux IPC)
 */
object DataPaths {
    const val BASE = "/Android/data/com.mengpaw"

    /** 记忆数据 — memory-plugin markdown 文件 */
    const val MEMORIES = "$BASE/记忆数据"

    /** 技能剧本 — skill-plugin YAML+markdown 文件 */
    const val SKILLS = "$BASE/技能剧本"

    /** 会话检查点 — session checkpoint JSON */
    const val CHECKPOINTS = "$BASE/会话检查点"

    /** 截图存档 — UI screenshots */
    const val SCREENSHOTS = "$BASE/截图存档"

    /** 插件仓库 — 下载的插件 JAR + manifests */
    const val PLUGIN_CACHE = "$BASE/插件仓库"

    /** Agent文档 — Agents.md/Soul.md/Profile.md/Memory.md/CLI.md */
    const val AGENTS = "$BASE/Agent文档"

    /** Unix domain socket for Termux IPC */
    const val SOCKET = "$BASE/mengpaw.sock"

    /** Agent 收件箱 (inbox) */
    const val AGENT_INBOX = "$AGENTS/inbox"

    /** 多智能体协作 — team 目录 */
    const val TEAM = "$AGENTS/team"
    const val TEAM_INBOX = "$TEAM/inbox"
    const val TEAM_MEMOS = "$TEAM/memos"

    /** 子 Agent 孵化器 */
    const val INCUBATOR = "$AGENTS/incubator"

    /** ACP 信任设备目录 */
    const val ACP_TRUSTED = "$AGENTS/acp/trusted"

    /** 插件输出目录 */
    const val COMFY_WORKFLOWS = "$PLUGIN_CACHE/comfy/workflows"
    const val COMFY_OUTPUTS = "$PLUGIN_CACHE/comfy/outputs"
    const val RENDER_OUTPUTS = "$PLUGIN_CACHE/renders"
    const val WORKFLOW_DIR = "$PLUGIN_CACHE/workflows"
    const val WORKFLOW_OUTPUTS = "$PLUGIN_CACHE/workflows/outputs"

    // ── Plugin-specific storage ───────────────────────────────────

    fun pluginDir(pluginId: String): String = "$PLUGIN_CACHE/${pluginFolderName(pluginId)}"

    /** Human-readable folder name from plugin ID. */
    fun pluginFolderName(pluginId: String): String = when (pluginId) {
        "fs-plugin" -> "文件系统插件-fs"
        "net-plugin" -> "网络插件-net"
        "memory-plugin" -> "记忆系统插件-memory"
        "skill-plugin" -> "技能系统插件-skill"
        "self-plugin" -> "自省插件-self"
        "ui-plugin" -> "界面操控插件-ui"
        "proc-plugin" -> "进程管理插件-proc"
        "clipboard-plugin" -> "剪贴板插件-clipboard"
        "notification-plugin" -> "通知插件-notification"
        "vision-plugin" -> "视觉识别插件-vision"
        "audio-plugin" -> "听觉识别插件-audio"
        else -> pluginId
    }
}
