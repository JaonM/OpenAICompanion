package com.openai.companion.kmp

import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
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

/**
 * MCP client connection backed by two in-memory ChannelTransports.
 * No socket, HTTP server, or subprocess is created for device tools.
 */
@OptIn(ExperimentalMcpApi::class)
class InProcessMcpServerConnection private constructor(
    private val client: Client,
    private val clientTransport: Transport,
) : McpServerConnection {
    private var closed = false
    private var toolsChangedListener: (suspend () -> Unit)? = null
    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
    override suspend fun listTools(): List<McpToolDescriptor> =
        client.listTools()?.tools.orEmpty().map { tool ->
            McpToolDescriptor(
                name = tool.name,
                description = tool.description.orEmpty(),
                inputSchemaJson = Json.encodeToString(tool.inputSchema),
            )
        }

    override suspend fun callTool(name: String, argumentsJson: String): McpCallResult {
        val arguments = Json.decodeFromString<JsonObject>(argumentsJson)
        val result = client.callTool(
            CallToolRequest(
                CallToolRequestParams(name = name, arguments = arguments),
            ),
        )
            ?: throw ToolExecutionException(
                ToolExecutionErrorCode.SERVER_INTERNAL_ERROR,
                "device MCP server returned an empty tool result",
            )
        return McpCallResult(result.toString(), result.isError == true)
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        notificationScope.cancel()
        client.close()
        clientTransport.close()
    }

    companion object {
        suspend fun connect(
            server: DeviceMcpServer,
        ): InProcessMcpServerConnection {
            val linked = ChannelTransport.createLinkedPair()
            server.server.createSession(linked.serverTransport)
            val client = Client(Implementation("device-mcp-client", "0.1.0"))
            client.connect(linked.clientTransport)
            return InProcessMcpServerConnection(client, linked.clientTransport)
        }
    }
}
