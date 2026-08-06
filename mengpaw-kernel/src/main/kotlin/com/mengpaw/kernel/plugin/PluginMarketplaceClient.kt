// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.plugin

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.error.ErrorCollector

/**
 * Client for the MengPaw plugin marketplace with dual-source smart routing.
 *
 * Architecture:
 *   China (CN) → fetches index from Gitee, downloads from Gitee first
 *   Other       → fetches index from GitHub, downloads from GitHub first
 *   On failure  → retries with alternate source, then ghproxy.com proxy
 *
 * Free public endpoints used:
 *   GitHub: raw.githubusercontent.com  (global CDN)
 *   Gitee:  gitee.com/raw/              (China CDN, no VPN needed)
 *   ghproxy: ghproxy.com                (GitHub proxy, last-resort fallback)
 */
class PluginMarketplaceClient(
    private val cacheDir: File = File(com.mengpaw.kernel.DataPaths.PLUGIN_CACHE)
) : AutoCloseable {
    private val client = HttpClient(OkHttp) {
        engine {
            config {
                // Gitee in China can be slow — 20s connect, generous read/write
                connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                // Connection pooling: reuse TLS sessions, reduce handshake overhead
                connectionPool(
                    okhttp3.ConnectionPool(5, 5, java.util.concurrent.TimeUnit.MINUTES)
                )
                // Retry on connection failures (OkHttp default: 0 for connection, we want 1 retry)
                retryOnConnectionFailure(true)
            }
        }
    }
    @Volatile private var cachedIndex: MarketplaceIndex? = null
    private var lastEtag: String? = null
    @Volatile private var lastFetchTime = 0L
    private val cacheTtlMs = 300_000L // 5 minutes

    companion object {
        const val GITHUB_INDEX_URL =
            "https://raw.githubusercontent.com/WowBlueStudio/MengPaw/master/plugins.json"
        const val GITEE_INDEX_URL =
            "https://gitee.com/WowBlueStudio/MengPaw/raw/master/plugins.json"
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

    /** Build a ghproxy.com proxy URL for GitHub-hosted resources (last-resort fallback). */
    private fun ghproxyUrl(original: String): String? {
        if ("github" !in original.lowercase()) return null
        return "https://ghproxy.com/$original"
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

        // Fallback 1: try the alternate source (Gitee ↔ GitHub)
        val fallback = fallbackIndexUrl(primary)
        val fbResult = tryFetch(fallback)
        if (fbResult.isSuccess) return fbResult

        // Fallback 2: ghproxy.com proxy (for GitHub URLs when both direct sources fail)
        val ghproxy = ghproxyUrl(primary)
        if (ghproxy != null) return tryFetch(ghproxy)

        // All sources failed — add proxy guidance
        val originalError = fbResult.exceptionOrNull()
        return Result.failure(MarketplaceNetworkException(
            "${originalError?.message ?: "Unknown error"}; 检查网络连接或使用网络代理; 中国用户可尝试配置 net.proxy"
        ))
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
                    MarketplaceNetworkException("HTTP ${response.status.value}")
                )
            }
        } catch (e: Exception) {
            ErrorCollector.report(e, "PluginMarketClient.tryFetch")
            if (cachedIndex != null) Result.success(cachedIndex ?: MarketplaceIndex())
            else Result.failure(e.asMarketplaceError())
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
     * Download a plugin with geo-routing + progress callback.
     * Tries best regional source → alternate → ghproxy.com proxy.
     *
     * @param onProgress optional callback receiving (bytesReceived, totalBytes) or (0, -1) when unknown.
     */
    suspend fun download(entry: MarketplaceEntry, destDir: File, onProgress: ((Long, Long) -> Unit)? = null): Result<File> {
        if (!entry.isDownloadable) {
            return Result.failure(RuntimeException("${entry.name} 已内置在 APK 中，无需下载"))
        }
        val primary = if (GeoRouter.isChina() && entry.mirrorUrl.isNotBlank())
            entry.mirrorUrl else entry.downloadUrl
        val fallback = if (primary == entry.mirrorUrl) entry.downloadUrl else entry.mirrorUrl

        val result = tryDownload(primary, entry, destDir, onProgress)
        if (result.isSuccess) return result

        // Fallback 1: alternate source (Gitee ↔ GitHub)
        if (fallback.isNotBlank() && fallback != primary) {
            val fbResult = tryDownload(fallback, entry, destDir, onProgress)
            if (fbResult.isSuccess) return fbResult
        }

        // Fallback 2: ghproxy.com proxy
        val ghproxy = ghproxyUrl(primary)
        if (ghproxy != null) {
            return tryDownload(ghproxy, entry, destDir, onProgress)
        }

        // All sources failed — add proxy guidance
        val originalError = result.exceptionOrNull()
        return Result.failure(MarketplaceNetworkException(
            "${originalError?.message ?: "Unknown error"}; 检查网络连接或使用网络代理; 中国用户可尝试配置 net.proxy"
        ))
    }

    private suspend fun tryDownload(url: String, entry: MarketplaceEntry, destDir: File, onProgress: ((Long, Long) -> Unit)? = null): Result<File> {
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

            // Download with real byte-level progress via streaming channel read
            val bytes = client.prepareGet(url).execute { response ->
                if (!response.status.isSuccess()) {
                    throw MarketplaceDownloadException("Download HTTP ${response.status.value}")
                }
                val total = response.contentLength() ?: -1L
                onProgress?.invoke(0, total)

                val channel: ByteReadChannel = response.bodyAsChannel()
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192) // 8 KB chunks
                var received = 0L
                try {
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        received += read
                        onProgress?.invoke(received, total)
                    }
                } catch (e: Exception) {
                    KernelLog.w("PluginMarket", "download channel read error: ${e.message}")
                    // channel may be closed abruptly — fall through to use whatever we got
                }
                out.toByteArray()
            }
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
            Result.failure(e.asMarketplaceError())
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

    /**
     * Fetch a marketplace index from an explicit URL (used by --from custom markets).
     * Does NOT update the cache, ETag, or lastFetchTime.
     */
    suspend fun fetchIndexFrom(url: String): Result<MarketplaceIndex> = tryFetch(url)

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
        ports = obj["ports"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content.toIntOrNull()?.takeIf { p -> p in 1..65535 } } ?: emptyList(),
        status = obj["status"]?.jsonPrimitive?.content ?: "remote",
        changelog = obj["changelog"]?.jsonPrimitive?.content ?: ""
    )

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Locale.ROOT: 默认 Locale 下 %02x 输出畸形 (阿拉伯语设备 — P2 修复)
        return digest.digest(bytes).joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
    }

    /** 将底层异常归类为网络/下载错误（逻辑异常如 SecurityException 保持原样）。 */
    private fun Exception.asMarketplaceError(): Exception = when (this) {
        is MarketplaceNetworkException, is MarketplaceDownloadException -> this
        is java.io.IOException -> MarketplaceNetworkException(message ?: toString())
        else -> this
    }

    private fun isPrivateUrl(url: String): Boolean {
        val host = try { java.net.URI(url).host ?: return true } catch (e: Exception) { ErrorCollector.report(e, "PluginMarketClient.isPrivateUrl"); return true }
        return host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0.0.0.0" ||
            host.startsWith("10.") || host.startsWith("192.168.") ||
            host.startsWith("172.") && host.substringAfter("172.").substringBefore(".").toIntOrNull()?.let { it in 16..31 } == true ||
            host.startsWith("169.254.")
    }

    /** Release the underlying HTTP client resources (connection pool, thread pool). */
    override fun close() {
        try { client.close() } catch (e: Exception) { KernelLog.w("PluginMarket", "client.close failed: ${e.message}") }
    }
}
