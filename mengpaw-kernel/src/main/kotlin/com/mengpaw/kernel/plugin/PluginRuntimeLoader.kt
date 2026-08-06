// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.plugin

import com.mengpaw.kernel.KernelLog
import java.io.File

/**
 * 插件运行时加载/文件完整性核查（自 PluginExecutor 拆出 — 400 行文件拆分批次 1）。
 *
 * 职责:
 * - [load]: DexClassLoader 运行时加载插件 JAR (SHA256 校验 → 候选类名反射实例化 → install+activate)
 * - [verifyOne]: 磁盘 JAR/AAR/odex 状态检查 (plugin.verify 用)
 */
internal object PluginRuntimeLoader {

    /**
     * Attempts to load a plugin JAR/DEX at runtime using DexClassLoader.
     *
     * On Android, dynamically loaded code must be in DEX format (not raw JAR class files).
     * Plugins distributed via the marketplace should be packaged as DEX-containing JARs.
     * Returns a success message on load, or null if loading is not possible.
     */
    suspend fun load(pluginManager: PluginManager, jarFile: File, entry: MarketplaceEntry): String? {
        return try {
            // ── SHA256 integrity verification BEFORE loading ──
            // Verify that the downloaded JAR matches the expected checksum from
            // the marketplace index. This prevents loading tampered/malicious code.
            if (entry.checksum.isNotBlank()) {
                val expected = entry.checksum.removePrefix("sha256:")
                if (!expected.matches(Regex("^[0-9a-fA-F]+$"))) {
                    KernelLog.w("PluginExecutor", "Unexpected checksum format: ${expected.take(16)}...")
                }
                val actual = sha256Hex(jarFile.readBytes())
                if (!actual.equals(expected, ignoreCase = true)) {
                    KernelLog.w("PluginExecutor", "SHA256 mismatch for ${entry.id}: expected $expected, got $actual")
                    return "Integrity check failed for ${entry.id}: JAR checksum does not match marketplace entry. The file may be corrupted or tampered."
                }
            } else {
                KernelLog.w("PluginExecutor", "No checksum in marketplace entry for ${entry.id} — skipping integrity verification (UNTRUSTED)")
            }

            val optimizedDir = File(jarFile.parentFile, "odex-${entry.id}")
            optimizedDir.mkdirs()

            // Try multiple class name patterns: PascalCase by convention, then PluginMain fallback
            val ns = pluginNamespaceFor(entry.id)
            val pascalNs = ns.replaceFirstChar { it.uppercase() }
            val candidateNames = listOf(
                "com.mengpaw.plugin.$ns.${pascalNs}Plugin",  // e.g. TavilyPlugin
                "com.mengpaw.plugin.$ns.PluginMain",          // legacy
            )

            // Use DexClassLoader via reflection (Android-only; safe fallback on JVM)
            var pluginInstance: Any? = null
            var loadedClass: String? = null
            try {
                val dexLoaderClass = Class.forName("dalvik.system.DexClassLoader")
                val dexLoader = dexLoaderClass.getConstructor(
                    String::class.java, String::class.java, String::class.java, ClassLoader::class.java
                ).newInstance(jarFile.absolutePath, optimizedDir.absolutePath, null, Plugin::class.java.classLoader)
                for (name in candidateNames) {
                    try {
                        val pluginClass = dexLoaderClass.getMethod("loadClass", String::class.java).invoke(dexLoader, name) as Class<*>
                        pluginInstance = pluginClass.getDeclaredConstructor().newInstance()
                        loadedClass = name
                        break
                    } catch (_: ClassNotFoundException) { KernelLog.w("PluginExecutor", "class not found in candidate list"); /* try next */ }
                }
            } catch (_: ClassNotFoundException) {
                KernelLog.w("PluginExecutor", "DexClassLoader not available (JVM/desktop)")
                null // dalvik not available (JVM/desktop) — JAR loading not supported
            }

            if (pluginInstance == null) return null
            if (pluginInstance !is Plugin) {
                return "Plugin class $loadedClass does not implement Plugin interface"
            }

            pluginManager.install(pluginInstance).getOrThrow()
            pluginManager.activate(entry.id).getOrThrow()

            "Downloaded and activated ${entry.id} v${entry.version} (runtime-loaded via DexClassLoader)"
        } catch (e: ClassNotFoundException) {
            KernelLog.w("PluginExecutor", "loadPluginJar(${entry.id}): dalvik DexClassLoader not available (JVM/desktop)")
            null
        } catch (e: NoClassDefFoundError) {
            KernelLog.w("PluginExecutor", "loadPluginJar(${entry.id}): missing class dependency: ${e.message}")
            null
        } catch (e: Exception) {
            KernelLog.w("PluginExecutor", "loadPluginJar(${entry.id}): ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    /** Check one plugin's files on disk. Returns (message, isOk). */
    fun verifyOne(id: String, version: String): Pair<String, Boolean> {
        val cacheDir = File(com.mengpaw.kernel.DataPaths.PLUGIN_CACHE)
        val jarFile = File(cacheDir, "$id-$version.jar")
        val aarFile = File(cacheDir, "$id-$version.aar")
        val odexDir = File(cacheDir, "odex-$id")

        val file = when {
            jarFile.exists() -> jarFile
            aarFile.exists() -> aarFile
            else -> null
        }

        val odexExists = odexDir.exists() && odexDir.isDirectory
        val odexCount = if (odexExists) odexDir.listFiles()?.size ?: 0 else 0

        return if (file != null) {
            val sizeMb = "%.1f".format(file.length() / 1_048_576.0)
            val sha = try {
                sha256Hex(file.readBytes()).take(16) + "..."
            } catch (e: Exception) { KernelLog.w("PluginExecutor", "verifyOne sha: ${e.message}"); "n/a" }
            val odexInfo = if (odexExists) ", odex: ${odexCount} files" else ", odex: missing"
            "✅ $id v$version: ${file.name} (${sizeMb}MB, sha256=$sha$odexInfo)" to true
        } else {
            "❌ $id v$version: no JAR/AAR found in ${cacheDir.absolutePath}" to false
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        // Locale.ROOT: 默认 Locale 下 %02x 输出畸形 (阿拉伯语设备 — P2 修复)
        return md.digest(bytes).joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
    }
}
