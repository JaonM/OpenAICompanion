package com.openai.companion.kmp

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.JsonObject
import platform.Foundation.NSUserDefaults

/** iOS adapters for the shared mobile MCP lifecycle. */
class IosMcpService(
    bindings: GeneratedHarnessBindings,
    approve: suspend (String, String) -> Boolean,
    requestInput: suspend (String, String, JsonObject) -> JsonObject,
) : MobileMcpService(
    bindings = bindings,
    approve = approve,
    requestInput = requestInput,
    tokenStore = IosMcpOAuthTokenStore(),
    endpointStore = object : McpEndpointStore {
        private val defaults = NSUserDefaults.standardUserDefaults
        override fun load(): String = defaults.stringForKey("mcpEndpoint") ?: ""
        override fun save(endpoint: String) { defaults.setObject(endpoint, forKey = "mcpEndpoint") }
        override fun clear() { defaults.removeObjectForKey("mcpEndpoint") }
    },
    httpClient = { timeout ->
        HttpClient(Darwin) {
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = timeout
                socketTimeoutMillis = timeout
            }
        }
    },
    crypto = IosMcpOAuthCrypto(),
    browser = IosMcpOAuthBrowser(),
    redirectUri = "openai-companion://oauth/callback",
    deferTokenLoadFailure = { it is IosKeychainUnavailableException },
)
