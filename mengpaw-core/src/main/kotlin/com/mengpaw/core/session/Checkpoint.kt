// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Persistence handler for checkpoints (save/resume Agent progress).
 * All I/O operations run on Dispatchers.IO to avoid blocking the main thread.
 */
class CheckpointManager(private val storageDir: String = com.mengpaw.core.DataPaths.CHECKPOINTS) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * Save a checkpoint to disk asynchronously.
     */
    suspend fun save(checkpoint: Checkpoint) = withContext(Dispatchers.IO) {
        val dir = File(storageDir)
        dir.mkdirs()
        val file = File(dir, "${checkpoint.sessionId}_step_${checkpoint.step}.json")
        file.writeText(json.encodeToString(checkpoint))
    }

    /**
     * Load the latest checkpoint for a session asynchronously.
     */
    suspend fun loadLatest(sessionId: String): Checkpoint? = withContext(Dispatchers.IO) {
        val dir = File(storageDir)
        if (!dir.exists()) return@withContext null
        val files = dir.listFiles { f -> f.name.startsWith(sessionId) }
            ?.sortedByDescending { it.lastModified() }
            ?: return@withContext null
        if (files.isEmpty()) return@withContext null
        try {
            json.decodeFromString<Checkpoint>(files.first().readText())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Clean up old checkpoints, keeping only the N most recent.
     */
    suspend fun cleanup(sessionId: String, keep: Int = 3) = withContext(Dispatchers.IO) {
        val dir = File(storageDir)
        if (!dir.exists()) return@withContext
        val files = dir.listFiles { f -> f.name.startsWith(sessionId) }
            ?.sortedByDescending { it.lastModified() }
            ?: return@withContext
        if (files.size > keep) {
            files.drop(keep).forEach { it.delete() }
        }
    }
}
