package com.openai.companion.kmp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MemorySyncClientTest {
    @Test
    fun requiresHttpsAndSendsBearerOnlyToSyncPath() = runBlocking {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            assertEquals("/v1/memories/sync", request.url.encodedPath)
            assertEquals("Bearer secret", request.headers[HttpHeaders.Authorization])
            respond("""{"records":[]}""")
        }
        HttpClient(engine).use { http ->
            val client = MemorySyncClient(http)
            assertFailsWith<IllegalArgumentException> {
                client.exchange("http://sync.example/v1/memories/sync", "secret", "[]")
            }
            assertFailsWith<IllegalArgumentException> {
                client.exchange("https://sync.example/other", "secret", "[]")
            }
            assertEquals("[]", client.exchange("https://sync.example/v1/memories/sync", "secret", "[]"))
            assertEquals(1, calls)
        }
    }
}
