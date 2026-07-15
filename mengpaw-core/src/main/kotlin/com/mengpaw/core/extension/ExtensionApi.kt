package com.mengpaw.core.extension

/**
 * Extension API for interacting with the core system.
 */
interface ExtensionApi {
    val name: String
    val version: String

    /**
     * Execute an action within this extension.
     */
    suspend fun execute(action: String, params: Map<String, String>): String
}
