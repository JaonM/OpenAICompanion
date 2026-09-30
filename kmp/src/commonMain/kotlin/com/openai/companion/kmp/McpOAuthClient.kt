package com.openai.companion.kmp

import io.ktor.client.HttpClient
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.formUrlEncode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import io.ktor.utils.io.readRemaining
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.datetime.Clock

interface McpOAuthCrypto {
    fun randomBytes(count: Int): ByteArray
    fun sha256(bytes: ByteArray): ByteArray
}

/** The platform browser must return the complete redirect URL, or throw when cancelled. */
interface McpOAuthBrowser {
    suspend fun authorize(url: String, redirectUri: String): String
}

data class McpOAuthRegistration(
    val clientId: String,
    val redirectUri: String,
    val issuer: String? = null,
)

data class McpOAuthTokens(
    val issuer: String,
    val resource: String,
    val clientId: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Long?,
    val issuedAtEpochMillis: Long,
)

/** Public-client authorization-code + PKCE flow; credential persistence is a separate platform concern. */
@OptIn(ExperimentalEncodingApi::class)
class McpOAuthClient(
    private val httpClient: HttpClient,
    private val discovery: McpOAuthDiscovery,
    private val crypto: McpOAuthCrypto,
    private val browser: McpOAuthBrowser,
) {
    suspend fun register(
        endpoint: String,
        redirectUri: String,
        challenge: McpAuthorizationChallenge? = null,
    ): McpOAuthRegistration {
        val metadata = discovery.discover(endpoint, challenge)
        val registrationEndpoint = metadata.registrationEndpoint
            ?: throw McpProtocolException("OAuth server has no dynamic registration endpoint; enter a pre-registered client ID")
        val body = buildJsonObject {
            put("client_name", "OpenAICompanion")
            put("redirect_uris", buildJsonArray { add(redirectUri) })
            put("grant_types", buildJsonArray { add("authorization_code"); add("refresh_token") })
            put("response_types", buildJsonArray { add("code") })
            put("token_endpoint_auth_method", "none")
            put("application_type", "native")
            if (metadata.scopes.isNotEmpty()) put("scope", metadata.scopes.joinToString(" "))
        }.toString()
        val response = httpClient.preparePost(registrationEndpoint) {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            headers.append(HttpHeaders.Accept, ContentType.Application.Json.toString())
            setBody(body)
        }.execute { result ->
            require(result.call.request.url.toString() == registrationEndpoint) {
                "OAuth registration endpoint redirected unexpectedly"
            }
            require(result.status.value in 200..299) { "OAuth registration HTTP ${result.status.value}" }
            val bytes = result.bodyAsChannel().readRemaining(MAX_TOKEN_BYTES + 1).readByteArray()
            require(bytes.size <= MAX_TOKEN_BYTES) { "OAuth registration response is too large" }
            try { Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject }
            catch (_: Exception) { throw McpProtocolException("OAuth registration response is not valid JSON") }
                ?: throw McpProtocolException("OAuth registration response is not an object")
        }
        val clientId = response["client_id"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: throw McpProtocolException("OAuth registration returned no client ID")
        require(response["token_endpoint_auth_method"]?.jsonPrimitive?.content in setOf(null, "none")) {
            "OAuth registration requires unsupported client authentication"
        }
        return McpOAuthRegistration(clientId, redirectUri, metadata.issuer)
    }

    suspend fun authorize(
        endpoint: String,
        registration: McpOAuthRegistration,
        challenge: McpAuthorizationChallenge? = null,
    ): McpOAuthTokens {
        require(registration.clientId.isNotBlank()) { "OAuth client ID is required" }
        val metadata = discovery.discover(endpoint, challenge)
        require(registration.issuer == null || registration.issuer == metadata.issuer) {
            "OAuth client registration belongs to a different issuer"
        }
        require("S256" in metadata.codeChallengeMethodsSupported) {
            "OAuth authorization server does not advertise PKCE S256"
        }
        val redirect = Url(registration.redirectUri)
        require(redirect.fragment.isEmpty() && redirect.parameters.isEmpty()) {
            "OAuth redirect URI must not contain a query or fragment"
        }
        val verifier = encode(crypto.randomBytes(32))
        val state = encode(crypto.randomBytes(32))
        require(verifier.length in 43..128 && state.isNotBlank()) { "OAuth randomness source failed" }
        val codeChallenge = encode(crypto.sha256(verifier.encodeToByteArray()))
        val authorizationUrl = URLBuilder(metadata.authorizationEndpoint).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", registration.clientId)
            parameters.append("redirect_uri", registration.redirectUri)
            parameters.append("code_challenge", codeChallenge)
            parameters.append("code_challenge_method", "S256")
            parameters.append("state", state)
            parameters.append("resource", metadata.resource)
            if (metadata.scopes.isNotEmpty()) parameters.append("scope", metadata.scopes.joinToString(" "))
        }.buildString()
        val callback = Url(browser.authorize(authorizationUrl, registration.redirectUri))
        require(callback.protocol == redirect.protocol && callback.host == redirect.host &&
            callback.port == redirect.port && callback.encodedPath == redirect.encodedPath &&
            callback.fragment.isEmpty()) { "OAuth callback URI does not match registered redirect" }
        val issuer = callback.parameters["iss"]
        require(issuer == null || issuer == metadata.issuer) { "OAuth callback issuer mismatch" }
        require(callback.parameters["state"] == state) { "OAuth callback state mismatch" }
        callback.parameters["error"]?.let { throw McpProtocolException("OAuth authorization failed: $it") }
        val code = callback.parameters["code"]?.takeIf(String::isNotBlank)
            ?: throw McpProtocolException("OAuth callback has no authorization code")
        val tokenBody = Parameters.build {
            append("grant_type", "authorization_code")
            append("code", code)
            append("redirect_uri", registration.redirectUri)
            append("client_id", registration.clientId)
            append("code_verifier", verifier)
            append("resource", metadata.resource)
        }.formUrlEncode()
        val token = exchangeToken(metadata.tokenEndpoint, tokenBody)
        return parseTokens(token, metadata, registration.clientId)
    }

    suspend fun refresh(endpoint: String, current: McpOAuthTokens): McpOAuthTokens {
        val refreshToken = current.refreshToken?.takeIf(String::isNotBlank)
            ?: throw McpProtocolException("OAuth refresh token is unavailable")
        val metadata = discovery.discover(endpoint)
        require(metadata.issuer == current.issuer && metadata.resource == current.resource) {
            "OAuth issuer or resource changed; existing credentials cannot be reused"
        }
        val body = Parameters.build {
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken)
            append("client_id", current.clientId)
            append("resource", current.resource)
        }.formUrlEncode()
        val token = exchangeToken(metadata.tokenEndpoint, body)
        return parseTokens(token, metadata, current.clientId, refreshToken)
    }

    private suspend fun exchangeToken(endpoint: String, body: String): JsonObject =
        httpClient.preparePost(endpoint) {
            headers.append(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            headers.append(HttpHeaders.Accept, ContentType.Application.Json.toString())
            setBody(body)
        }.execute { response ->
            require(response.call.request.url.toString() == endpoint) {
                "OAuth token endpoint redirected unexpectedly"
            }
            require(response.status.value in 200..299) { "OAuth token HTTP ${response.status.value}" }
            val bytes = response.bodyAsChannel().readRemaining(MAX_TOKEN_BYTES + 1).readByteArray()
            require(bytes.size <= MAX_TOKEN_BYTES) { "OAuth token response is too large" }
            try { Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject }
            catch (_: Exception) { throw McpProtocolException("OAuth token response is not valid JSON") }
                ?: throw McpProtocolException("OAuth token response is not an object")
        }

    private fun parseTokens(
        token: JsonObject,
        metadata: McpOAuthMetadata,
        clientId: String,
        previousRefreshToken: String? = null,
    ): McpOAuthTokens {
        require(token["token_type"]?.jsonPrimitive?.content.equals("Bearer", ignoreCase = true)) {
            "OAuth token type is not Bearer"
        }
        val accessToken = token["access_token"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: throw McpProtocolException("OAuth access token is missing")
        return McpOAuthTokens(
            issuer = metadata.issuer,
            resource = metadata.resource,
            clientId = clientId,
            accessToken = accessToken,
            refreshToken = token["refresh_token"]?.jsonPrimitive?.content ?: previousRefreshToken,
            expiresInSeconds = token["expires_in"]?.jsonPrimitive?.content?.toLongOrNull(),
            issuedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
        )
    }

    private fun encode(bytes: ByteArray): String = Base64.UrlSafe.encode(bytes).trimEnd('=')

    private companion object {
        const val MAX_TOKEN_BYTES = 64 * 1024L
    }
}
