package com.mengpaw.core.namespace

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.skill.SkillManager

/**
 * Skill namespace — Agent can list, run, enable, disable skills.
 *
 * Commands:
 *   skill.ls              — list all skills
 *   skill.run <name>      — run a skill by name
 *   skill.enable <name>   — enable a skill
 *   skill.disable <name>  — disable a skill
 */
object SkillExecutor {
    private val manager = SkillManager()

    val commands = mapOf(
        "ls" to ::ls,
        "run" to ::run,
        "enable" to ::enable,
        "disable" to ::disable
    )

    private suspend fun ls(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val skills = manager.list()
        if (skills.isEmpty()) return ExecutionResult.ok("（无 Skill）")
        return ExecutionResult.ok(skills.joinToString("\n") { s ->
            val status = if (s.enabled) "✅" else "⛔"
            "$status ${s.name} — ${s.description}"
        })
    }

    private suspend fun run(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("用法: skill.run <名称>")
        val skill = manager.get(args[0])
            ?: return ExecutionResult.fail("Skill 未找到: ${args[0]}")
        if (!skill.enabled) return ExecutionResult.fail("Skill 已禁用: ${args[0]}")
        return ExecutionResult.ok(
            "执行 Skill: ${skill.name}\n---\n${skill.content}\n---\n请按照上述步骤执行。"
        )
    }

    private suspend fun enable(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("用法: skill.enable <名称>")
        return if (manager.setEnabled(args[0], true))
            ExecutionResult.ok("已启用: ${args[0]}")
        else ExecutionResult.fail("未找到: ${args[0]}")
    }

    private suspend fun disable(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("用法: skill.disable <名称>")
        return if (manager.setEnabled(args[0], false))
            ExecutionResult.ok("已禁用: ${args[0]}")
        else ExecutionResult.fail("未找到: ${args[0]}")
    }
}
