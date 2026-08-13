// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.plugin

import com.mengpaw.kernel.KernelLog
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import java.io.File

/**
 * Built-in plugin.* CLI commands — plugin management is a core capability.
 * 命令集: marketplace/search/install/uninstall/list/info/enable/disable/
 * update/upgrade/auto/verify。
 */
class PluginExecutor(
    private val pluginManager: PluginManager,
    private val marketplaceClient: PluginMarketplaceClient = PluginMarketplaceClient(),
    /** Optional callback for download progress: (received, total). Only used by UI path. */
    var onDownloadProgress: ((Long, Long) -> Unit)? = null
) {
    /** Plugins that should never be auto-suspended (core functionality). */
    private val KEEP_AWAKE = setOf("agent-plugin")

    /** Plugins compiled into the APK — may not be uninstalled at runtime. */
    private val UNINSTALLABLE = setOf(
        "skill-plugin", "framework-plugin", "dev-plugin",
        "net-plugin", "clipboard-plugin",
        "memory-twin-plugin", "dream-plugin", "evolution-plugin"
    )

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "marketplace" to ::marketplace,
        "search" to ::search,
        "install" to ::install,
        "uninstall" to ::uninstall,
        "list" to ::list,
        "info" to ::info,
        "enable" to ::enable,
        "disable" to ::disable,
        "update" to ::update,
        "upgrade" to ::upgrade,
        "auto" to ::autoCmd,
        "verify" to ::verify
    )

    // ── Commands ──────────────────────────────────────────────────────

    private suspend fun marketplace(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val forceRefresh = args.contains("--refresh")
        return marketplaceClient.fetchIndex(forceRefresh).fold(
            onSuccess = { index ->
                val lines = mutableListOf(
                    "${index.marketplace} (v${index.version})",
                    "Updated: ${index.updated}",
                    "Plugins: ${index.plugins.size}",
                    ""
                )
                index.plugins.forEach { p ->
                    val installed = pluginManager.get(p.id)
                    val status = if (installed != null) {
                        val v = installed.metadata.version
                        if (v == p.version) "[已安装 v$v]"
                        else "[已安装 v$v, 可升级到 v${p.version}]"
                    } else "[未安装]"
                    val desc = if (p.description.isNotBlank()) " — ${p.description}" else ""
                    lines.add("• ${p.id} v${p.version} $status — ${p.name}$desc")
                }
                if (marketplaceClient.lastSnapshotServedAt > 0L) {
                    lines.add("")
                    lines.add("⚠ 离线缓存模式 — 索引来自磁盘快照 (网络不可达), 可浏览/安装但无法更新")
                }
                ExecutionResult.ok(lines.joinToString("\n"))
            },
            onFailure = { e ->
                ExecutionResult.fail(
                    "Marketplace unavailable: ${e.message}\n" +
                    "💡 建议:\n" +
                    "  • 使用 self.tools 查看已有命令\n" +
                    "  • 使用 plugin.list 查看已安装插件\n" +
                    "  • 检查网络连接 — 国内用户可能需要 VPN 访问 GitHub\n" +
                    "  • Gitee 镜像会自动启用，稍后重试",
                    errorCode = if (e is MarketplaceNetworkException) ErrorCodes.NETWORK_OFFLINE else ErrorCodes.ERR_INTERNAL
                )
            }
        )
    }

    private suspend fun search(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: plugin.search <query>", errorCode = ErrorCodes.ERR_INVALID_INPUT
        )
        val query = args.joinToString(" ")
        return marketplaceClient.search(query).fold(
            onSuccess = { results ->
                if (results.isEmpty()) ExecutionResult.ok("No plugins found for: $query")
                else ExecutionResult.ok(results.joinToString("\n") { "• ${it.id} v${it.version} — ${it.name}: ${it.description}" })
            },
            onFailure = { ExecutionResult.fail(
                "Search failed: ${it.message}\n" +
                "💡 使用 plugin.marketplace 浏览全部插件，或 self.tools 查看已有命令",
                errorCode = if (it is MarketplaceNetworkException) ErrorCodes.NETWORK_OFFLINE else ErrorCodes.ERR_INTERNAL
            ) }
        )
    }

    private suspend fun install(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        // Parse optional --from <url> before the plugin ID
        val fromIndex = args.indexOf("--from")
        val customIndexUrl: String? = if (fromIndex >= 0 && fromIndex + 1 < args.size) {
            args[fromIndex + 1]
        } else null
        // The plugin ID is the first arg that isn't --from or its value
        val positionalArgs = if (customIndexUrl != null) {
            args.toMutableList().apply { removeAt(fromIndex); removeAt(fromIndex) }
        } else args
        if (positionalArgs.isEmpty()) return ExecutionResult.fail(
            "Usage: plugin.install <id> [--from <url>]", errorCode = ErrorCodes.ERR_INVALID_INPUT
        )
        val id = positionalArgs[0]

        // Resolve entry from appropriate source
        val entry = if (customIndexUrl != null) {
            fetchFromCustomIndex(customIndexUrl, id).getOrElse {
                return ExecutionResult.fail("Custom marketplace unavailable or plugin not found: ${it.message}", errorCode = ErrorCodes.ERR_NOT_FOUND)
            }
        } else {
            marketplaceClient.getPlugin(id).getOrElse {
                return ExecutionResult.fail(
                    "Plugin not found in marketplace: $id",
                    errorCode = if (it is MarketplaceNetworkException) ErrorCodes.NETWORK_OFFLINE else ErrorCodes.ERR_NOT_FOUND
                )
            }
        }

        // FIX A11: Allow re-install for updates — only block if same version is already installed
        val installed = pluginManager.get(id)
        if (installed != null) {
            val updateAvailable = pluginManager.checkUpdate(id, entry.version)
            if (updateAvailable == null) {
                return ExecutionResult.ok("Plugin '$id' v${installed.metadata.version} is already up to date.")
            }
            // Uninstall old version first, then proceed with download
            pluginManager.uninstall(id)
        }

        // Download — uses entry.downloadUrl/mirrorUrl from whichever index it came from.
        // 统一下载到 PLUGIN_CACHE: 与 plugin.verify 检查点、PluginManager.uninstall 清理、
        // odex 目录 (jar 同级) 保持一致 — 此前写 workDir/plugins 导致安装后 verify 永远报缺失
        val destDir = File(com.mengpaw.kernel.DataPaths.PLUGIN_CACHE)
        val downloaded = marketplaceClient.download(entry, destDir, onDownloadProgress).getOrElse {
            val code = when (it) {
                is MarketplaceDownloadException -> ErrorCodes.DOWNLOAD_FAILED
                is MarketplaceNetworkException -> ErrorCodes.NETWORK_OFFLINE
                else -> ErrorCodes.ERR_INTERNAL
            }
            return ExecutionResult.fail("Download failed: ${it.message}", errorCode = code)
        }

        // Attempt runtime loading via DexClassLoader (实现见 PluginRuntimeLoader)
        // 语义: success=激活成功 / failure=明确激活失败 (报错, 不假安装) / null=环境不支持 (desktop 回退占位)
        val loadResult = PluginRuntimeLoader.load(pluginManager, downloaded, entry)
        return if (loadResult == null) {
            // v0.37.3 免激活体验: Android (dalvik 可用) 上 load==null = 真实激活失败 —
            // 明确报错可重试, 不再"下载完成但重启后生效"的假成功; 仅 JVM (无 dalvik,
            // desktop/测试) 保留注册元数据占位。
            if (dalvikAvailable()) {
                ExecutionResult.fail(
                    "安装失败: ${entry.name} v${entry.version} 无法激活 — " +
                    "插件产物依赖缺失或格式不正确, 请重试安装 (plugin.install ${entry.id})",
                    errorCode = ErrorCodes.ERR_INTERNAL
                )
            } else {
                try {
                    val dummyPlugin = object : com.mengpaw.kernel.plugin.Plugin {
                        override val metadata = com.mengpaw.kernel.plugin.PluginMetadata(
                            id = entry.id, name = entry.name, version = entry.version,
                            type = entry.type, author = entry.author, description = entry.description,
                            permissions = entry.permissions, dependencies = entry.dependencies,
                            commands = entry.commands
                        )
                        override val commands: Map<String, com.mengpaw.kernel.plugin.CommandHandler> = emptyMap()
                    }
                    pluginManager.install(dummyPlugin).getOrThrow()
                    ExecutionResult.ok(
                        "✅ ${entry.name} v${entry.version} 下载完成 (桌面环境, 运行时加载暂不支持)"
                    )
                } catch (metaErr: Exception) {
                    ExecutionResult.fail(
                        "Downloaded ${entry.id} v${entry.version} but activation failed.\n" +
                        "This plugin requires a DEX-packaged release or pre-compilation.",
                        errorCode = ErrorCodes.ERR_INTERNAL
                    )
                }
            }
        } else {
            loadResult.fold(
                onSuccess = {
                    val ns = pluginNamespaceFor(entry.id)
                    val cmdList = entry.commands.joinToString(", ") { it.removePrefix("$ns.") }
                    val sourceNote = if (customIndexUrl != null) "\n来源: $customIndexUrl" else ""
                    ExecutionResult.ok(
                        "✅ ${entry.name} v${entry.version} 安装成功$sourceNote\n" +
                        "命令: $cmdList\n" +
                        "💡 skill.run plugin-index 查看插件手册索引\n" +
                        "💡 self.tools $ns 验证命令已注册\n" +
                        "💡 plugin.info ${entry.id} 查看完整文档"
                    )
                },
                onFailure = {
                    ExecutionResult.fail(
                        "安装失败: ${it.message ?: "插件激活失败"}",
                        errorCode = ErrorCodes.ERR_INTERNAL
                    )
                }
            )
        }
    }

    /** Fetch a single plugin entry from a custom marketplace index URL. */
    private suspend fun fetchFromCustomIndex(indexUrl: String, pluginId: String): Result<MarketplaceEntry> {
        return marketplaceClient.fetchIndexFrom(indexUrl).map { index ->
            index.plugins.find { it.id == pluginId }
                ?: throw NoSuchElementException("Plugin '$pluginId' not found in custom marketplace at $indexUrl")
        }
    }

    private suspend fun uninstall(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: plugin.uninstall <id>", errorCode = ErrorCodes.ERR_INVALID_INPUT
        )
        val id = args[0]
        if (id in UNINSTALLABLE) {
            return ExecutionResult.fail(
                "$id 是内置插件，已编译在 APK 中，不可卸载。使用 plugin.disable 可临时禁用。",
                errorCode = ErrorCodes.ERR_PERMISSION_DENIED
            )
        }
        return pluginManager.uninstall(id).fold(
            onSuccess = { ExecutionResult.ok("Uninstalled: $id") },
            onFailure = { ExecutionResult.fail("Uninstall failed: ${it.message}", errorCode = ErrorCodes.ERR_NOT_FOUND) }
        )
    }

    private suspend fun list(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val all = pluginManager.listAll()
        if (all.isEmpty()) return ExecutionResult.ok("(No plugins installed)\nUse plugin.marketplace to browse available plugins.")
        return ExecutionResult.ok("Installed plugins (${all.size}):\n" + all.joinToString("\n") { (plugin, status) ->
            "  ${statusIcon(status)} ${plugin.metadata.id} v${plugin.metadata.version} [$status] — ${plugin.metadata.name}"
        } + "\n\nActive: ${pluginManager.activeCount()}/${pluginManager.count()}")
    }

    private suspend fun info(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: plugin.info <id>", errorCode = ErrorCodes.ERR_INVALID_INPUT
        )
        val plugin = pluginManager.get(args[0])
            ?: return ExecutionResult.fail("Plugin not found: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
        val status = pluginManager.status(args[0])
        return ExecutionResult.ok(buildString {
            appendLine("Plugin: ${plugin.metadata.id}")
            appendLine("Name: ${plugin.metadata.name}")
            appendLine("Version: ${plugin.metadata.version}")
            appendLine("Type: ${plugin.metadata.type}")
            appendLine("Author: ${plugin.metadata.author}")
            appendLine("Status: $status")
            appendLine("Description: ${plugin.metadata.description}")
            appendLine("Dependencies: ${plugin.metadata.dependencies.ifEmpty { listOf("(none)") }}")
            appendLine("Permissions: ${plugin.metadata.permissions.ifEmpty { listOf("(none)") }}")
            appendLine("Commands: ${plugin.metadata.commands.ifEmpty { listOf("(none)") }}")
            // Also check marketplace entry for sizeBytes (not in PluginMetadata)
            try {
                val entry = marketplaceClient.getPlugin(plugin.metadata.id).getOrNull()
                if (entry != null && entry.sizeBytes > 0) {
                    val size = when { entry.sizeBytes >= 1_048_576 -> "%.1f MB".format(entry.sizeBytes / 1_048_576.0); else -> "%.1f KB".format(entry.sizeBytes / 1024.0) }
                    appendLine("Size: $size")
                }
            } catch (e: Exception) { KernelLog.w("PluginExecutor", "info: ${e.message}") }
        })
    }

    private suspend fun enable(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: plugin.enable <id>", errorCode = ErrorCodes.ERR_INVALID_INPUT
        )
        return pluginManager.activate(args[0]).fold(
            onSuccess = { ExecutionResult.ok("Enabled: ${args[0]}") },
            onFailure = { ExecutionResult.fail("Enable failed: ${it.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
        )
    }

    private suspend fun disable(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: plugin.disable <id>", errorCode = ErrorCodes.ERR_INVALID_INPUT
        )
        return pluginManager.deactivate(args[0]).fold(
            onSuccess = { ExecutionResult.ok("Disabled: ${args[0]}") },
            onFailure = { ExecutionResult.fail("Disable failed: ${it.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
        )
    }

    private suspend fun update(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: plugin.update <id>", errorCode = ErrorCodes.ERR_INVALID_INPUT
        )
        val id = args[0]
        val installed = pluginManager.get(id)
            ?: return ExecutionResult.fail("Not installed: $id", errorCode = ErrorCodes.ERR_NOT_FOUND)

        val entry = marketplaceClient.getPlugin(id).getOrElse {
            return ExecutionResult.fail(
                "Not found in marketplace: $id",
                errorCode = if (it is MarketplaceNetworkException) ErrorCodes.NETWORK_OFFLINE else ErrorCodes.ERR_NOT_FOUND
            )
        }

        val update = pluginManager.checkUpdate(id, entry.version)
        return if (update != null) {
            val cl = if (entry.changelog.isNotBlank()) "\n\nChangelog (v$update):\n${entry.changelog}" else ""
            ExecutionResult.ok("Update available: $id v${installed.metadata.version} → v$update\nUse plugin.install to download the latest version.$cl")
        } else {
            ExecutionResult.ok("$id v${installed.metadata.version} is up to date.")
        }
    }

    private suspend fun upgrade(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (!args.contains("--all")) return ExecutionResult.fail(
            "Usage: plugin.upgrade --all", errorCode = ErrorCodes.ERR_INVALID_INPUT
        )

        val installed = pluginManager.listAll().associate { (p, _) -> p.metadata.id to p.metadata.version }
        return marketplaceClient.checkUpdates(installed).fold(
            onSuccess = { updates ->
                if (updates.isEmpty()) ExecutionResult.ok("All plugins are up to date.")
                else ExecutionResult.ok("Updates available (${updates.size}):\n" +
                    updates.joinToString("\n") { (id, ver) -> "  • $id → v$ver" } +
                    "\n\nUse plugin.update <id> to upgrade individually.")
            },
            onFailure = { ExecutionResult.fail("Upgrade check failed: ${it.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
        )
    }

    /**
     * Auto power-save: enable plugins on demand, auto-suspend idle ones.
     * plugin.auto wake <id>    — wake a sleeping plugin for immediate use
     * plugin.auto sleep <id>   — suspend to save power
     * plugin.auto status       — show power state of all plugins
     * plugin.auto sleep-idle   — suspend all plugins idle > 10min
     */
    private suspend fun autoCmd(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: plugin.auto wake|sleep|status|sleep-idle", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        when (args[0]) {
            "wake" -> {
                if (args.size < 2) return ExecutionResult.fail("Usage: plugin.auto wake <id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
                return pluginManager.activate(args[1]).fold(
                    onSuccess = { ExecutionResult.ok("Woke: ${args[1]}") },
                    onFailure = { ExecutionResult.fail("Wake failed: ${it.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
                )
            }
            "sleep" -> {
                if (args.size < 2) return ExecutionResult.fail("Usage: plugin.auto sleep <id>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
                return pluginManager.deactivate(args[1]).fold(
                    onSuccess = { ExecutionResult.ok("Sleep: ${args[1]}") },
                    onFailure = { ExecutionResult.fail("Sleep failed: ${it.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
                )
            }
            "status" -> {
                val all = pluginManager.listAll()
                return ExecutionResult.ok(all.joinToString("\n") { (p, s) ->
                    "${statusIcon(s)} ${p.metadata.id} [${s}] ${p.metadata.name}"
                } + "\n\nActive: ${pluginManager.activeCount()}/${pluginManager.count()} | " +
                  "Sleep: ${pluginManager.count() - pluginManager.activeCount()}")
            }
            "sleep-idle" -> {
                var count = 0
                pluginManager.listAll().forEach { (p, s) ->
                    if (s == PluginStatus.ACTIVE && p.metadata.id !in KEEP_AWAKE) {
                        pluginManager.deactivate(p.metadata.id)
                        count++
                    }
                }
                return ExecutionResult.ok("Suspended $count idle plugins to save power. Kept awake: ${KEEP_AWAKE.joinToString()}")
            }
        }
        return ExecutionResult.fail("Usage: plugin.auto wake|sleep|status|sleep-idle", errorCode = ErrorCodes.ERR_INVALID_INPUT)
    }

    /** plugin.verify <id> — check JAR/Odex filesystem state. plugin.verify --all for batch check. */
    private suspend fun verify(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        // --all: check all installed plugins
        if (args.contains("--all")) {
            val all = pluginManager.listAll()
            if (all.isEmpty()) return ExecutionResult.ok("(No plugins installed)")
            val results = all.map { (plugin, _) -> PluginRuntimeLoader.verifyOne(plugin.metadata.id, plugin.metadata.version) }
            val ok = results.count { it.second }
            return ExecutionResult.ok(
                "Verified ${results.size} plugins: $ok OK, ${results.size - ok} missing\n" +
                results.joinToString("\n") { (msg, _) -> msg }
            )
        }

        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: plugin.verify <id> | plugin.verify --all", errorCode = ErrorCodes.ERR_INVALID_INPUT
        )
        val id = args[0]
        val plugin = pluginManager.get(id)
            ?: return ExecutionResult.fail("Plugin not found: $id", errorCode = ErrorCodes.ERR_NOT_FOUND)
        val (msg, ok) = PluginRuntimeLoader.verifyOne(id, plugin.metadata.version)
        return if (ok) ExecutionResult.ok(msg) else ExecutionResult.fail(msg, errorCode = ErrorCodes.ERR_NOT_FOUND)
    }

    private fun statusIcon(status: PluginStatus): String = when (status) {
        PluginStatus.ACTIVE -> "✅"
        PluginStatus.INSTALLED -> "📦"
        PluginStatus.DISABLED -> "⛔"
        PluginStatus.DOWNLOADED -> "⬇️"
        PluginStatus.ERROR -> "❌"
    }

    /** Android 运行时是否可用 dalvik DexClassLoader (JVM/desktop 为 false)。 */
    private fun dalvikAvailable(): Boolean = try {
        Class.forName("dalvik.system.DexClassLoader")
        true
    } catch (_: ClassNotFoundException) {
        false
    }
}
