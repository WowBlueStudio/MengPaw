// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

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
                val assetManager = context.assets
                val fileList = assetManager.list(assetPath) ?: emptyArray()
                if (fileList.isEmpty()) {
                    KernelLog.w("AgentTemplates", "No template files found in assets/$assetPath")
                    continue
                }

                for (filename in fileList) {
                    if (!filename.endsWith(".md")) continue
                    val targetFile = File(targetDir, filename)
                    if (!targetFile.exists()) {
                        try {
                            assetManager.open("$assetPath/$filename").use { input ->
                                targetFile.writeBytes(input.readBytes())
                            }
                        } catch (e: Exception) {
                            KernelLog.w("AgentTemplates", "Failed to extract $lang/$filename: ${e.message}")
                        }
                    }
                }
                extracted.add(lang)
                KernelLog.i("AgentTemplates", "Extracted ${fileList.size} $lang template files to ${targetDir.absolutePath}")
            } catch (e: Exception) {
                KernelLog.w("AgentTemplates", "$lang template extraction failed: ${e.message}")
            }
        }
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

        val templateFiles = try { templateDir.listFiles()?.filter { it.extension == "md" } ?: emptyList() } catch (_: Exception) { emptyList() }
        for (template in templateFiles) {
            val targetFile = File(workspaceDir, template.name)
            if (targetFile.exists()) continue // Preserve agent's existing file

            try {
                // Atomic write: write to .tmp first, then rename
                val tmpFile = File(workspaceDir, "${template.name}.tmp")
                template.copyTo(tmpFile, overwrite = true)
                if (!tmpFile.renameTo(targetFile)) {
                    // Cross-device fallback: direct copy
                    template.copyTo(targetFile, overwrite = false)
                    tmpFile.delete()
                }
            } catch (e: Exception) {
                KernelLog.w("AgentTemplates", "Failed to copy ${template.name} for $agentName: ${e.message}")
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
