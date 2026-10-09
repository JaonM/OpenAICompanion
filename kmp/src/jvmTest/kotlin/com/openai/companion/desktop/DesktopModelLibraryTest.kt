package com.openai.companion.desktop

import com.openai.companion.kmp.ModelLibrary
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopModelLibraryTest {
    @Test fun successfulPullSelectsModelButFailedPullKeepsCurrentSelection() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var fail = false
        var requestBody = ""
        server.createContext("/api/pull") { exchange ->
            requestBody = exchange.requestBody.bufferedReader().readText()
            val response = (if (fail) "{\"error\":\"download failed\"}\n" else "{\"status\":\"pulling manifest\"}\n{\"status\":\"success\"}\n").toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        val adapter = DesktopModelServe()
        val oldEndpoint = adapter.endpoint
        val oldModel = adapter.model
        try {
            adapter.endpoint = "http://127.0.0.1:${server.address.port}/v1/chat/completions"
            adapter.installModel("qwen3-small")
            assertTrue(requestBody.contains(ModelLibrary.get("qwen3-small").ollamaName))
            assertEquals("qwen3-small", adapter.modelLibrary().selected)
            val selected = adapter.model
            fail = true
            assertFailsWith<IllegalStateException> { adapter.installModel("qwen35-gguf") }
            assertEquals(selected, adapter.model)
        } finally { adapter.save(oldEndpoint, oldModel, ""); server.stop(0) }
    }
}
