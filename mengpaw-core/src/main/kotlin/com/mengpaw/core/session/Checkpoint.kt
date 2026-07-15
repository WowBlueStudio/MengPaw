package com.mengpaw.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Persistence handler for checkpoints (save/resume Agent progress).
 */
class CheckpointManager(private val storageDir: String = "/data/data/com.mengpaw/files/checkpoints") {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * Save a checkpoint to disk.
     */
    fun save(checkpoint: Checkpoint) {
        val dir = File(storageDir)
        dir.mkdirs()
        val file = File(dir, "${checkpoint.sessionId}_step_${checkpoint.step}.json")
        file.writeText(json.encodeToString(checkpoint))
    }

    /**
     * Load the latest checkpoint for a session.
     */
    fun loadLatest(sessionId: String): Checkpoint? {
        val dir = File(storageDir)
        if (!dir.exists()) return null
        val files = dir.listFiles { f -> f.name.startsWith(sessionId) }
            ?.sortedByDescending { it.lastModified() }
            ?: return null
        if (files.isEmpty()) return null
        return try {
            json.decodeFromString<Checkpoint>(files.first().readText())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Clean up old checkpoints, keeping only the N most recent.
     */
    fun cleanup(sessionId: String, keep: Int = 3) {
        val dir = File(storageDir)
        if (!dir.exists()) return
        val files = dir.listFiles { f -> f.name.startsWith(sessionId) }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        if (files.size > keep) {
            files.drop(keep).forEach { it.delete() }
        }
    }
}
