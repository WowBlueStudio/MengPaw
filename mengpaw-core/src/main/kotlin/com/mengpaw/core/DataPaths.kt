// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

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
    /** Set by the app on startup. Falls back to `/sdcard/MengPaw` if not initialized. */
    @Volatile
    var BASE: String = "/sdcard/MengPaw"
        private set

    /** Must be called in Application.onCreate() or MainActivity.onCreate(). */
    fun initialize(context: android.content.Context) {
        BASE = context.filesDir.absolutePath
    }

    /** 记忆数据 — memory-plugin markdown 文件 */
    val MEMORIES get() = "$BASE/记忆数据"
    val SKILLS get() = "$BASE/技能剧本"
    val CHECKPOINTS get() = "$BASE/会话检查点"
    val SCREENSHOTS get() = "$BASE/截图存档"
    val PLUGIN_CACHE get() = "$BASE/插件仓库"
    val AGENTS get() = "$BASE/Agent文档"
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
    val WORKFLOW_DIR get() = "$PLUGIN_CACHE/workflows"
    val WORKFLOW_OUTPUTS get() = "$PLUGIN_CACHE/workflows/outputs"
    val ERROR_LOG get() = "$BASE/错误报告"
    val ERROR_QUEUE get() = "$ERROR_LOG/queue"

    // ── Plugin-specific storage ───────────────────────────────────

    fun pluginDir(pluginId: String): String = "${PLUGIN_CACHE}/${pluginFolderName(pluginId)}"

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
