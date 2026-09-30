package com.openai.companion.kmp

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class McpOAuthDiscoveryTest {
    @Test
    fun fallsBackFromPathMetadataAndUsesChallengeScopes() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = mutableListOf<String>()
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            seen += path
            val origin = "http://127.0.0.1:${server.address.port}"
            val result = when (path) {
                "/.well-known/oauth-protected-resource/mcp" -> null
                "/.well-known/oauth-protected-resource" ->
                    """{"resource":"$origin/mcp","authorization_servers":["$origin"],"scopes_supported":["fallback"]}"""
                "/.well-known/oauth-authorization-server" ->
                    """{"issuer":"$origin","authorization_endpoint":"$origin/authorize","token_endpoint":"$origin/token","registration_endpoint":"$origin/register","client_id_metadata_document_supported":true,"code_challenge_methods_supported":["S256"]}"""
                else -> error("Unexpected OAuth path $path")
            }
            val body = result?.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (body == null) 404 else 200, body?.size?.toLong() ?: -1)
            body?.let { exchange.responseBody.use { stream -> stream.write(it) } }
            exchange.close()
        }
        server.start()
        val client = HttpClient(CIO) { followRedirects = false }
        try {
            val origin = "http://127.0.0.1:${server.address.port}"
            val metadata = McpOAuthDiscovery(client).discover(
                "$origin/mcp", McpAuthorizationChallenge(null, listOf("read", "write"), null),
            )
            assertEquals("$origin/mcp", metadata.resource)
            assertEquals("$origin/authorize", metadata.authorizationEndpoint)
            assertEquals("$origin/token", metadata.tokenEndpoint)
            assertEquals(listOf("read", "write"), metadata.scopes)
            assertEquals(true, metadata.clientIdMetadataDocumentSupported)
            assertEquals(listOf("S256"), metadata.codeChallengeMethodsSupported)
            assertEquals(listOf(
                "/.well-known/oauth-protected-resource/mcp",
                "/.well-known/oauth-protected-resource",
                "/.well-known/oauth-authorization-server",
            ), seen)
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun refusesInsecureMetadataOrigin() = runBlocking {
        val client = HttpClient(CIO) { followRedirects = false }
        try {
            assertFailsWith<IllegalArgumentException> {
                McpOAuthDiscovery(client).discover("http://example.com/mcp")
            }
        } finally {
            client.close()
        }
        Unit
    }
}
