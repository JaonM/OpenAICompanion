package com.openai.companion.kmp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class HuggingFaceModelCatalogTest {
    private val revision = "a".repeat(40)
    @Test fun browsingAndPagingReadMetadataOnlyAndKeepInstalledModelIdentity() = runBlocking {
        val urls = mutableListOf<Url>()
        val client = HttpClient(MockEngine { request ->
            urls += request.url
            respond("""[{"id":"mlx-community/Qwen3.5-4B-MLX-4bit","sha":"$revision","downloads":123,"likes":45,"lastModified":"2026-10-09T00:00:00Z","gated":false}]""",
                headers = headersOf("Link", "<https://huggingface.co/api/models?cursor=next>; rel=\"next\""))
        })
        val catalog = HuggingFaceModelCatalog(client)
        catalog.browse("MLX", "Qwen3.5", false)
        assertEquals("mlx-community", urls.single().parameters["author"])
        assertEquals("Qwen3.5", urls.single().parameters["search"])
        assertEquals("qwen35-mlx", catalog.items.single().id)
        assertEquals(123L, catalog.items.single().downloads)
        assertTrue(catalog.hasMore)
        catalog.browse("MLX", "Qwen3.5", true)
        assertEquals("next", urls.last().parameters["cursor"])
        assertEquals(1, catalog.items.size)
        assertTrue(urls.all { it.encodedPath == "/api/models" }, "浏览不能访问权重 resolve 地址")
        client.close()
    }
    @Test fun enginesUseDifferentSourcesAndGgufRequiresFileChoice() = runBlocking {
        val urls = mutableListOf<Url>()
        val client = HttpClient(MockEngine { request ->
            urls += request.url
            if (request.url.encodedPath == "/api/models") respond("""[{"id":"example/tiny-GGUF","sha":"$revision","gated":false}]""")
            else respond("""{"sha":"$revision","siblings":[{"rfilename":"tiny-Q4.gguf","size":1000,"lfs":{"sha256":"${"b".repeat(64)}"}},{"rfilename":"tiny-00001-of-00002.gguf","size":1000,"lfs":{"sha256":"${"c".repeat(64)}"}}]}""")
        })
        val catalog = HuggingFaceModelCatalog(client)
        catalog.browse("llama.cpp", "tiny", false)
        assertEquals("gguf", urls.first().parameters["filter"])
        assertNull(urls.first().parameters["author"])
        assertFalse(catalog.items.single().resolved)
        val files = catalog.inspect(catalog.items.single().id)
        assertEquals(2, files.size)
        assertTrue(files.first().resolved)
        assertEquals("b".repeat(64), files.first().sha256)
        assertNotNull(files.last().unavailableReason)
        assertTrue(urls.all { it.encodedPath.startsWith("/api/models") })
        client.close()
    }
    @Test fun mlxManifestUsesPinnedRevisionAndChecksIntegrityMetadata() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            assertTrue(request.url.encodedPath.contains("/revision/"))
            respond("""{"sha":"$revision","config":{"model_type":"qwen3"},"siblings":[{"rfilename":"config.json","size":20,"blobId":"${"d".repeat(40)}"},{"rfilename":"tokenizer.json","size":30,"blobId":"${"e".repeat(40)}"},{"rfilename":"model.safetensors","size":1000,"lfs":{"sha256":"${"f".repeat(64)}"}},{"rfilename":"../escape.json","size":10,"blobId":"${"d".repeat(40)}"}]}""")
        })
        val catalog = HuggingFaceModelCatalog(client)
        val resolved = catalog.inspect("qwen35-mlx").single()
        assertEquals(1050L, resolved.bytes)
        assertEquals(3, resolved.files.size)
        assertEquals(revision, resolved.revision)
        assertFalse(HuggingFaceModelCatalog.validRepository("../bad"))
        assertFalse(HuggingFaceModelCatalog.validArtifact("../escape"))
        client.close()
    }
}
