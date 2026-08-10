// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.acp

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.error.ErrorCollector
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64

/**
 * 舰队文件互传 handler (v0.36) — 任意格式文件经局域网 Fleet共享 目录互传。
 * 接收方: 路径消毒 → base64 解码 → 原子落盘 `{BASE}/Fleet共享/` → 校验大小上限。
 * 与孪生同步 (仅 .md) 无关 — 这是 Fleet 模式的一部分。
 */
class FleetFileHandler : AcpHandler {

    override val supportedTypes: List<AcpMessageType> = listOf(AcpMessageType.FLEET_FILE)

    private val json = Json { ignoreUnknownKeys = true }

    /** 单文件上限 64MB (base64 后 ~85MB, HTTP body 可承载)。 */
    companion object {
        const val MAX_FILE_BYTES = 64L * 1024 * 1024
    }

    override suspend fun handle(message: AcpMessage, server: AcpServer): AcpResult? {
        val payload = try { json.parseToJsonElement(message.payload).jsonObject } catch (_: Exception) { null }
            ?: return AcpResult(false, "invalid_fleet_file")
        val fileName = payload["fileName"]?.jsonPrimitive?.content ?: return AcpResult(false, "no_file_name")
        val contentBase64 = payload["content"]?.jsonPrimitive?.content ?: ""
        val declaredSize = payload["size"]?.jsonPrimitive?.long ?: 0L
        val declaredSha = payload["sha256"]?.jsonPrimitive?.content ?: ""

        val safeName = sanitizeFleetFileName(fileName) ?: return AcpResult(false, "invalid_file_name")
        if (declaredSize <= 0 || declaredSize > MAX_FILE_BYTES) {
            return AcpResult(false, "file_size_rejected", "上限 ${MAX_FILE_BYTES / 1024 / 1024}MB")
        }

        return try {
            val bytes = Base64.getDecoder().decode(contentBase64)
            if (bytes.size.toLong() != declaredSize) return AcpResult(false, "size_mismatch")
            if (declaredSha.isNotBlank() && sha256Hex(bytes) != declaredSha) {
                return AcpResult(false, "sha_mismatch")
            }
            val dir = File(DataPaths.FLEET_SHARE).also { it.mkdirs() }
            val target = File(dir, safeName)
            val tmp = File(dir, "$safeName.tmp")
            tmp.writeBytes(bytes)
            java.nio.file.Files.move(
                tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
            if (tmp.exists()) try { tmp.delete() } catch (_: Exception) {}
            AcpResult(true, "file_saved", safeName)
        } catch (e: Exception) {
            ErrorCollector.report(e, "FleetFileHandler.save")
            AcpResult(false, "save_failed", e.message?.take(120) ?: "unknown")
        }
    }

    /** 文件名消毒 — 仅允许单段安全文件名, 拒绝路径分隔符/../盘符 (防穿越)。 */
    internal fun sanitizeFleetFileName(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isBlank() || trimmed.length > 128) return null
        if (trimmed.startsWith("/") || trimmed.startsWith("\\")) return null
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")) return null
        if (trimmed.contains(":")) return null
        return trimmed
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
    }
}
