package com.mengpaw.plugin.skill

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.cli.ErrorCodes
import com.mengpaw.core.plugin.Plugin
import com.mengpaw.core.plugin.PluginContext
import com.mengpaw.core.plugin.PluginMetadata
import com.mengpaw.core.plugin.PluginType
import java.io.File

/**
 * Skill system plugin — provides skill.* CLI commands.
 * Each skill is a .md file with YAML frontmatter.
 */
class SkillPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "skill-plugin",
        name = "技能系统",
        version = "1.0.0",
        type = PluginType.NATIVE,
        author = "MengPaw",
        description = "可复用的 Agent 剧本系统（YAML+Markdown），含 3 个默认 Skill",
        minCoreVersion = "0.2.0",
        commands = listOf("skill.ls", "skill.run", "skill.enable", "skill.disable")
    )

    override val commands: Map<String, com.mengpaw.core.plugin.CommandHandler> = mapOf(
        "ls" to ::ls, "run" to ::run, "enable" to ::enable, "disable" to ::disable
    )

    private var storageDir = com.mengpaw.core.DataPaths.SKILLS

    override suspend fun onInstall(ctx: PluginContext) {
        storageDir = "${ctx.storageDir}/skills"
        File(storageDir).mkdirs()
    }

    private val dir: File get() = File(storageDir).also { it.mkdirs() }

    private suspend fun ls(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val skills = listSkills()
        if (skills.isEmpty()) return ExecutionResult.ok("(No skills installed)")
        return ExecutionResult.ok(skills.joinToString("\n") {
            "${if (it.enabled) "✅" else "⛔"} ${it.name} — ${it.description}"
        })
    }

    private suspend fun run(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: skill.run <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val skill = getSkill(args[0]) ?: return ExecutionResult.fail("Skill not found: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
        if (!skill.enabled) return ExecutionResult.fail("Skill disabled: ${args[0]}", errorCode = ErrorCodes.ERR_PERMISSION_DENIED)
        return ExecutionResult.ok(skill.content)
    }

    private suspend fun enable(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: skill.enable <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        setEnabled(args[0], true)
        return ExecutionResult.ok("Enabled: ${args[0]}")
    }

    private suspend fun disable(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: skill.disable <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        setEnabled(args[0], false)
        return ExecutionResult.ok("Disabled: ${args[0]}")
    }

    // ── Skill CRUD ────────────────────────────────────────────────────

    fun listSkills(): List<SkillDef> = dir.listFiles { f -> f.extension == "md" }
        ?.mapNotNull { parseSkill(it) }?.sortedBy { it.name } ?: emptyList()

    fun getSkill(name: String): SkillDef? {
        val file = File(dir, "$name.md")
        return if (file.exists()) parseSkill(file) else null
    }

    fun setEnabled(name: String, enabled: Boolean): Boolean {
        val skill = getSkill(name) ?: return false
        val newContent = skill.rawText.replace(
            Regex("(?m)^enabled:\\s*(true|false)"),
            "enabled: $enabled"
        )
        File(dir, "$name.md").writeText(newContent)
        return true
    }

    private fun parseSkill(file: File): SkillDef? {
        if (!file.exists()) return null
        val text = file.readText()
        val fm = Regex("^---\\s*\n(.+?)\\n---", RegexOption.DOT_MATCHES_ALL).find(text.trimStart())
        val frontmatter = fm?.groupValues?.get(1) ?: ""
        val contentStart = fm?.range?.last?.plus(1) ?: 0
        val content = text.substring(contentStart).trim()
        val props = frontmatter.lines().filter { it.isNotBlank() && it.contains(":") }.associate {
            val idx = it.indexOf(":"); it.take(idx).trim() to it.drop(idx + 1).trim()
        }
        return SkillDef(
            name = props["name"] ?: file.nameWithoutExtension,
            description = props["description"] ?: "",
            enabled = props["enabled"]?.toBooleanStrictOrNull() ?: true,
            category = props["category"] ?: "general",
            content = content,
            rawText = text
        )
    }
}

data class SkillDef(
    val name: String, val description: String, val enabled: Boolean,
    val category: String, val content: String, val rawText: String = ""
)
