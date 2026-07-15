package com.mengpaw.core.transport

import com.mengpaw.core.cli.ExecutionResult

/**
 * AIDL interface definition for extension-to-core communication.
 * In production this would be an actual .aidl file.
 */
interface ICoreService {
    suspend fun executeCommand(command: String): ExecutionResult
    suspend fun getSessionStatus(): String
    fun registerCallback(callback: IExtensionCallback)
}

interface IExtensionCallback {
    fun onEvent(eventType: String, data: String)
    fun onError(code: Int, message: String)
}

interface IExtensionPlugin {
    val name: String
    val version: String
    suspend fun execute(action: String, params: String): String
    fun registerCallback(callback: IExtensionCallback)
}
