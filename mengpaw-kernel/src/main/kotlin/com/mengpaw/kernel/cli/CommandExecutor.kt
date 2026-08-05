// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.kernel.cli

/**
 * Standard error codes for CLI command execution.
 */
object ErrorCodes {
    const val ERR_NOT_FOUND = "ERR_NOT_FOUND"
    const val ERR_PERMISSION_DENIED = "ERR_PERMISSION_DENIED"
    const val ERR_INVALID_INPUT = "ERR_INVALID_INPUT"
    const val ERR_INTERNAL = "ERR_INTERNAL"
    const val ERR_TIMEOUT = "ERR_TIMEOUT"
    const val ERR_IO = "ERR_IO"
    /** 参数格式与命令签名不匹配（如模型发 JSON，命令期望 CLI 纯文本）。 */
    const val PARAM_FORMAT_ERROR = "PARAM_FORMAT_ERROR"
    /** 插件/资源下载失败（HTTP 错误、文件损坏等）。 */
    const val DOWNLOAD_FAILED = "DOWNLOAD_FAILED"
    /** 网络不可达/离线（连接超时、断网、全部源失败）。 */
    const val NETWORK_OFFLINE = "NETWORK_OFFLINE"
}

/**
 * Represents the result of executing a CLI command.
 */
data class ExecutionResult(
    val success: Boolean,
    val output: String,
    val error: String? = null,
    val exitCode: Int = if (success) 0 else 1,
    val errorCode: String? = null
) {
    companion object {
        fun ok(output: String) = ExecutionResult(true, output)
        fun fail(error: String, code: Int = 1, errorCode: String? = null) =
            ExecutionResult(false, "", error, code, errorCode)
    }
}

/**
 * Context carrying metadata for command execution.
 *
 * @param scope lifecycle scope: "agent" (default) / "framework" / "system" / "swarm".
 *        "swarm" = 火种模式 worker 会话 — 零待命临时执行体, 屏蔽记忆写入 (防并行噪音污染三轨记忆).
 */
data class ExecutionContext(
    val sessionId: String,
    val userId: String = "agent",
    val workDir: String = com.mengpaw.kernel.DataPaths.BASE,
    val environment: Map<String, String> = emptyMap(),
    val agentName: String? = null,
    val scope: String = "agent"
)

/**
 * Pluggable command executor — enables plugins to invoke CLI commands
 * programmatically (e.g. workflow nodes executing other plugin commands).
 *
 * The framework injects an implementation via [com.mengpaw.kernel.plugin.PluginContext].
 * Kernel provides [DefaultCommandExecutor] as the reference implementation.
 */
interface CommandExecutor {
    /**
     * Execute a raw command line (e.g. "tavily.search hello world").
     *
     * @param commandLine  the full CLI command string including namespace and args
     * @param ctx          execution context (session, user, workDir)
     * @return [ExecutionResult] with success flag, output, and optional error details
     */
    suspend fun execute(commandLine: String, ctx: ExecutionContext): ExecutionResult
}
