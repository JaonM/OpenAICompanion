package com.openai.companion.kmp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private data class RegisteredDeviceTool(
    val descriptor: McpToolDescriptor,
)

/**
 * Lifecycle-owned device MCP server. A platform transport can expose [server]
 * over stdio, streamable HTTP, or an in-process transport without changing the
 * aggregate/provider contract.
 */
class DeviceMcpServer(
    name: String,
    version: String = "0.1.0",
) {
    val server = Server(
        serverInfo = Implementation(name, version),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
    )
    var started: Boolean = false
        private set
    private val registeredTools = linkedMapOf<String, RegisteredDeviceTool>()
    private var inProcessConnection: InProcessMcpServerConnection? = null

    fun registerTool(
        name: String,
        description: String,
        inputSchema: ToolSchema,
        handler: suspend (JsonObject?) -> String,
    ) {
        require(name.isNotBlank()) { "device MCP tool name must not be blank" }
        check(name != "load_more_tools") { "reserved tool name: $name" }
        check(!registeredTools.containsKey(name)) { "duplicate device MCP tool: $name" }
        server.addTool(name, description, inputSchema) { request ->
            try {
                CallToolResult(content = listOf(TextContent(handler(request.arguments))))
            } catch (error: ToolExecutionException) {
                CallToolResult(
                    content = listOf(TextContent(error.message.orEmpty())),
                    isError = true,
                )
            } catch (error: IllegalArgumentException) {
                CallToolResult(
                    content = listOf(TextContent(error.message.orEmpty())),
                    isError = true,
                )
            } catch (error: Throwable) {
                CallToolResult(
                    content = listOf(TextContent(error.message.orEmpty())),
                    isError = true,
                )
            }
        }
        registeredTools.put(name, RegisteredDeviceTool(
            McpToolDescriptor(
                name = name,
                description = description,
                inputSchemaJson = Json.encodeToString(inputSchema),
            ),
        ))
    }

    fun removeTool(name: String): Boolean {
        check(name != "load_more_tools") { "reserved tool name: $name" }
        val removed = server.removeTool(name)
        if (removed) registeredTools.remove(name)
        return removed
    }

    internal fun tools(): List<McpToolDescriptor> = registeredTools.values.map { it.descriptor }

    suspend fun start(): McpServerConnection {
        check(!started) { "device MCP server is already started" }
        started = true
        return try {
            InProcessMcpServerConnection.connect(this).also {
                inProcessConnection = it
            }
        } catch (error: Throwable) {
            server.close()
            started = false
            throw error
        }
    }

    suspend fun stop() {
        check(started) { "device MCP server is not started" }
        inProcessConnection?.close()
        inProcessConnection = null
        server.close()
        started = false
    }
}
