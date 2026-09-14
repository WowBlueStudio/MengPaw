// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.harness

/**
 * Harness 平台抽象 — 逻辑路径解析 (A 阶段, 2026-08-21)。
 *
 * 存在意义: kernel 现有 [com.mengpaw.kernel.DataPaths] 是可变全局单例 (`BASE` 靠启动时
 * initialize 注入), 且全部路径以字符串拼接暴露给 152 个调用点。这带来三个跨平台障碍:
 *  ① 全局可变状态 → 同一进程无法跑两个独立 harness 实例;
 *  ② 宿主必须理解 MengPaw 的目录布局 (中文目录名) 才能初始化;
 *  ③ iOS/JS 无 `/sdcard/...` 语义, 路径形态不一致。
 *
 * 本接口把「逻辑路径」与「物理路径」解耦: harness 内部只请求 [configDir] / [agentDir] 等
 * **逻辑位置**, 由宿主决定映射到何种文件系统布局。基类 [BaseDirPathResolver] 提供
 * 与旧 DataPaths 完全一致的默认布局 (单一 baseDir + 中文目录名), 保证行为零变化。
 */
interface HarnessPathResolver {

    /** 数据根目录 — 所有内置路径的锚点。 */
    val baseDir: String

    /** 宿主可写输出目录 (用户可见的导出产物)。 */
    val outputDir: String

    val configDir: String
    val skillsDir: String
    val pluginDir: String
    val checkpointDir: String
    val recordingDir: String
    val errorDir: String

    /** 全部 Agent 工作区的父目录。 */
    val agentsDir: String
    /** 无主进化档案目录 (agentName 为空时的归属地)。 */
    val evolutionDir: String
    /** 局域网互传共享目录。 */
    val fleetShareDir: String
    /** Agent 模板目录。 */
    val agentTemplatesDir: String
    /** IPC socket 路径 (仅 Unix 类宿主有意义; 非 Unix 宿主返回空串)。 */
    val socketPath: String

    /** 单个 Agent 的工作区根目录 (agentName 已消毒)。 */
    fun agentDir(agentName: String): String

    /** Agent 记忆目录 — 三轨记忆 (long/mid/project) 的物理落点。 */
    fun memoryDir(agentName: String): String

    /** Agent 对话归档目录 (压缩前的原始 dialog)。 */
    fun dialogArchiveDir(agentName: String): String

    /** Agent 工具结果外存目录 (长输出离屏)。 */
    fun toolResultsDir(agentName: String): String

    /** Agent 本地技能目录。 */
    fun agentSkillsDir(agentName: String): String

    /** Agent 本地工具目录 (agent 自建 CLI 命令)。 */
    fun agentToolsDir(agentName: String): String

    /** Agent 进化档案目录 (agentName 为空/默认时回落 [evolutionDir])。 */
    fun agentEvolutionDir(agentName: String?): String

    /** 路径段安全化 — 防路径穿越。所有接收外部 agentName 的实现必须走此函数。 */
    fun sanitizeSegment(raw: String): String
}

/**
 * 默认路径解析器 — 布局与旧 [com.mengpaw.kernel.DataPaths] 完全一致。
 *
 * @param baseDir 数据根目录。Android 传 context.filesDir, JVM/桌面传任意可写目录。
 *   **不再提供全局默认值** — 宿主必须显式给出, 消除「忘记 initialize」的隐患。
 * @param outputDir 用户可见输出目录; 留空则回落 `baseDir/输出`。
 * @param socketPath IPC socket 路径; 留空则回落 `baseDir/mengpaw.sock`。
 */
class BaseDirPathResolver(
    override val baseDir: String,
    outputOverride: String? = null,
    socketOverride: String? = null,
    /** 目录中文名可覆盖 — 非中文宿主可保持目录名 ASCII, 不影响逻辑路径。 */
    private val names: DirectoryNames = DirectoryNames.CHINESE
) : HarnessPathResolver {

    /** 目录命名集 — 默认沿用 MengPaw 既定中文目录名 (与旧 DataPaths 逐字一致)。 */
    data class DirectoryNames(
        val config: String = "配置",
        val skills: String = "技能剧本",
        val pluginCache: String = "插件仓库",
        val checkpoints: String = "会话检查点",
        val recordings: String = "录音",
        val errors: String = "错误报告",
        val agents: String = "Agent文档",
        val evolution: String = "进化档案",
        val fleetShare: String = "Fleet共享",
        val agentTemplates: String = "agent-templates",
        val output: String = "输出"
    ) {
        companion object {
            val CHINESE = DirectoryNames()
            /** ASCII 目录名 — 供对中文路径过敏的宿主 (如某些 CI 镜像) 使用。 */
            val ASCII = DirectoryNames(
                config = "config", skills = "skills", pluginCache = "plugins",
                checkpoints = "checkpoints", recordings = "recordings", errors = "errors",
                agents = "agents", evolution = "evolution", fleetShare = "fleet-share",
                agentTemplates = "agent-templates", output = "output"
            )
        }
    }

    override val outputDir: String = outputOverride?.takeIf { it.isNotBlank() } ?: "$baseDir/${names.output}"
    override val socketPath: String = socketOverride?.takeIf { it.isNotBlank() } ?: "$baseDir/mengpaw.sock"

    override val configDir: String get() = "$baseDir/${names.config}"
    override val skillsDir: String get() = "$baseDir/${names.skills}"
    override val pluginDir: String get() = "$baseDir/${names.pluginCache}"
    override val checkpointDir: String get() = "$baseDir/${names.checkpoints}"
    override val recordingDir: String get() = "$baseDir/${names.recordings}"
    override val errorDir: String get() = "$baseDir/${names.errors}"
    override val agentsDir: String get() = "$baseDir/${names.agents}"
    override val evolutionDir: String get() = "$baseDir/${names.evolution}"
    override val fleetShareDir: String get() = "$baseDir/${names.fleetShare}"
    override val agentTemplatesDir: String get() = "$baseDir/${names.agentTemplates}"

    override fun agentDir(agentName: String): String = "$agentsDir/${sanitizeSegment(agentName)}"

    override fun memoryDir(agentName: String): String = "${agentDir(agentName)}/memory"

    override fun dialogArchiveDir(agentName: String): String = "${agentDir(agentName)}/dialog"

    override fun toolResultsDir(agentName: String): String = "${agentDir(agentName)}/tool_results"

    override fun agentSkillsDir(agentName: String): String = "${agentDir(agentName)}/skills"

    override fun agentToolsDir(agentName: String): String = "${agentDir(agentName)}/tools"

    override fun agentEvolutionDir(agentName: String?): String =
        if (agentName.isNullOrBlank() || agentName == DEFAULT_AGENT) evolutionDir
        else "${agentDir(agentName)}/evolution"

    override fun sanitizeSegment(raw: String): String = raw.replace(SEPARATOR_REGEX, "_")

    private companion object {
        /** 与 EvolutionStore.DEFAULT_AGENT 对齐的保留字 (无主档案归属判据)。 */
        const val DEFAULT_AGENT = "default"
        val SEPARATOR_REGEX = Regex("[/\\\\]")
    }
}
