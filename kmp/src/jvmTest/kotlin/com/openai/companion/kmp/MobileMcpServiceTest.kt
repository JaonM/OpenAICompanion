package com.openai.companion.kmp

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MobileMcpServiceTest {
    @Test
    fun sharedServiceConnectsPublishesToolsAndClearsStoredEndpoint() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { exchange ->
            val request = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val result = when (request.getValue("method").jsonPrimitive.content) {
                "server/discover" -> """{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}"""
                "tools/list" -> """{"tools":[{"name":"get_weather","inputSchema":{"type":"object"}}]}"""
                else -> error("Unexpected MCP method")
            }
            val body = """{"jsonrpc":"2.0","id":${request.getValue("id")},"result":$result}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        val bindings = RecordingBindings()
        val endpoints = MemoryEndpointStore()
        val service = MobileMcpService(
            bindings = bindings,
            approve = { _, _ -> false },
            requestInput = { _, _, _: JsonObject -> error("Unexpected input request") },
            tokenStore = object : McpOAuthTokenStore {
                override suspend fun load(endpoint: String): McpOAuthTokens? = null
                override suspend fun save(endpoint: String, tokens: McpOAuthTokens) = Unit
                override suspend fun delete(endpoint: String) = Unit
            },
            endpointStore = endpoints,
            httpClient = { HttpClient(CIO) { followRedirects = false } },
            crypto = object : McpOAuthCrypto {
                override fun randomBytes(count: Int) = ByteArray(count)
                override fun sha256(bytes: ByteArray) = bytes
            },
            browser = object : McpOAuthBrowser {
                override suspend fun authorize(url: String, redirectUri: String): String = error("Unexpected OAuth")
            },
            redirectUri = "openai-companion://oauth/callback",
        )
        try {
            service.start()
            val endpoint = "http://127.0.0.1:${server.address.port}/mcp"
            service.connect(endpoint)
            assertEquals(endpoint, endpoints.value)
            assertEquals("get_weather", bindings.tools.single().name)
            assertTrue(service.status.startsWith("已连接"))
            service.connect("")
            assertEquals("", endpoints.value)
            assertEquals(emptyList(), bindings.tools)
        } finally {
            server.stop(0)
        }
    }

    private class MemoryEndpointStore : McpEndpointStore {
        var value = ""
        override fun load() = value
        override fun save(endpoint: String) { value = endpoint }
        override fun clear() { value = "" }
    }

    private class RecordingBindings : GeneratedHarnessBindings {
        var tools: List<McpTool> = emptyList()
        override fun registerToolProvider(provider: RustToolProvider) = Unit
        override fun registerModelServeCallback(provider: AppModelServe) = Unit
        override fun updateMcpTools(tools: List<McpTool>) { this.tools = tools }
        override fun unregisterToolProvider() = Unit
        override fun unregisterModelServeCallback() = Unit
        override fun registerAgentEventSink(sink: AppAgentEventSink) = Unit
        override fun unregisterAgentEventSink() = Unit
        override fun configureContextDirectories(agentsDirectory: String, personaDirectory: String) = Unit
        override fun clearContextDirectories() = Unit
        override fun cancelAgentLoop() = Unit
    }
}
