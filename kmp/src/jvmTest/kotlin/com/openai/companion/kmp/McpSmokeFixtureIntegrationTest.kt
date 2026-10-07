package com.openai.companion.kmp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/** Opt-in wire test against the same standalone server used for iOS Simulator acceptance. */
class McpSmokeFixtureIntegrationTest {
    @Test
    fun sharedClientCompletesFixtureToolsAndInputRoundTrip() = runBlocking {
        assumeTrue(System.getenv("RUN_MCP_SMOKE_FIXTURE") == "1")
        val fixture = File("../scripts/mcp_smoke_server.py").canonicalFile
        require(fixture.isFile) { "Missing MCP smoke fixture: $fixture" }
        val process = ProcessBuilder("python3", fixture.path, "--port", "0")
            .redirectErrorStream(true)
            .start()
        var connection: StreamableMcpConnection? = null
        try {
            val banner = process.inputStream.bufferedReader().readLine()
            require(banner?.startsWith("MCP fixture: http://127.0.0.1:") == true) {
                "MCP fixture failed to start: $banner"
            }
            val endpoint = banner.removePrefix("MCP fixture: ")
            val base = endpoint.removeSuffix("/mcp")
            val client = StreamableMcpConnection(
                HttpClient(CIO) {
                    followRedirects = false
                    install(HttpTimeout) { requestTimeoutMillis = 5_000 }
                },
                endpoint,
                inputHandler = { method, params ->
                    assertEquals("elicitation/create", method)
                    assertTrue(params.getValue("message").jsonPrimitive.content.contains("昵称"))
                    buildJsonObject {
                        put("action", "accept")
                        put("content", buildJsonObject { put("name", "Tester") })
                    }
                },
                inputCapabilities = buildJsonObject {
                    put("elicitation", buildJsonObject { put("form", JsonObject(emptyMap())) })
                },
            )
            connection = client
            withTimeout(10_000) {
                assertEquals(
                    listOf("echo", "report_error", "ask_name"),
                    client.listTools().map { it.name },
                )
                val echoed = client.callTool("echo", """{"text":"hello"}""")
                assertFalse(echoed.isError)
                val content = Json.parseToJsonElement(echoed.contentJson).jsonObject
                    .getValue("content").jsonArray
                assertEquals("hello", content.single().jsonObject.getValue("text").jsonPrimitive.content)
                val error = client.callTool("report_error", "{}")
                assertTrue(error.isError)
                assertTrue(error.contentJson.contains("network timeout"))
                val named = client.callTool("ask_name", "{}")
                assertFalse(named.isError)
                assertTrue(named.contentJson.contains("你好，Tester"))
            }
            val counts = URI("$base/stats").toURL().openStream().bufferedReader().use {
                Json.parseToJsonElement(it.readText()).jsonObject
            }
            assertEquals("1", counts.getValue("echo").jsonPrimitive.content)
            assertEquals("1", counts.getValue("report_error").jsonPrimitive.content)
            assertEquals("2", counts.getValue("ask_name").jsonPrimitive.content)
        } finally {
            connection?.close()
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }
}
