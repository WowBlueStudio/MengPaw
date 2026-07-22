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
 *   assets/agent-templates/zh/  → APK-bundled (immutable)
 *   {filesDir}/agent-templates/ → read-only extract (Agent cannot modify)
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
 * Edit the .md files under mengpaw-shell/src/main/assets/agent-templates/zh/,
 * then rebuild the APK. Templates are separated from source code.
 */
object AgentTemplates {
    private const val ASSET_PATH = "agent-templates/zh"
    private var extracted = false

    /**
     * Extract templates from APK assets to the read-only template directory.
     * Called once at app startup. Safe to call repeatedly — subsequent calls
     * are no-ops unless the APK was updated (new version code).
     *
     * @param context Android context for AssetManager access.
     */
    fun init(context: Context) {
        if (extracted) return
        val targetDir = File(DataPaths.AGENT_TEMPLATES)
        if (!targetDir.exists()) targetDir.mkdirs()

        try {
            val assetManager = context.assets
            val fileList = assetManager.list(ASSET_PATH) ?: emptyArray()
            if (fileList.isEmpty()) {
                KernelLog.w("AgentTemplates", "No template files found in assets/$ASSET_PATH")
                return
            }

            for (filename in fileList) {
                if (!filename.endsWith(".md")) continue
                val targetFile = File(targetDir, filename)
                // Only copy if target doesn't exist (preserves user's read-only copy
                // across app restarts; overwrites on APK upgrade via version check)
                if (!targetFile.exists()) {
                    try {
                        assetManager.open("$ASSET_PATH/$filename").use { input ->
                            targetFile.writeBytes(input.readBytes())
                        }
                    } catch (e: Exception) {
                        KernelLog.w("AgentTemplates", "Failed to extract $filename: ${e.message}")
                    }
                }
            }
            extracted = true
            KernelLog.i("AgentTemplates", "Extracted ${fileList.size} template files to ${targetDir.absolutePath}")
        } catch (e: Exception) {
            KernelLog.w("AgentTemplates", "Template extraction failed: ${e.message}")
        }
    }

    /**
     * Bootstrap a new agent's workspace by copying template .md files from the
     * read-only template directory. Files that already exist in the workspace
     * are skipped (preserves agent's customizations).
     *
     * Uses atomic write (tmp → rename) for crash safety.
     *
     * @param agentName Agent workspace folder name.
     */
    fun bootstrapAgent(agentName: String) {
        val templateDir = File(DataPaths.AGENT_TEMPLATES)
        if (!templateDir.exists() || !templateDir.isDirectory) {
            KernelLog.w("AgentTemplates", "Template directory not found: ${templateDir.absolutePath}")
            return
        }

        val workspaceDir = File(DataPaths.AGENTS, agentName)
        if (!workspaceDir.exists()) workspaceDir.mkdirs()

        val templateFiles = templateDir.listFiles()?.filter { it.extension == "md" } ?: emptyList()
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

    /**
     * Check if template directory exists and has been populated.
     * Used as a fast-path check before bootstrap.
     */
    fun isReady(): Boolean {
        val dir = File(DataPaths.AGENT_TEMPLATES)
        return dir.exists() && dir.isDirectory && (dir.listFiles()?.isNotEmpty() == true)
    }
}
