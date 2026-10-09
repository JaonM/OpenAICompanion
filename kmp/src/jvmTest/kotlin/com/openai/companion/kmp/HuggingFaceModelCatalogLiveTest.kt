package com.openai.companion.kmp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.plugin
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Explicit opt-in live metadata check; the send interceptor forbids artifact URLs. */
class HuggingFaceModelCatalogLiveTest {
    @Test fun officialMlxAndGgufMetadataWithoutDownloadingWeights() = runBlocking {
        if (System.getenv("HF_CATALOG_LIVE") != "1") return@runBlocking
        val client = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 45_000 } }
        var requests = 0
        client.plugin(HttpSend).intercept { request ->
            check(request.url.host == "huggingface.co" && request.url.build().encodedPath.startsWith("/api/models")) { "元数据验收禁止下载权重" }
            requests++
            execute(request)
        }
        try {
            val catalog = HuggingFaceModelCatalog(client)
            catalog.browse("MLX", "Qwen3-0.6B-4bit", false)
            val mlx = catalog.items.first { it.repository == "mlx-community/Qwen3-0.6B-4bit" }
            assertFalse(mlx.resolved)
            val resolved = catalog.inspect(mlx.id).single()
            assertTrue(resolved.resolved)
            assertNull(resolved.unavailableReason)
            assertTrue(resolved.files.any { it.file == "model.safetensors" && it.sha256.length == 64 })
            assertTrue(resolved.bytes > 300_000_000)
            catalog.browse("llama.cpp", "Qwen3-0.6B-GGUF", false)
            val gguf = catalog.items.first { it.repository == "Qwen/Qwen3-0.6B-GGUF" }
            val files = catalog.inspect(gguf.id)
            assertTrue(files.any { it.file == "Qwen3-0.6B-Q8_0.gguf" && it.sha256.length == 64 })
            assertEquals(4, requests)
        } finally { client.close() }
    }
}
