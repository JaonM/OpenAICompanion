package com.openai.companion.kmp

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentType
import io.ktor.http.ContentType
import io.ktor.http.Url
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Host settings are non-secret; tokens use the platform's existing secure A2A token store. */
class CrossDeviceService(
    private val http: HttpClient,
    private val a2a: A2aClient,
    private val tokens: A2aTokenStore,
    load: () -> String,
    private val save: (String) -> Unit,
    private val platform: String,
    private val foreground: suspend () -> Boolean,
    private val tools: suspend () -> List<McpToolDescriptor>,
    private val execute: suspend (String) -> String,
    private val answerQuestion: suspend (String) -> String = { "{\"answer\":null}" },
) : McpServerConnection {
    private val gate = Mutex()
    private val tickGate = Mutex()
    private var executing = false
    private var settings = runCatching { Json.parseToJsonElement(load()).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    private val mutableStatus = MutableStateFlow("未配置跨设备执行")
    val status = mutableStatus.asStateFlow()
    val endpoint: String get() = settings.string("endpoint")
    val deviceName: String get() = settings.string("name").ifBlank { platform }
    val acceptsTasks: Boolean get() = settings["acceptTasks"]?.jsonPrimitive?.booleanOrNull ?: false
    private var deviceId = ""
    private var started = false

    private fun persist(value: JsonObject) {
        settings = value
        save(settings.toString())
    }

    /** Clear only after acknowledgement; an upload retry never re-executes the task. */
    private suspend fun uploadPending() {
        val pending = settings["pending"] as? JsonObject ?: return
        request("/v1/devices/finish", pending)
        persist(JsonObject(settings - "pending"))
    }

    private fun agentCardPath(id: String) = "/agents/$id/card"
    private fun agentCardUrl(id: String) = endpoint + agentCardPath(id)

    suspend fun configure(rawEndpoint: String, token: String, name: String, acceptTasks: Boolean) = gate.withLock {
        val base = rawEndpoint.trim().trimEnd('/')
        require(!executing && settings["pending"] == null) { "有结果尚未回传，请先恢复原服务连接" }
        if (base.isNotBlank()) {
            validateGateway(base)
            require(name.isNotBlank() && name.length <= 100) { "请输入设备名称" }
            if (token.isNotBlank()) tokens.save(secretKey(base), token.trim())
            require(!tokens.load(secretKey(base)).isNullOrBlank()) { "新服务需要设备令牌" }
        }
        val answered = settings["answered"].takeIf { base == endpoint }
        persist(buildJsonObject {
            put("endpoint", base); put("name", name.trim()); put("acceptTasks", acceptTasks)
            answered?.let { put("answered", it) }
        })
        deviceId = ""
        mutableStatus.value = if (base.isBlank()) "已停用跨设备执行" else "已保存，连接中"
    }

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            while (isActive) {
                try { tick(); answerPendingQuestion() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { mutableStatus.value = "跨设备连接失败：${error.message}" }
                delay(15_000)
            }
        }
    }

    private suspend fun request(path: String, payload: JsonObject? = null): JsonObject {
        val token = tokens.load(secretKey(endpoint)) ?: error("缺少设备令牌")
        val response = if (payload == null) http.get(endpoint + path) { header("Authorization", "Bearer $token") }
        else http.post(endpoint + path) {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        require(response.status.value in 200..299) { "设备服务返回 HTTP ${response.status.value}" }
        val body = response.bodyAsText()
        require(body.length <= 1_000_000) { "设备服务响应过大" }
        return Json.parseToJsonElement(body).jsonObject
    }

    private suspend fun executionTools() = tools().filter { it.name !in setOf("route_task", "list_execution_devices") }

    private suspend fun register(): List<McpToolDescriptor> {
        val available = executionTools()
        val reply = request("/v1/devices/register", buildJsonObject {
            put("name", deviceName); put("platform", platform)
            put("foreground", foreground()); put("acceptTasks", acceptsTasks)
            put("resources", JsonArray(emptyList()))
            put("tools", JsonArray(available.map { tool -> buildJsonObject {
                put("name", tool.name); put("description", tool.description.ifBlank { tool.name }.take(1000))
                put("requiresForeground", tool.policy.requiresForeground)
            } }))
        })
        deviceId = reply.string("id")
        return available
    }

    suspend fun tick() = tickGate.withLock tick@ {
        val (job, available) = gate.withLock prepare@ {
            if (endpoint.isBlank()) return@prepare null
            validateGateway(endpoint)
            val available = register()
            uploadPending()
            discover()
            mutableStatus.value = "已连接 · $deviceName · ${if (acceptsTasks) "前台可接单" else "仅发起任务"}"
            if (!acceptsTasks || !foreground()) return@prepare null
            val claimed = request("/v1/devices/claim", JsonObject(emptyMap()))["job"] as? JsonObject ?: return@prepare null
            executing = true
            claimed to available
        } ?: return@tick
        try {
            mutableStatus.value = "正在执行远端任务 ${job.string("id").take(8)}"
            val result = coroutineScope {
                val heartbeat = launch {
                    while (isActive) {
                        delay(20_000)
                        try { request("/v1/devices/heartbeat", buildJsonObject {
                            put("id", job.getValue("id")); put("attempt", job.getValue("attempt"))
                        }) } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* A lost lease is never permission to execute again. */ }
                    }
                }
                try {
                    if (!foreground()) error("请在前台打开执行设备")
                    Json.parseToJsonElement(execute(buildJsonObject {
                        put("input", job.getValue("input")); put("checkpoint", job.getValue("checkpoint"))
                        put("allowed_tools", JsonArray(available.map { JsonPrimitive(it.name) }))
                    }.toString())).jsonObject
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { buildJsonObject {
                    put("state", "TASK_STATE_FAILED"); put("text", error.message ?: "设备执行失败")
                    put("checkpoint", job.getValue("checkpoint"))
                } } finally { heartbeat.cancel() }
            }
            val report = JsonObject(result + mapOf("id" to job.getValue("id"), "attempt" to job.getValue("attempt")))
            gate.withLock {
                persist(JsonObject(settings + ("pending" to report)))
                uploadPending()
                mutableStatus.value = "已回传远端任务结果"
            }
        } finally { gate.withLock { executing = false } }
    }

    /** Quotes only information already delegated; reservation is durable before inference/send. */
    suspend fun answerPendingQuestion() {
        val task = gate.withLock reserve@ {
            if (endpoint.isBlank() || executing) return@reserve null
            val liveKeys = a2a.tasks.value.filter { !it.terminal }.map { "${it.id}:${it.remoteTaskId}" }.toSet()
            val ledger = JsonObject(((settings["answered"] as? JsonObject) ?: JsonObject(emptyMap())).filterKeys { it in liveKeys })
            if (ledger.size >= 64) return@reserve null
            val candidate = a2a.tasks.value.firstOrNull { task ->
                val key = "${task.id}:${task.remoteTaskId}"
                val attempts = (ledger[key] as? JsonArray).orEmpty()
                task.state == "TASK_STATE_INPUT_REQUIRED" && task.agentId.startsWith("$endpoint/agents/") &&
                    !task.question.isNullOrBlank() && task.question.length <= 4096 && attempts.size < 3 &&
                    JsonPrimitive(task.question.hashCode()) !in attempts
            } ?: return@reserve null
            val key = "${candidate.id}:${candidate.remoteTaskId}"
            val attempts = (ledger[key] as? JsonArray).orEmpty() + JsonPrimitive(candidate.question!!.hashCode())
            persist(JsonObject(settings + ("answered" to JsonObject(ledger + (key to JsonArray(attempts))))))
            executing = true
            candidate
        } ?: return
        try {
            val response = Json.parseToJsonElement(answerQuestion(buildJsonObject {
                put("task", task.requestText); put("question", task.question)
            }.toString())).jsonObject
            val quote = response["answer"]?.jsonPrimitive?.contentOrNull ?: return
            if (quote.isBlank() || quote.length > 4096 || !task.requestText.contains(quote)) return
            val current = a2a.tasks.value.firstOrNull { it.id == task.id }
            if (current?.state == "TASK_STATE_INPUT_REQUIRED" && current.question == task.question) {
                a2a.reply(task.id, quote)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Preserve the original question for an explicit user reply. */ }
        finally { gate.withLock { executing = false } }
    }

    private suspend fun discover(): List<JsonObject> {
        val devices = (request("/v1/devices")["devices"] as? JsonArray).orEmpty().map { it.jsonObject }
        val token = tokens.load(secretKey(endpoint)) ?: error("缺少设备令牌")
        devices.filter { it.string("id") != deviceId }.forEach { device ->
            val id = device.string("id")
            a2a.installDeviceAgent(agentCardUrl(id), request(agentCardPath(id)), token)
        }
        return devices.map { device ->
            val own = device.string("id") == deviceId
            val peerEnabled = a2a.agents.value.any { it.id == agentCardUrl(device.string("id")) && it.enabled }
            JsonObject(device + ("delegationEnabled" to JsonPrimitive(own || peerEnabled)))
        }
    }

    override suspend fun listTools() = listOf(
        McpToolDescriptor("list_execution_devices", "List paired devices, exact capability names, resource references and availability. Use only when a task needs device execution.", "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}", routingPolicy),
        McpToolDescriptor("route_task", "Resolve execution for a task. Ordinary chat stays local. Specify exact capability names from list_execution_devices; never invent resources. This tool selects a device but does not execute. For REMOTE use delegate_to_agent with the returned agent_id. NEEDS_USER_ACTION means ask the user; do not silently switch data sources.",
            """{"type":"object","properties":{"required_capabilities":{"type":"array","items":{"type":"string"}},"resource_refs":{"type":"array","items":{"type":"string"}},"target_device":{"type":"string"}},"required":["required_capabilities"],"additionalProperties":false}""", routingPolicy),
    )

    override suspend fun callTool(name: String, argumentsJson: String): McpCallResult = gate.withLock {
        if (endpoint.isBlank()) return@withLock McpCallResult("跨设备执行未配置；请使用当前设备或在设置中连接设备服务", true)
        register()
        val devices = discover()
        val result = when (name) {
            "list_execution_devices" -> buildJsonObject { put("current_device_id", deviceId); put("devices", JsonArray(devices)) }
            "route_task" -> resolve(devices, Json.parseToJsonElement(argumentsJson).jsonObject)
            else -> error("未知路由工具")
        }
        McpCallResult(result.toString(), false)
    }

    internal fun resolve(devices: List<JsonObject>, args: JsonObject): JsonObject {
        require(args.keys.all { it in setOf("required_capabilities", "resource_refs", "target_device") })
        val required = args.getValue("required_capabilities").jsonArray.map { it.jsonPrimitive.content }
        val resources = (args["resource_refs"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
        require(required.size <= 32 && resources.size <= 32)
        val target = args.string("target_device")
        fun compatible(device: JsonObject): Boolean {
            val capabilities = device.getValue("tools").jsonArray.map { it.jsonObject.string("name") } + "model.complete"
            val refs = (device["resources"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
            return required.all { it in capabilities } && resources.all { it in refs }
        }
        val candidates = devices.filter { it["delegationEnabled"] != JsonPrimitive(false) && (target.isBlank() || it.string("id") == target) && compatible(it) }
        val local = candidates.firstOrNull { it.string("id") == deviceId }
        val chosen = local ?: candidates.firstOrNull { it["online"] == JsonPrimitive(true) && it["foreground"] == JsonPrimitive(true) && it["acceptTasks"] == JsonPrimitive(true) }
            ?: candidates.firstOrNull()
        return buildJsonObject {
            if (chosen == null) {
                put("decision", "UNSUPPORTED"); put("reason", "没有设备满足能力与资源要求；请核对资源或选择设备")
            } else {
                val isLocal = chosen.string("id") == deviceId
                val decision = when {
                    chosen["online"] != JsonPrimitive(true) -> "WAITING"
                    chosen["foreground"] != JsonPrimitive(true) -> "NEEDS_USER_ACTION"
                    !isLocal && chosen["acceptTasks"] != JsonPrimitive(true) -> "NEEDS_USER_ACTION"
                    isLocal -> "LOCAL"
                    else -> "REMOTE"
                }
                put("decision", decision); put("device_id", chosen.string("id")); put("device_name", chosen.string("name"))
                if (!isLocal) put("agent_id", agentCardUrl(chosen.string("id")))
                put("reason", when (decision) {
                    "LOCAL" -> "当前设备满足要求"
                    "REMOTE" -> "目标设备具备所需能力与资源，且允许接单"
                    "WAITING" -> "目标设备离线；可等待或明确委托排队"
                    else -> "请打开目标设备 App 并启用接单；系统工具权限仍需执行时确认"
                })
            }
        }
    }

    override suspend fun close() = Unit
    companion object {
        private fun secretKey(base: String) = "device-tasks:$base"
        private val routingPolicy = ToolPolicy(origin = "device", effect = "read", dataClass = "personal", requiresApproval = false)
        fun validateGateway(value: String) {
            val url = Url(value)
            require(url.protocol.name == "https" || url.protocol.name == "http" && url.host in setOf("localhost", "127.0.0.1")) { "设备服务需要 HTTPS；本机开发允许 HTTP" }
            require(url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.encodedPath in setOf("", "/") && url.parameters.isEmpty() && url.fragment.isEmpty()) { "请输入不含路径、查询参数和账号的服务地址" }
        }
    }
}

private fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
