// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.shell.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.mengpaw.design.tokens.ArcoColors
import com.mengpaw.shell.ui.localization.AppStrings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 侧栏数据模型 + 图标助手 — 从 SidebarContent.kt 拆出 (2026-08-04, >40KB UI 文件拆分)。
 * sidebarAppJson 为 internal 共享实例 (SidebarContent + TwinPairingDialogs 解码用);
 * 命名避开同名冲突 — 各对话框文件已有自己的 private appJson (AddFrameworkDialog/FrameworkCardDialog)。
 */
internal val sidebarAppJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

@Serializable
data class FrameworkContactFile(
    val name: String = "",
    val address: String = "",
    val remark: String = "",
    val frameworkType: String = "mengpaw"
)

@Serializable
data class TwinPairFile(
    val deviceName: String = "",
    val deviceModel: String = "",
    val peerId: String = ""
)

/** Agent online / presence status for external frameworks. */
enum class FrameworkStatus(val indicatorColor: Color) {
    ONLINE(ArcoColors.Green6),
    BUSY(ArcoColors.Orange6),
    OFFLINE(ArcoColors.Gray6)
}

/** Localized Framework Status label (English mode → English). */
fun FrameworkStatus.label(strings: AppStrings): String = when (this) {
    FrameworkStatus.ONLINE -> strings.frameworkStatusOnline
    FrameworkStatus.BUSY -> strings.frameworkStatusBusy
    FrameworkStatus.OFFLINE -> strings.frameworkStatusOffline
}

/** Localized Framework Status explanation. */
fun FrameworkStatus.desc(strings: AppStrings): String = when (this) {
    FrameworkStatus.ONLINE -> strings.frameworkStatusOnlineDesc
    FrameworkStatus.BUSY -> strings.frameworkStatusBusyDesc
    FrameworkStatus.OFFLINE -> strings.frameworkStatusOfflineDesc
}

/** A framework peer (ACP node) that may host multiple agents. */
data class FrameworkContact(
    val name: String,
    val address: String,
    val online: Boolean,
    val trusted: Boolean,
    val agents: List<String>,
    val version: String = "",
    val frameworkName: String = "",
    val remark: String = "",
    val frameworkType: String = "mengpaw",
    val fingerprint: String = "",
    val discovered: Boolean = false   // v0.34.3: 未入册的 mDNS 发现节点 (可添加)
)

/** Data class for new agent creation form. */
data class NewAgentForm(
    val name: String = "",
    val workspaceFolder: String = "",
    val intro: String = ""
)

/** 根据框架类型返回对应图标。 */
@Composable
fun frameworkTypeIcon(frameworkType: String): androidx.compose.ui.graphics.vector.ImageVector = when (frameworkType) {
    "claude-code", "trea-ide", "trea-work", "cursor", "opencode",
    "reasonix", "workbuddy" -> Icons.Outlined.Terminal  // MCP
    "openclaw", "qclaw", "hermes", "codex" -> Icons.Outlined.Dns  // WebSocket
    "qwenpaw", "coze" -> Icons.Outlined.Language  // REST
    "collab-cli" -> Icons.Outlined.Folder  // File
    "kimi-desktop" -> Icons.Outlined.DesktopWindows  // 未知
    "custom" -> Icons.Outlined.MoreHoriz
    else -> Icons.Outlined.Hub  // mengpaw
}
