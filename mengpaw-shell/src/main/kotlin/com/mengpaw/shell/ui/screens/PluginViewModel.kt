// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mengpaw.kernel.plugin.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

/**
 * Install progress state for UI rendering.
 */
sealed class InstallState {
    data object Idle : InstallState()
    data class Downloading(val progress: Float) : InstallState()
    data object Verifying : InstallState()
    data class Installing(val step: String) : InstallState()
    data class Done(val pluginId: String) : InstallState()
    data class Failed(val error: String) : InstallState()
}

/**
 * Plugin suggestion triggered when Agent tries to use an uninstalled command.
 */
data class PluginSuggestion(
    val namespace: String,
    val pluginId: String,
    val pluginName: String,
    val description: String,
    val missingCommand: String
)

/**
 * Whether a plugin can be installed from the marketplace.
 */
enum class PluginAvailability {
    /** Already compiled into the APK — no download needed. */
    BUILTIN,
    /** Available for download from the marketplace. */
    DOWNLOADABLE,
    /** Listed but not yet released. */
    UNAVAILABLE,
    /** 已嵌入引擎 — 内置功能，不可卸载 */
    EMBEDDED
}

/**
 * UI-ready plugin item combining marketplace info with install status.
 */
data class PluginUiItem(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val type: PluginType,
    val author: String,
    val permissions: List<String>,
    val commands: List<String>,
    val isInstalled: Boolean,
    val isActive: Boolean,
    val installState: InstallState = InstallState.Idle,
    val availability: PluginAvailability = PluginAvailability.BUILTIN
)

/**
 * ViewModel bridging the plugin system to Compose UI.
 *
 * Connects PluginManager (lifecycle) + PluginMarketplaceClient (market) +
 * PluginExecutor (CLI) into observable UI state.
 */
class PluginViewModel : ViewModel() {

    private val pluginManager = PluginManager.globalInstance
    private val marketplace = PluginMarketplaceClient()

    /** Last-seen marketplace "updated" timestamp — used to detect remote version changes. */
    private var lastRemoteUpdated: String = ""

    // ── Observable state ──────────────────────────────────────────────

    private val _marketplacePlugins = MutableStateFlow<List<MarketplaceEntry>>(emptyList())
    val marketplacePlugins: StateFlow<List<MarketplaceEntry>> = _marketplacePlugins.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _installStates = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    val installStates: StateFlow<Map<String, InstallState>> = _installStates.asStateFlow()

    private val _suggestions = MutableSharedFlow<PluginSuggestion>()
    val suggestions: SharedFlow<PluginSuggestion> = _suggestions.asSharedFlow()

    /** Combined UI items: marketplace + install status. */
    val pluginItems: StateFlow<List<PluginUiItem>> = combine(
        _marketplacePlugins, _installStates, _searchQuery
    ) { plugins, states, query ->
        plugins
            .filter { entry ->
                query.isBlank() ||
                entry.id.contains(query, ignoreCase = true) ||
                entry.name.contains(query, ignoreCase = true) ||
                entry.description.contains(query, ignoreCase = true)
            }
            .map { entry ->
                val installed = pluginManager.get(entry.id)
                val status = pluginManager.status(entry.id)
                PluginUiItem(
                    id = entry.id,
                    name = entry.name,
                    version = entry.version,
                    description = entry.description,
                    type = entry.type,
                    author = entry.author,
                    permissions = entry.permissions,
                    commands = entry.commands,
                    isInstalled = installed != null,
                    isActive = status == PluginStatus.ACTIVE,
                    installState = states[entry.id] ?: InstallState.Idle,
                    availability = when {
                        entry.status == "embedded" -> PluginAvailability.EMBEDDED
                        entry.status == "deprecated" -> PluginAvailability.UNAVAILABLE
                        // Only pluginClassRegistry reflects actually compiled-in plugins
                        pluginClassRegistry.containsKey(entry.id) -> PluginAvailability.BUILTIN
                        entry.isDownloadable -> PluginAvailability.DOWNLOADABLE
                        else -> PluginAvailability.UNAVAILABLE
                    }
                )
            }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val installedCount: StateFlow<Int> = pluginItems
        .map { items -> items.count { it.isInstalled } }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val activeCount: StateFlow<Int> = pluginItems
        .map { items -> items.count { it.isActive } }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    /** Active plugin UI buttons grouped by placement. Lazily initialized, refreshed on plugin state changes. */
    val activeButtons: Map<com.mengpaw.kernel.plugin.ButtonPlacement, List<com.mengpaw.kernel.plugin.PluginUiButton>> get() {
        return pluginManager.getActiveButtons().groupBy({ it.second.placement }, { it.second })
    }

    // ── Actions ───────────────────────────────────────────────────────

    /** Local cache file for offline/instant loading. */
    private val cacheFile: File
        get() = java.io.File(com.mengpaw.kernel.DataPaths.PLUGIN_CACHE, "marketplace_cache.json")

    /** Load cached plugins from disk — instant display while fetching online. */
    private fun loadCachedPlugins() {
        try {
            if (cacheFile.exists()) {
                val pluginList = marketplace.parseIndex(cacheFile.readText())
                _marketplacePlugins.value = pluginList.plugins
                registerBuiltins(pluginList)
            }
        } catch (_: Exception) { }
    }

    private fun saveCachedPlugins(rawJson: String) {
        try {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(rawJson)
        } catch (_: Exception) { }
    }

    /** Fetch marketplace index. Shows cached list instantly, then refreshes in background. */
    fun refreshMarketplace(forceRefresh: Boolean = true) {
        // Show cached data immediately on first open
        if (_marketplacePlugins.value.isEmpty()) loadCachedPlugins()
        viewModelScope.launch {
            _isLoading.value = true
            // fetchIndexRaw to also return raw JSON for caching
            marketplace.fetchIndex(forceRefresh).fold(
                onSuccess = { index ->
                    lastRemoteUpdated = index.updated
                    _marketplacePlugins.value = index.plugins
                    registerBuiltins(index)
                },
                onFailure = { /* keep cached data */ }
            )
            _isLoading.value = false
        }
    }

    /** Auto-register only plugins whose class is actually present in this APK build. */
    private fun registerBuiltins(index: MarketplaceIndex) {
        index.plugins.forEach { entry ->
            // Only register if the class was already registered at startup (MainActivity)
            // OR if we can actually load the class via reflection
            if (pluginClassRegistry.containsKey(entry.id)) return@forEach
            val className = builtinPluginClass(entry.id) ?: return@forEach
            try {
                Class.forName(className)
                PluginViewModel.registerPluginClass(entry.id, className)
            } catch (_: ClassNotFoundException) { /* not in this build */ }
        }
    }

    /** Search marketplace by keyword. */
    fun search(query: String) {
        _searchQuery.value = query
    }

    /** Install a plugin: builtin → instantiate via reflection; remote → download → verify → install → activate. */
    fun installPlugin(id: String) {
        viewModelScope.launch {
            // Find marketplace entry
            val entry = _marketplacePlugins.value.find { it.id == id }
            if (entry == null) {
                updateInstallState(id, InstallState.Failed("Plugin not found in marketplace"))
                return@launch
            }

            // Builtin plugins: determined by local class registry, NOT remote JSON status
            val isBuiltin = pluginClassRegistry.containsKey(id)
            if (isBuiltin) {
                updateInstallState(id, InstallState.Installing("正在激活 ${entry.name}..."))
                val plugin = createPluginInstance(entry)
                if (plugin == null) {
                    updateInstallState(id, InstallState.Failed("无法激活内置插件: ${entry.id}"))
                    return@launch
                }
                pluginManager.install(plugin).fold(
                    onSuccess = {
                        pluginManager.activate(id).fold(
                            onSuccess = { updateInstallState(id, InstallState.Done(id)) },
                            onFailure = { e -> updateInstallState(id, InstallState.Failed("激活失败: ${e.message}")) }
                        )
                    },
                    onFailure = { e -> updateInstallState(id, InstallState.Failed("安装失败: ${e.message}")) }
                )
                return@launch
            }

            // Remote plugins: download → verify → install → activate via PluginExecutor
            if (!entry.isDownloadable) {
                updateInstallState(id, InstallState.Failed("${entry.name} 暂未发布，无法下载"))
                return@launch
            }

            updateInstallState(id, InstallState.Downloading(0f))
            updateInstallState(id, InstallState.Verifying)

            // Delegate to PluginExecutor which handles download + SHA256 verify + DexClassLoader activate
            val executor = PluginExecutor(pluginManager, marketplace)
            val ctx = com.mengpaw.kernel.cli.ExecutionContext(
                sessionId = "ui-install", agentName = "MengPaw",
                workDir = com.mengpaw.kernel.DataPaths.PLUGIN_CACHE
            )
            val result = executor.commands["install"]?.invoke(listOf(id), ctx)
            if (result != null && result.success) {
                updateInstallState(id, InstallState.Done(id))
            } else {
                updateInstallState(id, InstallState.Failed(result?.error ?: "Install failed: ${entry.id}"))
            }
        }
    }

    /** Uninstall a plugin. */
    fun uninstallPlugin(id: String) {
        viewModelScope.launch {
            pluginManager.uninstall(id).fold(
                onSuccess = { updateInstallState(id, InstallState.Idle) },
                onFailure = { updateInstallState(id, InstallState.Failed("Uninstall failed: ${it.message}")) }
            )
        }
    }

    /** Enable a disabled plugin. */
    fun enablePlugin(id: String) {
        viewModelScope.launch {
            try { pluginManager.activate(id) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { com.mengpaw.kernel.error.ErrorCollector.report(e, "PluginViewModel.enablePlugin") }
        }
    }

    /** Disable a plugin (keep installed but deactivate commands). */
    fun disablePlugin(id: String) {
        viewModelScope.launch {
            try { pluginManager.deactivate(id) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { com.mengpaw.kernel.error.ErrorCollector.report(e, "PluginViewModel.disablePlugin") }
        }
    }

    /**
     * Analyze an error output to suggest a missing plugin.
     * Called by AgentViewModel when command execution fails with "Unknown command".
     */
    fun suggestPluginForCommand(errorOutput: String) {
        val regex = Regex("Unknown command: (\\w+)\\.")
        val match = regex.find(errorOutput) ?: return
        val namespace = match.groupValues[1]
        val pluginId = "$namespace-plugin"

        val entry = _marketplacePlugins.value.find { it.id == pluginId } ?: return

        viewModelScope.launch {
            _suggestions.emit(
                PluginSuggestion(
                    namespace = namespace,
                    pluginId = pluginId,
                    pluginName = entry.name,
                    description = entry.description,
                    missingCommand = errorOutput.substringAfter("Unknown command:").trim()
                )
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun updateInstallState(id: String, state: InstallState) {
        _installStates.value = _installStates.value + (id to state)
    }

    /**
     * Create a Plugin instance from a marketplace entry using the registered class name.
     * Uses reflection with safety validation — only instantiates classes that actually
     * implement the Plugin interface and are from allowed packages.
     */
    private fun createPluginInstance(entry: MarketplaceEntry): Plugin? {
        val className = pluginClassRegistry[entry.id] ?: return null
        return try {
            val clazz = Class.forName(className)

            // VULN-FIX: Verify the class actually implements Plugin
            if (!Plugin::class.java.isAssignableFrom(clazz)) {
                android.util.Log.w("PluginViewModel", "Class $className does not implement Plugin — blocked")
                return null
            }

            // VULN-FIX: Only allow classes from com.mengpaw.plugin package
            val pkg = clazz.`package`?.name ?: ""
            if (!pkg.startsWith("com.mengpaw.plugin.")) {
                android.util.Log.w("PluginViewModel", "Class $className from untrusted package $pkg — blocked")
                return null
            }

            clazz.getDeclaredConstructor().newInstance() as Plugin
        } catch (e: Exception) {
            android.util.Log.e("PluginViewModel", "Failed to instantiate $className: ${e.message}")
            null
        }
    }

    companion object {
        /**
         * Registry mapping plugin IDs to their fully-qualified class names.
         * Populated at app startup and auto-registered for builtin plugins from marketplace.
         */
        val pluginClassRegistry = mutableMapOf<String, String>()

        /** Register a known plugin class for instantiation. */
        fun registerPluginClass(pluginId: String, className: String) {
            pluginClassRegistry[pluginId] = className
        }

        /** Mapping from plugin ID to known builtin class name. */
        private val BUILTIN_CLASSES = mapOf(
            "fs-plugin" to "com.mengpaw.plugin.fs.FsPlugin",
            "net-plugin" to "com.mengpaw.plugin.net.NetPlugin",
            "memory-plugin" to "com.mengpaw.plugin.memory.MemoryPlugin",
            "framework-plugin" to "com.mengpaw.plugin.framework.FrameworkPlugin",
            "skill-plugin" to "com.mengpaw.plugin.skill.SkillPlugin",
            "self-plugin" to "com.mengpaw.plugin.self.SelfPlugin",
            "clipboard-plugin" to "com.mengpaw.plugin.clipboard.ClipboardPlugin",
            "notification-plugin" to "com.mengpaw.plugin.notification.NotificationPlugin",
            "tavily-plugin" to "com.mengpaw.plugin.tavily.TavilyPlugin",
            "hermes-plugin" to "com.mengpaw.plugin.hermes.HermesPlugin",
            "workflow-plugin" to "com.mengpaw.plugin.workflow.WorkflowPlugin",
            "incubator-plugin" to "com.mengpaw.plugin.incubator.IncubatorPlugin",
            "render-plugin" to "com.mengpaw.plugin.render.RenderPlugin",
            "comfy-plugin" to "com.mengpaw.plugin.comfy.ComfyPlugin",
            "translate-plugin" to "com.mengpaw.plugin.translate.TranslatePlugin",
            "dev-plugin" to "com.mengpaw.plugin.dev.DevPlugin",
            "error-report-plugin" to "com.mengpaw.plugin.errorreport.ErrorReportPlugin",
            "browser-push-plugin" to "com.mengpaw.plugin.browserpush.BrowserPushPlugin",
            "browser-search-plugin" to "com.mengpaw.plugin.browsersearch.BrowserSearchPlugin",
            "browser-mcp-plugin" to "com.mengpaw.plugin.browsermcp.BrowserMcpPlugin",
            "browser-cdp-plugin" to "com.mengpaw.plugin.browsercdp.BrowserCdpPlugin",
            "browser-inspector-plugin" to "com.mengpaw.plugin.browserinspector.BrowserInspectorPlugin",
            "update-plugin" to "com.mengpaw.plugin.update.UpdatePlugin"
        )

        /** Look up the class name for a builtin plugin by its ID. */
        fun builtinPluginClass(pluginId: String): String? = BUILTIN_CLASSES[pluginId]
    }
}
