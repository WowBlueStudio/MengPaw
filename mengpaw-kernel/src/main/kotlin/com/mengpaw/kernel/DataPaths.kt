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
 *   ├── 记忆数据/              ← memory-plugin data
 *   ├── 技能剧本/              ← skill-plugin data
 *   ├── 会话检查点/            ← session checkpoints
 *   ├── 截图存档/              ← UI screenshots
 *   ├── 插件仓库/              ← plugin cache + downloaded JARs
 *   ├── Agent文档/             ← Agent document system
 *   │   └── {agent-id}/
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
    val MEMORIES get() = "$BASE/记忆数据"
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
    val WORKFLOW_DIR get() = "$PLUGIN_CACHE/workflows"
    val WORKFLOW_OUTPUTS get() = "$PLUGIN_CACHE/workflows/outputs"
    val ERROR_LOG get() = "$BASE/错误报告"
    val ERROR_QUEUE get() = "$ERROR_LOG/queue"

    // ── Memory Twin ────────────────────────────────────────────────
    val TWIN_LEDGER get() = "$AGENTS/twin/ledger"
    val TWIN_PEERS get() = "$AGENTS/twin/peers"
    val TWIN_AUDIT get() = "$AGENTS/twin/audit.log"
    val TWIN_DREAMS get() = "$AGENTS/twin/dreams"

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
