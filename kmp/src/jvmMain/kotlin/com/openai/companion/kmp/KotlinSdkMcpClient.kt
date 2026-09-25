package com.openai.companion.kmp

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.ToolListChangedNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Remote MCP client backed by the official Kotlin SDK. */
class KotlinSdkMcpClient(
    private val httpClient: HttpClient,
    private val endpoint: String,
) : McpServerConnection {
    private val client = Client(Implementation("openai-companion", "0.1.0"))
    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var toolsChangedListener: (suspend () -> Unit)? = null
    private var connected = false

    init {
        client.setNotificationHandler<ToolListChangedNotification>(
            Method.Defined.NotificationsToolsListChanged,
        ) {
            notificationScope.async {
                toolsChangedListener?.invoke()
            }
        }
    }

    override fun setToolsChangedListener(listener: suspend () -> Unit) {
        toolsChangedListener = listener
    }

    override suspend fun listTools(): List<McpToolDescriptor> {
        ensureConnected()
        return client.listTools()?.tools.orEmpty().map { tool ->
            McpToolDescriptor(
                name = tool.name,
                description = tool.description.orEmpty(),
                inputSchemaJson = Json.encodeToString(tool.inputSchema),
            )
        }
    }

    override suspend fun callTool(name: String, argumentsJson: String): McpCallResult {
        ensureConnected()
        val arguments = Json.decodeFromString<JsonObject>(argumentsJson)
        val result = client.callTool(
            CallToolRequest(
                CallToolRequestParams(name = name, arguments = arguments),
            ),
        )
            ?: throw ToolExecutionException(
                ToolExecutionErrorCode.SERVER_INTERNAL_ERROR,
                "MCP server returned an empty tool result",
            )
        return McpCallResult(result.toString(), result.isError == true)
    }

    override suspend fun close() {
        if (connected) client.close()
        notificationScope.cancel()
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
