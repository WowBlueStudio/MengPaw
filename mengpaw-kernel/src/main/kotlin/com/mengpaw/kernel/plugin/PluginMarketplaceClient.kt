// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.kernel.plugin

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import com.mengpaw.kernel.error.ErrorCollector

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
    /** "builtin" = compiled into APK, no download needed. "remote" = downloadable from marketplace. */
    val status: String = "remote"
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
 * Smart geo-router for plugin downloads.
 *
 * Detects China vs. rest-of-world via system locale/timezone — instant, no network.
 *   China (CN) → Gitee primary, GitHub fallback
 *   Other       → GitHub primary, Gitee fallback
 */
object GeoRouter {
    /** Returns true if the device is likely in mainland China. */
    fun isChina(): Boolean {
        // 系统语言检测 — 简体中文
        val lang = java.util.Locale.getDefault().language
        val country = java.util.Locale.getDefault().country
        if (lang == "zh" && (country.isBlank() || country == "CN")) return true

        // 时区检测 — 中国标准时间
        val tz = java.util.TimeZone.getDefault().id
        if (tz == "Asia/Shanghai" || tz == "Asia/Chongqing" ||
            tz == "Asia/Harbin" || tz == "Asia/Urumqi") return true

        return false
    }
}

/**
 * Client for the MengPaw plugin marketplace with dual-source smart routing.
 *
 * Architecture:
 *   China (CN) → fetches index from Gitee, downloads from Gitee first
 *   Other       → fetches index from GitHub, downloads from GitHub first
 *   On failure  → automatically retries with the alternate source
 *
 * Free public endpoints used:
 *   GitHub: raw.githubusercontent.com  (global CDN)
 *   Gitee:  gitee.com/raw/              (China CDN, no VPN needed)
 */
class PluginMarketplaceClient(
    private val cacheDir: File = File(com.mengpaw.kernel.DataPaths.PLUGIN_CACHE)
) {
    private val client = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }
    private var cachedIndex: MarketplaceIndex? = null
    private var lastEtag: String? = null
    private var lastFetchTime = 0L
    private val cacheTtlMs = 300_000L // 5 minutes

    companion object {
        const val GITHUB_INDEX_URL =
            "https://raw.githubusercontent.com/XMeng5257/mengpaw-plugins/main/plugins.json"
        const val GITEE_INDEX_URL =
            "https://gitee.com/XMeng5257/mengpaw-plugins/raw/main/plugins.json"
    }

    /** Resolve the best index URL based on geo-location. */
    private fun resolveIndexUrl(): String {
        val useGitee = GeoRouter.isChina()
        return if (useGitee) GITEE_INDEX_URL else GITHUB_INDEX_URL
    }

    /** Get the fallback index URL. */
    private fun fallbackIndexUrl(primary: String): String {
        return if (primary == GITEE_INDEX_URL) GITHUB_INDEX_URL else GITEE_INDEX_URL
    }

    /**
     * Fetch the marketplace index with geo-routing and automatic fallback.
     */
    suspend fun fetchIndex(forceRefresh: Boolean = false): Result<MarketplaceIndex> {
        if (!forceRefresh && cachedIndex != null &&
            System.currentTimeMillis() - lastFetchTime < cacheTtlMs
        ) {
            return Result.success(cachedIndex ?: MarketplaceIndex())
        }

        val primary = resolveIndexUrl()
        val result = tryFetch(primary)
        if (result.isSuccess) return result

        // Fallback: try the alternate source
        val fallback = fallbackIndexUrl(primary)
        return tryFetch(fallback)
    }

    private suspend fun tryFetch(url: String): Result<MarketplaceIndex> {
        return try {
            val response = client.get(url) {
                lastEtag?.let { header(HttpHeaders.IfNoneMatch, it) }
            }
            when {
                response.status == HttpStatusCode.NotModified -> {
                    lastFetchTime = System.currentTimeMillis()
                    Result.success(cachedIndex ?: MarketplaceIndex())
                }
                response.status.isSuccess() -> {
                    val body = response.bodyAsText()
                    val index = parseIndex(body)
                    cachedIndex = index
                    lastFetchTime = System.currentTimeMillis()
                    response.headers[HttpHeaders.ETag]?.let { lastEtag = it }
                    Result.success(index)
                }
                else -> Result.failure(
                    RuntimeException("HTTP ${response.status.value}")
                )
            }
        } catch (e: Exception) {
            ErrorCollector.report(e, "PluginMarketClient.tryFetch")
            if (cachedIndex != null) Result.success(cachedIndex ?: MarketplaceIndex())
            else Result.failure(e)
        }
    }

    /**
     * Search plugins in the marketplace index by keyword.
     */
    suspend fun search(query: String): Result<List<MarketplaceEntry>> {
        return fetchIndex().map { index ->
            val q = query.lowercase()
            index.plugins.filter {
                it.id.lowercase().contains(q) ||
                it.name.lowercase().contains(q) ||
                it.description.lowercase().contains(q)
            }
        }
    }

    /**
     * Get a single plugin entry from the marketplace.
     */
    suspend fun getPlugin(id: String): Result<MarketplaceEntry> {
        return fetchIndex().map { index ->
            index.plugins.find { it.id == id }
                ?: throw NoSuchElementException("Plugin not found: $id")
        }
    }

    /**
     * Download a plugin with geo-routing.
     * Tries the best regional source first, falls back to the alternate.
     */
    suspend fun download(entry: MarketplaceEntry, destDir: File): Result<File> {
        if (!entry.isDownloadable) {
            return Result.failure(RuntimeException("${entry.name} 已内置在 APK 中，无需下载"))
        }
        val primary = if (GeoRouter.isChina() && entry.mirrorUrl.isNotBlank())
            entry.mirrorUrl else entry.downloadUrl
        val fallback = if (primary == entry.mirrorUrl) entry.downloadUrl else entry.mirrorUrl

        val result = tryDownload(primary, entry, destDir)
        if (result.isSuccess) return result

        // Try alternate source
        if (fallback.isNotBlank() && fallback != primary) {
            return tryDownload(fallback, entry, destDir)
        }
        return result
    }

    private suspend fun tryDownload(url: String, entry: MarketplaceEntry, destDir: File): Result<File> {
        return try {
            if (!url.startsWith("https://")) {
                return Result.failure(SecurityException("Plugin download requires HTTPS: $url"))
            }
            if (isPrivateUrl(url)) {
                return Result.failure(SecurityException("Plugin download blocked: internal network address"))
            }
            destDir.mkdirs()
            val ext = if (url.endsWith(".aar")) "aar" else "jar"
            val destFile = File(destDir, "${entry.id}-${entry.version}.$ext")
            val response = client.get(url)
            if (!response.status.isSuccess()) {
                return Result.failure(RuntimeException("Download HTTP ${response.status.value}"))
            }
            val bytes = response.bodyAsBytes()
            destFile.writeBytes(bytes)
            if (entry.checksum.isNotBlank()) {
                val actual = sha256(bytes)
                val expected = entry.checksum.removePrefix("sha256:")
                if (!actual.equals(expected, ignoreCase = true)) {
                    destFile.delete()
                    return Result.failure(SecurityException("Checksum mismatch"))
                }
            }
            Result.success(destFile)
        } catch (e: Exception) {
            ErrorCollector.report(e, "PluginMarketClient.tryDownload")
            Result.failure(e)
        }
    }

    /**
     * Check for available updates by comparing installed versions against the marketplace.
     */
    suspend fun checkUpdates(installed: Map<String, String>): Result<List<Pair<String, String>>> {
        return fetchIndex().map { index ->
            val updates = mutableListOf<Pair<String, String>>()
            index.plugins.forEach { entry ->
                val installedVersion = installed[entry.id]
                if (installedVersion != null) {
                    val current = PluginVersion.parse(installedVersion)
                    val latest = PluginVersion.parse(entry.version)
                    if (latest > current) updates.add(entry.id to entry.version)
                }
            }
            updates
        }
    }

    /** Clear the in-memory cache (forces refresh + re-detect geo). */
    fun clearCache() {
        cachedIndex = null
        lastEtag = null
        lastFetchTime = 0L
    }

    // ── Private helpers ───────────────────────────────────────────────

    fun parseIndex(json: String): MarketplaceIndex {
        return try {
            val root = Json.parseToJsonElement(json).jsonObject
            MarketplaceIndex(
                marketplace = root["marketplace"]?.jsonPrimitive?.content ?: "",
                version = root["version"]?.jsonPrimitive?.int ?: 1,
                updated = root["updated"]?.jsonPrimitive?.content ?: "",
                plugins = root["plugins"]?.jsonArray?.map { parseEntry(it.jsonObject) } ?: emptyList()
            )
        } catch (e: Exception) { ErrorCollector.report(e, "PluginMarketClient.parseIndex"); MarketplaceIndex() }
    }

    private fun parseEntry(obj: JsonObject): MarketplaceEntry = MarketplaceEntry(
        id = obj["id"]?.jsonPrimitive?.content ?: "",
        name = obj["name"]?.jsonPrimitive?.content ?: "",
        version = obj["version"]?.jsonPrimitive?.content ?: "0.0.0",
        type = try { PluginType.valueOf((obj["type"]?.jsonPrimitive?.content ?: "native").uppercase()) } catch (e: Exception) { ErrorCollector.report(e, "PluginMarketClient.parseEntry"); PluginType.NATIVE },
        author = obj["author"]?.jsonPrimitive?.content ?: "",
        description = obj["description"]?.jsonPrimitive?.content ?: "",
        downloadUrl = obj["downloadUrl"]?.jsonPrimitive?.content ?: "",
        mirrorUrl = obj["mirrorUrl"]?.jsonPrimitive?.content ?: "",
        checksum = obj["checksum"]?.jsonPrimitive?.content ?: "",
        sizeBytes = obj["size"]?.jsonPrimitive?.long ?: 0L,
        minCoreVersion = obj["minCoreVersion"]?.jsonPrimitive?.content ?: "0.1.0",
        dependencies = obj["dependencies"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
        permissions = obj["permissions"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
        commands = obj["commands"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
        status = obj["status"]?.jsonPrimitive?.content ?: "remote"
    )

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun isPrivateUrl(url: String): Boolean {
        val host = try { java.net.URI(url).host ?: return true } catch (e: Exception) { ErrorCollector.report(e, "PluginMarketClient.isPrivateUrl"); return true }
        return host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0.0.0.0" ||
            host.startsWith("10.") || host.startsWith("192.168.") ||
            host.startsWith("172.") && host.substringAfter("172.").substringBefore(".").toIntOrNull()?.let { it in 16..31 } == true ||
            host.startsWith("169.254.")
    }
}
