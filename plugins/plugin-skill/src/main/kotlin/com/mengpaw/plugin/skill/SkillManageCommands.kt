// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.skill

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.error.ErrorCollector
import java.io.File

/**
 * 技能管理命令: 创建 / 删除 / 全局池拉取上传 / 启停。
 *
 * 从 SkillPlugin 拆出 (文件行数红线) — 与 SkillFlowCommands (派生/索取) 同模式:
 * 命令对象接收插件实例, 经 internal 成员 (localDir/globalDir/skillFile/parseSkill) 访问技能池。
 */
internal object SkillManageCommands {

    suspend fun create(plugin: SkillPlugin, args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: skill.create <name> [--category <cat>] [--description <desc>]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val name = args[0]
        if (!name.matches(Regex("^[a-zA-Z0-9_-]+$"))) return ExecutionResult.fail("Skill 名称只能包含英文字母、数字、下划线和连字符。", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        var category = "general"
        var description = ""
        var i = 1
        while (i < args.size) {
            when (args[i]) {
                "--category", "-c" -> { if (i + 1 < args.size) { category = args[++i]; if (category !in SkillPlugin.CATEGORIES) category = "general" } }
                "--description", "-d" -> { if (i + 1 < args.size) description = args[++i] }
            }; i++
        }
        if (description.isBlank()) description = "$name 技能"
        val target = plugin.localDir(ctx.agentName ?: "")
        val file = File(target, "$name.md")
        if (file.exists()) return ExecutionResult.fail("本地 Skill 已存在: $name\n使用 skill.run $name 执行，或 skill.info $name 查看。", errorCode = ErrorCodes.ERR_INTERNAL)
        val template = buildSkillTemplate(name, category, description)
        return try {
            file.writeText(template)
            ExecutionResult.ok("✅ Skill '$name' 已创建到 Agent 本地。\n\n| 属性 | 值 |\n|------|-----|\n| 分类 | $category |\n| 路径 | ${file.absolutePath} |\n\n使用 skill.run $name 执行。使用 skill.push $name 上传到全局池。")
        } catch (e: Exception) { ErrorCollector.report(e, "SkillPlugin.create"); ExecutionResult.fail("创建失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    suspend fun rm(plugin: SkillPlugin, args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: skill.rm <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val name = args[0]
        // P1 修复: 路径消毒 — 拒绝越过技能根目录的名称
        val file = plugin.skillFile(plugin.localDir(ctx.agentName ?: ""), name)
            ?: return ExecutionResult.fail("非法技能名: $name (不能包含路径分隔符或穿越段)", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        if (!file.exists()) return ExecutionResult.fail("本地未找到 Skill: $name\n使用 skill.ls --local 查看本地技能。", errorCode = ErrorCodes.ERR_NOT_FOUND)
        return try { file.delete(); ExecutionResult.ok("Skill '$name' 已从本地删除。") }
        catch (e: Exception) { ErrorCollector.report(e, "SkillPlugin.rm"); ExecutionResult.fail("删除失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    suspend fun pull(plugin: SkillPlugin, args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: skill.pull <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val name = args[0]
        // P1 修复: 路径消毒 — 拒绝越过技能根目录的名称
        val source = plugin.skillFile(plugin.globalDir, name)
            ?: return ExecutionResult.fail("非法技能名: $name (不能包含路径分隔符或穿越段)", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        if (!source.exists()) return ExecutionResult.fail("全局池中未找到 Skill: $name\n使用 skill.ls 查看全局可用技能。", errorCode = ErrorCodes.ERR_NOT_FOUND)
        val targetDir = plugin.localDir(ctx.agentName ?: ""); val target = File(targetDir, "$name.md")
        if (target.exists()) return ExecutionResult.ok("Skill '$name' 已在本地。使用 skill.run $name 执行。")
        return try { source.copyTo(target, overwrite = false); ExecutionResult.ok("Skill '$name' 已从全局池拉取到本地。\n使用 skill.run $name 执行。") }
        catch (e: Exception) { ErrorCollector.report(e, "SkillPlugin.pull"); ExecutionResult.fail("拉取失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    suspend fun push(plugin: SkillPlugin, args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: skill.push <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val name = args[0]
        // P1 修复: 路径消毒 — 拒绝越过技能根目录的名称
        val source = plugin.skillFile(plugin.localDir(ctx.agentName ?: ""), name)
            ?: return ExecutionResult.fail("非法技能名: $name (不能包含路径分隔符或穿越段)", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        if (!source.exists()) return ExecutionResult.fail("本地未找到 Skill: $name\n使用 skill.ls --local 查看本地技能。", errorCode = ErrorCodes.ERR_NOT_FOUND)
        val target = File(plugin.globalDir, "$name.md"); val exists = target.exists()
        return try { source.copyTo(target, overwrite = true); val msg = if (exists) "已覆盖" else "已上传"; ExecutionResult.ok("Skill '$name' $msg 到全局池。\n现在所有 Agent 都可通过 skill.run $name 使用。") }
        catch (e: Exception) { ErrorCollector.report(e, "SkillPlugin.push"); ExecutionResult.fail("上传失败: ${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    suspend fun enable(plugin: SkillPlugin, args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: skill.enable <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        return if (setEnabled(plugin, args[0], true, ctx.agentName)) ExecutionResult.ok("Enabled: ${args[0]}")
        else ExecutionResult.fail("未找到技能: ${args[0]}\n使用 skill.ls 或 skill.ls --local 查看。", errorCode = ErrorCodes.ERR_NOT_FOUND)
    }

    suspend fun disable(plugin: SkillPlugin, args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: skill.disable <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        return if (setEnabled(plugin, args[0], false, ctx.agentName)) ExecutionResult.ok("Disabled: ${args[0]}")
        else ExecutionResult.fail("未找到技能: ${args[0]}\n使用 skill.ls 或 skill.ls --local 查看。", errorCode = ErrorCodes.ERR_NOT_FOUND)
    }

    /**
     * 切换技能启停 — 与 skill.run 查找顺序一致: 先本地后全局。
     * P1 修复: 路径消毒经 skillFile canonicalPath 前缀校验, 拒绝越过技能根目录。
     */
    fun setEnabled(plugin: SkillPlugin, name: String, enabled: Boolean, agentName: String? = null): Boolean {
        val localFile = agentName?.let { plugin.skillFile(plugin.localDir(it), name) }
        val file = localFile?.takeIf { it.exists() } ?: plugin.skillFile(plugin.globalDir, name)?.takeIf { it.exists() } ?: return false
        val text = try { file.readText() } catch (_: Exception) { return false }
        val newContent = text.replace(Regex("(?m)^enabled:\\s*(true|false)"), "enabled: $enabled")
        return try { file.writeText(newContent); true } catch (e: Exception) { ErrorCollector.report(e, "SkillPlugin.setEnabled"); false }
    }

    /** internal 为测试可见性 (模板生成单测)。 */
    internal fun buildSkillTemplate(name: String, category: String, description: String): String {
        val hints = when (category) {
            "dev" -> "## 执行步骤\n1. 分析代码结构\n2. 执行开发任务\n3. 验证结果\n4. 汇报完成情况"
            "office" -> "## 执行步骤\n1. 确认需求\n2. 使用 agent.write 生成文档\n3. 检查输出质量\n4. 交付确认\n\n使用 {{param}} 占位符实现参数化。"
            "browser" -> "## 执行步骤\n1. 打开目标页面\n2. 数据采集/操作\n3. 整理结果\n4. 保存或汇报"
            "system" -> "## 执行步骤\n1. 使用 self.status 获取系统状态\n2. 分析诊断\n3. 执行维护\n4. 记录结果\n\n## 安全\n修改系统配置前需用户确认。"
            "meta" -> "## 执行步骤\n1. 分析目标\n2. 制定 Skill 结构\n3. 使用 skill.create 或 agent.write 写入\n4. 使用 skill.info 验证"
            else -> "## 执行步骤\n1. 确认任务目标\n2. 使用 self.tools 确认可用命令\n3. 逐步执行\n4. 汇报结果"
        }
        return "---\nname: $name\ndescription: $description\nenabled: true\ncategory: $category\n---\n# $name\n\n$hints\n"
    }
}
