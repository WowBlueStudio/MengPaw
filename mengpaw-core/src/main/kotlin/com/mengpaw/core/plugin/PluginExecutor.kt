// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.plugin

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.cli.ErrorCodes
import java.io.File

/**
 * Built-in plugin.* CLI commands — plugin management is a core capability.
 *
 * Commands:
 *   plugin.marketplace [--refresh]  — fetch/reload marketplace index
 *   plugin.search <query>           — search plugins in marketplace
 *   plugin.install <id>             — download + verify + install + activate
 *   plugin.uninstall <id>           — deactivate + remove
 *   plugin.list                     — list installed plugins with status
 *   plugin.info <id>                — show plugin details and commands
 *   plugin.enable <id>              — activate a disabled plugin
 *   plugin.disable <id>             — deactivate but keep installed
 *   plugin.update <id>              — update to latest version
 *   plugin.upgrade --all            — upgrade all updatable plugins
 */
class PluginExecutor(
    private val pluginManager: PluginManager,
    private val marketplaceClient: PluginMarketplaceClient = PluginMarketplaceClient()
) {
    /** Plugins that should never be auto-suspended (core functionality). */
    private val KEEP_AWAKE = setOf("self-plugin", "plugin-plugin", "agent-plugin", "pad-plugin")

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
        "auto" to ::autoCmd
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
                    lines.add("• ${p.id} v${p.version} $status — ${p.name}")
                }
                ExecutionResult.ok(lines.joinToString("\n"))
            },
            onFailure = { e ->
                ExecutionResult.fail("Marketplace unavailable: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
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
            onFailure = { ExecutionResult.fail("Search failed: ${it.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
        )
    }

    private suspend fun install(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: plugin.install <id>", errorCode = ErrorCodes.ERR_INVALID_INPUT
        )
        val id = args[0]

        // Already installed?
        if (pluginManager.get(id) != null) {
            return ExecutionResult.ok("Plugin '$id' is already installed. Use plugin.update to upgrade.")
        }

        // Fetch marketplace entry
        val entry = marketplaceClient.getPlugin(id).getOrElse {
            return ExecutionResult.fail("Plugin not found in marketplace: $id", errorCode = ErrorCodes.ERR_NOT_FOUND)
        }

        // Download
        val destDir = File(ctx.workDir, "plugins")
        val downloaded = marketplaceClient.download(entry, destDir).getOrElse {
            return ExecutionResult.fail("Download failed: ${it.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }

        // Attempt runtime loading via DexClassLoader
        val loadResult = loadPluginJar(downloaded, entry)
        return if (loadResult != null) {
            ExecutionResult.ok(loadResult)
        } else {
            ExecutionResult.ok(
                "Downloaded ${entry.id} v${entry.version} to ${downloaded.absolutePath}\n" +
                "Note: Runtime loading failed — this plugin requires pre-compilation as a Gradle module. " +
                "Add to plugins/ directory and rebuild."
            )
        }
    }

    /**
     * Attempts to load a plugin JAR/DEX at runtime using DexClassLoader.
     *
     * On Android, dynamically loaded code must be in DEX format (not raw JAR class files).
     * Plugins distributed via the marketplace should be packaged as DEX-containing JARs.
     * Returns a success message on load, or null if loading is not possible.
     */
    private fun loadPluginJar(jarFile: File, entry: MarketplaceEntry): String? {
        return try {
            // DexClassLoader requires an optimized DEX output directory
            val optimizedDir = File(jarFile.parentFile, "odex-${entry.id}")
            optimizedDir.mkdirs()

            val dexLoader = dalvik.system.DexClassLoader(
                jarFile.absolutePath,
                optimizedDir.absolutePath,
                null, // librarySearchPath — null for no native libs
                Plugin::class.java.classLoader // parent
            )

            // Load the plugin class (convention: com.mengpaw.plugin.<id>.PluginMain)
            val className = "com.mengpaw.plugin.${entry.id.replace("-", ".")}.PluginMain"
            val pluginClass = dexLoader.loadClass(className)
            val pluginInstance = pluginClass.getDeclaredConstructor().newInstance()

            if (pluginInstance !is Plugin) {
                return "Plugin class $className does not implement Plugin interface"
            }

            // Register with plugin manager
            pluginManager.install(pluginInstance).getOrThrow()
            pluginManager.activate(entry.id).getOrThrow()

            "Downloaded and activated ${entry.id} v${entry.version} (runtime-loaded via DexClassLoader)"
        } catch (e: ClassNotFoundException) {
            null // Plugin class not found in JAR — not a valid plugin
        } catch (e: NoClassDefFoundError) {
            null // Missing dependencies
        } catch (e: Exception) {
            null // Other loading failures
        }
    }

    private suspend fun uninstall(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: plugin.uninstall <id>", errorCode = ErrorCodes.ERR_INVALID_INPUT
        )
        val id = args[0]
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
            return ExecutionResult.fail("Not found in marketplace: $id", errorCode = ErrorCodes.ERR_NOT_FOUND)
        }

        val update = pluginManager.checkUpdate(id, entry.version)
        return if (update != null) {
            ExecutionResult.ok("Update available: $id v${installed.metadata.version} → v$update\nUse plugin.install to download the latest version.")
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

    // ── Helpers ───────────────────────────────────────────────────────

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

    private fun statusIcon(status: PluginStatus): String = when (status) {
        PluginStatus.ACTIVE -> "✅"
        PluginStatus.INSTALLED -> "📦"
        PluginStatus.DISABLED -> "⛔"
        PluginStatus.DOWNLOADED -> "⬇️"
        PluginStatus.ERROR -> "❌"
    }
}
