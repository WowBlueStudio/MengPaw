// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.plugin.skill

import com.mengpaw.kernel.DataPaths
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.llm.LlmProvider
import com.mengpaw.kernel.llm.ProviderInfo
import com.mengpaw.kernel.llm.ProviderType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * skill 命令流集成测试 (P1 路径消毒在命令层的落实 + 参数替换)。
 * 每测试独立临时 DataPaths — 注意 SkillPlugin 构造时捕获 storageDir,
 * 必须先 DataPaths.initialize 再构造插件。
 */
class SkillCommandsTest {

    private lateinit var plugin: SkillPlugin
    private val ctx = ExecutionContext(sessionId = "skill-test", agentName = "tester")

    @Before
    fun setUp() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "mengpaw-skill-test-${System.currentTimeMillis()}")
        tmp.mkdirs()
        DataPaths.initialize(tmp.absolutePath)
        plugin = SkillPlugin()
        SkillPlugin.llmProvider = null
    }

    private suspend fun run(cmd: String, vararg args: String) =
        plugin.commands[cmd]!!(args.toList(), ctx)

    /** 按序返回响应的脚本化 LLM — from.project 提炼测试用。 */
    private class ScriptedLlmProvider(responses: List<String>) : LlmProvider {
        private val queue = ArrayDeque(responses)
        override suspend fun complete(prompt: String): String = queue.removeFirst()
        override suspend fun completeStreaming(prompt: String, onToken: (String) -> Unit): String =
            complete(prompt).also { onToken(it) }
        override fun info(): ProviderInfo = ProviderInfo("mock", "skill-test", ProviderType.LOCAL)
        override fun close() {}
    }

    // ── 名称合法性 (create 正则) ────────────────────────────────────────

    @Test
    fun `create rejects traversal and invalid names`() = runBlocking {
        assertFalse("穿越名应拒绝", run("create", "../evil").success)
        assertFalse("含空格应拒绝", run("create", "my skill").success)
        assertFalse("非 ASCII 应拒绝", run("create", "技能").success)
    }

    // ── 创建与落盘 ──────────────────────────────────────────────────────

    @Test
    fun `create writes skill to agent local dir`() = runBlocking {
        val r = run("create", "test-skill", "--category", "dev", "--description", "测试技能")
        assertTrue("合法创建应成功: ${r.error}", r.success)
        val file = File(DataPaths.agentSkillsDir("tester"), "test-skill.md")
        assertTrue("技能文件应写入 Agent 本地", file.exists())
        assertTrue(file.readText().contains("category: dev"))
    }

    @Test
    fun `unknown category falls back to general`() = runBlocking {
        val r = run("create", "cat-skill", "--category", "nonsense")
        assertTrue(r.success)
        val file = File(DataPaths.agentSkillsDir("tester"), "cat-skill.md")
        assertTrue(file.readText().contains("category: general"))
    }

    // ── 执行与参数替换 ──────────────────────────────────────────────────

    @Test
    fun `run substitutes key value params`() = runBlocking {
        run("create", "greet")
        val file = File(DataPaths.agentSkillsDir("tester"), "greet.md")
        file.writeText("---\nname: greet\ndescription: 问候\nenabled: true\ncategory: general\n---\n你好 {{who}}!")
        val r = run("run", "greet", "who=世界")
        assertTrue("执行应成功: ${r.error}", r.success)
        assertTrue("{{who}} 应被替换", r.output.contains("你好 世界!"))
    }

    @Test
    fun `run falls back to global pool when local missing`() = runBlocking {
        File(DataPaths.SKILLS).mkdirs()
        File(DataPaths.SKILLS, "global-skill.md").writeText(
            "---\nname: global-skill\ndescription: 全局\nenabled: true\ncategory: general\n---\n全局技能正文"
        )
        val r = run("run", "global-skill")
        assertTrue(r.success)
        assertTrue(r.output.contains("全局技能正文"))
    }

    // ── P1 消毒在命令层落实 ─────────────────────────────────────────────

    @Test
    fun `run rejects traversal name`() = runBlocking {
        val r = run("run", "../secret")
        assertFalse("穿越名不应命中任何技能", r.success)
        assertTrue((r.error ?: "").contains("not found", ignoreCase = true))
    }

    @Test
    fun `rm rejects traversal name as illegal`() = runBlocking {
        val r = run("rm", "../x")
        assertFalse(r.success)
        assertTrue((r.error ?: "").contains("非法技能名"))
    }

    // ── 状态控制 ────────────────────────────────────────────────────────

    @Test
    fun `run disabled skill is denied`() = runBlocking {
        run("create", "off-skill")
        val f = File(DataPaths.agentSkillsDir("tester"), "off-skill.md")
        f.writeText(f.readText().replace("enabled: true", "enabled: false"))
        val r = run("run", "off-skill")
        assertFalse("停用技能应拒绝执行", r.success)
        assertTrue((r.error ?: "").contains("disabled", ignoreCase = true))
    }

    @Test
    fun `info shows skill details and params`() = runBlocking {
        run("create", "showcase")
        val f = File(DataPaths.agentSkillsDir("tester"), "showcase.md")
        f.writeText("---\nname: showcase\ndescription: 演示\nenabled: true\ncategory: meta\n---\n使用 {{param}} 参数")
        val r = run("info", "showcase")
        assertTrue(r.success)
        assertTrue(r.output.contains("演示"))
        assertTrue("占位符应列出", r.output.contains("{{param}}"))
    }

    // ── 来源标签 (source) — 预置技能与用户技能的区分 ────────────────────

    @Test
    fun `parseSkill reads source from frontmatter`() = runBlocking {
        // 预置技能 (plugin 来源) — 全局池文件带 source 标记
        File(DataPaths.SKILLS).mkdirs()
        val preset = File(DataPaths.SKILLS, "tavily.md")
        preset.writeText("---\nname: tavily\ndescription: AI 搜索\nenabled: true\ncategory: general\nsource: plugin\n---\n正文")
        val parsed = plugin.parseSkill(preset)
        assertNotNull(parsed)
        assertEquals("插件来源应解析", "plugin", parsed!!.source)
        // 用户技能 (create 模板) — 无 source
        run("create", "mine")
        val mine = File(DataPaths.agentSkillsDir("tester"), "mine.md")
        val parsedMine = plugin.parseSkill(mine)
        assertEquals("用户创建技能 source 应为空", "", parsedMine!!.source)
    }

    @Test
    fun `ls shows source column with labels`() = runBlocking {
        File(DataPaths.SKILLS).mkdirs()
        File(DataPaths.SKILLS, "core-skill.md").writeText(
            "---\nname: core-skill\ndescription: 核心手册\nenabled: true\ncategory: system\nsource: core\n---\n正文")
        File(DataPaths.SKILLS, "user-skill.md").writeText(
            "---\nname: user-skill\ndescription: 用户技能\nenabled: true\ncategory: general\n---\n正文")
        val r = run("ls")
        assertTrue(r.success)
        assertTrue("来源列头应存在", r.output.contains("来源"))
        assertTrue("核心标签应显示", r.output.contains("核心"))
        assertTrue("用户技能应标用户", r.output.contains("用户"))
    }

    // ── 技能派生: skill.from.project ────────────────────────────────────

    @Test
    fun `from project derives skill with evolution goal and source`() = runBlocking {
        writeProjectMemory("demo", "## 2026-08-16 10:00 · 里程碑总结\n\n1. 分析需求\n2. 使用 agent.write 生成页面\n3. 验证渲染结果\n4. 汇报\n---")
        SkillPlugin.llmProvider = ScriptedLlmProvider(listOf(
            "OK\n---\nname: theme-workflow\ndescription: 为公众号文章排版引擎新增主题的工作流\nenabled: true\ncategory: dev\n---\n" +
                "# 主题工作流\n## 适用场景\n需要新增渲染主题时\n## 执行步骤\n1. 分析\n## 验证规则\n页面可渲染\n## 来源\nproject_demo · 项目记忆\n" +
                "## 进化目标\n- 目标: 覆盖主流主题新增\n- 稳定锚点: 渲染管线不变\n- 收敛原则: 升级朝目标收敛"
        ))
        val r = run("from.project", "demo")
        assertTrue("派生应成功: ${r.error}", r.success)
        val file = File(DataPaths.agentSkillsDir("tester"), "theme-workflow.md")
        assertTrue("技能文件应写入本地", file.exists())
        val text = file.readText()
        assertTrue("应含进化目标三要素", text.contains("## 进化目标"))
        assertTrue("应含来源", text.contains("## 来源"))
        assertTrue("frontmatter 应含 description", text.contains("description: 为公众号"))
    }

    @Test
    fun `from project not flow returns without writing`() = runBlocking {
        writeProjectMemory("facts", "## 2026-08-16 10:00 · 里程碑总结\n\n项目使用蓝色主题, 用户偏好简洁风格。\n---")
        SkillPlugin.llmProvider = ScriptedLlmProvider(listOf("NOT_FLOW\n仅事实记录"))
        val r = run("from.project", "facts")
        assertTrue(r.success)
        assertTrue("应提示无可流程化", r.output.contains("无可流程化"))
        assertTrue("不应写入技能", File(DataPaths.agentSkillsDir("tester")).listFiles().isNullOrEmpty())
    }

    @Test
    fun `from project duplicate aborts by description semantics`() = runBlocking {
        writeProjectMemory("dup", "## 2026-08-16 10:00 · 里程碑总结\n\n1. 步骤\n---")
        SkillPlugin.llmProvider = ScriptedLlmProvider(listOf("DUPLICATE\n已有技能: weather"))
        val r = run("from.project", "dup")
        assertFalse(r.success)
        assertTrue((r.error ?: "").contains("已有相似技能"))
        assertTrue("应带出已有技能名", (r.error ?: "").contains("weather"))
    }

    @Test
    fun `from project rejects same name conflict`() = runBlocking {
        writeProjectMemory("conf", "## 2026-08-16 10:00 · 里程碑总结\n\n1. 步骤\n---")
        run("create", "same-name")
        SkillPlugin.llmProvider = ScriptedLlmProvider(listOf(
            "OK\n---\nname: same-name\ndescription: 同名冲突\nenabled: true\ncategory: general\n---\n# X\n## 适用场景\n## 执行步骤\n## 验证规则\n## 来源\nproject_conf · 项目记忆\n## 进化目标\n- 目标\n- 稳定锚点\n- 收敛原则"
        ))
        val r = run("from.project", "conf")
        assertFalse(r.success)
        assertTrue((r.error ?: "").contains("已存在同名技能"))
    }

    @Test
    fun `from project missing memory not found`() = runBlocking {
        val r = run("from.project", "nope")
        assertFalse(r.success)
        assertTrue((r.error ?: "").contains("未找到项目记忆"))
    }

    @Test
    fun `from project rejects traversal project name`() = runBlocking {
        val r = run("from.project", "../escape")
        assertFalse(r.success)
        assertTrue((r.error ?: "").contains("非法项目名"))
    }

    @Test
    fun `from project without llm fails gracefully`() = runBlocking {
        writeProjectMemory("x", "## 2026-08-16 10:00 · 里程碑总结\n\n1. 步骤\n---")
        SkillPlugin.llmProvider = null
        val r = run("from.project", "x")
        assertFalse(r.success)
        assertTrue((r.error ?: "").contains("LLM 未就绪"))
    }

    @Test
    fun `from project malformed llm output fails`() = runBlocking {
        writeProjectMemory("y", "## 2026-08-16 10:00 · 里程碑总结\n\n1. 步骤\n---")
        SkillPlugin.llmProvider = ScriptedLlmProvider(listOf("随便输出"))
        val r = run("from.project", "y")
        assertFalse(r.success)
        assertTrue((r.error ?: "").contains("格式异常"))
    }

    // ── 技能索取: skill.request + skill.ls --agent ──────────────────────

    @Test
    fun `request copies skill from another agent and stamps source`() = runBlocking {
        val srcDir = File(DataPaths.agentSkillsDir("alice")); srcDir.mkdirs()
        File(srcDir, "alice-trick.md").writeText(
            "---\nname: alice-trick\ndescription: 数据整理技巧\nenabled: true\ncategory: general\n---\n## 执行步骤\n1. 整理\n2. 验证"
        )
        val r = run("request", "alice-trick", "alice")
        assertTrue("索取应成功: ${r.error}", r.success)
        val mine = File(DataPaths.agentSkillsDir("tester"), "alice-trick.md")
        assertTrue("技能应复制到本地", mine.exists())
        assertTrue("应补来源标记", mine.readText().contains("索取自 Agent `alice`"))
    }

    @Test
    fun `request same name conflict aborts`() = runBlocking {
        val srcDir = File(DataPaths.agentSkillsDir("alice")); srcDir.mkdirs()
        File(srcDir, "trick.md").writeText("---\nname: trick\ndescription: 技巧\nenabled: true\ncategory: general\n---\n正文")
        run("create", "trick")
        val r = run("request", "trick", "alice")
        assertFalse(r.success)
        assertTrue((r.error ?: "").contains("已存在同名技能"))
    }

    @Test
    fun `request duplicate description aborts`() = runBlocking {
        val srcDir = File(DataPaths.agentSkillsDir("alice")); srcDir.mkdirs()
        File(srcDir, "alice-way.md").writeText("---\nname: alice-way\ndescription: 批量处理资料\nenabled: true\ncategory: general\n---\n正文")
        run("create", "mine-way", "--description", "批量处理资料")
        val r = run("request", "alice-way", "alice")
        assertFalse(r.success)
        assertTrue((r.error ?: "").contains("简介相同"))
    }

    @Test
    fun `request missing skill not found`() = runBlocking {
        val r = run("request", "ghost", "alice")
        assertFalse(r.success)
        assertTrue((r.error ?: "").contains("未找到"))
    }

    @Test
    fun `request rejects traversal skill name`() = runBlocking {
        val r = run("request", "../secret", "alice")
        assertFalse(r.success)
        assertTrue((r.error ?: "").contains("非法技能名"))
    }

    @Test
    fun `request self rejected`() = runBlocking {
        val r = run("request", "x", "tester")
        assertFalse(r.success)
        assertTrue((r.error ?: "").contains("不能向自己"))
    }

    @Test
    fun `ls agent lists another agent skills`() = runBlocking {
        val srcDir = File(DataPaths.agentSkillsDir("bob")); srcDir.mkdirs()
        File(srcDir, "bob-skill.md").writeText("---\nname: bob-skill\ndescription: 鲍勃技能\nenabled: true\ncategory: general\n---\n正文")
        val r = run("ls", "--agent", "bob")
        assertTrue(r.success)
        assertTrue("应列出目标 Agent 技能", r.output.contains("bob-skill"))
        assertTrue("应带描述", r.output.contains("鲍勃技能"))
        assertTrue("应提示索取入口", r.output.contains("skill.request"))
    }

    private fun writeProjectMemory(name: String, content: String) {
        val dir = File(DataPaths.midTermMemoryDir("tester")); dir.mkdirs()
        File(dir, "project_${name}_memory.md").writeText(content)
    }
}
