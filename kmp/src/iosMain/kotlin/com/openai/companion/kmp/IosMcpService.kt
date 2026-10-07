package com.openai.companion.kmp

import com.openai.companion.kmp.device.createDeviceTools

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.JsonObject
import platform.Foundation.NSUserDefaults
import platform.Foundation.preferredLanguages

/** iOS adapters for the shared mobile MCP lifecycle. */
class IosMcpService(
    bindings: GeneratedHarnessBindings,
    approve: suspend (String, String) -> Boolean,
    requestInput: suspend (String, String, JsonObject) -> JsonObject,
) : MobileMcpService(
    deviceTools = { createDeviceTools(
        "ios", { platform.Foundation.NSLocale.preferredLanguages.firstOrNull() as? String ?: "und" },
        IosCalendarEventDataSource(),
        { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            platform.UIKit.UIApplication.sharedApplication.applicationState == platform.UIKit.UIApplicationState.UIApplicationStateActive
        } }, com.openai.companion.kmp.device.BindingsDeviceOperationJournal(bindings)) },
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
