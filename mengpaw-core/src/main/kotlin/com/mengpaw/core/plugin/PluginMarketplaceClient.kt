// SPDX-FileCopyrightText: 2026 MengPaw
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.plugin

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

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
    val downloadUrl: String,
    val checksum: String = "",
    val sizeBytes: Long = 0,
    val minCoreVersion: String = "0.1.0",
    val dependencies: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val commands: List<String> = emptyList()
)

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
 * Client for the MengPaw plugin marketplace.
 *
 * Features:
 * - Fetch marketplace index with ETag/If-Modified-Since caching
 * - Download plugins with SHA256 verification
 * - Local cache for marketplace index
 */
class PluginMarketplaceClient(
    private val marketplaceUrl: String = DEFAULT_MARKETPLACE_URL,
    private val cacheDir: File = File(com.mengpaw.core.DataPaths.PLUGIN_CACHE)
) {
    private val client = HttpClient(OkHttp)
    private var cachedIndex: MarketplaceIndex? = null
    private var lastEtag: String? = null
    private var lastFetchTime = 0L
    private val cacheTtlMs = 300_000L // 5 minutes

    companion object {
        const val DEFAULT_MARKETPLACE_URL =
            "https://raw.githubusercontent.com/mengpaw/plugins/main/plugins.json"
    }

    /**
     * Fetch the marketplace index. Uses ETag caching to minimize bandwidth.
     * Returns cached result if within TTL.
     */
    suspend fun fetchIndex(forceRefresh: Boolean = false): Result<MarketplaceIndex> {
        if (!forceRefresh && cachedIndex != null &&
            System.currentTimeMillis() - lastFetchTime < cacheTtlMs
        ) {
            return Result.success(cachedIndex!!)
        }

        return try {
            val response = client.get(marketplaceUrl) {
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
                    RuntimeException("Marketplace HTTP ${response.status.value}: ${response.bodyAsText().take(200)}")
                )
            }
        } catch (e: Exception) {
            // Return cached index if available, even if expired
            if (cachedIndex != null) Result.success(cachedIndex!!)
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
                ?: throw NoSuchElementException("Plugin not found in marketplace: $id")
        }
    }

    /**
     * Download a plugin JAR/APK and verify its SHA256 checksum.
     * Enforces HTTPS-only and blocks internal/private IP ranges (SSRF protection).
     *
     * @param entry The marketplace entry with downloadUrl and checksum.
     * @param destDir Destination directory for the downloaded file.
     * @return The downloaded file.
     */
    suspend fun download(entry: MarketplaceEntry, destDir: File): Result<File> {
        return try {
            // VULN-FIX: Validate download URL — HTTPS only, no SSRF to internal networks
            val url = entry.downloadUrl
            if (!url.startsWith("https://")) {
                return Result.failure(SecurityException("Plugin download requires HTTPS: $url"))
            }
            if (isPrivateUrl(url)) {
                return Result.failure(SecurityException("Plugin download blocked: internal/private network address"))
            }

            cacheDir.mkdirs()
            val destFile = File(destDir, "${entry.id}-${entry.version}.jar")

            val response = client.get(url)
            if (!response.status.isSuccess()) {
                return Result.failure(
                    RuntimeException("Download failed: HTTP ${response.status.value}")
                )
            }

            val bytes = response.bodyAsBytes()
            destFile.writeBytes(bytes)

            // Verify checksum if provided
            if (entry.checksum.isNotBlank()) {
                val actual = sha256(bytes)
                val expected = entry.checksum.removePrefix("sha256:")
                if (!actual.equals(expected, ignoreCase = true)) {
                    destFile.delete()
                    return Result.failure(
                        SecurityException("Checksum mismatch: expected $expected, got $actual")
                    )
                }
            }

            Result.success(destFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check for available updates by comparing installed plugin versions
     * against the marketplace.
     */
    suspend fun checkUpdates(installed: Map<String, String>): Result<List<Pair<String, String>>> {
        return fetchIndex().map { index ->
            val updates = mutableListOf<Pair<String, String>>()
            index.plugins.forEach { entry ->
                val installedVersion = installed[entry.id]
                if (installedVersion != null) {
                    val current = PluginVersion.parse(installedVersion)
                    val latest = PluginVersion.parse(entry.version)
                    if (latest > current) {
                        updates.add(entry.id to entry.version)
                    }
                }
            }
            updates
        }
    }

    /** Clear the in-memory cache (forces refresh on next fetchIndex). */
    fun clearCache() {
        cachedIndex = null
        lastEtag = null
        lastFetchTime = 0L
    }

    // ── Private helpers ───────────────────────────────────────────────

    private fun parseIndex(json: String): MarketplaceIndex {
        return try {
            val root = Json.parseToJsonElement(json).jsonObject
            MarketplaceIndex(
                marketplace = root["marketplace"]?.jsonPrimitive?.content ?: "",
                version = root["version"]?.jsonPrimitive?.int ?: 1,
                updated = root["updated"]?.jsonPrimitive?.content ?: "",
                plugins = root["plugins"]?.jsonArray?.map { parseEntry(it.jsonObject) } ?: emptyList()
            )
        } catch (e: Exception) {
            MarketplaceIndex()
        }
    }

    private fun parseEntry(obj: JsonObject): MarketplaceEntry = MarketplaceEntry(
        id = obj["id"]?.jsonPrimitive?.content ?: "",
        name = obj["name"]?.jsonPrimitive?.content ?: "",
        version = obj["version"]?.jsonPrimitive?.content ?: "0.0.0",
        type = try { PluginType.valueOf((obj["type"]?.jsonPrimitive?.content ?: "native").uppercase()) } catch (e: Exception) { PluginType.NATIVE },
        author = obj["author"]?.jsonPrimitive?.content ?: "",
        description = obj["description"]?.jsonPrimitive?.content ?: "",
        downloadUrl = obj["downloadUrl"]?.jsonPrimitive?.content ?: "",
        checksum = obj["checksum"]?.jsonPrimitive?.content ?: "",
        sizeBytes = obj["size"]?.jsonPrimitive?.long ?: 0L,
        minCoreVersion = obj["minCoreVersion"]?.jsonPrimitive?.content ?: "0.1.0",
        dependencies = obj["dependencies"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
        permissions = obj["permissions"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
        commands = obj["commands"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
    )

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /**
     * Check if a URL points to a private/internal network (SSRF prevention).
     * Blocks: localhost, 10.x, 172.16-31.x, 192.168.x, 127.x, 0.0.0.0, [::1]
     */
    private fun isPrivateUrl(url: String): Boolean {
        val host = try {
            val uri = java.net.URI(url)
            uri.host ?: return true // No host = suspicious
        } catch (e: Exception) { return true }

        return host == "localhost" ||
            host == "127.0.0.1" || host == "::1" || host == "0.0.0.0" ||
            host.startsWith("10.") ||
            host.startsWith("192.168.") ||
            host.startsWith("172.") && host.substringAfter("172.").substringBefore(".").toIntOrNull()?.let { it in 16..31 } == true ||
            host.startsWith("169.254.")  // link-local
    }
}
