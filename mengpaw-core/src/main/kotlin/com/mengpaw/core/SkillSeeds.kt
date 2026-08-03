// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core

import android.content.Context
import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.KernelLog
import java.io.File
import java.security.MessageDigest

/**
 * Bundled skill scripts manager — syncs skill .md files from APK assets
 * (assets/skills/) to the device. Skills are real .md files bundled in the APK
 * (see plugins/plugin-skill/src/main/assets/skills/), NOT hardcoded strings in
 * source — the folder holds the last-evolved version and updates with each APP release.
 *
 * Two-layer model (mirrors [AgentTemplates]'s read-only extract layer):
 *   assets/skills/ 中的 *.md      → APK-bundled (immutable, version follows the APP)
 *   {BASE}/技能剧本/seed/ 中的 *.md → APP bundled version on device (read-only reference —
 *                                    Agent can diff its evolved skills against this)
 *   {BASE}/技能剧本/ 中的 *.md     → global skill pool (Agent freely evolves)
 *
 * Pool sync strategy:
 * - First launch (no manifest yet): copy all bundled skills into the pool.
 * - Later launches: a file is updated only when it still matches the previous
 *   bundled version (recorded in seed/.manifest.json) — i.e. the Agent has NOT
 *   evolved it. Agent-evolved files are kept untouched, letting the Agent diff
 *   its version against seed/ and decide whether to merge.
 */
object SkillSeeds {

    private val manifestFile: File get() = File(DataPaths.SKILLS, "seed/.manifest.json")

    private fun sha256(file: File): String? = try {
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }

    /** Manifest format: one "sha256 filename" pair per line (no JSON dependency in core). */
    private fun readManifest(): Map<String, String>? = try {
        if (!manifestFile.exists()) null
        else manifestFile.readLines()
            .mapNotNull { line -> line.trim().split(Regex("\\s+")).takeIf { it.size == 2 } }
            .associate { (sha, name) -> name to sha }
    } catch (_: Exception) {
        KernelLog.w("SkillSeeds", "manifest 解析失败（损坏则按首次处理）"); null
    }

    private fun writeManifest(manifest: Map<String, String>) {
        try {
            manifestFile.parentFile?.mkdirs()
            manifestFile.writeText(
                manifest.entries.joinToString("\n") { (name, sha) -> "$sha $name" } + "\n")
        } catch (e: Exception) { KernelLog.w("SkillSeeds", "write manifest failed: ${e.message}") }
    }

    /**
     * Sync bundled skills from APK assets. Called at app startup. Safe to call repeatedly.
     */
    fun ensure(context: Context) {
        val seedDir = File(DataPaths.SKILLS, "seed").also { it.mkdirs() }
        try {
            val fileList = context.assets.list("skills") ?: emptyArray()
            if (fileList.isEmpty()) {
                KernelLog.w("SkillSeeds", "No skill files found in assets/skills")
                return
            }

            // 1. Previous bundled versions — null on first launch (or corrupted manifest)
            val prev = readManifest()

            // 2. Sync assets → seed/ (APP bundled version on device) + build new manifest
            val next = linkedMapOf<String, String>()
            for (filename in fileList) {
                if (!filename.endsWith(".md")) continue
                try {
                    val bytes = context.assets.open("skills/$filename").use { it.readBytes() }
                    val seedFile = File(seedDir, filename)
                    val existing = try { seedFile.readBytes() } catch (_: Exception) { null }
                    if (existing?.contentEquals(bytes) != true) {
                        val tmp = File(seedDir, "$filename.tmp")
                        tmp.writeBytes(bytes)
                        if (!tmp.renameTo(seedFile)) { seedFile.writeBytes(bytes); tmp.delete() }
                    }
                    next[filename] = MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it) }
                } catch (e: Exception) {
                    KernelLog.w("SkillSeeds", "Failed to sync seed $filename: ${e.message}")
                }
            }
            writeManifest(next)

            // 3. Global pool sync — update only files the Agent has NOT evolved
            val poolDir = File(DataPaths.SKILLS)
            var written = 0
            next.forEach { (name, newSha) ->
                val target = File(poolDir, name)
                if (!target.exists()) {
                    copyFromSeed(seedDir, name, target); written++
                } else {
                    val prevSha = prev?.get(name)
                    val currentSha = sha256(target)
                    // Not evolved (matches previous bundled version) → update to current version;
                    // first launch (prev == null) → take the bundled version as the initial state.
                    if (prevSha == null || currentSha == prevSha) {
                        if (currentSha != newSha) { copyFromSeed(seedDir, name, target); written++ }
                    }
                }
            }
            // 4. Cleanup: bundled skills removed from assets — drop the pool copy if not evolved
            prev?.keys?.minus(next.keys)?.forEach { name ->
                val target = File(poolDir, name)
                if (target.exists() && sha256(target) == prev[name]) {
                    try { target.delete() } catch (_: Exception) {}
                }
            }
            KernelLog.i("SkillSeeds", "Synced pool (${written} updated) · ${next.size} bundled skills → ${seedDir.absolutePath}")
        } catch (e: Exception) {
            KernelLog.w("SkillSeeds", "Skill sync failed: ${e.message}")
        }
    }

    private fun copyFromSeed(seedDir: File, name: String, target: File) {
        try {
            val tmp = File(target.parentFile, "$name.tmp")
            File(seedDir, name).copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(target)) { File(seedDir, name).copyTo(target, overwrite = true); tmp.delete() }
        } catch (e: Exception) { KernelLog.w("SkillSeeds", "Failed to copy $name: ${e.message}") }
    }
}
