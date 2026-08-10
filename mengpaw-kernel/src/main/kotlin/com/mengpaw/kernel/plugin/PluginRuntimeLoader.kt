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

            // ── 宿主可加载产物检查 (v0.35.6) ──
            // DexClassLoader 只接受含 classes.dex 的容器 (dex/jar/apk)。标准 AAR 内是
            // classes.jar (JVM 字节码), 直接传入必然加载失败 → 给出可操作提示而非静默假安装。
            val zipEntries = try {
                java.util.zip.ZipFile(jarFile).use { zip ->
                    zip.entries().asSequence().map { it.name }.toList()
                }
            } catch (_: Exception) {
                emptyList()
            }
            if (zipEntries.none { it == "classes.dex" }) {
                return "无法激活 ${entry.id}: 发布产物不含 classes.dex (DexClassLoader 只接受 dex 容器)。" +
                    "标准 AAR 不可用 — 请发布 fat dex JAR (连接器见 mengpaw-connectors/scripts/package-connectors.ps1)。"
            }

            val optimizedDir = File(jarFile.parentFile, "odex-${entry.id}")
            optimizedDir.mkdirs()

            // 主类定位: META-INF/plugin-class 清单优先 (支持任意包名/类名, 连接器等
            // 含连字符命名空间的插件), 回退 PascalCase 约定 + PluginMain legacy。
            val ns = pluginNamespaceFor(entry.id)
            val pascalNs = ns.replaceFirstChar { it.uppercase() }
            val manifestClass = readPluginClass(jarFile)
            val candidateNames = buildList {
                manifestClass?.let { add(it) }
                add("com.mengpaw.plugin.$ns.${pascalNs}Plugin")  // e.g. TavilyPlugin
                add("com.mengpaw.plugin.$ns.PluginMain")          // legacy
            }

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

    /**
     * 读插件 JAR 内 META-INF/plugin-class 主类清单 (发布工具写入, 见连接器仓库
     * scripts/package-connectors.ps1)。不存在/损坏时回退 null → 走候选类名规则。
     */
    internal fun readPluginClass(jarFile: File): String? = try {
        java.util.zip.ZipFile(jarFile).use { zip ->
            val entry = zip.getEntry("META-INF/plugin-class") ?: return null
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText().trim().ifBlank { null }
            }
        }
    } catch (_: Exception) {
        null
    }
}
