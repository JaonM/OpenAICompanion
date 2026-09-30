package com.openai.companion.kmp

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareDelete
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Streamable HTTP MCP client for targets where the SDK has no published binary (notably iOS). */
class StreamableMcpConnection(
    private val httpClient: HttpClient,
    private val endpoint: String,
    private val inputHandler: (suspend (String, JsonObject) -> JsonObject)? = null,
    private val inputCapabilities: JsonObject = JsonObject(emptyMap()),
    private val accessToken: (suspend () -> String?)? = null,
    private val refreshAccessToken: (suspend (rejectedToken: String?) -> String?)? = null,
    private val onAuthorizationRequired: (suspend (ToolExecutionException) -> Unit)? = null,
) : McpServerConnection {
    private val gate = Mutex()
    private var version = MODERN_VERSION
    private var modern = true
    private var connected = false
    private var sessionId: String? = null
    private var nextId = 1
    private var legacyListenerStarted = false
    private var toolHeaders: Map<String, List<HeaderParameter>> = emptyMap()
    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val toolsChangedListener = MutableStateFlow<(suspend () -> Unit)?>(null)
    private val toolChangeSignals = Channel<Unit>(Channel.CONFLATED)

    init {
        notificationScope.launch {
            for (signal in toolChangeSignals) {
                var failures = 0
                while (currentCoroutineContext().isActive) {
                    try {
                        toolsChangedListener.value?.invoke()
                        break
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        failures++
                        delay((1_000L shl minOf(failures - 1, 5)).coerceAtMost(30_000L))
                    }
                }
            }
        }
    }

    override fun setToolsChangedListener(listener: suspend () -> Unit) {
        toolsChangedListener.value = listener
    }

    override suspend fun listTools(): List<McpToolDescriptor> = gate.withLock {
        connectIfNeeded()
        val result = mutableListOf<McpToolDescriptor>()
        val names = mutableSetOf<String>()
        val cursors = mutableSetOf<String>()
        val annotations = mutableMapOf<String, List<HeaderParameter>>()
        var cursor: String? = null
        var pages = 0
        do {
            if (++pages > MAX_TOOL_PAGES) {
                throw McpProtocolException("MCP tools/list exceeded $MAX_TOOL_PAGES pages")
            }
            val parameters = cursor?.let {
                require(cursors.add(it)) { "MCP tools/list repeated a pagination cursor" }
                buildJsonObject { put("cursor", it) }
            } ?: JsonObject(emptyMap())
            val page = rpc("tools/list", parameters).result()
            for (entry in page["tools"]?.jsonArray.orEmpty()) {
                val tool = entry.jsonObject
                val name = tool["name"]?.jsonPrimitive?.content.orEmpty()
                val schema = tool["inputSchema"] as? JsonObject
                    ?: throw McpProtocolException("MCP tool has no input schema")
                require(name.isNotBlank() && name != "load_more_tools") {
                    "MCP tool name is empty or reserved"
                }
                require(names.add(name)) { "Duplicate MCP tool name: $name" }
                if (names.size > MAX_TOOLS) {
                    throw McpProtocolException("MCP tools/list exceeded $MAX_TOOLS tools")
                }
                if (modern) {
                    // Invalid x-mcp-header annotations invalidate only that tool.
                    val headers = try { extractHeaders(schema) } catch (_: IllegalArgumentException) { continue }
                    annotations[name] = headers
                }
                result += McpToolDescriptor(
                    name = name,
                    description = tool["description"]?.jsonPrimitive?.content.orEmpty(),
                    inputSchemaJson = schema.toString(),
                )
            }
            cursor = page["nextCursor"]?.jsonPrimitive?.content
        } while (cursor != null)
        toolHeaders = annotations
        result
    }

    override suspend fun callTool(name: String, argumentsJson: String): McpCallResult = gate.withLock {
        connectIfNeeded()
        val arguments = try { Json.parseToJsonElement(argumentsJson).jsonObject }
        catch (error: Exception) {
            throw ToolExecutionException(ToolExecutionErrorCode.INVALID_ARGUMENTS, "MCP arguments must be a JSON object", error)
        }
        val originalParameters = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }
        val headers = if (modern) parameterHeaders(name, arguments) else emptyMap()
        var parameters = originalParameters
        repeat(MAX_INPUT_ROUNDS + 1) { round ->
            val result = rpc("tools/call", parameters, headers).result()
            val resultType = result["resultType"]?.jsonPrimitive?.content
            if (resultType == null || resultType == "complete") {
                if (modern && result["content"] !is JsonArray) {
                    throw McpProtocolException("MCP tools/call complete result has no content array")
                }
                val errorFlag = result["isError"]
                val isError = when {
                    errorFlag == null -> false
                    errorFlag is JsonPrimitive && !errorFlag.isString &&
                        errorFlag.content in setOf("true", "false") -> errorFlag.content == "true"
                    else -> throw McpProtocolException("MCP tools/call isError must be a boolean")
                }
                return@withLock McpCallResult(
                    result.toString(), isError,
                )
            }
            if (resultType != "input_required" || !modern) {
                throw McpProtocolException("MCP tool requires unsupported interaction: $resultType")
            }
            if (round == MAX_INPUT_ROUNDS) {
                throw McpProtocolException("MCP input_required exceeded $MAX_INPUT_ROUNDS rounds")
            }
            val handler = inputHandler
                ?: throw McpProtocolException("MCP tool requires input, but no client input handler is configured")
            val requests = result["inputRequests"] as? JsonObject
                ?: throw McpProtocolException("MCP input_required has no inputRequests object")
            if (requests.size > MAX_INPUT_REQUESTS) {
                throw McpProtocolException("MCP input_required contains too many requests")
            }
            val responses = buildJsonObject {
                for ((key, value) in requests) {
                    val request = value as? JsonObject
                        ?: throw McpProtocolException("MCP input request is not an object")
                    val method = request["method"]?.jsonPrimitive?.content
                        ?: throw McpProtocolException("MCP input request has no method")
                    val params = request["params"] as? JsonObject
                        ?: throw McpProtocolException("MCP input request has no params object")
                    if (inputCapabilities[method.substringBefore('/')] == null) {
                        throw McpProtocolException("MCP input request was not advertised: $method")
                    }
                    put(key, handler(method, params))
                }
            }
            parameters = buildJsonObject {
                originalParameters.forEach { (key, value) -> put(key, value) }
                put("inputResponses", responses)
                result["requestState"]?.let { state ->
                    if (state !is JsonPrimitive || !state.isString) {
                        throw McpProtocolException("MCP requestState must be an opaque string")
                    }
                    put("requestState", state)
                }
            }
        }
        throw McpProtocolException("MCP input_required did not complete")
    }

    override suspend fun close() = gate.withLock {
        notificationScope.cancel()
        val closingSession = if (modern) null else sessionId
        if (closingSession != null) {
            withTimeoutOrNull(2_000) {
                try {
                    val bearer = bearerToken()
                    httpClient.prepareDelete(endpoint) {
                        bearer?.let { headers.append(HttpHeaders.Authorization, "Bearer $it") }
                        headers.append("MCP-Protocol-Version", version)
                        headers.append("Mcp-Session-Id", closingSession)
                    }.execute { _ -> Unit }
                } catch (_: Exception) {
                    // Local shutdown must still finish if the server rejects DELETE.
                }
            }
        }
        httpClient.close()
        connected = false
        sessionId = null
        toolHeaders = emptyMap()
    }

    private suspend fun connectIfNeeded() {
        if (connected) return
        if (!modern) {
            initializeLegacy()
            connected = true
            startLegacyListener()
            return
        }
        try {
            val discovery = rpc("server/discover", JsonObject(emptyMap())).result()
            val versions = discovery["supportedVersions"]?.jsonArray
                ?.map { it.jsonPrimitive.content }.orEmpty()
            require(MODERN_VERSION in versions) { "MCP server does not support $MODERN_VERSION" }
            connected = true
            val tools = (discovery["capabilities"] as? JsonObject)?.get("tools") as? JsonObject
            if (tools?.get("listChanged")?.jsonPrimitive?.content == "true") {
                notificationScope.launch { listenForToolChanges() }
            }
            return
        } catch (_: LegacyFallback) {
            modern = false
            version = LEGACY_VERSION
        }
        initializeLegacy()
        connected = true
        startLegacyListener()
    }

    private fun startLegacyListener() {
        if (legacyListenerStarted) return
        legacyListenerStarted = true
        notificationScope.launch { listenForLegacyToolChanges() }
    }

    private suspend fun initializeLegacy() {
        val initialized = rpc(
            "initialize",
            buildJsonObject {
                put("protocolVersion", version)
                put("capabilities", buildJsonObject { put("tools", JsonObject(emptyMap())) })
                put("clientInfo", clientInfo())
            },
            initializing = true,
        ).result()
        version = initialized["protocolVersion"]?.jsonPrimitive?.content
            ?: throw McpProtocolException("MCP initialize response has no protocol version")
        post(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        }, initializing = false) { response ->
            if (response.status.value == 401 || response.status.value == 403) {
                throw reportAuthorizationFailure(response)
            }
            if (response.status.value !in 200..299) {
                throw McpProtocolException("MCP initialized notification failed: HTTP ${response.status.value}")
            }
            Unit
        }
    }

    private suspend fun rpc(
        method: String,
        params: JsonObject,
        extraHeaders: Map<String, String> = emptyMap(),
        initializing: Boolean = false,
        retryExpiredSession: Boolean = true,
    ): JsonObject {
        val id = nextId++
        val effectiveParams = if (modern) JsonObject(params + ("_meta" to modernMetadata())) else params
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", effectiveParams)
        }
        try {
            return post(message, extraHeaders, initializing) { response ->
                if (response.status.value == 401 || response.status.value == 403) {
                    throw reportAuthorizationFailure(response)
                }
                if (!modern && !initializing && sessionId != null && response.status.value == 404) {
                    throw LegacySessionExpired()
                }
                if (modern && method == "server/discover" && response.status.value in 400..499) {
                    val error = parseObjectOrNull(readRpcBody(response))?.get("error") as? JsonObject
                    if (error?.get("code")?.jsonPrimitive?.content == "-32022") {
                        val supported = (error["data"] as? JsonObject)?.get("supported")?.jsonArray
                            ?.map { it.jsonPrimitive.content }.orEmpty()
                        if (LEGACY_VERSION in supported || "2025-06-18" in supported) throw LegacyFallback()
                        throw McpProtocolException("MCP server does not support $MODERN_VERSION")
                    }
                    throw LegacyFallback()
                }
                if (response.status.value !in 200..299) {
                    throw McpProtocolException("MCP HTTP ${response.status.value}: ${readRpcBody(response).take(250)}")
                }
                val reply = if (response.headers[HttpHeaders.ContentType].orEmpty()
                    .startsWith("text/event-stream", ignoreCase = true)) {
                    readSseReply(response, id)
                } else {
                    parseObjectOrNull(readRpcBody(response))
                        ?.takeIf { it["id"]?.jsonPrimitive?.content == id.toString() }
                        ?: throw McpProtocolException("MCP returned no matching JSON-RPC response")
                }
                val error = reply["error"] as? JsonObject
                if (error != null) {
                    if (modern && method == "server/discover" &&
                        error["code"]?.jsonPrimitive?.content == "-32601") throw LegacyFallback()
                    throw McpProtocolException(error["message"]?.jsonPrimitive?.content ?: "MCP JSON-RPC error")
                }
                reply
            }
        } catch (_: LegacySessionExpired) {
            if (!retryExpiredSession) throw McpProtocolException("MCP session expired again after reinitialization")
            connected = false
            sessionId = null
            version = LEGACY_VERSION
            initializeLegacy()
            connected = true
            return rpc(method, params, extraHeaders, initializing = false, retryExpiredSession = false)
        }
    }

    private fun authorizationFailure(response: HttpResponse): ToolExecutionException {
        val challenge = parseMcpAuthorizationChallenge(
            response.headers.getAll(HttpHeaders.WWWAuthenticate).orEmpty(),
        )
        val message = if (response.status.value == 403 && challenge?.error == "insufficient_scope") {
            "MCP server requires additional scopes: ${challenge.scopes.joinToString(" ")}".trim()
        } else "MCP server requires authorization"
        return ToolExecutionException(
            ToolExecutionErrorCode.PERMISSION_DENIED,
            message,
            authorizationChallenge = challenge,
        )
    }

    private suspend fun reportAuthorizationFailure(response: HttpResponse): ToolExecutionException =
        authorizationFailure(response).also { onAuthorizationRequired?.invoke(it) }

    private suspend fun <T> post(
        message: JsonObject,
        extraHeaders: Map<String, String> = emptyMap(),
        initializing: Boolean,
        consume: suspend (HttpResponse) -> T,
    ): T {
        val method = message["method"]?.jsonPrimitive?.content.orEmpty()
        var bearer = bearerToken()
        for (attempt in 0..1) {
            try {
                return httpClient.preparePost(endpoint) {
                    bearer?.let { headers.append(HttpHeaders.Authorization, "Bearer $it") }
                    headers.append(HttpHeaders.ContentType, "application/json")
                    headers.append(HttpHeaders.Accept, "application/json, text/event-stream")
                    if (modern) {
                        headers.append("MCP-Protocol-Version", version)
                        headers.append("Mcp-Method", method)
                        if (method == "tools/call") {
                            val name = message["params"]?.jsonObject?.get("name")?.jsonPrimitive?.content.orEmpty()
                            headers.append("Mcp-Name", encodeHeaderValue(name))
                        }
                    } else if (!initializing) {
                        headers.append("MCP-Protocol-Version", version)
                        sessionId?.let { headers.append("Mcp-Session-Id", it) }
                    }
                    extraHeaders.forEach { (name, value) -> headers.append(name, value) }
                    setBody(message.toString())
                }.execute { response ->
                    if (response.status.value == 401 && attempt == 0 && refreshAccessToken != null) {
                        throw RetryUnauthorized(authorizationFailure(response))
                    }
                    if (initializing && !modern) sessionId = response.headers["Mcp-Session-Id"]
                    consume(response)
                }
            } catch (retry: RetryUnauthorized) {
                bearer = refreshRejectedToken(bearer, retry.failure)
            }
        }
        error("MCP authorization retry exhausted")
    }

    private class RetryUnauthorized(val failure: ToolExecutionException) : Exception()

    private suspend fun refreshRejectedToken(rejected: String?, failure: ToolExecutionException): String {
        val renewed = try { refreshAccessToken?.invoke(rejected) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { null }
        if (renewed.isNullOrBlank() || renewed == rejected) {
            onAuthorizationRequired?.invoke(failure)
            throw failure
        }
        return renewed
    }

    private fun JsonObject.result(): JsonObject = this["result"] as? JsonObject
        ?: throw McpProtocolException("MCP response has no result object")

    private fun parseObjectOrNull(text: String): JsonObject? =
        try { Json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }

    private suspend fun readRpcBody(response: HttpResponse): String {
        val bytes = response.bodyAsChannel().readRemaining(MAX_RPC_BYTES.toLong() + 1).readByteArray()
        if (bytes.size > MAX_RPC_BYTES) {
            throw McpProtocolException("MCP JSON-RPC response exceeds $MAX_RPC_BYTES bytes")
        }
        return bytes.decodeToString()
    }

    private suspend fun readSseReply(response: HttpResponse, id: Int): JsonObject {
        val pending = PendingReply()
        consumeReplyStream(response, id, pending)
        pending.reply?.let { return it }
        if (modern || pending.cursor == null) {
            throw McpProtocolException("MCP returned no matching JSON-RPC response")
        }
        return resumeLegacyReply(id, pending)
    }

    private suspend fun consumeReplyStream(response: HttpResponse, id: Int, pending: PendingReply) {
        try {
            readSse(
                response,
                onMessage = { payload ->
                    dispatchToolChange(payload)
                    if (payload["id"]?.jsonPrimitive?.content == id.toString()) {
                        pending.reply = payload
                        true
                    } else false
                },
                onEventId = { pending.cursor = it.takeIf(String::isNotEmpty) },
                onRetry = { pending.retryMs = it },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: McpProtocolException) {
            throw error
        } catch (error: Exception) {
            if (modern || pending.cursor == null) throw error
            // A legacy server can close a resumable stream before its response.
        }
    }

    private suspend fun resumeLegacyReply(id: Int, pending: PendingReply): JsonObject {
        val resumed = withTimeoutOrNull(120_000) {
            var consecutiveFailures = 0
            while (pending.reply == null) {
                val cursor = pending.cursor
                    ?: throw McpProtocolException("MCP response stream ended without a resumable event ID")
                val backoff = if (consecutiveFailures == 0) 0L else
                    (1_000L shl minOf(consecutiveFailures - 1, 5)).coerceAtMost(30_000L)
                delay(maxOf(pending.retryMs, backoff))
                try {
                    var bearer = bearerToken()
                    for (attempt in 0..1) {
                        try {
                            httpClient.prepareGet(endpoint) {
                                bearer?.let { headers.append(HttpHeaders.Authorization, "Bearer $it") }
                                headers.append(HttpHeaders.Accept, "text/event-stream")
                                headers.append("MCP-Protocol-Version", version)
                                sessionId?.let { headers.append("Mcp-Session-Id", it) }
                                headers.append("Last-Event-ID", cursor)
                            }.execute { response ->
                                if (response.status.value == 404 && sessionId != null) {
                                    connected = false
                                    sessionId = null
                                    throw McpProtocolException("MCP session expired while resuming a request")
                                }
                                if (response.status.value == 401 && attempt == 0 && refreshAccessToken != null) {
                                    throw RetryUnauthorized(authorizationFailure(response))
                                }
                                if (response.status.value == 401 || response.status.value == 403) {
                                    throw reportAuthorizationFailure(response)
                                }
                                if (response.status.value == 408 || response.status.value == 429 ||
                                    response.status.value in 500..599) throw McpResumeRetryable()
                                if (response.status.value !in 200..299 ||
                                    !response.headers[HttpHeaders.ContentType].orEmpty()
                                        .startsWith("text/event-stream", ignoreCase = true)) {
                                    throw McpProtocolException("MCP server cannot resume response stream: HTTP ${response.status.value}")
                                }
                                consumeReplyStream(response, id, pending)
                            }
                            break
                        } catch (retry: RetryUnauthorized) {
                            bearer = refreshRejectedToken(bearer, retry.failure)
                        }
                    }
                    consecutiveFailures = 0
                } catch (error: CancellationException) {
                    throw error
                } catch (error: McpProtocolException) {
                    throw error
                } catch (error: ToolExecutionException) {
                    throw error
                } catch (_: Exception) {
                    consecutiveFailures++
                }
            }
            pending.reply
        }
        return resumed ?: throw ToolExecutionException(ToolExecutionErrorCode.TIMEOUT,
            "Timed out waiting for a resumed MCP response")
    }

    private suspend fun listenForToolChanges() {
        var sequence = 0
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive) {
            val id = "tools-listen-${++sequence}"
            val request = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", "subscriptions/listen")
                put("params", buildJsonObject {
                    put("notifications", buildJsonObject { put("toolsListChanged", true) })
                    put("_meta", modernMetadata())
                })
            }
            try {
                val accepted = post(request, initializing = false) { response ->
                    if (response.status.value == 401 || response.status.value == 403) {
                        throw reportAuthorizationFailure(response)
                    }
                    if (response.status.value !in 200..299 ||
                        !response.headers[HttpHeaders.ContentType].orEmpty()
                            .startsWith("text/event-stream", ignoreCase = true)) return@post false
                    var acknowledged = false
                    var honored = false
                    readSse(response, onMessage = { payload ->
                        when (payload["method"]?.jsonPrimitive?.content) {
                            "notifications/subscriptions/acknowledged" -> {
                                val params = payload["params"] as? JsonObject
                                if (subscriptionId(payload) != id) return@readSse true
                                acknowledged = true
                                val notifications = params?.get("notifications") as? JsonObject
                                honored = notifications?.get("toolsListChanged")
                                    ?.jsonPrimitive?.content == "true"
                                if (honored) dispatchToolChange(null)
                                !honored
                            }
                            "notifications/tools/list_changed" -> {
                                if (acknowledged && honored && subscriptionId(payload) == id) {
                                    dispatchToolChange(payload)
                                }
                                false
                            }
                            else -> payload["id"]?.jsonPrimitive?.content == id
                        }
                    })
                    acknowledged && honored
                }
                if (!accepted) return
                consecutiveFailures = 0
            } catch (error: CancellationException) {
                throw error
            } catch (error: ToolExecutionException) {
                if (error.code == ToolExecutionErrorCode.PERMISSION_DENIED) return
                consecutiveFailures++
            } catch (_: Exception) {
                // A dropped stream is re-established; the ack triggers a fresh list read.
                consecutiveFailures++
            }
            delay((1_000L shl minOf((consecutiveFailures - 1).coerceAtLeast(0), 5))
                .coerceAtMost(30_000L))
        }
    }

    private suspend fun listenForLegacyToolChanges() {
        var lastEventId: String? = null
        var retryDelayMs = 1_000L
        while (currentCoroutineContext().isActive) {
            val requestedSessionId = sessionId
            try {
                var bearer = bearerToken()
                var outcome: LegacyListenOutcome? = null
                for (attempt in 0..1) {
                    try {
                        outcome = httpClient.prepareGet(endpoint) {
                            bearer?.let { headers.append(HttpHeaders.Authorization, "Bearer $it") }
                            headers.append(HttpHeaders.Accept, "text/event-stream")
                            headers.append("MCP-Protocol-Version", version)
                            requestedSessionId?.let { headers.append("Mcp-Session-Id", it) }
                            lastEventId?.let { headers.append("Last-Event-ID", it) }
                        }.execute { response ->
                            when {
                                response.status.value == 405 -> LegacyListenOutcome.UNSUPPORTED
                                response.status.value == 404 && requestedSessionId != null ->
                                    LegacyListenOutcome.SESSION_EXPIRED
                                response.status.value == 401 && attempt == 0 && refreshAccessToken != null ->
                                    throw RetryUnauthorized(authorizationFailure(response))
                                response.status.value == 401 || response.status.value == 403 ->
                                    throw reportAuthorizationFailure(response)
                                response.status.value !in 200..299 ->
                                    throw McpProtocolException("MCP GET HTTP ${response.status.value}")
                                !response.headers[HttpHeaders.ContentType].orEmpty()
                                    .startsWith("text/event-stream", ignoreCase = true) ->
                                    throw McpProtocolException("MCP GET did not return SSE")
                                else -> {
                                    readSse(
                                        response,
                                        onMessage = { payload -> dispatchToolChange(payload); false },
                                        onEventId = { lastEventId = it },
                                        onRetry = { retryDelayMs = it },
                                    )
                                    LegacyListenOutcome.STREAM_ENDED
                                }
                            }
                        }
                        break
                    } catch (retry: RetryUnauthorized) {
                        bearer = refreshRejectedToken(bearer, retry.failure)
                    }
                }
                when (outcome) {
                    LegacyListenOutcome.UNSUPPORTED -> return
                    LegacyListenOutcome.SESSION_EXPIRED -> {
                        gate.withLock {
                            if (sessionId == requestedSessionId) {
                                connected = false
                                sessionId = null
                                version = LEGACY_VERSION
                                connectIfNeeded()
                            }
                        }
                        lastEventId = null
                        continue
                    }
                    LegacyListenOutcome.STREAM_ENDED -> Unit
                    null -> error("MCP subscription retry exhausted")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ToolExecutionException) {
                if (error.code == ToolExecutionErrorCode.PERMISSION_DENIED) return
            } catch (_: Exception) {
                // A disconnected GET stream is retried with its last event cursor.
            }
            delay(retryDelayMs)
        }
    }

    private fun dispatchToolChange(payload: JsonObject?) {
        if (payload == null || payload["method"]?.jsonPrimitive?.content == "notifications/tools/list_changed") {
            toolChangeSignals.trySend(Unit)
        }
    }

    private fun subscriptionId(payload: JsonObject): String? =
        ((payload["params"] as? JsonObject)?.get("_meta") as? JsonObject)
            ?.get("io.modelcontextprotocol/subscriptionId")?.jsonPrimitive?.content

    private suspend fun readSse(
        response: HttpResponse,
        onMessage: suspend (JsonObject) -> Boolean,
        onEventId: (String) -> Unit = {},
        onRetry: (Long) -> Unit = {},
    ) {
        val channel = response.bodyAsChannel()
        val data = StringBuilder()
        var eventId: String? = null
        var retryMs: Long? = null
        suspend fun flush(): Boolean {
            eventId?.let(onEventId)
            retryMs?.let(onRetry)
            eventId = null
            retryMs = null
            if (data.isEmpty()) return false
            val payload = parseObjectOrNull(data.toString())
                ?: throw McpProtocolException("MCP SSE event contains invalid JSON")
            data.clear()
            return onMessage(payload)
        }
        try {
            while (true) {
                val line = channel.readUTF8Line(2 * 1024 * 1024) ?: break
                if (line.isEmpty()) {
                    if (flush()) return
                } else if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                    if (data.length > 4 * 1024 * 1024) {
                        throw McpProtocolException("MCP SSE event is too large")
                    }
                } else if (line.startsWith("id:")) {
                    val value = line.removePrefix("id:").removePrefix(" ")
                    if ('\u0000' !in value) eventId = value
                } else if (line.startsWith("retry:")) {
                    retryMs = line.removePrefix("retry:").trim().toLongOrNull()
                        ?.takeIf { it >= 0 }
                }
            }
            flush()
        } finally {
            channel.cancel(null)
        }
    }

    private suspend fun bearerToken(): String? = accessToken?.invoke()?.also { token ->
        require(token.isNotEmpty() && token.all { character ->
            character.isLetterOrDigit() && character.code < 128 || character in "-._~+/="
        }) { "Invalid MCP bearer token" }
    }

    private fun modernMetadata() = buildJsonObject {
        put("io.modelcontextprotocol/protocolVersion", version)
        put("io.modelcontextprotocol/clientInfo", clientInfo())
        put("io.modelcontextprotocol/clientCapabilities", buildJsonObject {
            put("tools", JsonObject(emptyMap()))
            inputCapabilities.forEach { (key, value) -> put(key, value) }
        })
    }

    private fun parameterHeaders(name: String, arguments: JsonObject): Map<String, String> = buildMap {
        for (field in toolHeaders[name].orEmpty()) {
            var value: JsonElement? = arguments
            for (part in field.path) value = (value as? JsonObject)?.get(part)
            if (value == null || value == JsonNull) continue
            val primitive = value as? JsonPrimitive
                ?: throw ToolExecutionException(ToolExecutionErrorCode.INVALID_ARGUMENTS, "MCP header parameter is not primitive")
            val string = when (field.type) {
                "string" -> primitive.content.takeIf { primitive.isString }
                "boolean" -> primitive.content.takeIf { it == "true" || it == "false" }
                "integer" -> primitive.content.takeIf { it.toLongOrNull()?.toString() == it &&
                    (it.toLongOrNull() ?: Long.MAX_VALUE) in -9007199254740991L..9007199254740991L }
                else -> null
            } ?: throw ToolExecutionException(ToolExecutionErrorCode.INVALID_ARGUMENTS, "MCP header parameter type mismatch")
            put("Mcp-Param-${field.name}", encodeHeaderValue(string))
        }
    }

    private fun extractHeaders(schema: JsonObject): List<HeaderParameter> {
        val result = mutableListOf<HeaderParameter>()
        val used = mutableSetOf<String>()
        fun walk(value: JsonElement, path: List<String>, reachable: Boolean, property: Boolean) {
            when (value) {
                is JsonObject -> {
                    val annotation = value["x-mcp-header"]
                    if (annotation != null) {
                        val name = (annotation as? JsonPrimitive)?.content.orEmpty()
                        val type = value["type"]?.jsonPrimitive?.content.orEmpty()
                        require(reachable && property && path.isNotEmpty() &&
                            name.isNotEmpty() && name.all { it.isLetterOrDigit() && it.code < 128 ||
                                it in "!#$%&'*+-.^_`|~" } &&
                            type in setOf("string", "integer", "boolean") && used.add(name.lowercase())) {
                            "Invalid x-mcp-header annotation"
                        }
                        result += HeaderParameter(name, path, type)
                    }
                    for ((key, child) in value) {
                        if (key == "properties" && reachable && child is JsonObject) {
                            for ((childName, definition) in child) {
                                walk(definition, path + childName, true, true)
                            }
                        } else if (key != "x-mcp-header") walk(child, path, false, false)
                    }
                }
                is kotlinx.serialization.json.JsonArray -> value.forEach { walk(it, path, false, false) }
                else -> Unit
            }
        }
        walk(schema, emptyList(), true, false)
        return result
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encodeHeaderValue(value: String): String {
        val safe = value.isNotEmpty() && value.first() != ' ' && value.last() != ' ' &&
            value.all { it.code in 32..126 } &&
            !(value.startsWith("=?base64?") && value.endsWith("?="))
        return if (safe) value else "=?base64?${Base64.encode(value.encodeToByteArray())}?="
    }

    private fun clientInfo() = buildJsonObject {
        put("name", "OpenAICompanion")
        put("version", "0.1.0")
    }

    private data class HeaderParameter(val name: String, val path: List<String>, val type: String)
    private class PendingReply(
        var reply: JsonObject? = null,
        var cursor: String? = null,
        var retryMs: Long = 1_000L,
    )
    private class LegacyFallback : Exception()
    private class LegacySessionExpired : Exception()
    private class McpResumeRetryable : Exception()
    private enum class LegacyListenOutcome { STREAM_ENDED, UNSUPPORTED, SESSION_EXPIRED }

    companion object {
        private const val MODERN_VERSION = "2026-07-28"
        private const val MAX_INPUT_ROUNDS = 10
        private const val MAX_INPUT_REQUESTS = 8
        private const val MAX_TOOL_PAGES = 32
        private const val MAX_TOOLS = 512
        private const val MAX_RPC_BYTES = 4 * 1024 * 1024
        private const val LEGACY_VERSION = "2025-11-25"
    }
}

class McpProtocolException(message: String) : Exception(message)
