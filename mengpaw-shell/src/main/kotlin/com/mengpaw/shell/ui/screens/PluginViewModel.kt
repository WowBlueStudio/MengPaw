package com.mengpaw.shell.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mengpaw.core.plugin.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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
    val installState: InstallState = InstallState.Idle
)

/**
 * ViewModel bridging the plugin system to Compose UI.
 *
 * Connects PluginManager (lifecycle) + PluginMarketplaceClient (market) +
 * PluginExecutor (CLI) into observable UI state.
 */
class PluginViewModel : ViewModel() {

    private val pluginManager = PluginManager()
    private val marketplace = PluginMarketplaceClient()

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
                    installState = states[entry.id] ?: InstallState.Idle
                )
            }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val installedCount: StateFlow<Int> = pluginItems
        .map { items -> items.count { it.isInstalled } }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val activeCount: StateFlow<Int> = pluginItems
        .map { items -> items.count { it.isActive } }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    // ── Actions ───────────────────────────────────────────────────────

    /** Fetch marketplace index (uses ETag cache internally). */
    fun refreshMarketplace() {
        viewModelScope.launch {
            _isLoading.value = true
            marketplace.fetchIndex().fold(
                onSuccess = { index ->
                    _marketplacePlugins.value = index.plugins
                },
                onFailure = { /* keep cached data */ }
            )
            _isLoading.value = false
        }
    }

    /** Search marketplace by keyword. */
    fun search(query: String) {
        _searchQuery.value = query
    }

    /** Install a plugin: download → verify → install → activate. */
    fun installPlugin(id: String) {
        viewModelScope.launch {
            updateInstallState(id, InstallState.Downloading(0f))

            // Find marketplace entry
            val entry = _marketplacePlugins.value.find { it.id == id }
            if (entry == null) {
                updateInstallState(id, InstallState.Failed("Plugin not found in marketplace"))
                return@launch
            }

            // Download (in-memory for pre-compiled plugins — real download for remote)
            updateInstallState(id, InstallState.Verifying)

            // For pre-compiled Gradle plugins, create the Plugin instance directly.
            // In production, this would download a JAR and use ClassLoader.
            val plugin = createPluginInstance(entry)
            if (plugin == null) {
                updateInstallState(id, InstallState.Failed("Cannot instantiate plugin: ${entry.id}"))
                return@launch
            }

            updateInstallState(id, InstallState.Installing("Installing ${entry.name}..."))

            // Install into PluginManager
            pluginManager.install(plugin).fold(
                onSuccess = {
                    // Activate (register commands)
                    pluginManager.activate(id).fold(
                        onSuccess = {
                            updateInstallState(id, InstallState.Done(id))
                        },
                        onFailure = { e ->
                            updateInstallState(id, InstallState.Failed("Activation failed: ${e.message}"))
                        }
                    )
                },
                onFailure = { e ->
                    updateInstallState(id, InstallState.Failed("Install failed: ${e.message}"))
                }
            )
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
            pluginManager.activate(id)
        }
    }

    /** Disable a plugin (keep installed but deactivate commands). */
    fun disablePlugin(id: String) {
        viewModelScope.launch {
            pluginManager.deactivate(id)
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
         * Populated at app startup. In production, this would be populated
         * dynamically after downloading plugin JARs.
         */
        val pluginClassRegistry = mutableMapOf<String, String>()

        /** Register a known plugin class for instantiation. */
        fun registerPluginClass(pluginId: String, className: String) {
            pluginClassRegistry[pluginId] = className
        }
    }
}
