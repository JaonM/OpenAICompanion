package com.openai.companion.kmp

/** A stable boundary around either a remote or device-hosted MCP server. */
interface McpServerConnection {
    suspend fun listTools(): List<McpToolDescriptor>

    suspend fun callTool(name: String, argumentsJson: String): String

    suspend fun close()
}

data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
)

