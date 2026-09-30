package com.openai.companion.kmp

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinSdkMcpClientTest {
    @Test
    fun remoteStreamableHttpListsAndCallsTools() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val methods = mutableListOf<String>()
        server.createContext("/mcp") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            val message = Json.parseToJsonElement(body).jsonObject
            val method = message["method"]?.jsonPrimitive?.content.orEmpty()
            methods += method
            exchange.responseHeaders.add("Content-Type", "application/json")
            if (method == "initialize") exchange.responseHeaders.add("Mcp-Session-Id", "test-session")
            val result = when (method) {
                "initialize" -> """{"protocolVersion":"2025-03-26","capabilities":{"tools":{"listChanged":true}},"serverInfo":{"name":"fixture","version":"1"}}"""
                "tools/list" -> """{"tools":[{"name":"echo","description":"Echo input","inputSchema":{"type":"object","properties":{"text":{"type":"string"}}}}]}"""
                "tools/call" -> """{"content":[{"type":"text","text":"hello from MCP"}],"isError":false}"""
                else -> "{}"
            }
            if (method == "notifications/initialized") {
                exchange.sendResponseHeaders(202, -1)
            } else {
                val payload = """{"jsonrpc":"2.0","id":${message["id"]},"result":$result}"""
                    .toByteArray()
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            exchange.close()
        }
        server.start()
        val client = KotlinSdkMcpClient.create("http://127.0.0.1:${server.address.port}/mcp")
        try {
            val tools = client.listTools()
            assertEquals(listOf("echo"), tools.map { it.name })
            val output = client.callTool("echo", "{\"text\":\"hello\"}")
            assertFalse(output.isError)
            assertTrue(output.contentJson.contains("hello from MCP"))
            assertTrue(methods.containsAll(listOf("initialize", "tools/list", "tools/call")))
        } finally {
            client.close()
            server.stop(0)
        }
    }
}
