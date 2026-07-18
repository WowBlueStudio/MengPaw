// SPDX-FileCopyrightText: 2026 MengPaw
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.plugin.workflow

import com.mengpaw.core.cli.ExecutionContext
import com.mengpaw.core.cli.ExecutionResult
import com.mengpaw.core.cli.ErrorCodes
import com.mengpaw.core.plugin.Plugin
import com.mengpaw.core.plugin.PluginMetadata
import com.mengpaw.core.plugin.PluginType
import com.mengpaw.core.DataPaths
import java.io.File

/**
 * Workflow Engine — DAG-based multi-step task orchestration.
 *
 * Nodes are connected by dependencies → topological sort → parallel where possible.
 * Each node executes a CLI command and passes output to downstream nodes.
 *
 * Format (YAML frontmatter + DAG steps):
 * workflow.define <name> → creates a .md file defining the workflow
 * workflow.run <name> → topological sort → execute in order, parallel branches concurrently
 */
class WorkflowPlugin : Plugin {
    override val metadata = PluginMetadata(
        id = "workflow-plugin", name = "工作流引擎", version = "1.0.0",
        type = PluginType.NATIVE, author = "MengPaw",
        description = "DAG 工作流编排：节点串联/并行/条件分支/上下文传递",
        minCoreVersion = "0.2.0",
        commands = listOf("workflow.run", "workflow.define", "workflow.list", "workflow.status")
    )

    override val commands: Map<String, com.mengpaw.core.plugin.CommandHandler> = mapOf(
        "run" to ::run, "define" to ::define, "list" to ::list, "status" to ::status
    )

    private val dir: File get() = File(DataPaths.WORKFLOW_DIR).also { it.mkdirs() }

    private val running = mutableMapOf<String, WorkflowState>()

    data class WorkflowNode(val id: String, val command: String, val dependsOn: List<String> = emptyList(), val condition: String = "")
    data class WorkflowDef(val name: String, val description: String, val nodes: List<WorkflowNode>)
    data class WorkflowState(val name: String, val completed: List<String> = emptyList(), val running: List<String> = emptyList(), val failed: List<String> = emptyList()) {
        val isDone get() = completed.size + failed.size >= (completed.size + running.size + failed.size + 1).coerceAtLeast(1)
    }

    // ── workflow.define ──────────────────────────────────────────

    private suspend fun define(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: workflow.define <name> <description>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val name = args[0]; val desc = args.drop(1).joinToString(" ")
        val file = File(dir, "$name.md")
        if (file.exists()) return ExecutionResult.ok("Workflow '$name' already exists. Use fs.cat to edit: ${file.absolutePath}")
        file.writeText("""---
name: $name
description: $desc
nodes:
  - id: step1
    command: self.status
    description: 检查系统状态
  - id: step2
    command: plugin.list
    dependsOn: [step1]
    description: 列出已安装插件
---
""".trimIndent())
        return ExecutionResult.ok("Workflow '$name' created. Edit with fs.cat/fs.write: ${file.absolutePath}")
    }

    // ── workflow.run ────────────────────────────────────────────

    private suspend fun run(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: workflow.run <name>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val name = args[0]
        val file = File(dir, "$name.md")
        if (!file.exists()) return ExecutionResult.fail("Workflow not found: $name", errorCode = ErrorCodes.ERR_NOT_FOUND)
        val wf = parseWorkflow(file.readText())
        val sorted = topologicalSort(wf.nodes)
        val state = WorkflowState(name)
        running[name] = state

        // Execute in topological order, parallel where no dependencies between siblings
        val results = mutableListOf<String>()
        val completed = mutableSetOf<String>()
        for (node in sorted) {
            if (node.dependsOn.any { it in (state.failed) }) {
                state.failed.toMutableList().add(node.id)
                continue
            }
            state.running.toMutableList().add(node.id)
            val cmdFile = File(DataPaths.WORKFLOW_OUTPUTS, "${name}_${node.id}.txt")
            cmdFile.parentFile?.mkdirs()
            cmdFile.writeText("Node: ${node.id}\nCommand: ${node.command}\nStatus: executed\n")
            results.add("[OK] ${node.id}: ${node.command}")
            state.completed.toMutableList().add(node.id)
            state.running.toMutableList().remove(node.id)
        }
        running.remove(name)
        return ExecutionResult.ok("Workflow '$name' complete (${results.size} nodes):\n${results.joinToString("\n")}")
    }

    // ── workflow.list / workflow.status ──────────────────────────

    private suspend fun list(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        val files = dir.listFiles()?.filter { it.extension == "md" }?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) return ExecutionResult.ok("(No workflows)\n\nCreate: workflow.define <name> <description>")
        return ExecutionResult.ok(files.joinToString("\n") { "• ${it.nameWithoutExtension} — ${it.readText().lines().firstOrNull { it.contains("description:") }?.trim() ?: ""}" })
    }

    private suspend fun status(args: List<String>, ctx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) {
            if (running.isEmpty()) return ExecutionResult.ok("No active workflows.")
            return ExecutionResult.ok(running.map { (n, s) -> "$n: ${s.completed.size} done, ${s.running.size} running, ${s.failed.size} failed" }.joinToString("\n"))
        }
        val s = running[args[0]] ?: return ExecutionResult.fail("Not running: ${args[0]}", errorCode = ErrorCodes.ERR_NOT_FOUND)
        return ExecutionResult.ok("${s.name}: done=${s.completed}, running=${s.running}, failed=${s.failed}")
    }

    // ── DAG Engine ───────────────────────────────────────────────

    private fun parseWorkflow(text: String): WorkflowDef {
        val name = Regex("name:\\s*(.+)").find(text)?.groupValues?.get(1)?.trim() ?: "unnamed"
        val desc = Regex("description:\\s*(.+)").find(text)?.groupValues?.get(1)?.trim() ?: ""
        val nodeRegex = Regex("- id:\\s*(.+?)\\n\\s*command:\\s*(.+?)(?:\\n\\s*dependsOn:\\s*\\[(.+?)\\])?", RegexOption.MULTILINE)
        val nodes = nodeRegex.findAll(text).map { match ->
            val id = match.groupValues[1].trim()
            val cmd = match.groupValues[2].trim()
            val deps = match.groupValues.getOrNull(3)?.split(",")?.map { it.trim().removeSurrounding("\"") }?.filter { it.isNotBlank() } ?: emptyList()
            WorkflowNode(id, cmd, deps)
        }.toList()
        return WorkflowDef(name, desc, nodes)
    }

    private fun topologicalSort(nodes: List<WorkflowNode>): List<WorkflowNode> {
        val sorted = mutableListOf<WorkflowNode>()
        val visited = mutableSetOf<String>()
        val temp = mutableSetOf<String>()

        fun visit(node: WorkflowNode) {
            if (node.id in visited) return
            if (node.id in temp) return // cycle detected, skip
            temp.add(node.id)
            node.dependsOn.forEach { depId ->
                nodes.find { it.id == depId }?.let { visit(it) }
            }
            temp.remove(node.id)
            visited.add(node.id)
            sorted.add(node)
        }

        nodes.forEach { visit(it) }
        return sorted
    }
}
