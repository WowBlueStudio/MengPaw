package com.mengpaw.core.transport

import com.mengpaw.core.cli.ExecutionResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.*

/**
 * Unix Socket server for Termux communication.
 * Listens on a local socket for JSON-formatted command requests.
 */
class UnixSocket(private val socketPath: String = "/data/data/com.mengpaw/files/mengpaw.sock") {

    private val commandChannel = Channel<String>(Channel.BUFFERED)
    val commands: Flow<String> = commandChannel.receiveAsFlow()

    private var running = false

    suspend fun start() {
        running = true
        // In production: creates a LocalServerSocket and listens
    }

    fun stop() {
        running = false
        val file = File(socketPath)
        if (file.exists()) file.delete()
    }

    suspend fun sendResponse(requestId: String, result: ExecutionResult) {
        // In production: writes JSON response back to the socket
    }
}
