package com.openai.companion.desktop

import com.openai.companion.kmp.ModelStreamCallback
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DesktopModelServeTest {
    @Test
    fun streamsOpenAiCompatibleSseChunks() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requestBody = ""
        server.createContext("/v1/chat/completions") { exchange ->
            requestBody = exchange.requestBody.bufferedReader().readText()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter().use { writer ->
                writer.write("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"思考\"}}]}\n\n")
                writer.flush()
                writer.write("data: {\"choices\":[{\"delta\":{\"content\":\"回答\"}}]}\n\n")
                writer.write("data: [DONE]\n\n")
            }
        }
        server.start()
        try {
            val provider = DesktopModelServe().apply {
                endpoint = "http://127.0.0.1:${server.address.port}/v1/chat/completions"
                model = "local-test"
            }
            val chunks = mutableListOf<String>()
            provider.complete("""{"messages":[{"role":"user","content":"你好"}],"tools":[]}""",
                object : ModelStreamCallback {
                    override fun onChunk(chunkJson: String) { chunks += chunkJson }
                })
            assertEquals(2, chunks.size)
            assertTrue(chunks[0].contains("思考"))
            assertTrue(chunks[1].contains("回答"))
            assertTrue(requestBody.contains("\"stream\":true"))
            assertTrue(requestBody.contains("\"model\":\"local-test\""))
            assertTrue(!requestBody.contains("\"tools\""))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun cancelStopsRequestBeforeResponseHeadersArrive() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.use { it.readAllBytes() }
            received.countDown()
            release.await(10, TimeUnit.SECONDS)
            runCatching { exchange.sendResponseHeaders(200, -1) }
            exchange.close()
        }
        server.start()
        try {
            val provider = DesktopModelServe().apply {
                endpoint = "http://127.0.0.1:${server.address.port}/v1/chat/completions"
                model = "local-test"
            }
            val request = async(Dispatchers.IO) {
                runCatching {
                    provider.complete("""{"messages":[{"role":"user","content":"hello"}]}""",
                        object : ModelStreamCallback {
                            override fun onChunk(chunkJson: String) = Unit
                        })
                }
            }
            assertTrue(received.await(5, TimeUnit.SECONDS), "Server did not receive request")
            provider.cancelCurrentRequest()
            assertEquals("生成已取消", provider.lastError)
            assertTrue(withTimeout(5_000) { request.await() }.isFailure)
            assertEquals("生成已取消", provider.lastError)
        } finally {
            release.countDown()
            server.stop(0)
        }
    }
}
