package com.mengpaw.core.extension

/**
 * Loads and manages extensions, checking version compatibility.
 */
class ExtensionLoader(private val coreVersion: String = "0.1.0") {

    private val extensions = mutableMapOf<String, ExtensionApi>()

    /**
     * Load an extension, checking version compatibility.
     */
    fun load(info: ExtensionInfo, api: ExtensionApi): Result<LoadedExtension> {
        return runCatching {
            val min = Version.parse(info.minCoreVersion)
            val max = Version.parse(info.maxCoreVersion)
            val current = Version.parse(coreVersion)

            require(current >= min) { "Extension '${info.name}' requires Core >= ${info.minCoreVersion}" }
            require(current <= max) { "Extension '${info.name}' not tested with Core > ${info.maxCoreVersion}" }

            val resolvedApi = minOf(info.apiVersion, CURRENT_API_VERSION)
            val loaded = LoadedExtension(info, resolvedApi)
            extensions[info.name] = api
            loaded
        }
    }

    /**
     * Find a loaded extension by name.
     */
    fun find(name: String): ExtensionApi? = extensions[name]

    /**
     * List all loaded extensions.
     */
    fun list(): List<ExtensionInfo> = extensions.values.map { it.toInfo() }

    /**
     * Unload an extension.
     */
    fun unload(name: String): Boolean {
        return extensions.remove(name) != null
    }

    private fun ExtensionApi.toInfo() = ExtensionInfo(
        name = name,
        version = version,
        minCoreVersion = "0.1.0",
        maxCoreVersion = "0.3.0"
    )

    companion object {
        const val CURRENT_API_VERSION = 1
    }
}
