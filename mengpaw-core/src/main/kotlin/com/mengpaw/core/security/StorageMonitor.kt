package com.mengpaw.core.security

import android.os.StatFs
import java.io.File

/**
 * Monitors disk space and prevents out-of-space crashes.
 * Performs automatic cleanup when storage is low.
 */
object StorageMonitor {
    private const val MIN_FREE_MB = 500L
    private const val MAX_STORAGE_BYTES = 2L * 1024 * 1024 * 1024 // 2GB
    private const val CLEANUP_THRESHOLD = 0.8 // 80% of max

    /**
     * Check if there's enough space before a write operation.
     * @throws InsufficientStorageException if space is insufficient.
     */
    fun checkBeforeWrite(dataDir: String, bytesToWrite: Long = 0) {
        val stat = StatFs(dataDir)
        val available = stat.availableBytes
        val needed = bytesToWrite + MIN_FREE_MB * 1024 * 1024
        if (available < needed) {
            throw InsufficientStorageException(
                "Need ${bytesToWrite / 1024 / 1024}MB, " +
                "only ${available / 1024 / 1024}MB available (need ${MIN_FREE_MB}MB buffer)"
            )
        }
    }

    /**
     * Get available space in bytes.
     */
    fun availableBytes(dataDir: String): Long {
        val stat = StatFs(dataDir)
        return stat.availableBytes
    }

    /**
     * Auto-cleanup old files when storage exceeds limit.
     * Deletes oldest files first.
     */
    fun autoCleanup(filesDir: File) {
        if (!filesDir.exists()) return
        val totalSize = filesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        if (totalSize <= MAX_STORAGE_BYTES * CLEANUP_THRESHOLD) return

        val toFree = totalSize - (MAX_STORAGE_BYTES * CLEANUP_THRESHOLD).toLong()
        var freed = 0L

        filesDir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.lastModified() } // oldest first
            .takeWhile { freed < toFree }
            .forEach {
                freed += it.length()
                it.delete()
            }
    }

    class InsufficientStorageException(message: String) : RuntimeException(message)
}
