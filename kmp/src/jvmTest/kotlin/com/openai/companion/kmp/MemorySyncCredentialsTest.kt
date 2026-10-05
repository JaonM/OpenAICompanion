package com.openai.companion.kmp

import kotlin.test.*
import kotlinx.coroutines.runBlocking

class MemorySyncCredentialsTest {
    @Test fun onlySameServiceCanReuseStoredCredential() = runBlocking<Unit> {
        val first = "https://a.example/v1/memories/sync"
        val second = "https://b.example/v1/memories/sync"
        assertEquals("saved", MemorySyncCredentials.resolve(first, first, "") { "saved" })
        assertFailsWith<IllegalArgumentException> {
            MemorySyncCredentials.resolve(second, first, "") { error("must not load old credential") }
        }
        assertEquals("new", MemorySyncCredentials.resolve(second, first, "new") { error("must not load") })
        assertFailsWith<IllegalArgumentException> {
            MemorySyncCredentials.resolve("https://user:pass@a.example/v1/memories/sync", first, "new") { null }
        }
    }
}
