// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.framework

import com.mengpaw.kernel.DataPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** 框架指纹持久化 — JSON 原子读写。 */
object FrameworkPeerStore {
    private const val FILE_NAME = "framework_peers.json"

    private val file: File get() = File(DataPaths.CONFIG, FILE_NAME)

    data class FrameworkPeer(
        val fingerprint: String,
        val name: String,
        val version: String,
        val frameworkName: String = "MengPaw",
        val address: String,
        val port: Int = 9876,
        val capabilities: List<String> = emptyList(),
        val agents: List<String> = emptyList(),
        val lastSeen: Long = System.currentTimeMillis(),
        val trusted: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("fingerprint", fingerprint)
            put("name", name)
            put("version", version)
            put("frameworkName", frameworkName)
            put("address", address)
            put("port", port)
            put("capabilities", JSONArray(capabilities))
            put("agents", JSONArray(agents))
            put("lastSeen", lastSeen)
            put("trusted", trusted)
        }

        companion object {
            fun fromJson(obj: JSONObject): FrameworkPeer = FrameworkPeer(
                fingerprint = obj.optString("fingerprint", ""),
                name = obj.optString("name", ""),
                version = obj.optString("version", ""),
                frameworkName = obj.optString("frameworkName", "MengPaw"),
                address = obj.optString("address", ""),
                port = obj.optInt("port", 9876),
                capabilities = obj.optJSONArray("capabilities")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                agents = obj.optJSONArray("agents")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                lastSeen = obj.optLong("lastSeen", 0L),
                trusted = obj.optBoolean("trusted", false)
            )
        }
    }

    fun loadAll(): List<FrameworkPeer> {
        return try {
            if (!file.exists() || file.length() == 0L) return emptyList()
            val text = file.readText()
            if (text.isBlank()) return emptyList()
            val arr = JSONArray(text)
            (0 until arr.length()).map { FrameworkPeer.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    fun save(peer: FrameworkPeer) {
        val all = loadAll().toMutableList()
        all.removeAll { it.fingerprint == peer.fingerprint }
        all.add(peer)
        writeAll(all)
    }

    fun remove(fingerprint: String) {
        val all = loadAll().filter { it.fingerprint != fingerprint }
        writeAll(all)
    }

    fun findByFingerprint(fp: String): FrameworkPeer? = loadAll().find { it.fingerprint == fp }
    fun findByName(name: String): FrameworkPeer? = loadAll().find { it.name == name }

    private fun writeAll(peers: List<FrameworkPeer>) {
        try {
            file.parentFile?.mkdirs()
            val arr = JSONArray()
            peers.forEach { arr.put(it.toJson()) }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(arr.toString(2))
            tmp.renameTo(file)
            if (tmp.exists()) { try { tmp.delete() } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    /** 生成框架指纹 = SHA256(名称 + 设备ID前缀) */
    fun computeFingerprint(name: String, deviceId: String): String {
        val input = "$name|$deviceId"
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).take(8)
            .joinToString("") { "%02x".format(it) }
    }
}
