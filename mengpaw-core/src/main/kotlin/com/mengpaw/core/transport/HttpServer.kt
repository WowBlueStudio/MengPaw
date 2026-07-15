package com.mengpaw.core.transport

import com.mengpaw.core.cli.ExecutionResult

/**
 * Lightweight HTTP server stub.
 * On Android, a full HTTP server requires additional setup.
 * This stub is here for the API contract; enable with Ktor server dependencies.
 */
class HttpServer(private val port: Int = 8080) {

    private var commandHandler: (suspend (String) -> ExecutionResult)? = null

    fun onCommand(handler: suspend (String) -> ExecutionResult) {
        commandHandler = handler
    }

    fun start() {
        // HTTP server requires ktor-server-core on Android.
        // Add the dependency in build.gradle.kts to enable.
        println("HttpServer: add ktor-server-core dependency to enable")
    }

    fun stop() {
        // no-op
    }
}
