package com.openai.companion.kmp

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Url
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

data class McpOAuthMetadata(
    val resource: String,
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String?,
    val scopes: List<String>,
    val clientIdMetadataDocumentSupported: Boolean,
    val codeChallengeMethodsSupported: List<String>,
)

/** Discovers OAuth endpoints without handling credentials. Callers should disable HTTP redirects. */
class McpOAuthDiscovery(private val client: HttpClient) {
    suspend fun discover(endpoint: String, challenge: McpAuthorizationChallenge? = null): McpOAuthMetadata {
        val resourceUrl = safeOAuthUrl(endpoint)
        require(resourceUrl.parameters.isEmpty() && resourceUrl.fragment.isEmpty()) {
            "MCP resource URL must not contain a query or fragment"
        }
        val protectedResource = protectedResourceCandidates(resourceUrl, challenge).firstNotNullOfOrNull { candidate ->
            fetchJson(candidate)?.also { metadata ->
                val declared = metadata["resource"]?.jsonPrimitive?.content
                    ?: throw McpProtocolException("OAuth protected resource metadata has no resource")
                val declaredUrl = safeOAuthUrl(declared)
                val declaredPath = declaredUrl.encodedPath.trimEnd('/')
                require(declaredUrl.origin() == resourceUrl.origin() &&
                    (declaredPath.isEmpty() || declaredPath == resourceUrl.encodedPath.trimEnd('/') ||
                        resourceUrl.encodedPath.startsWith("$declaredPath/"))) {
                    "OAuth protected resource does not match MCP server"
                }
            }
        } ?: throw McpProtocolException("MCP protected resource metadata was not found")
        val resource = protectedResource["resource"]!!.jsonPrimitive.content
        val authorizationServers = protectedResource["authorization_servers"]?.jsonArray
            ?.map { it.jsonPrimitive.content }.orEmpty()
        require(authorizationServers.isNotEmpty()) { "MCP resource has no authorization server" }
        val issuer = authorizationServers.first()
        val issuerUrl = safeOAuthUrl(issuer)
        var metadata: JsonObject? = null
        for (candidate in authorizationServerCandidates(issuerUrl)) {
            metadata = fetchJson(candidate)
            if (metadata != null) break
        }
        metadata = metadata ?: throw McpProtocolException("MCP authorization server metadata was not found")
        require(metadata["issuer"]?.jsonPrimitive?.content == issuer) {
            "OAuth authorization server issuer mismatch"
        }
        val authorizationEndpoint = metadata["authorization_endpoint"]?.jsonPrimitive?.content
            ?: throw McpProtocolException("OAuth authorization endpoint is missing")
        val tokenEndpoint = metadata["token_endpoint"]?.jsonPrimitive?.content
            ?: throw McpProtocolException("OAuth token endpoint is missing")
        safeOAuthUrl(authorizationEndpoint)
        safeOAuthUrl(tokenEndpoint)
        val registrationEndpoint = metadata["registration_endpoint"]?.jsonPrimitive?.content
        registrationEndpoint?.let(::safeOAuthUrl)
        val challengeScopes = challenge?.scopes.orEmpty()
        val metadataScopes = protectedResource["scopes_supported"]?.jsonArray
            ?.map { it.jsonPrimitive.content }.orEmpty()
        return McpOAuthMetadata(
            resource = resource,
            issuer = issuer,
            authorizationEndpoint = authorizationEndpoint,
            tokenEndpoint = tokenEndpoint,
            registrationEndpoint = registrationEndpoint,
            scopes = challengeScopes.ifEmpty { metadataScopes },
            clientIdMetadataDocumentSupported =
                metadata["client_id_metadata_document_supported"]?.jsonPrimitive?.content == "true",
            codeChallengeMethodsSupported = metadata["code_challenge_methods_supported"]?.jsonArray
                ?.map { it.jsonPrimitive.content }.orEmpty(),
        )
    }

    private suspend fun fetchJson(url: String): JsonObject? = client.prepareGet(url).execute { response ->
        require(response.call.request.url.toString() == url) { "OAuth metadata redirected unexpectedly" }
        if (response.status.value == 404) return@execute null
        if (response.status.value !in 200..299) {
            throw McpProtocolException("OAuth metadata HTTP ${response.status.value}")
        }
        val bytes = response.bodyAsChannel().readRemaining(MAX_METADATA_BYTES + 1).readByteArray()
        require(bytes.size <= MAX_METADATA_BYTES) { "OAuth metadata is too large" }
        val body = bytes.decodeToString()
        try { Json.parseToJsonElement(body) as? JsonObject }
        catch (error: Exception) { throw McpProtocolException("OAuth metadata is not valid JSON") }
            ?: throw McpProtocolException("OAuth metadata is not a JSON object")
    }

    private fun protectedResourceCandidates(url: Url, challenge: McpAuthorizationChallenge?): List<String> {
        challenge?.resourceMetadataUrl?.let { return listOf(safeOAuthUrl(it).toString()) }
        val base = url.origin() + "/.well-known/oauth-protected-resource"
        val path = url.encodedPath.trimEnd('/')
        return listOf(base + path.takeUnless { it == "/" }.orEmpty(), base).distinct()
    }

    private fun authorizationServerCandidates(url: Url): List<String> {
        val path = url.encodedPath.trimEnd('/').takeUnless { it == "/" }.orEmpty()
        val origin = url.origin()
        return listOf(
            "$origin/.well-known/oauth-authorization-server$path",
            "$origin/.well-known/openid-configuration$path",
            "$origin$path/.well-known/openid-configuration",
        ).distinct()
    }

    private fun safeOAuthUrl(value: String): Url {
        val url = try { Url(value) } catch (_: Exception) { throw McpProtocolException("Invalid OAuth URL") }
        require(url.protocol.name == "https" ||
            url.protocol.name == "http" && url.host in setOf("localhost", "127.0.0.1")) {
            "OAuth URL must use HTTPS outside localhost"
        }
        require(url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.fragment.isEmpty()) {
            "OAuth URL must not contain credentials or a fragment"
        }
        return url
    }

    private fun Url.origin(): String {
        val authorityHost = if (':' in host && !host.startsWith('[')) "[$host]" else host
        val portSuffix = specifiedPort.takeIf { it > 0 && it != protocol.defaultPort }?.let { ":$it" }.orEmpty()
        return "${protocol.name}://$authorityHost$portSuffix"
    }

    private companion object {
        const val MAX_METADATA_BYTES = 64 * 1024L
    }
}
