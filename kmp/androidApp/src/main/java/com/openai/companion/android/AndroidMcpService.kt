package com.openai.companion.android

import android.content.Context
import com.openai.companion.kmp.GeneratedHarnessBindings
import com.openai.companion.kmp.McpEndpointStore
import com.openai.companion.kmp.McpOAuthBrowser
import com.openai.companion.kmp.MobileMcpService
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.JsonObject

/** Android adapters for the shared mobile MCP lifecycle. */
class AndroidMcpService(
    context: Context,
    browser: McpOAuthBrowser,
    bindings: GeneratedHarnessBindings,
    approve: suspend (String, String) -> Boolean,
    requestInput: suspend (String, String, JsonObject) -> JsonObject,
) : MobileMcpService(
    bindings = bindings,
    approve = approve,
    requestInput = requestInput,
    tokenStore = AndroidMcpOAuthTokenStore(context),
    endpointStore = object : McpEndpointStore {
        private val preferences = context.getSharedPreferences("companion_mcp", Context.MODE_PRIVATE)
        override fun load(): String = preferences.getString("mcpEndpoint", null) ?: ""
        override fun save(endpoint: String) { preferences.edit().putString("mcpEndpoint", endpoint).apply() }
        override fun clear() { preferences.edit().remove("mcpEndpoint").apply() }
    },
    httpClient = { timeout ->
        HttpClient(OkHttp) {
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = timeout
                socketTimeoutMillis = timeout
            }
        }
    },
    crypto = AndroidMcpOAuthCrypto(),
    browser = browser,
    redirectUri = AndroidMcpOAuthBrowser.REDIRECT_URI,
)
