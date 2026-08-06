// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.plugin

/**
 * Marketplace index entry for a single plugin.
 */
data class MarketplaceEntry(
    val id: String,
    val name: String,
    val version: String,
    val type: PluginType = PluginType.NATIVE,
    val author: String = "",
    val description: String = "",
    val downloadUrl: String = "",
    /** Mirror download URL (Gitee for China, GitHub for others — auto-selected by GeoRouter) */
    val mirrorUrl: String = "",
    val checksum: String = "",
    val sizeBytes: Long = 0,
    val minCoreVersion: String = "0.1.0",
    val dependencies: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val commands: List<String> = emptyList(),
    /** 插件声明占用的端口 (1-65535) — 与 PluginMetadata.ports 对应. */
    val ports: List<Int> = emptyList(),
    /** "builtin" = compiled into APK, no download needed. "remote" = downloadable from marketplace. */
    val status: String = "remote",
    /** Release notes for the current version (markdown). */
    val changelog: String = ""
) {
    /** Whether this plugin can be downloaded from the marketplace. Download URL is the ground truth. */
    val isDownloadable: Boolean get() = status != "deprecated" && downloadUrl.isNotBlank()
    /** Whether this plugin is already built into the app. Only true if no download URL exists. */
    val isBuiltin: Boolean get() = status != "deprecated" && downloadUrl.isBlank()
}

/**
 * Full marketplace index response.
 */
data class MarketplaceIndex(
    val marketplace: String = "MengPaw Plugin Marketplace",
    val version: Int = 1,
    val updated: String = "",
    val plugins: List<MarketplaceEntry> = emptyList()
)

/**
 * Marketplace 网络/连接层失败（断网、超时、全部源不可达）— 映射 NETWORK_OFFLINE。
 */
class MarketplaceNetworkException(message: String) : RuntimeException(message)

/**
 * Marketplace 下载 HTTP 失败（404/5xx 等）— 映射 DOWNLOAD_FAILED。
 */
class MarketplaceDownloadException(message: String) : RuntimeException(message)
