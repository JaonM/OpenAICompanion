package com.openai.companion.kmp

/** A stable boundary around either a remote or device-hosted MCP server. */
interface McpServerConnection {
    suspend fun listTools(): List<McpToolDescriptor>

    suspend fun callTool(name: String, argumentsJson: String): McpCallResult

    suspend fun close()
}

data class McpCallResult(
    val contentJson: String,
    val isError: Boolean,
)

enum class ToolExecutionErrorCode {
    TIMEOUT,
    PERMISSION_DENIED,
    NETWORK_UNREACHABLE,
    INVALID_ARGUMENTS,
    RESOURCE_NOT_FOUND,
    SERVER_INTERNAL_ERROR,
    CANCELLED,
    UNKNOWN,
}

class ToolExecutionException(
    val code: ToolExecutionErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val retryable: Boolean = false,
)
