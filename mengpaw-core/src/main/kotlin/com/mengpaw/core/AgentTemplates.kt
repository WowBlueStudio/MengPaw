// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.core

import android.content.Context
import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.KernelLog
import java.io.File

/**
 * Agent MD template manager — extracts pre-built .md templates from APK assets
 * to a read-only path, then copies them to agent workspaces on init.
 *
 * Three-path model:
 *   assets/agent-templates/{zh,en}/  → APK-bundled (immutable)
 *   {filesDir}/agent-templates/{zh,en}/ → read-only extract (Agent cannot modify)
 *   {filesDir}/Agent文档/{name}/ → workspace (Agent can freely edit)
 *
 * ## Performance
 *
 * Agent creation now does bare file copies (~1ms each) instead of building
 * large Kotlin multiline strings and writing them. No string concatenation,
 * no trimIndent(), no template variable interpolation overhead.
 *
 * ## Customization
 *
 * Edit the .md files under mengpaw-shell/src/main/assets/agent-templates/{zh,en}/,
 * then rebuild the APK. Templates are separated from source code.
 */
object AgentTemplates {
    private val SUPPORTED_LANGS = listOf("zh", "en")
    private val extracted = mutableSetOf<String>()

    /**
     * Extract templates for all supported languages from APK assets.
     * Called once at app startup. Safe to call repeatedly.
     */
    fun init(context: Context) {
        for (lang in SUPPORTED_LANGS) {
            if (lang in extracted) continue
            val targetDir = File(DataPaths.AGENT_TEMPLATES, lang)
            if (!targetDir.exists()) targetDir.mkdirs()

            try {
                val assetPath = "agent-templates/$lang"
                val count = extractAssets(context.assets, assetPath, targetDir)
                if (count == 0) {
                    KernelLog.w("AgentTemplates", "No template files found in assets/$assetPath")
                    continue
                }
                extracted.add(lang)
                KernelLog.i("AgentTemplates", "Extracted $count $lang template files to ${targetDir.absolutePath}")
            } catch (e: Exception) {
                KernelLog.w("AgentTemplates", "$lang template extraction failed: ${e.message}")
            }
        }
    }

    /**
     * Recursively extract .md assets (including subdirectories like memory/)
     * preserving the relative directory structure.
     * @return number of files extracted
     */
    private fun extractAssets(assetManager: android.content.res.AssetManager, assetPath: String, targetDir: File): Int {
        val entries = try { assetManager.list(assetPath) ?: emptyArray() } catch (_: Exception) { emptyArray() }
        var count = 0
        for (entry in entries) {
            val childAsset = "$assetPath/$entry"
            val childTarget = File(targetDir, entry)
            // 目录判定：能列出子条目说明是目录（文件 open 会抛异常，list 返回 null/空）
            val subEntries = try { assetManager.list(childAsset) } catch (_: Exception) { null }
            if (subEntries != null && subEntries.isNotEmpty()) {
                childTarget.mkdirs()
                count += extractAssets(assetManager, childAsset, childTarget)
            } else if (entry.endsWith(".md")) {
                if (!childTarget.exists()) {
                    try {
                        assetManager.open(childAsset).use { input ->
                            childTarget.parentFile?.mkdirs()
                            childTarget.writeBytes(input.readBytes())
                            count++
                        }
                    } catch (e: Exception) {
                        KernelLog.w("AgentTemplates", "Failed to extract $childAsset: ${e.message}")
                    }
                }
            }
        }
        return count
    }

    /**
     * Bootstrap a new agent's workspace by copying template .md files from the
     * read-only template directory for the specified language.
     *
     * Falls back to zh if the requested language's templates don't exist.
     */
    fun bootstrapAgent(agentName: String, language: String = "zh") {
        val lang = if (language in SUPPORTED_LANGS) language else "zh"
        val templateDir = File(DataPaths.AGENT_TEMPLATES, lang)
        if (!templateDir.exists() || !templateDir.isDirectory) {
            KernelLog.w("AgentTemplates", "Template directory not found: ${templateDir.absolutePath}")
            return
        }

        val workspaceDir = File(DataPaths.AGENTS, agentName)
        if (!workspaceDir.exists()) workspaceDir.mkdirs()

        copyTemplates(templateDir, workspaceDir)
    }

    /**
     * Recursively copy template .md files (including subdirectories like memory/)
     * into the workspace, preserving relative structure. Existing targets are kept
     * untouched (preserve the agent's own files).
     */
    private fun copyTemplates(srcDir: File, destDir: File) {
        val entries = try { srcDir.listFiles() ?: emptyArray() } catch (_: Exception) { emptyArray() }
        for (entry in entries) {
            if (entry.isDirectory) {
                val subDest = File(destDir, entry.name)
                subDest.mkdirs()
                copyTemplates(entry, subDest)
            } else if (entry.extension == "md") {
                val targetFile = File(destDir, entry.name)
                if (targetFile.exists()) continue // Preserve agent's existing file
                try {
                    // Atomic write: write to .tmp first, then rename
                    val tmpFile = File(destDir, "${entry.name}.tmp")
                    entry.copyTo(tmpFile, overwrite = true)
                    if (!tmpFile.renameTo(targetFile)) {
                        // Cross-device fallback: direct copy
                        entry.copyTo(targetFile, overwrite = false)
                        tmpFile.delete()
                    }
                } catch (e: Exception) {
                    KernelLog.w("AgentTemplates", "Failed to copy ${entry.name}: ${e.message}")
                }
            }
        }
    }

    fun isReady(): Boolean {
        for (lang in SUPPORTED_LANGS) {
            val dir = File(DataPaths.AGENT_TEMPLATES, lang)
            if (dir.exists() && dir.isDirectory && (try { dir.listFiles()?.isNotEmpty() == true } catch (_: Exception) { false })) {
                return true
            }
        }
        return false
    }
}
