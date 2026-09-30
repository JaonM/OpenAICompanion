package com.openai.companion.desktop

import com.openai.companion.kmp.AppModelServe
import com.openai.companion.kmp.AppAgentEventSink
import com.openai.companion.kmp.GeneratedHarnessBindingsAdapter
import com.openai.companion.kmp.ModelStreamCallback
import com.openai.companion.kmp.McpServerManager
import com.openai.companion.kmp.StreamableMcpConnection
import com.openai.companion.kmp.registerMcpProvider
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.net.InetSocketAddress
import java.nio.file.Files
import java.io.StringReader
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import uniffi.harness.appDeleteSession
import uniffi.harness.appListSessions
import uniffi.harness.appLoadSession
import uniffi.harness.appOpenStore
import uniffi.harness.appResumeSession
import uniffi.harness.appSendMessage
import uniffi.harness.appStartSession

class DesktopBridgeTest {
    @Test
    fun mcpToolCallRoundTripsAcrossKotlinAndRustHarness() = runBlocking {
        val library = System.getenv("HARNESS_LIBRARY_PATH")
            ?: error("HARNESS_LIBRARY_PATH must point to libharness.dylib")
        System.setProperty("uniffi.component.harness.libraryOverride", library)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val toolCalls = AtomicInteger(0)
        val serverReportsError = AtomicBoolean(false)
        server.createContext("/mcp") { exchange ->
            val message = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val method = message.getValue("method").jsonPrimitive.content
            val result = when (method) {
                "server/discover" -> """{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}"""
                "tools/list" -> """{"tools":[{"name":"echo","description":"Echo text","inputSchema":{"type":"object","properties":{"text":{"type":"string"}}}}]}"""
                "tools/call" -> {
                    toolCalls.incrementAndGet()
                    assertEquals("echo", message.getValue("params").jsonObject.getValue("name").jsonPrimitive.content)
                    if (serverReportsError.get()) {
                        """{"content":[{"type":"text","text":"network timeout in search query"}],"isError":true}"""
                    } else {
                        """{"content":[{"type":"text","text":"MCP says hello"}],"isError":false}"""
                    }
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
        val bindings = GeneratedHarnessBindingsAdapter()
        val manager = McpServerManager()
        val database = Files.createTempFile("companion-mcp-bridge-", ".sqlite")
        val approvals = AtomicInteger(0)
        val modelTurns = AtomicInteger(0)
        var approved = true
        try {
            manager.attach("remote", StreamableMcpConnection(
                HttpClient(CIO) { followRedirects = false },
                "http://127.0.0.1:${server.address.port}/mcp",
            ))
            registerMcpProvider(bindings, manager, approve = { name, _ ->
                assertEquals("echo", name)
                approvals.incrementAndGet()
                approved
            })
            bindings.registerModelServeCallback(object : AppModelServe {
                override suspend fun complete(requestJson: String, callback: ModelStreamCallback) {
                    if (requestJson.contains("提取未来有用的原子记忆点")) {
                        callback.onChunk("""{"choices":[{"message":{"content":"[]"}}]}""")
                        return
                    }
                    assertTrue(requestJson.contains("echo"))
                    if (modelTurns.incrementAndGet() % 2 == 1) {
                        callback.onChunk("""{"choices":[{"message":{"tool_calls":[{"id":"call-1","type":"function","function":{"name":"echo","arguments":"{\"text\":\"hello\"}"}}]}}]}""")
                    } else {
                        when {
                            !approved -> assertTrue(requestJson.contains("PermissionDenied"))
                            serverReportsError.get() -> assertTrue(requestJson.contains("network timeout in search query"))
                            else -> assertTrue(requestJson.contains("MCP says hello"))
                        }
                        callback.onChunk("""{"choices":[{"delta":{"content":"工具调用完成"}}]}""")
                    }
                }
            })
            assertTrue(appOpenStore(database.toString()).ok)
            assertTrue(appStartSession().ok)
            val reply = appSendMessage("调用 echo")
            assertTrue(reply.ok, reply.error)
            assertEquals("工具调用完成", Json.parseToJsonElement(reply.valueJson)
                .jsonObject.getValue("output").jsonPrimitive.content)
            assertEquals(1, approvals.get())
            assertEquals(1, toolCalls.get())
            assertEquals(2, modelTurns.get())
            approved = false
            assertTrue(appStartSession().ok)
            val denied = appSendMessage("不要批准 echo")
            assertTrue(denied.ok, denied.error)
            assertEquals(2, approvals.get())
            assertEquals(1, toolCalls.get())
            assertEquals(4, modelTurns.get())
            approved = true
            serverReportsError.set(true)
            val failedToolSession = appStartSession()
            assertTrue(failedToolSession.ok, failedToolSession.error)
            val failedToolId = Json.parseToJsonElement(failedToolSession.valueJson)
                .jsonObject.getValue("id").jsonPrimitive.content.toLong()
            val reportedError = appSendMessage("让 echo 返回工具错误")
            assertTrue(reportedError.ok, reportedError.error)
            assertEquals(3, approvals.get())
            assertEquals(2, toolCalls.get()) // Tool-level isError must not trigger a retry.
            assertEquals(6, modelTurns.get())
            val toolMessages = Json.parseToJsonElement(appLoadSession(failedToolId).valueJson)
                .jsonObject.getValue("messages").jsonArray
            assertTrue(toolMessages.any { message ->
                val value = message.jsonObject
                value["is_error"]?.jsonPrimitive?.content == "true" &&
                    value["content"]?.jsonPrimitive?.content.orEmpty().contains("network timeout in search query")
            })
        } finally {
            bindings.unregisterModelServeCallback()
            bindings.unregisterToolProvider()
            manager.detach("remote")
            server.stop(0)
            Files.deleteIfExists(database)
        }
    }

    @Test
    fun parsesSseDataAndDoneMarker() {
        val chunks = mutableListOf<String>()
        consumeServerSentEvents(StringReader("""
            : keepalive
            data: {"choices":[{"delta":{"reasoning_content":"先想"}}]}

            data: {"choices":[{"delta":{"content":"你好"}}]}

            data: [DONE]

            data: ignored

        """.trimIndent()), chunks::add)
        assertEquals(2, chunks.size)
        assertTrue(chunks.first().contains("先想"))
        assertTrue(chunks.last().contains("你好"))
    }

    @Test
    fun sessionRoundTripAcrossKotlinAndRust() {
        val library = System.getenv("HARNESS_LIBRARY_PATH")
            ?: error("HARNESS_LIBRARY_PATH must point to libharness.dylib")
        System.setProperty("uniffi.component.harness.libraryOverride", library)
        val bindings = GeneratedHarnessBindingsAdapter()
        bindings.registerModelServeCallback(object : AppModelServe {
            override suspend fun complete(requestJson: String, callback: ModelStreamCallback) {
                if (requestJson.contains("提取未来有用的原子记忆点")) {
                    callback.onChunk("""{"choices":[{"message":{"content":"[]"}}]}""")
                    return
                }
                callback.onChunk("""{"choices":[{"delta":{"reasoning_content":"先分析，"}}]}""")
                callback.onChunk("""{"choices":[{"delta":{"reasoning_content":"再回答。","content":"来自 "}}]}""")
                callback.onChunk("""{"choices":[{"delta":{"content":"Rust loop 的回复"}}]}""")
            }
        })
        val events = mutableListOf<String>()
        bindings.registerAgentEventSink(object : AppAgentEventSink {
            override fun onReasoningDelta(text: String) { events += "reasoning:$text" }
            override fun onTextDelta(text: String) { events += "text:$text" }
            override fun onCompleted(finalText: String) { events += "completed:$finalText" }
            override fun onError(errorJson: String) { events += "error:$errorJson" }
        })
        val database = Files.createTempFile("companion-desktop-bridge-", ".sqlite")
        try {
            assertTrue(appOpenStore(database.toString()).ok)
            val started = appStartSession()
            assertTrue(started.ok, started.error)
            val id = Json.parseToJsonElement(started.valueJson).jsonObject
                .getValue("id").jsonPrimitive.content.toLong()

            val reply = appSendMessage("你好")
            assertTrue(reply.ok, reply.error)
            assertEquals("来自 Rust loop 的回复", Json.parseToJsonElement(reply.valueJson)
                .jsonObject.getValue("output").jsonPrimitive.content)
            assertEquals("reasoning:先分析，", events[0])
            assertEquals("reasoning:再回答。", events[1])
            assertTrue(events.contains("completed:来自 Rust loop 的回复"))
            assertTrue(appResumeSession(id).ok)
            val trace = Json.parseToJsonElement(appLoadSession(id).valueJson).jsonObject
                .getValue("messages").jsonArray
            assertEquals(3, trace.size)
            assertEquals("你好", trace[0].jsonObject.getValue("content").jsonPrimitive.content)
            assertEquals("先分析，再回答。", trace[1].jsonObject.getValue("content").jsonPrimitive.content)
            assertEquals(1, Json.parseToJsonElement(appListSessions().valueJson).jsonArray.size)
            bindings.registerModelServeCallback(object : AppModelServe {
                override suspend fun complete(requestJson: String, callback: ModelStreamCallback) {
                    error("model offline")
                }
            })
            assertTrue(appOpenStore(database.toString()).ok)
            val failedSession = appStartSession()
            assertTrue(failedSession.ok, failedSession.error)
            val failedId = Json.parseToJsonElement(failedSession.valueJson).jsonObject
                .getValue("id").jsonPrimitive.content.toLong()
            assertEquals(id, failedId)
            val failed = appSendMessage("再试一次")
            assertTrue(!failed.ok)
            assertTrue(failed.error.contains("model offline"), failed.error)
            val failedMessages = Json.parseToJsonElement(appLoadSession(failedId).valueJson)
                .jsonObject.getValue("messages").jsonArray
            assertEquals("user", failedMessages[3].jsonObject.getValue("role").jsonPrimitive.content)
            assertEquals("status", failedMessages[4].jsonObject.getValue("role").jsonPrimitive.content)
            assertTrue(failedMessages[4].jsonObject.getValue("content").jsonPrimitive.content
                .contains("model offline"))
            assertTrue(!appDeleteSession(id).ok)
            assertEquals(1, Json.parseToJsonElement(appListSessions().valueJson).jsonArray.size)
        } finally {
            bindings.unregisterAgentEventSink()
            bindings.unregisterModelServeCallback()
            Files.deleteIfExists(database)
        }
    }
}
