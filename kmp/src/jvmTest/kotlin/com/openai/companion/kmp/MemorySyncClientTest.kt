package com.openai.companion.kmp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class MemorySyncClientTest {
    private val endpoint = "https://sync.example/v1/memories/sync"
    private fun page(records: String = "[]", cursor: Long = 0, more: Boolean = false) =
        """{"version":2,"records":$records,"cursor":$cursor,"has_more":$more}"""

    @Test fun requiresHttpsAndSendsBearerOnlyToSyncPath() = runBlocking<Unit> {
        var calls = 0
        HttpClient(MockEngine { request ->
            calls++
            assertEquals("/v1/memories/sync", request.url.encodedPath)
            assertEquals("Bearer secret", request.headers[HttpHeaders.Authorization])
            respond(page())
        }).use { http ->
            val client = MemorySyncClient(http)
            assertFailsWith<IllegalArgumentException> { client.exchange("http://sync.example/v1/memories/sync", "secret", "[]") {} }
            assertFailsWith<IllegalArgumentException> { client.exchange("https://sync.example/other", "secret", "[]") {} }
            client.exchange(endpoint, "secret", "[]") { assertEquals("[]", it) }
            assertEquals(1, calls)
        }
    }

    @Test fun uploadsBeyondOldLimitAndMergesEachPageBeforeAdvancing() = runBlocking<Unit> {
        var uploaded = 0
        var merged = 0L
        var calls = 0
        HttpClient(MockEngine { request ->
            val body = (request.body as TextContent).text
            assertTrue(body.encodeToByteArray().size <= MemorySyncClient.PAGE_BYTES)
            val payload = Json.parseToJsonElement(body).jsonObject
            assertEquals(merged, payload.getValue("cursor").jsonPrimitive.long)
            val batch = payload.getValue("records").jsonArray
            assertTrue(batch.size <= 256)
            uploaded += batch.size
            calls++
            respond(page("[{}]", calls.toLong(), more = uploaded == 10001 && calls < 42))
        }).use { http ->
            MemorySyncClient(http).exchange(endpoint, "secret", JsonArray(List(10001) { JsonObject(emptyMap()) }).toString()) {
                merged++
            }
        }
        assertEquals(10001, uploaded)
        assertEquals(42, calls)
        assertEquals(42L, merged)
    }

    @Test fun mergeFailureStopsPagingAndRetryRestartsFromZero() = runBlocking<Unit> {
        var calls = 0
        HttpClient(MockEngine { request ->
            calls++
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals(0L, body.getValue("cursor").jsonPrimitive.long)
            respond(page("[{}]", 1, true))
        }).use { http ->
            repeat(2) {
                assertFailsWith<IllegalStateException> {
                    MemorySyncClient(http).exchange(endpoint, "secret", "[]") { error("database unavailable") }
                }
            }
        }
        assertEquals(2, calls)
    }

    @Test fun rejectsNonAdvancingCursor() = runBlocking<Unit> {
        HttpClient(MockEngine { respond(page("[]", 0, true)) }).use { http ->
            assertFailsWith<IllegalArgumentException> { MemorySyncClient(http).exchange(endpoint, "secret", "[]") {} }
        }
    }
}
