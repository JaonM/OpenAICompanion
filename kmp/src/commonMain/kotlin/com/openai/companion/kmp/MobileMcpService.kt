package com.openai.companion.kmp

import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface McpEndpointStore {
    fun load(): String
    fun save(endpoint: String)
    fun clear()
}

/** Shared mobile MCP connection, OAuth and status lifecycle. */
open class MobileMcpService(
    private val bindings: GeneratedHarnessBindings,
    private val approve: suspend (String, String) -> Boolean,
    private val requestInput: suspend (String, String, JsonObject) -> JsonObject,
    private val tokenStore: McpOAuthTokenStore,
    private val endpointStore: McpEndpointStore,
    private val httpClient: (requestTimeoutMillis: Long) -> HttpClient,
    private val crypto: McpOAuthCrypto,
    private val browser: McpOAuthBrowser,
    private val redirectUri: String,
    private val deferTokenLoadFailure: (Throwable) -> Boolean = { false },
) {
    private val manager = McpServerManager()
    private val tokenGate = Mutex()
    private val connectionGate = Mutex()
    private val authorizationGate = Mutex()
    private val serverId = "remote"
    private var authChallenge: McpAuthorizationChallenge? = null
    private var tokens: McpOAuthTokens? = null
    private var tokenEndpoint: String? = null
    private val mutableStatus = MutableStateFlow("未连接 MCP")
    val statusUpdates: StateFlow<String> = mutableStatus.asStateFlow()
    var endpoint: String = endpointStore.load()
        private set
    var status: String
        get() = mutableStatus.value
        private set(value) { mutableStatus.value = value }

    suspend fun start() {
        registerMcpProvider(
            bindings, manager,
            approve = { name, arguments ->
                val server = endpoint.takeIf(String::isNotBlank)?.let { Url(it).host } ?: "未配置服务"
                approve("$server · $name", arguments)
            },
            onToolCountChanged = { count ->
                if (status.startsWith("已连接")) status = "已连接 · $count 个工具"
            },
        )
        if (endpoint.isNotEmpty()) {
            try { connect(endpoint) }
            catch (error: Exception) { status = "连接失败：${error.message.orEmpty()}" }
        }
    }

    suspend fun connect(rawEndpoint: String) = connectionGate.withLock {
        connectLocked(rawEndpoint)
    }

    /** Serializes endpoint and credential state changes with refresh and OAuth completion. */
    private suspend fun connectLocked(rawEndpoint: String) {
        val normalized = rawEndpoint.trim()
        if (normalized.isEmpty()) {
            manager.detach(serverId)
            if (endpoint.isNotEmpty() && tokens != null) tokenStore.delete(endpoint)
            tokenGate.withLock {
                tokens = null
                tokenEndpoint = null
            }
            authChallenge = null
            endpoint = ""
            status = "未连接 MCP"
            endpointStore.clear()
            return
        }
        val url = try { Url(normalized) }
        catch (error: Exception) { throw IllegalArgumentException("MCP 地址格式无效", error) }
        require(url.protocol.name == "https" ||
            url.protocol.name == "http" && url.host in setOf("localhost", "127.0.0.1")) {
            "MCP 地址必须是 HTTPS；只有本机允许 HTTP"
        }
        require(url.user.isNullOrEmpty() && url.password.isNullOrEmpty()) {
            "MCP 地址不能包含账号或密码"
        }
        val previousChallenge = authChallenge
        var deferredLoadFailure: Throwable? = null
        val previousCredentials = tokenGate.withLock {
            val previous = tokenEndpoint to tokens
            if (normalized != tokenEndpoint) {
                tokens = try { tokenStore.load(normalized) }
                catch (error: Throwable) {
                    if (!deferTokenLoadFailure(error)) throw error
                    deferredLoadFailure = error
                    null
                }
                tokenEndpoint = normalized
                authChallenge = null
            }
            previous
        }
        val candidate = StreamableMcpConnection(httpClient(60_000), normalized,
            inputHandler = { method, params -> requestInput(url.host, method, params) }, inputCapabilities = buildJsonObject {
            put("elicitation", buildJsonObject { put("form", JsonObject(emptyMap())) })
        }, accessToken = { accessToken(normalized) },
            refreshAccessToken = { rejected -> refreshAfterUnauthorized(normalized, rejected) },
            onAuthorizationRequired = { error ->
                if (endpoint == normalized) {
                    authChallenge = error.authorizationChallenge
                    status = "需要 OAuth 授权 · ${url.host}"
                }
            })
        try {
            manager.attach(serverId, candidate)
        } catch (error: ToolExecutionException) {
            if (error.code != ToolExecutionErrorCode.PERMISSION_DENIED) {
                restoreCredentials(previousCredentials)
                authChallenge = previousChallenge
                throw error
            }
            manager.detach(serverId)
            if (deferredLoadFailure != null) {
                restoreCredentials(previousCredentials)
                authChallenge = previousChallenge
                throw deferredLoadFailure!!
            }
            authChallenge = error.authorizationChallenge
            endpoint = normalized
            endpointStore.save(normalized)
            status = "需要 OAuth 授权 · ${url.host}"
            return
        } catch (error: Throwable) {
            restoreCredentials(previousCredentials)
            authChallenge = previousChallenge
            throw error
        }
        authChallenge = null
        endpoint = normalized
        endpointStore.save(normalized)
        status = "已连接 · ${manager.tools().size} 个工具"
    }

    suspend fun authorize(clientId: String) = authorizationGate.withLock {
        val authorizationEndpoint = endpoint
        require(authorizationEndpoint.isNotBlank()) { "请先设置 MCP 地址" }
        if (status.startsWith("已连接") && authChallenge == null) return@withLock
        val challenge = authChallenge
        val priorRegistration = tokenGate.withLock {
            tokens?.takeIf { tokenEndpoint == authorizationEndpoint }?.let {
                McpOAuthRegistration(it.clientId, redirectUri, it.issuer)
            }
        }
        val authorizationClient = httpClient(30_000)
        try {
            val client = McpOAuthClient(
                authorizationClient,
                McpOAuthDiscovery(authorizationClient),
                crypto,
                browser,
            )
            val registration = if (clientId.isBlank()) {
                priorRegistration ?: client.register(authorizationEndpoint, redirectUri, challenge)
            } else McpOAuthRegistration(clientId.trim(), redirectUri)
            val granted = client.authorize(authorizationEndpoint, registration, challenge)
            connectionGate.withLock {
                tokenGate.withLock {
                    require(endpoint == authorizationEndpoint) { "MCP 地址在授权期间发生变化，请重新授权" }
                    tokenStore.save(authorizationEndpoint, granted)
                    tokens = granted
                    tokenEndpoint = authorizationEndpoint
                }
                connectLocked(authorizationEndpoint)
            }
        } finally {
            authorizationClient.close()
        }
    }

    suspend fun refresh() = connectionGate.withLock {
        if (endpoint.isEmpty()) return@withLock
        try {
            manager.refresh()
            status = "已连接 · ${manager.tools().size} 个工具"
        } catch (error: Exception) {
            status = "MCP 不可用：${error.message.orEmpty()}"
            manager.detach(serverId)
            try { connectLocked(endpoint) } catch (_: Exception) { }
        }
    }

    suspend fun tools(): List<McpToolDescriptor> = manager.tools()

    private suspend fun restoreCredentials(previous: Pair<String?, McpOAuthTokens?>) = tokenGate.withLock {
        tokenEndpoint = previous.first
        tokens = previous.second
    }

    private suspend fun accessToken(requestEndpoint: String): String? = tokenGate.withLock {
        val current = tokens?.takeIf { tokenEndpoint == requestEndpoint } ?: return@withLock null
        val lifetime = current.expiresInSeconds
        val elapsed = Clock.System.now().toEpochMilliseconds() - current.issuedAtEpochMillis
        if (lifetime == null || lifetime > 60 && elapsed < (lifetime - 60).coerceAtMost(Long.MAX_VALUE / 1_000) * 1_000) {
            return@withLock current.accessToken
        }
        if (current.refreshToken.isNullOrBlank()) return@withLock current.accessToken
        renew(requestEndpoint, current).accessToken
    }

    private suspend fun refreshAfterUnauthorized(requestEndpoint: String, rejected: String?): String? =
        tokenGate.withLock {
            val current = tokens?.takeIf { tokenEndpoint == requestEndpoint } ?: return@withLock null
            if (current.accessToken != rejected) return@withLock current.accessToken
            if (current.refreshToken.isNullOrBlank()) return@withLock null
            renew(requestEndpoint, current).accessToken
        }

    /** Call only while holding tokenGate so concurrent MCP requests rotate the token once. */
    private suspend fun renew(requestEndpoint: String, current: McpOAuthTokens): McpOAuthTokens {
        val http = oauthHttpClient()
        try {
            val renewed = McpOAuthClient(
                http, McpOAuthDiscovery(http), crypto, browser,
            ).refresh(requestEndpoint, current)
            tokenStore.save(requestEndpoint, renewed)
            tokens = renewed
            return renewed
        } finally {
            http.close()
        }
    }

    private fun oauthHttpClient() = httpClient(30_000)
}
