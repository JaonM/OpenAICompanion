package com.openai.companion.kmp

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamableMcpConnectionTest {
    @Test
    fun modernToolResultRequiresContentButAllowsStructuredArray() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val calls = AtomicInteger(0)
        server.createContext("/mcp") { exchange ->
            val message = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val result = when (message.getValue("method").jsonPrimitive.content) {
                "server/discover" -> """{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}"""
                "tools/list" -> """{"tools":[{"name":"read","inputSchema":{"type":"object"}}]}"""
                "tools/call" -> when (calls.incrementAndGet()) {
                    1 -> """{"resultType":"complete","structuredContent":[1,2]}"""
                    2 -> """{"resultType":"complete","content":[],"structuredContent":[1,2]}"""
                    else -> """{"resultType":"complete","content":[],"isError":"true"}"""
                }
                else -> error("Unexpected MCP method")
            }
            val body = """{"jsonrpc":"2.0","id":${message.getValue("id")},"result":$result}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        val client = StreamableMcpConnection(
            HttpClient(CIO) { followRedirects = false },
            "http://127.0.0.1:${server.address.port}/mcp",
        )
        try {
            client.listTools()
            val failure = assertFailsWith<McpProtocolException> { client.callTool("read", "{}") }
            assertTrue(failure.message.orEmpty().contains("no content array"))
            assertTrue(client.callTool("read", "{}").contentJson.contains("[1,2]"))
            val invalidFlag = assertFailsWith<McpProtocolException> { client.callTool("read", "{}") }
            assertTrue(invalidFlag.message.orEmpty().contains("isError must be a boolean"))
            assertEquals(3, calls.get())
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun endlessDistinctToolCursorsStopAtPageLimit() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pages = AtomicInteger(0)
        server.createContext("/mcp") { exchange ->
            val message = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val result = when (message.getValue("method").jsonPrimitive.content) {
                "server/discover" -> """{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}"""
                "tools/list" -> """{"tools":[],"nextCursor":"page-${pages.incrementAndGet()}"}"""
                else -> error("Unexpected MCP method")
            }
            val body = """{"jsonrpc":"2.0","id":${message.getValue("id")},"result":$result}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        val client = StreamableMcpConnection(
            HttpClient(CIO) { followRedirects = false },
            "http://127.0.0.1:${server.address.port}/mcp",
        )
        try {
            val failure = assertFailsWith<McpProtocolException> { client.listTools() }
            assertTrue(failure.message.orEmpty().contains("exceeded 32 pages"))
            assertEquals(32, pages.get())
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun oversizedJsonRpcResponseIsRejectedBeforeParsing() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { exchange ->
            exchange.requestBody.close()
            val body = ByteArray(4 * 1024 * 1024 + 1) { ' '.code.toByte() }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        val client = StreamableMcpConnection(
            HttpClient(CIO) { followRedirects = false },
            "http://127.0.0.1:${server.address.port}/mcp",
        )
        try {
            val failure = assertFailsWith<McpProtocolException> { client.listTools() }
            assertTrue(failure.message.orEmpty().contains("exceeds 4194304 bytes"))
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun excessiveToolCountIsRejected() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { exchange ->
            val message = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val result = when (message.getValue("method").jsonPrimitive.content) {
                "server/discover" -> """{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}"""
                "tools/list" -> """{"tools":[${(0..512).joinToString(",") { index ->
                    """{"name":"tool-$index","inputSchema":{"type":"object"}}"""
                }}]}"""
                else -> error("Unexpected MCP method")
            }
            val body = """{"jsonrpc":"2.0","id":${message.getValue("id")},"result":$result}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        val client = StreamableMcpConnection(
            HttpClient(CIO) { followRedirects = false },
            "http://127.0.0.1:${server.address.port}/mcp",
        )
        try {
            val failure = assertFailsWith<McpProtocolException> { client.listTools() }
            assertTrue(failure.message.orEmpty().contains("exceeded 512 tools"))
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun revokedBearerRefreshesAndRetriesToolCallOnlyOnce() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val calls = AtomicInteger(0)
        val refreshes = AtomicInteger(0)
        val authorizationPrompts = AtomicInteger(0)
        var token = "old-token"
        server.createContext("/mcp") { exchange ->
            val message = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val method = message.getValue("method").jsonPrimitive.content
            val result = when (method) {
                "server/discover" -> """{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}"""
                "tools/list" -> """{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}"""
                "tools/call" -> {
                    calls.incrementAndGet()
                    if (exchange.requestHeaders.getFirst("Authorization") == "Bearer old-token") {
                        exchange.responseHeaders.add("WWW-Authenticate", "Bearer error=\"invalid_token\"")
                        exchange.sendResponseHeaders(401, -1)
                        exchange.close()
                        return@createContext
                    }
                    assertEquals("Bearer new-token", exchange.requestHeaders.getFirst("Authorization"))
                    """{"content":[{"type":"text","text":"ok"}]}"""
                }
                else -> error("Unexpected MCP method $method")
            }
            val payload = """{"jsonrpc":"2.0","id":${message.getValue("id")},"result":$result}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
            exchange.close()
        }
        server.start()
        val client = StreamableMcpConnection(
            HttpClient(CIO) { followRedirects = false },
            "http://127.0.0.1:${server.address.port}/mcp",
            accessToken = { token },
            refreshAccessToken = { rejected ->
                assertEquals("old-token", rejected)
                refreshes.incrementAndGet()
                token = "new-token"
                token
            },
            onAuthorizationRequired = { authorizationPrompts.incrementAndGet() },
        )
        try {
            assertEquals(listOf("echo"), client.listTools().map { it.name })
            assertTrue(client.callTool("echo", "{}").contentJson.contains("ok"))
            assertEquals(2, calls.get())
            assertEquals(1, refreshes.get())
            assertEquals(0, authorizationPrompts.get())
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun repeatedUnauthorizedDoesNotRetryToolCallAgain() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val calls = AtomicInteger(0)
        val refreshes = AtomicInteger(0)
        val authorizationPrompts = AtomicInteger(0)
        server.createContext("/mcp") { exchange ->
            val message = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val result = when (message.getValue("method").jsonPrimitive.content) {
                "server/discover" -> """{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}"""
                "tools/list" -> """{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}"""
                "tools/call" -> {
                    calls.incrementAndGet()
                    exchange.sendResponseHeaders(401, -1)
                    exchange.close()
                    return@createContext
                }
                else -> error("Unexpected MCP method")
            }
            val payload = """{"jsonrpc":"2.0","id":${message.getValue("id")},"result":$result}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
            exchange.close()
        }
        server.start()
        val client = StreamableMcpConnection(
            HttpClient(CIO) { followRedirects = false },
            "http://127.0.0.1:${server.address.port}/mcp",
            accessToken = { "old-token" },
            refreshAccessToken = { refreshes.incrementAndGet(); "new-token" },
            onAuthorizationRequired = { authorizationPrompts.incrementAndGet() },
        )
        try {
            client.listTools()
            val error = assertFailsWith<ToolExecutionException> { client.callTool("echo", "{}") }
            assertEquals(ToolExecutionErrorCode.PERMISSION_DENIED, error.code)
            assertEquals(2, calls.get())
            assertEquals(1, refreshes.get())
            assertEquals(1, authorizationPrompts.get())
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun bearerTokenIsSentOnModernDiscoveryListAndCall() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = mutableListOf<String>()
        server.createContext("/mcp") { exchange ->
            assertEquals("Bearer test-token", exchange.requestHeaders.getFirst("Authorization"))
            val message = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val method = message.getValue("method").jsonPrimitive.content
            seen += method
            val result = when (method) {
                "server/discover" -> """{"resultType":"complete","supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}"""
                "tools/list" -> """{"resultType":"complete","tools":[{"name":"echo","inputSchema":{"type":"object"}}]}"""
                "tools/call" -> """{"resultType":"complete","content":[{"type":"text","text":"ok"}]}"""
                else -> error("Unexpected MCP method $method")
            }
            val bytes = """{"jsonrpc":"2.0","id":${message.getValue("id")},"result":$result}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        val client = StreamableMcpConnection(
            HttpClient(CIO) { followRedirects = false },
            "http://127.0.0.1:${server.address.port}/mcp",
            accessToken = { "test-token" },
        )
        try {
            assertEquals(listOf("echo"), client.listTools().map { it.name })
            assertTrue(client.callTool("echo", "{}").contentJson.contains("ok"))
            assertEquals(listOf("server/discover", "tools/list", "tools/call"), seen)
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun authorizationChallengeSurvivesModernDiscoveryFailureWithoutLegacyFallback() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val calls = AtomicInteger(0)
        val prompts = AtomicInteger(0)
        server.createContext("/mcp") { exchange ->
            calls.incrementAndGet()
            exchange.responseHeaders.add("WWW-Authenticate",
                "Bearer resource_metadata=\"https://auth.example/resource\", scope=\"read write\"")
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        server.start()
        val client = StreamableMcpConnection(
            HttpClient(CIO), "http://127.0.0.1:${server.address.port}/mcp",
            refreshAccessToken = { null },
            onAuthorizationRequired = { prompts.incrementAndGet() },
        )
        try {
            val error = assertFailsWith<ToolExecutionException> { client.listTools() }
            assertEquals(ToolExecutionErrorCode.PERMISSION_DENIED, error.code)
            assertEquals("https://auth.example/resource", error.authorizationChallenge?.resourceMetadataUrl)
            assertEquals(listOf("read", "write"), error.authorizationChallenge?.scopes)
            assertEquals(1, calls.get())
            assertEquals(1, prompts.get())
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun modernInputRequiredEchoesStateAndReplacesResponsesEachRound() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val callIds = mutableListOf<String>()
        val answered = mutableListOf<String>()
        server.createContext("/mcp") { exchange ->
            val message = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val method = message.getValue("method").jsonPrimitive.content
            val params = message.getValue("params").jsonObject
            val result = when (method) {
                "server/discover" -> """{"resultType":"complete","supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}"""
                "tools/call" -> {
                    assertEquals("echo", params.getValue("name").jsonPrimitive.content)
                    assertEquals("{}", params.getValue("arguments").toString())
                    callIds += message.getValue("id").jsonPrimitive.content
                    when (callIds.size) {
                        1 -> {
                            assertFalse("inputResponses" in params)
                            """{"resultType":"input_required","requestState":"a.b+c","inputRequests":{"first":{"method":"elicitation/create","params":{"message":"First","requestedSchema":{"type":"object"}}}}}"""
                        }
                        2 -> {
                            assertEquals("a.b+c", params.getValue("requestState").jsonPrimitive.content)
                            assertEquals(setOf("first"), params.getValue("inputResponses").jsonObject.keys)
                            """{"resultType":"input_required","requestState":"second-state","inputRequests":{"second":{"method":"elicitation/create","params":{"message":"Second","requestedSchema":{"type":"object"}}}}}"""
                        }
                        else -> {
                            assertEquals("second-state", params.getValue("requestState").jsonPrimitive.content)
                            assertEquals(setOf("second"), params.getValue("inputResponses").jsonObject.keys)
                            """{"resultType":"complete","content":[{"type":"text","text":"done"}]}"""
                        }
                    }
                }
                else -> error("Unexpected MCP method: $method")
            }
            val payload = """{"jsonrpc":"2.0","id":${message.getValue("id")},"result":$result}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
            exchange.close()
        }
        server.start()
        val client = StreamableMcpConnection(
            HttpClient(CIO), "http://127.0.0.1:${server.address.port}/mcp",
            inputHandler = { method, params ->
                assertEquals("elicitation/create", method)
                answered += params.getValue("message").jsonPrimitive.content
                buildJsonObject { put("action", "decline") }
            },
            inputCapabilities = buildJsonObject {
                put("elicitation", buildJsonObject { put("form", JsonObject(emptyMap())) })
            },
        )
        try {
            assertTrue(client.callTool("echo", "{}").contentJson.contains("done"))
            assertEquals(listOf("First", "Second"), answered)
            assertEquals(3, callIds.distinct().size)
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun modernSubscriptionReportsInsufficientScope() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val authorizationRequired = CompletableDeferred<ToolExecutionException>()
        server.createContext("/mcp") { exchange ->
            val message = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val method = message.getValue("method").jsonPrimitive.content
            if (method == "subscriptions/listen") {
                exchange.responseHeaders.add("WWW-Authenticate", "Bearer error=\"insufficient_scope\", scope=\"tools.read\"")
                exchange.sendResponseHeaders(403, -1)
            } else {
                val result = when (method) {
                    "server/discover" -> """{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{"listChanged":true}}}"""
                    "tools/list" -> """{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}"""
                    else -> error("Unexpected MCP method $method")
                }
                val payload = """{"jsonrpc":"2.0","id":${message.getValue("id")},"result":$result}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.write(payload)
            }
            exchange.close()
        }
        server.start()
        val client = StreamableMcpConnection(
            HttpClient(CIO), "http://127.0.0.1:${server.address.port}/mcp",
            onAuthorizationRequired = { authorizationRequired.complete(it) },
        )
        try {
            assertEquals(listOf("echo"), client.listTools().map { it.name })
            val failure = withTimeout(2_000) { authorizationRequired.await() }
            assertEquals(ToolExecutionErrorCode.PERMISSION_DENIED, failure.code)
            assertEquals(listOf("tools.read"), failure.authorizationChallenge?.scopes)
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun failedToolChangeRefreshRetriesWithoutAnotherNotification() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        val releaseStream = CountDownLatch(1)
        server.createContext("/mcp") { exchange ->
            val message = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val method = message.getValue("method").jsonPrimitive.content
            if (method == "subscriptions/listen") {
                val id = message.getValue("id").jsonPrimitive.content
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                val ack = """{"jsonrpc":"2.0","method":"notifications/subscriptions/acknowledged","params":{"notifications":{"toolsListChanged":true},"_meta":{"io.modelcontextprotocol/subscriptionId":"$id"}}}"""
                exchange.responseBody.write("data: $ack\n\n".toByteArray())
                exchange.responseBody.flush()
                releaseStream.await(5, TimeUnit.SECONDS)
            } else {
                val result = when (method) {
                    "server/discover" -> """{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{"listChanged":true}}}"""
                    "tools/list" -> """{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}"""
                    else -> error("Unexpected MCP method: $method")
                }
                val payload = """{"jsonrpc":"2.0","id":${message.getValue("id")},"result":$result}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.write(payload)
            }
            exchange.close()
        }
        server.start()
        val client = StreamableMcpConnection(HttpClient(CIO), "http://127.0.0.1:${server.address.port}/mcp")
        val attempts = AtomicInteger(0)
        val refreshed = CompletableDeferred<Unit>()
        client.setToolsChangedListener {
            if (attempts.incrementAndGet() == 1) error("transient refresh failure")
            assertEquals(listOf("echo"), client.listTools().map { it.name })
            refreshed.complete(Unit)
        }
        try {
            assertEquals(listOf("echo"), client.listTools().map { it.name })
            withTimeout(4_000) { refreshed.await() }
            assertEquals(2, attempts.get())
        } finally {
            releaseStream.countDown()
            client.close()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun modernSubscriptionRefreshesToolsAndReconnectsAfterStreamCloses() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        val revision = AtomicInteger(0)
        val publish = CountDownLatch(1)
        val publishAfterReconnect = CountDownLatch(1)
        val allowFirstRealNotification = CountDownLatch(1)
        val subscriptionCount = AtomicInteger(0)
        val subscribed = CompletableDeferred<Unit>()
        val resubscribed = CompletableDeferred<Unit>()
        val forgedSent = CompletableDeferred<Unit>()
        server.createContext("/mcp") { exchange ->
            val message = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val method = message.getValue("method").jsonPrimitive.content
            assertEquals(method, exchange.requestHeaders.getFirst("Mcp-Method"))
            if (method == "subscriptions/listen") {
                val sequence = subscriptionCount.incrementAndGet()
                val id = message.getValue("id").jsonPrimitive.content
                assertEquals("true", message.getValue("params").jsonObject
                    .getValue("notifications").jsonObject.getValue("toolsListChanged").jsonPrimitive.content)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                val ack = """{"jsonrpc":"2.0","method":"notifications/subscriptions/acknowledged","params":{"notifications":{"toolsListChanged":true},"_meta":{"io.modelcontextprotocol/subscriptionId":"$id"}}}"""
                exchange.responseBody.write("data: $ack\n\n".toByteArray())
                exchange.responseBody.flush()
                if (sequence == 1) subscribed.complete(Unit) else resubscribed.complete(Unit)
                val gate = if (sequence == 1) publish else publishAfterReconnect
                if (gate.await(3, TimeUnit.SECONDS)) {
                    if (sequence == 1) {
                        val forged = """{"jsonrpc":"2.0","method":"notifications/tools/list_changed","params":{"_meta":{"io.modelcontextprotocol/subscriptionId":"wrong"}}}"""
                        exchange.responseBody.write("data: $forged\n\n".toByteArray())
                        exchange.responseBody.flush()
                        forgedSent.complete(Unit)
                        allowFirstRealNotification.await(3, TimeUnit.SECONDS)
                    }
                    val changed = """{"jsonrpc":"2.0","method":"notifications/tools/list_changed","params":{"_meta":{"io.modelcontextprotocol/subscriptionId":"$id"}}}"""
                    exchange.responseBody.write("data: $changed\n\n".toByteArray())
                    exchange.responseBody.flush()
                }
                Thread.sleep(500)
            } else {
                val result = when (method) {
                    "server/discover" -> """{"resultType":"complete","supportedVersions":["2026-07-28"],"capabilities":{"tools":{"listChanged":true}}}"""
                    "tools/list" -> """{"resultType":"complete","tools":[{"name":"${when (revision.get()) { 0 -> "old"; 1 -> "new"; else -> "newest" }}","inputSchema":{"type":"object"}}]}"""
                    else -> error("Unexpected MCP method: $method")
                }
                val payload = """{"jsonrpc":"2.0","id":${message.getValue("id")},"result":$result}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.write(payload)
            }
            exchange.close()
        }
        server.start()
        val client = StreamableMcpConnection(HttpClient(CIO), "http://127.0.0.1:${server.address.port}/mcp")
        val initialRefresh = CompletableDeferred<Unit>()
        val updated = CompletableDeferred<Unit>()
        val updatedAfterReconnect = CompletableDeferred<Unit>()
        client.setToolsChangedListener {
            when (client.listTools().singleOrNull()?.name) {
                "old" -> initialRefresh.complete(Unit)
                "new" -> updated.complete(Unit)
                "newest" -> updatedAfterReconnect.complete(Unit)
            }
        }
        try {
            assertEquals(listOf("old"), client.listTools().map { it.name })
            withTimeout(2_000) { subscribed.await() }
            withTimeout(2_000) { initialRefresh.await() }
            revision.set(1)
            publish.countDown()
            withTimeout(2_000) { forgedSent.await() }
            assertNull(withTimeoutOrNull(300) { updated.await() })
            allowFirstRealNotification.countDown()
            withTimeout(2_000) { updated.await() }
            assertEquals(listOf("new"), client.listTools().map { it.name })
            withTimeout(4_000) { resubscribed.await() }
            revision.set(2)
            publishAfterReconnect.countDown()
            withTimeout(2_000) { updatedAfterReconnect.await() }
            assertEquals(listOf("newest"), client.listTools().map { it.name })
        } finally {
            allowFirstRealNotification.countDown()
            client.close()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun sseReplyFinishesBeforeServerClosesStream() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { exchange ->
            val message = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val method = message.getValue("method").jsonPrimitive.content
            val result = when (method) {
                "server/discover" -> """{"resultType":"complete","supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}"""
                "tools/list" -> """{"resultType":"complete","tools":[{"name":"echo","inputSchema":{"type":"object"}}]}"""
                "tools/call" -> """{"resultType":"complete","content":[{"type":"text","text":"streamed"}],"isError":false}"""
                else -> error("Unexpected MCP method: $method")
            }
            val response = """{"jsonrpc":"2.0","id":${message.getValue("id")},"result":$result}"""
            if (method == "tools/call") {
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}\n\n".toByteArray())
                exchange.responseBody.write("data: $response\n\n".toByteArray())
                exchange.responseBody.flush()
                Thread.sleep(2_500)
            } else {
                val payload = response.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.write(payload)
            }
            exchange.close()
        }
        server.start()
        val client = StreamableMcpConnection(HttpClient(CIO), "http://127.0.0.1:${server.address.port}/mcp")
        val notification = CompletableDeferred<Unit>()
        client.setToolsChangedListener { notification.complete(Unit) }
        try {
            client.listTools()
            val result = withTimeout(1_500) { client.callTool("echo", "{}") }
            assertTrue(result.contentJson.contains("streamed"))
            withTimeout(1_000) { notification.await() }
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun modernServerUsesPerRequestMetadataAndEncodedParameterHeaders() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val methods = mutableListOf<String>()
        server.createContext("/mcp") { exchange ->
            val message = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val method = message.getValue("method").jsonPrimitive.content
            methods += method
            assertEquals("2026-07-28", exchange.requestHeaders.getFirst("MCP-Protocol-Version"))
            assertEquals(method, exchange.requestHeaders.getFirst("Mcp-Method"))
            val metadata = message.getValue("params").jsonObject.getValue("_meta").jsonObject
            assertEquals("2026-07-28", metadata.getValue("io.modelcontextprotocol/protocolVersion").jsonPrimitive.content)
            if (method == "tools/call") {
                assertEquals("echo", exchange.requestHeaders.getFirst("Mcp-Name"))
                assertEquals("=?base64?IOS4lueVjCA=?=", exchange.requestHeaders.getFirst("Mcp-Param-Text"))
            }
            val result = when (method) {
                "server/discover" -> """{"resultType":"complete","supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}"""
                "tools/list" -> """{"resultType":"complete","tools":[{"name":"echo","description":"Echo","inputSchema":{"type":"object","properties":{"text":{"type":"string","x-mcp-header":"Text"}}}}]}"""
                "tools/call" -> """{"resultType":"complete","content":[{"type":"text","text":"ok"}],"isError":false}"""
                else -> error("Unexpected MCP method: $method")
            }
            val payload = """{"jsonrpc":"2.0","id":${message.getValue("id")},"result":$result}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
            exchange.close()
        }
        server.start()
        val client = StreamableMcpConnection(
            HttpClient(CIO), "http://127.0.0.1:${server.address.port}/mcp",
        )
        try {
            assertEquals(listOf("echo"), client.listTools().map { it.name })
            val result = client.callTool("echo", "{\"text\":\" 世界 \"}")
            assertFalse(result.isError)
            assertTrue(result.contentJson.contains("ok"))
            assertEquals(listOf("server/discover", "tools/list", "tools/call"), methods)
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun legacyPostSseResumesWithoutRepeatingToolCall() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val toolCalls = AtomicInteger(0)
        val resumeGets = AtomicInteger(0)
        val refreshes = AtomicInteger(0)
        val toolCallId = AtomicInteger(-1)
        var token = "old-token"
        server.createContext("/mcp") { exchange ->
            if (exchange.requestMethod == "DELETE") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            if (exchange.requestMethod == "GET") {
                if (exchange.requestHeaders.getFirst("Last-Event-ID") == "cursor-1") {
                    assertEquals("session-1", exchange.requestHeaders.getFirst("Mcp-Session-Id"))
                    when (resumeGets.incrementAndGet()) {
                        1 -> {
                            assertEquals("Bearer old-token", exchange.requestHeaders.getFirst("Authorization"))
                            exchange.sendResponseHeaders(401, -1)
                        }
                        2 -> {
                            assertEquals("Bearer new-token", exchange.requestHeaders.getFirst("Authorization"))
                            exchange.sendResponseHeaders(503, -1)
                        }
                        else -> {
                            assertEquals("Bearer new-token", exchange.requestHeaders.getFirst("Authorization"))
                            val result = """{"content":[{"type":"text","text":"resumed"}],"isError":false}"""
                            val reply = """{"jsonrpc":"2.0","id":${toolCallId.get()},"result":$result}"""
                            val payload = "data: $reply\n\n".toByteArray()
                            exchange.responseHeaders.add("Content-Type", "text/event-stream")
                            exchange.sendResponseHeaders(200, payload.size.toLong())
                            exchange.responseBody.write(payload)
                        }
                    }
                } else exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            val message = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val method = message.getValue("method").jsonPrimitive.content
            when (method) {
                "server/discover" -> {
                    val payload = """{"jsonrpc":"2.0","id":${message.getValue("id")},"error":{"code":-32601,"message":"unknown"}}""".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(400, payload.size.toLong())
                    exchange.responseBody.write(payload)
                }
                "initialize" -> {
                    exchange.responseHeaders.add("Mcp-Session-Id", "session-1")
                    val result = """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"fixture","version":"1"}}"""
                    val payload = """{"jsonrpc":"2.0","id":${message.getValue("id")},"result":$result}""".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.write(payload)
                }
                "notifications/initialized" -> exchange.sendResponseHeaders(202, -1)
                "tools/list" -> {
                    val result = """{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}"""
                    val payload = """{"jsonrpc":"2.0","id":${message.getValue("id")},"result":$result}""".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.write(payload)
                }
                "tools/call" -> {
                    toolCalls.incrementAndGet()
                    toolCallId.set(message.getValue("id").jsonPrimitive.content.toInt())
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write("id: cursor-1\nretry: 0\ndata:\n\n".toByteArray())
                    exchange.responseBody.flush()
                }
                else -> error("Unexpected method: $method")
            }
            exchange.close()
        }
        server.start()
        val client = StreamableMcpConnection(
            HttpClient(CIO), "http://127.0.0.1:${server.address.port}/mcp",
            accessToken = { token },
            refreshAccessToken = { rejected ->
                assertEquals("old-token", rejected)
                refreshes.incrementAndGet()
                token = "new-token"
                token
            },
        )
        try {
            assertEquals(listOf("echo"), client.listTools().map { it.name })
            val result = withTimeout(4_000) { client.callTool("echo", "{}") }
            assertTrue(result.contentJson.contains("resumed"))
            assertEquals(1, toolCalls.get())
            assertEquals(3, resumeGets.get())
            assertEquals(1, refreshes.get())
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun legacyPostReinitializesOnExpiredSession() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val initializeCount = AtomicInteger(0)
        server.createContext("/mcp") { exchange ->
            if (exchange.requestMethod == "DELETE") {
                assertEquals("session-2", exchange.requestHeaders.getFirst("Mcp-Session-Id"))
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            if (exchange.requestMethod == "GET") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            val message = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val method = message.getValue("method").jsonPrimitive.content
            when (method) {
                "server/discover" -> {
                    val payload = """{"jsonrpc":"2.0","id":${message.getValue("id")},"error":{"code":-32601,"message":"unknown"}}""".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(400, payload.size.toLong())
                    exchange.responseBody.write(payload)
                }
                "initialize" -> {
                    val session = initializeCount.incrementAndGet()
                    exchange.responseHeaders.add("Mcp-Session-Id", "session-$session")
                    val result = """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"fixture","version":"1"}}"""
                    val payload = """{"jsonrpc":"2.0","id":${message.getValue("id")},"result":$result}""".toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.write(payload)
                }
                "notifications/initialized" -> exchange.sendResponseHeaders(202, -1)
                "tools/list" -> {
                    val session = exchange.requestHeaders.getFirst("Mcp-Session-Id")
                    if (session == "session-1") exchange.sendResponseHeaders(404, -1)
                    else {
                        assertEquals("session-2", session)
                        val result = """{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}"""
                        val payload = """{"jsonrpc":"2.0","id":${message.getValue("id")},"result":$result}""".toByteArray()
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, payload.size.toLong())
                        exchange.responseBody.write(payload)
                    }
                }
                else -> error("Unexpected method: $method")
            }
            exchange.close()
        }
        server.start()
        val client = StreamableMcpConnection(HttpClient(CIO), "http://127.0.0.1:${server.address.port}/mcp")
        try {
            assertEquals(listOf("echo"), client.listTools().map { it.name })
            assertEquals(2, initializeCount.get())
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun legacyToolChangeStreamRefreshesRejectedBearer() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val firstGet = AtomicInteger(0)
        val refreshes = AtomicInteger(0)
        val resumed = CompletableDeferred<Unit>()
        var token = "old-token"
        server.createContext("/mcp") { exchange ->
            if (exchange.requestMethod == "DELETE") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            if (exchange.requestMethod == "GET") {
                if (firstGet.incrementAndGet() == 1) {
                    assertEquals("Bearer old-token", exchange.requestHeaders.getFirst("Authorization"))
                    exchange.sendResponseHeaders(401, -1)
                } else {
                    assertEquals("Bearer new-token", exchange.requestHeaders.getFirst("Authorization"))
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    val payload = ": connected\n\n".toByteArray()
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.write(payload)
                    resumed.complete(Unit)
                }
                exchange.close()
                return@createContext
            }
            val message = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            when (message.getValue("method").jsonPrimitive.content) {
                "server/discover" -> {
                    exchange.sendResponseHeaders(400, -1)
                    exchange.close()
                    return@createContext
                }
                "notifications/initialized" -> {
                    exchange.sendResponseHeaders(202, -1)
                    exchange.close()
                    return@createContext
                }
            }
            val result = when (message.getValue("method").jsonPrimitive.content) {
                "initialize" -> {
                    exchange.responseHeaders.add("Mcp-Session-Id", "session-1")
                    """{"protocolVersion":"2025-11-25","capabilities":{"tools":{"listChanged":true}},"serverInfo":{"name":"fixture","version":"1"}}"""
                }
                "tools/list" -> """{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}"""
                else -> error("Unexpected MCP method")
            }
            val payload = """{"jsonrpc":"2.0","id":${message.getValue("id")},"result":$result}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
            exchange.close()
        }
        server.start()
        val client = StreamableMcpConnection(
            HttpClient(CIO), "http://127.0.0.1:${server.address.port}/mcp",
            accessToken = { token },
            refreshAccessToken = { rejected ->
                assertEquals("old-token", rejected)
                refreshes.incrementAndGet()
                token = "new-token"
                token
            },
        )
        try {
            assertEquals(listOf("echo"), client.listTools().map { it.name })
            withTimeout(2_000) { resumed.await() }
            assertEquals(1, refreshes.get())
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun legacyGetResumesAndReinitializesExpiredSession() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        val initialized = AtomicInteger(0)
        val getCount = AtomicInteger(0)
        val revision = AtomicInteger(0)
        val publishFirst = CountDownLatch(1)
        val publishAfterReinitialize = CountDownLatch(1)
        val firstGet = CompletableDeferred<Unit>()
        val thirdGet = CompletableDeferred<Unit>()
        server.createContext("/mcp") { exchange ->
            if (exchange.requestMethod == "DELETE") {
                assertEquals("session-2", exchange.requestHeaders.getFirst("Mcp-Session-Id"))
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            if (exchange.requestMethod == "GET") {
                val number = getCount.incrementAndGet()
                assertEquals("text/event-stream", exchange.requestHeaders.getFirst("Accept"))
                assertEquals("2025-11-25", exchange.requestHeaders.getFirst("MCP-Protocol-Version"))
                if (number == 2) {
                    assertEquals("session-1", exchange.requestHeaders.getFirst("Mcp-Session-Id"))
                    assertEquals("cursor-1", exchange.requestHeaders.getFirst("Last-Event-ID"))
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    assertEquals("session-${if (number == 1) 1 else 2}",
                        exchange.requestHeaders.getFirst("Mcp-Session-Id"))
                    if (number == 3) assertEquals(null, exchange.requestHeaders.getFirst("Last-Event-ID"))
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    if (number == 1) firstGet.complete(Unit) else thirdGet.complete(Unit)
                    val gate = if (number == 1) publishFirst else publishAfterReinitialize
                    if (gate.await(5, TimeUnit.SECONDS)) {
                        revision.set(if (number == 1) 1 else 2)
                        val event = """{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}"""
                        exchange.responseBody.write("id: cursor-$number\ndata: $event\n\n".toByteArray())
                        exchange.responseBody.flush()
                    }
                }
                exchange.close()
                return@createContext
            }
            val message = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val method = message.getValue("method").jsonPrimitive.content
            if (method == "server/discover") {
                val payload = """{"jsonrpc":"2.0","id":${message.getValue("id")},"error":{"code":-32601,"message":"unknown"}}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(400, payload.size.toLong())
                exchange.responseBody.write(payload)
            } else if (method == "notifications/initialized") {
                exchange.sendResponseHeaders(202, -1)
            } else {
                val result = when (method) {
                    "initialize" -> {
                        val session = initialized.incrementAndGet()
                        exchange.responseHeaders.add("Mcp-Session-Id", "session-$session")
                        """{"protocolVersion":"2025-11-25","capabilities":{"tools":{"listChanged":true}},"serverInfo":{"name":"fixture","version":"1"}}"""
                    }
                    "tools/list" -> """{"tools":[{"name":"${when (revision.get()) { 0 -> "old"; 1 -> "new"; else -> "newest" }}","inputSchema":{"type":"object"}}]}"""
                    else -> error("Unexpected MCP method: $method")
                }
                val payload = """{"jsonrpc":"2.0","id":${message.getValue("id")},"result":$result}""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.write(payload)
            }
            exchange.close()
        }
        server.start()
        val client = StreamableMcpConnection(HttpClient(CIO), "http://127.0.0.1:${server.address.port}/mcp")
        val changed = CompletableDeferred<Unit>()
        val changedAfterReinitialize = CompletableDeferred<Unit>()
        client.setToolsChangedListener {
            when (client.listTools().singleOrNull()?.name) {
                "new" -> changed.complete(Unit)
                "newest" -> changedAfterReinitialize.complete(Unit)
            }
        }
        try {
            assertEquals(listOf("old"), client.listTools().map { it.name })
            withTimeout(2_000) { firstGet.await() }
            publishFirst.countDown()
            withTimeout(2_000) { changed.await() }
            withTimeout(4_000) { thirdGet.await() }
            publishAfterReinitialize.countDown()
            withTimeout(2_000) { changedAfterReinitialize.await() }
            assertEquals(2, initialized.get())
            assertEquals(listOf("newest"), client.listTools().map { it.name })
        } finally {
            publishFirst.countDown()
            publishAfterReinitialize.countDown()
            client.close()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun legacyServerFallsBackToInitializeAndParsesSse() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val methods = mutableListOf<String>()
        server.createContext("/mcp") { exchange ->
            if (exchange.requestMethod == "DELETE") {
                assertEquals("session-123", exchange.requestHeaders.getFirst("Mcp-Session-Id"))
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            if (exchange.requestMethod == "GET") {
                assertEquals("text/event-stream", exchange.requestHeaders.getFirst("Accept"))
                assertEquals("session-123", exchange.requestHeaders.getFirst("Mcp-Session-Id"))
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return@createContext
            }
            val message = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val method = message.getValue("method").jsonPrimitive.content
            methods += method
            if (method == "server/discover") {
                exchange.responseHeaders.add("Content-Type", "application/json")
                val payload = """{"jsonrpc":"2.0","id":${message.getValue("id")},"error":{"code":-32601,"message":"unknown method"}}""".toByteArray()
                exchange.sendResponseHeaders(400, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            } else if (method == "notifications/initialized") {
                assertEquals("session-123", exchange.requestHeaders.getFirst("Mcp-Session-Id"))
                exchange.sendResponseHeaders(202, -1)
            } else {
                if (method == "initialize") {
                    exchange.responseHeaders.add("Mcp-Session-Id", "session-123")
                } else {
                    assertEquals("session-123", exchange.requestHeaders.getFirst("Mcp-Session-Id"))
                }
                val result = when (method) {
                    "initialize" -> """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"fixture","version":"1"}}"""
                    "tools/list" -> """{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}"""
                    "tools/call" -> """{"content":[{"type":"text","text":"legacy ok"}],"isError":false}"""
                    else -> error("Unexpected MCP method: $method")
                }
                val response = """{"jsonrpc":"2.0","id":${message.getValue("id")},"result":$result}"""
                val payload = if (method == "tools/call") "data: $response\n\n" else response
                exchange.responseHeaders.add("Content-Type", if (method == "tools/call") "text/event-stream" else "application/json")
                val bytes = payload.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        val client = StreamableMcpConnection(
            HttpClient(CIO), "http://127.0.0.1:${server.address.port}/mcp",
        )
        try {
            assertEquals(listOf("echo"), client.listTools().map { it.name })
            assertTrue(client.callTool("echo", "{}").contentJson.contains("legacy ok"))
            assertEquals(listOf("server/discover", "initialize", "notifications/initialized", "tools/list", "tools/call"), methods)
        } finally {
            client.close()
            server.stop(0)
        }
    }
}
