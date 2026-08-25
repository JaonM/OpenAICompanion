package com.openai.companion.kmp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject

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

    fun registerTool(
        name: String,
        description: String,
        inputSchema: ToolSchema,
        handler: suspend (JsonObject?) -> String,
    ) {
        server.addTool(name, description, inputSchema) { request ->
            CallToolResult(content = listOf(TextContent(handler(request.arguments))) )
        }
    }

    fun start() {
        check(!started) { "device MCP server is already started" }
        started = true
    }

    suspend fun stop() {
        check(started) { "device MCP server is not started" }
        server.close()
        started = false
    }
}

