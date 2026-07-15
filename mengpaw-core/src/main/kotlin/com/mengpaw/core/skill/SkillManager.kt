package com.mengpaw.core.skill

import java.io.File

/**
 * A reusable Skill (playbook) that the Agent can invoke.
 * Each skill is stored as a markdown file with YAML frontmatter.
 */
data class Skill(
    val name: String,
    val description: String = "",
    val content: String = "",
    val enabled: Boolean = true,
    val category: String = "general",
    val fileName: String = ""
) {
    fun toMarkdown(): String = buildString {
        appendLine("---")
        appendLine("name: $name")
        appendLine("description: $description")
        appendLine("enabled: $enabled")
        appendLine("category: $category")
        appendLine("---")
        appendLine()
        append(content.trim())
        appendLine()
    }

    companion object {
        fun fromMarkdown(file: File): Skill? {
            if (!file.exists()) return null
            val text = file.readText()
            val fileName = file.nameWithoutExtension
            val frontmatterMatch = Regex("^---\\s*\n(.+?)\\n---", RegexOption.DOT_MATCHES_ALL)
                .find(text.trimStart())
            val frontmatter = frontmatterMatch?.groupValues?.get(1) ?: ""
            val contentStart = frontmatterMatch?.range?.last?.plus(1) ?: 0
            val content = text.substring(contentStart).trim()
            val props = frontmatter.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && it.contains(":") }
                .associate { line ->
                    val idx = line.indexOf(":")
                    line.take(idx).trim() to line.drop(idx + 1).trim()
                }
            return Skill(
                name = props["name"] ?: fileName,
                description = props["description"] ?: "",
                content = content,
                enabled = props["enabled"]?.toBooleanStrictOrNull() ?: true,
                category = props["category"] ?: "general",
                fileName = fileName
            )
        }
    }
}

/**
 * Manages Skills — reusable playbooks for the Agent.
 * Each skill is a .md file with YAML frontmatter in skills/ directory.
 */
class SkillManager(private val storageDir: String = "/data/data/com.mengpaw/files/skills") {

    private val dir: File get() = File(storageDir).also { it.mkdirs() }

    fun list(): List<Skill> {
        return dir.listFiles { f -> f.extension == "md" }
            ?.mapNotNull { Skill.fromMarkdown(it) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun get(name: String): Skill? {
        val file = File(dir, "$name.md")
        return Skill.fromMarkdown(file)
    }

    fun save(skill: Skill): Skill {
        val file = File(dir, "${skill.fileName.ifBlank { skill.name }}.md")
        file.writeText(skill.toMarkdown())
        return skill
    }

    fun delete(name: String): Boolean = File(dir, "$name.md").delete()

    fun setEnabled(name: String, enabled: Boolean): Boolean {
        val skill = get(name) ?: return false
        save(skill.copy(enabled = enabled, fileName = name))
        return true
    }

    fun importFromText(text: String, fileName: String): Skill? {
        val tempFile = File(dir, fileName)
        tempFile.writeText(text)
        val skill = Skill.fromMarkdown(tempFile) ?: return null
        return save(skill)
    }

    fun installDefaults() {
        listOf(DEFAULT_SEARCH_SKILL, DEFAULT_SUMMARIZE_SKILL, DEFAULT_TRANSLATE_SKILL).forEach { text ->
            val name = Regex("^name:\\s*(.+)", RegexOption.MULTILINE)
                .find(text)?.groupValues?.get(1)?.trim() ?: return@forEach
            if (get(name) == null) {
                importFromText(text, "$name.md")
            }
        }
    }

    /** Generate a condensed skill index for Agent preload. */
    fun generateIndex(): String = buildString {
        appendLine("# Skill 索引")
        appendLine()
        appendLine("> 这是所有可用 Skill 的索引。先加载本文档了解可用能力，再根据需要加载具体 Skill。")
        appendLine("> 加载 Skill 内容: `memory.read skill-{名称}`")
        appendLine()
        val skills = list()
        if (skills.isEmpty()) {
            appendLine("（暂无 Skill）")
            return@buildString
        }
        skills.forEach { s ->
            val status = if (s.enabled) "enabled" else "disabled"
            appendLine("### $status: ${s.name}")
            appendLine()
            appendLine("描述: ${s.description}")
            appendLine("分类: ${s.category}")
            appendLine("加载: memory.read skill-${s.name}")
            appendLine()
        }
    }

    companion object {
        val DEFAULT_SEARCH_SKILL = """---
name: 搜索
description: 搜索互联网并返回结果摘要
enabled: true
category: network
---
你是一个搜索助手。使用 net.curl 搜索指定关键词。

步骤：
1. 使用 net.curl 调用搜索 API
2. 提取并总结搜索结果
3. 用中文返回简洁的答案
"""

        val DEFAULT_SUMMARIZE_SKILL = """---
name: 总结
description: 总结文件或网页内容
enabled: true
category: text
---
你是一个总结助手。

步骤：
1. 使用 fs.cat 读取文件内容
2. 或使用 net.curl 获取网页内容
3. 提取关键信息
4. 用 3-5 句话总结核心内容
"""

        val DEFAULT_TRANSLATE_SKILL = """---
name: 翻译
description: 翻译文本到指定语言
enabled: true
category: text
---
你是一个翻译助手。

用法：告诉我要翻译的内容和目标语言。

步骤：
1. 读取要翻译的文本
2. 翻译为目标语言
3. 返回原文和译文对照
"""
    }
}
