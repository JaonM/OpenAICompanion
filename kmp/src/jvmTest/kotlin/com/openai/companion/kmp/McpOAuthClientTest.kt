package com.openai.companion.kmp

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.Url
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class McpOAuthClientTest {
    @Test
    fun pkceStateIssuerAndResourceAreBoundToTheTokenExchange() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val tokenRequests = AtomicInteger(0)
        val registrationRequests = AtomicInteger(0)
        val origin = "http://127.0.0.1:${server.address.port}"
        var expectedVerifier = Base64.UrlSafe.encode(ByteArray(32) { 7 }).trimEnd('=')
        server.createContext("/") { exchange ->
            val result = when (exchange.requestURI.path) {
                "/.well-known/oauth-protected-resource/mcp" ->
                    """{"resource":"$origin/mcp","authorization_servers":["$origin"],"scopes_supported":["read"]}"""
                "/.well-known/oauth-authorization-server" ->
                    """{"issuer":"$origin","authorization_endpoint":"$origin/authorize","token_endpoint":"$origin/token","registration_endpoint":"$origin/register","code_challenge_methods_supported":["S256"]}"""
                "/register" -> {
                    registrationRequests.incrementAndGet()
                    val body = exchange.requestBody.bufferedReader().readText()
                    assertTrue("\"application_type\":\"native\"" in body)
                    assertTrue("\"token_endpoint_auth_method\":\"none\"" in body)
                    """{"client_id":"registered-client","token_endpoint_auth_method":"none"}"""
                }
                "/token" -> {
                    tokenRequests.incrementAndGet()
                    val body = exchange.requestBody.bufferedReader().readText()
                    assertTrue("resource=http%3A%2F%2F127.0.0.1%3A${server.address.port}%2Fmcp" in body)
                    assertTrue("client_id=registered-client" in body)
                    if ("grant_type=refresh_token" in body) {
                        assertTrue("refresh_token=refresh-token" in body)
                        """{"token_type":"Bearer","access_token":"renewed-token","refresh_token":"rotated-refresh","expires_in":3600}"""
                    } else {
                        assertTrue("code_verifier=$expectedVerifier" in body)
                        assertTrue("code=one-time-code" in body)
                        """{"token_type":"Bearer","access_token":"secret-token","refresh_token":"refresh-token","expires_in":3600}"""
                    }
                }
                else -> error("Unexpected path ${exchange.requestURI.path}")
            }
            val bytes = result.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        val client = HttpClient(CIO) { followRedirects = false }
        val crypto = object : McpOAuthCrypto {
            var calls = 0
            override fun randomBytes(count: Int): ByteArray = ByteArray(count) { if (calls++ < 32) 7 else 9 }
            override fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        }
        var requestedScope = "read"
        val browser = object : McpOAuthBrowser {
            override suspend fun authorize(url: String, redirectUri: String): String {
                val request = Url(url)
                assertEquals("S256", request.parameters["code_challenge_method"])
                assertEquals("$origin/mcp", request.parameters["resource"])
                assertEquals(requestedScope, request.parameters["scope"])
                val expectedChallenge = Base64.UrlSafe.encode(
                    MessageDigest.getInstance("SHA-256").digest(expectedVerifier.encodeToByteArray()),
                ).trimEnd('=')
                assertEquals(expectedChallenge, request.parameters["code_challenge"])
                return "$redirectUri?code=one-time-code&state=${request.parameters["state"]}&iss=$origin"
            }
        }
        try {
            val oauth = McpOAuthClient(client, McpOAuthDiscovery(client), crypto, browser)
            val registration = oauth.register("$origin/mcp", "openai-companion://oauth/callback")
            assertEquals("registered-client", registration.clientId)
            assertEquals(origin, registration.issuer)
            val tokens = oauth
                .authorize("$origin/mcp", registration)
            assertEquals("secret-token", tokens.accessToken)
            assertEquals("refresh-token", tokens.refreshToken)
            assertEquals(3600, tokens.expiresInSeconds)
            assertEquals(origin, tokens.issuer)
            assertEquals(1, tokenRequests.get())
            val refreshed = oauth.refresh("$origin/mcp", tokens)
            assertEquals("renewed-token", refreshed.accessToken)
            assertEquals("rotated-refresh", refreshed.refreshToken)
            assertEquals(2, tokenRequests.get())
            requestedScope = "write"
            expectedVerifier = Base64.UrlSafe.encode(ByteArray(32) { 9 }).trimEnd('=')
            val steppedUp = oauth.authorize(
                "$origin/mcp", registration,
                McpAuthorizationChallenge(null, listOf("write"), "insufficient_scope"),
            )
            assertEquals("secret-token", steppedUp.accessToken)
            assertEquals(1, registrationRequests.get())
            assertEquals(3, tokenRequests.get())
            assertFailsWith<IllegalArgumentException> {
                oauth.refresh("$origin/mcp", refreshed.copy(issuer = "https://other.example"))
            }
            assertEquals(3, tokenRequests.get())
            val hostileBrowser = object : McpOAuthBrowser {
                override suspend fun authorize(url: String, redirectUri: String): String =
                    "$redirectUri?code=stolen&state=wrong&iss=$origin"
            }
            assertFailsWith<IllegalArgumentException> {
                McpOAuthClient(client, McpOAuthDiscovery(client), crypto, hostileBrowser)
                    .authorize("$origin/mcp", registration)
            }
            val mixedIssuerBrowser = object : McpOAuthBrowser {
                override suspend fun authorize(url: String, redirectUri: String): String =
                    "$redirectUri?code=stolen&state=${Url(url).parameters["state"]}&iss=https%3A%2F%2Fevil.example"
            }
            assertFailsWith<IllegalArgumentException> {
                McpOAuthClient(client, McpOAuthDiscovery(client), crypto, mixedIssuerBrowser)
                    .authorize("$origin/mcp", registration)
            }
            assertEquals(3, tokenRequests.get())
        } finally {
            client.close()
            server.stop(0)
        }
    }

}
