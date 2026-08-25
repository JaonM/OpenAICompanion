package com.openai.companion.kmp

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Remote MCP client backed by the official Kotlin SDK. */
class KotlinSdkMcpClient(
    private val httpClient: HttpClient,
    private val endpoint: String,
) : McpServerConnection {
    private val client = Client(Implementation("openai-companion", "0.1.0"))
    private var connected = false

    override suspend fun listTools(): List<McpToolDescriptor> {
        ensureConnected()
        return client.listTools()?.tools.orEmpty().map { tool ->
            McpToolDescriptor(
                name = tool.name,
                description = tool.description.orEmpty(),
                inputSchemaJson = tool.inputSchema.toString(),
            )
        }
    }

    override suspend fun callTool(name: String, argumentsJson: String): String {
        ensureConnected()
        val arguments = Json.decodeFromString<JsonObject>(argumentsJson)
        val result = client.callTool(CallToolRequest(name = name, arguments = arguments))
        return result?.toString().orEmpty()
    }

    override suspend fun close() {
        if (connected) client.close()
        httpClient.close()
        connected = false
    }

    private suspend fun ensureConnected() {
        if (!connected) {
            client.connect(
                StreamableHttpClientTransport(
                    client = httpClient,
                    url = endpoint,
                ),
            )
            connected = true
        }
    }

    companion object {
        fun create(endpoint: String): KotlinSdkMcpClient =
            KotlinSdkMcpClient(HttpClient { install(SSE) }, endpoint)
    }
}

