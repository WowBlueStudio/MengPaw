// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.agent

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult

/**
 * Built-in agent.memory.* CLI commands — 记忆三轨管理 (长期/中期/项目)。
 *
 * 从 AgentExecutor.kt 拆出 (2026-08-01, ≥50KB 文件拆分): 本类 18 条 memory 系命令,
 * 只依赖 AgentDocs/DataPaths, 无 UI/文档依赖; AgentExecutor 经
 * `val commands = 通用命令 + memoryExecutor.commands` 合并注册, 命名空间不变 (agent.*)。
 * 只读命令已拆至 [AgentMemoryReadCommands], 写/改/删命令拆至
 * [AgentMemoryMutateCommands] (400 行文件拆分)。
 */
class AgentMemoryExecutor {

    /** 只读命令 (memory/memory.read/memory.search/memory.stats/memory.mid/memory.project)。 */
    private val readCommands = AgentMemoryReadCommands()

    /** 写/改/删命令 (record/keep/write/project.save/rm/edit 及 mid/project 系增删改)。 */
    private val mutateCommands = AgentMemoryMutateCommands()

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "memory" to readCommands::memory,
        "memory.record" to mutateCommands::memoryRecord,
        "memory.keep" to mutateCommands::memoryKeep,
        "memory.read" to readCommands::memoryRead,
        "memory.search" to readCommands::memorySearch,
        "memory.stats" to readCommands::memoryStats,
        "memory.write" to mutateCommands::memoryWrite,
        "memory.mid" to readCommands::memoryMid,
        "memory.project" to readCommands::memoryProject,
        "memory.project.save" to mutateCommands::memoryProjectSave,
        "memory.project.delete" to mutateCommands::memoryProjectDelete,
        "memory.mid.delete" to mutateCommands::memoryMidDelete,
        "memory.rm" to mutateCommands::memoryRm,
        "memory.edit" to mutateCommands::memoryEdit,
        "memory.mid.rm" to mutateCommands::memoryMidRm,
        "memory.mid.edit" to mutateCommands::memoryMidEdit,
        "memory.project.rm" to mutateCommands::memoryProjectRm,
        "memory.project.edit" to mutateCommands::memoryProjectEdit
    )
}

/** Resolve the effective agent name, falling back to default. */
internal fun agentName(ctx: ExecutionContext) = ctx.agentName ?: "agent"

/**
 * 火种模式 (scope="swarm") 的 worker 是零待命临时执行体——
 * 禁止写记忆, 防止并行 worker 向 Agent 三轨记忆注入噪音。
 */
/** 零待命并行 worker（swarm + 历史 mission 会话兼容）屏蔽记忆写命令。 */
internal fun swarmWriteBlocked(ctx: ExecutionContext): Boolean =
    ctx.scope == "swarm" || ctx.scope == "mission"
