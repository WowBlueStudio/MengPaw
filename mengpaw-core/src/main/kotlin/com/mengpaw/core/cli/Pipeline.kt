package com.mengpaw.core.cli

import com.mengpaw.core.security.SecurityPolicy

/**
 * Execution pipeline that ties together parsing, security checks, and execution.
 */
class Pipeline(
    private val interpreter: CliInterpreter = CliInterpreter(),
    private val registry: CommandRegistry = CommandRegistry(),
    private val securityPolicy: SecurityPolicy = SecurityPolicy()
) {
    /**
     * Execute a command string through the full pipeline:
     * 1. Parse the command
     * 2. Check security policy
     * 3. Execute via registered handler
     */
    suspend fun execute(input: String, context: ExecutionContext): ExecutionResult {
        try {
            val trimmed = input.trim()
            if (trimmed.isBlank()) {
                return ExecutionResult.fail("Empty command")
            }

            val parsed = interpreter.parse(trimmed)
            if (parsed.command.isBlank()) {
                return ExecutionResult.fail("Empty command")
            }

            // Security check
            if (!securityPolicy.isAllowed(parsed.command)) {
                return ExecutionResult.fail(
                    "Command '${parsed.command}' is blocked by security policy"
                )
            }

            // Find and execute
            val executor = registry.find(parsed.command)
                ?: return ExecutionResult.fail("Unknown command: ${parsed.command}")

            return executor(parsed.args, context)
        } catch (e: Exception) {
            return ExecutionResult.fail("Execution error: ${e.message ?: e::class.simpleName}")
        }
    }
}
