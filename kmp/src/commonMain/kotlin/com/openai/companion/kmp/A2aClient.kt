package com.openai.companion.kmp

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.random.Random

data class A2aAgent(
    val id: String,
    val name: String,
    val cardUrl: String,
    val card: JsonObject,
    val enabled: Boolean = true,
) {
    val interfaceUrl: String get() = supportedInterface(card)["url"]!!.jsonPrimitive.content
    val skills: String get() = (card["skills"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.content }
        .joinToString("、")
    val bearerSupported: Boolean get() {
        val schemes = card["securitySchemes"] as? JsonObject ?: return false
        val bearerNames = schemes.filterValues { scheme ->
            val http = (scheme as? JsonObject)?.get("httpAuthSecurityScheme") as? JsonObject
            field(http ?: JsonObject(emptyMap()), "scheme")?.equals("Bearer", ignoreCase = true) == true
        }.keys
        val requirements = card["securityRequirements"] as? JsonArray
        return bearerNames.isNotEmpty() && (requirements.isNullOrEmpty() || requirements.any { requirement ->
            val required = ((requirement as? JsonObject)?.get("schemes") as? JsonObject)?.keys.orEmpty()
            required.size == 1 && required.first() in bearerNames
        })
    }
    val requiresAuthentication: Boolean get() {
        val requirements = card["securityRequirements"] as? JsonArray ?: return false
        return requirements.isNotEmpty() && requirements.none { requirement ->
            ((requirement as? JsonObject)?.get("schemes") as? JsonObject)?.isEmpty() == true
        }
    }
}

data class A2aTask(
    val id: Long,
    val agentId: String,
    val requestText: String,
    val state: String,
    val remoteTaskId: String? = null,
    val contextId: String? = null,
    val question: String? = null,
    val result: String? = null,
    val updatedAt: Long = 0,
    val pendingMessageId: String? = null,
) {
    val terminal: Boolean get() = state in setOf(
        "TASK_STATE_COMPLETED", "TASK_STATE_FAILED", "TASK_STATE_CANCELED", "TASK_STATE_REJECTED", "DIRECT_MESSAGE", "SEND_UNCERTAIN",
    )
}

data class A2aDelegation(val agent: A2aAgent, val taskText: String)

interface A2aStore {
    suspend fun agents(): List<A2aAgent>
    suspend fun putAgent(agent: A2aAgent)
    suspend fun disableAgent(id: String)
    suspend fun tasks(): List<A2aTask>
    suspend fun putTask(task: A2aTask): A2aTask
}

interface A2aTokenStore {
    suspend fun load(agentId: String): String?
    suspend fun save(agentId: String, token: String)
    suspend fun delete(agentId: String)
}

/** Text-only A2A 1.0 JSON-RPC client. The store is owned by the host app. */
class A2aClient(
    private val http: HttpClient,
    private val store: A2aStore,
    private val tokens: A2aTokenStore,
    private val scope: CoroutineScope,
    private val approve: suspend (A2aDelegation) -> Boolean,
) {
    private val mutableAgents = MutableStateFlow<List<A2aAgent>>(emptyList())
    private val mutableTasks = MutableStateFlow<List<A2aTask>>(emptyList())
    private val userOperationGate = Mutex()
    val agents: StateFlow<List<A2aAgent>> = mutableAgents.asStateFlow()
    val tasks: StateFlow<List<A2aTask>> = mutableTasks.asStateFlow()
    private var polling = false

    suspend fun start() {
        store.tasks().forEach { task ->
            when (task.state) {
                "SUBMITTING" -> store.putTask(task.copy(state = "SEND_UNCERTAIN", result = "应用中断期间发送结果未知"))
                "REPLY_SUBMITTING" -> store.putTask(task.copy(state = "REPLY_UNCERTAIN", result = "应用中断期间回复结果未知"))
            }
        }
        reload()
        refresh()
        if (!polling) {
            polling = true
            scope.launch {
                while (isActive) {
                    delay(15_000)
                    try { refresh() }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { /* Retain the durable last-known state. */ }
                }
            }
        }
    }

    suspend fun reload() {
        mutableAgents.value = store.agents()
        mutableTasks.value = store.tasks()
    }

    suspend fun addAgent(rawCardUrl: String) {
        val cardUrl = rawCardUrl.trim()
        validateUrl(cardUrl)
        val existing = store.agents().firstOrNull { it.id == cardUrl && it.bearerSupported }
        val knownToken = existing?.let { tokens.load(it.id) }
        val response = http.get(cardUrl) { knownToken?.let { header("Authorization", "Bearer $it") } }
        require(response.status.value == 200) { "读取 Agent Card 失败：HTTP ${response.status.value}" }
        val body = response.bodyAsText()
        require(body.length <= 512_000) { "Agent Card 过大" }
        val card = Json.parseToJsonElement(body).jsonObject
        val name = card["name"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: error("Agent Card 缺少名称")
        supportedInterface(card)
        require((card["defaultInputModes"] as? JsonArray).orEmpty().any { it.jsonPrimitive.content == "text/plain" }) {
            "该 Agent 不支持文本输入"
        }
        require((card["defaultOutputModes"] as? JsonArray).orEmpty().any { it.jsonPrimitive.content == "text/plain" }) {
            "该 Agent 不支持文本输出"
        }
        val extensions = ((card["capabilities"] as? JsonObject)?.get("extensions") as? JsonArray).orEmpty()
        require(extensions.none { (it as? JsonObject)?.get("required")?.jsonPrimitive?.content == "true" }) {
            "该 Agent 要求尚未支持的 A2A 扩展"
        }
        val id = cardUrl
        store.putAgent(A2aAgent(id, name, cardUrl, card))
        reload()
    }

    /** Authenticated discovery by an explicitly configured personal-device gateway. */
    suspend fun installDeviceAgent(cardUrl: String, card: JsonObject, token: String) {
        validateUrl(cardUrl)
        val selected = supportedInterface(card)
        val origin = Url(cardUrl)
        val destination = Url(field(selected, "url") ?: error("Agent endpoint missing"))
        require(origin.protocol == destination.protocol && origin.host == destination.host && origin.port == destination.port) {
            "设备 Agent 接口必须属于同一网关，不能向其他服务发送设备令牌"
        }
        val previous = store.agents().firstOrNull { it.id == cardUrl }
        if (previous?.enabled == false) return
        store.putAgent(A2aAgent(cardUrl, field(card, "name") ?: error("Agent name missing"), cardUrl, card))
        tokens.save(cardUrl, token)
        reload()
    }

    suspend fun disableAgent(id: String) {
        require(store.tasks().none { it.agentId == id && !it.terminal }) { "请先处理该 Agent 的未结束任务" }
        store.disableAgent(id)
        tokens.delete(id)
        reload()
    }

    suspend fun setBearerToken(agentId: String, token: String) {
        require(agents.value.any { it.id == agentId && it.enabled && it.bearerSupported }) { "Agent 未声明 Bearer 认证" }
        require(token.isNotBlank()) { "访问令牌不能为空" }
        require(token.length <= 16_000 && '\n' !in token && '\r' !in token) { "访问令牌格式无效" }
        tokens.save(agentId, token.trim())
        refresh()
    }

    suspend fun listForModel(): String = JsonArray(agents.value.filter { it.enabled }.map { agent ->
        buildJsonObject {
            put("agent_id", agent.id)
            put("name", agent.name)
            put("skills", agent.skills)
        }
    }).toString()

    suspend fun delegate(agentId: String, taskText: String): String {
        val configured = agents.value
        val target = agentId.trim()
        val knownId = configured.firstOrNull { it.id == target }
        val agent = (if (knownId != null) knownId.takeIf { it.enabled }
            else configured.filter { it.enabled && it.name == target }.singleOrNull())
            ?: return "{\"error\":\"未找到唯一已启用的远端 Agent；请使用 list_remote_agents 返回的 agent_id\"}"
        val text = taskText.trim()
        if (text.isEmpty()) return "{\"error\":\"委托内容为空\"}"
        if (agent.requiresAuthentication && !agent.bearerSupported) {
            return "{\"error\":\"该 Agent 使用尚未支持的认证方式\"}"
        }
        if (agent.requiresAuthentication && tokens.load(agent.id) == null) {
            return "{\"error\":\"该 Agent 要求认证，请先在设置中配置 Bearer 访问令牌\"}"
        }
        if (!approve(A2aDelegation(agent, text))) return "{\"error\":\"用户拒绝委托\"}"
        if (agents.value.none { it.id == agent.id && it.card == agent.card && it.enabled }) {
            return "{\"error\":\"Agent 配置已变化，请重新确认\"}"
        }
        val message = userMessage(text)
        var task = store.putTask(A2aTask(0, agent.id, text, "SUBMITTING", pendingMessageId = field(message, "messageId")))
        reload()
        try {
            task = fromSendResult(task, rpc(agent, "SendMessage", buildJsonObject {
                put("message", message)
                put("configuration", buildJsonObject { put("returnImmediately", true) })
            }))
            store.putTask(task)
            reload()
            return buildJsonObject {
                put("local_task_id", task.id)
                put("remote_task_id", task.remoteTaskId ?: "")
                put("state", task.state)
            }.toString()
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                store.putTask(task.copy(state = "SEND_UNCERTAIN", result = "发送已中断；请检查远端任务，勿直接重发"))
                reload()
            }
            throw error
        } catch (error: Exception) {
            store.putTask(task.copy(state = "SEND_UNCERTAIN", result = error.message ?: error.toString()))
            reload()
            return buildJsonObject { put("error", "发送结果未知，可能已被远端接收；本地任务 ${task.id} 已保存") }.toString()
        }
    }

    suspend fun refresh() {
        val current = store.tasks()
        for (task in current) {
            val agent = agents.value.firstOrNull { it.id == task.agentId } ?: continue
            if (task.state in setOf("SEND_UNCERTAIN", "REPLY_UNCERTAIN") && task.pendingMessageId != null &&
                (agent.card["metadata"] as? JsonObject)?.get("companionRecoveryVersion") == JsonPrimitive(1)) {
                try {
                    val receipt = rpc(agent, "companion/GetMessageReceipt", buildJsonObject { put("messageId", task.pendingMessageId) })
                    val remote = receipt["task"] as? JsonObject ?: continue
                    val latest = store.tasks().firstOrNull { it.id == task.id }
                    if (latest?.state == task.state && latest.pendingMessageId == task.pendingMessageId) {
                        store.putTask(fromRemoteTask(task, remote))
                    }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* Query receipts only; never replay a send or reply. */ }
                continue
            }
            if (task.terminal || task.remoteTaskId == null || task.state in setOf("TASK_STATE_INPUT_REQUIRED", "REPLY_SUBMITTING")) continue
            try {
                val result = rpc(agent, "GetTask", buildJsonObject { put("id", task.remoteTaskId) })
                val latest = store.tasks().firstOrNull { it.id == task.id }
                if (latest?.state == task.state && latest.remoteTaskId == task.remoteTaskId) {
                    store.putTask(fromRemoteTask(task, result))
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Retry read-only status lookup later. */ }
        }
        reload()
    }

    suspend fun reply(localId: Long, text: String) = withUserOperation {
        val task = tasks.value.firstOrNull { it.id == localId } ?: error("远端任务不存在")
        require(task.state == "TASK_STATE_INPUT_REQUIRED") { "该任务当前不等待回复" }
        require(text.isNotBlank()) { "回复不能为空" }
        val agent = agents.value.firstOrNull { it.id == task.agentId } ?: error("Agent 配置不存在")
        val message = userMessage(text.trim(), task.remoteTaskId, task.contextId)
        val pending = task.copy(state = "REPLY_SUBMITTING", pendingMessageId = field(message, "messageId"))
        store.putTask(pending)
        reload()
        try {
            val result = rpc(agent, "SendMessage", buildJsonObject {
                put("message", message)
                put("configuration", buildJsonObject { put("returnImmediately", true) })
            })
            store.putTask(fromSendResult(pending, result))
            reload()
        } catch (error: Exception) {
            withContext(NonCancellable) {
                store.putTask(pending.copy(state = "REPLY_UNCERTAIN", result = error.message ?: error.toString()))
                reload()
            }
            throw error
        }
    }

    suspend fun decline(localId: Long) = reply(localId, "我拒绝提供这项信息，请停止当前任务。")

    suspend fun cancel(localId: Long) = withUserOperation {
        val task = tasks.value.firstOrNull { it.id == localId } ?: error("远端任务不存在")
        require(!task.terminal) { "该任务已经结束" }
        val remoteId = task.remoteTaskId ?: error("任务尚无远端 ID，无法取消")
        val agent = agents.value.firstOrNull { it.id == task.agentId } ?: error("Agent 配置不存在")
        store.putTask(task.copy(state = "CANCEL_UNCERTAIN"))
        reload()
        try {
            store.putTask(fromRemoteTask(task, rpc(agent, "CancelTask", buildJsonObject { put("id", remoteId) })))
            reload()
        } catch (error: Exception) {
            refresh()
            throw error
        }
    }

    private suspend fun <T> withUserOperation(block: suspend () -> T): T {
        userOperationGate.lock()
        try { return block() }
        finally { userOperationGate.unlock() }
    }

    private suspend fun rpc(agent: A2aAgent, method: String, params: JsonObject): JsonObject {
        val selected = supportedInterface(agent.card)
        val endpoint = field(selected, "url") ?: error("Agent 接口地址缺失")
        val version = field(selected, "protocolVersion") ?: error("Agent 协议版本缺失")
        val tenant = field(selected, "tenant")
        validateUrl(endpoint)
        val token = if (agent.bearerSupported) tokens.load(agent.id) else null
        val response = http.post(endpoint) {
            contentType(ContentType.Application.Json)
            header("A2A-Version", version)
            token?.let { header("Authorization", "Bearer $it") }
            setBody(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", Random.nextLong().toString())
                put("method", method)
                put("params", if (tenant == null) params else JsonObject(params + ("tenant" to JsonPrimitive(tenant))))
            }.toString())
        }
        require(response.status.value in 200..299) { "A2A $method 返回 HTTP ${response.status.value}" }
        val responseText = response.bodyAsText()
        require(responseText.length <= 2_000_000) { "A2A 响应过大" }
        val body = Json.parseToJsonElement(responseText).jsonObject
        body["error"]?.let { error("A2A $method: $it") }
        return body["result"]?.jsonObject ?: error("A2A $method 缺少 result")
    }

    private fun fromSendResult(task: A2aTask, result: JsonObject): A2aTask {
        val remoteTask = result["task"] as? JsonObject
        if (remoteTask != null) return fromRemoteTask(task, remoteTask)
        val message = result["message"] as? JsonObject ?: error("A2A 响应缺少 task/message")
        return task.copy(state = "DIRECT_MESSAGE", pendingMessageId = null,
            result = textParts(message).ifBlank { "远端返回了非文本内容" }, contextId = field(message, "contextId"))
    }

    private fun fromRemoteTask(local: A2aTask, remote: JsonObject): A2aTask {
        val remoteId = field(remote, "id") ?: error("A2A Task 缺少 id")
        val status = remote["status"] as? JsonObject ?: error("A2A Task 缺少 status")
        val remoteState = field(status, "state") ?: error("A2A Task 缺少 state")
        val uncertain = (remote["metadata"] as? JsonObject)?.get("executionUnknown")?.jsonPrimitive?.content == "true"
        val state = if (uncertain) "EXECUTION_UNKNOWN" else remoteState
        val prompt = (status["message"] as? JsonObject)?.let(::textParts).orEmpty()
        val artifactObjects = (remote["artifacts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val artifacts = artifactObjects.joinToString("\n") { textParts(it) }
        return local.copy(
            pendingMessageId = null,
            remoteTaskId = remoteId, contextId = field(remote, "contextId"), state = state,
            question = prompt.takeIf { state == "TASK_STATE_INPUT_REQUIRED" || state == "TASK_STATE_AUTH_REQUIRED" },
            result = artifacts.ifBlank { prompt.ifBlank { if (artifactObjects.isNotEmpty()) "远端返回了非文本内容" else "" } }
                .takeIf { state in setOf("TASK_STATE_COMPLETED", "TASK_STATE_FAILED", "TASK_STATE_REJECTED", "EXECUTION_UNKNOWN") },
        )
    }
}

private fun userMessage(text: String, taskId: String? = null, contextId: String? = null) = buildJsonObject {
    put("messageId", "companion-${Random.nextLong()}-${Random.nextLong()}")
    put("role", "ROLE_USER")
    put("parts", JsonArray(listOf(buildJsonObject { put("text", text) })))
    taskId?.let { put("taskId", it) }
    contextId?.let { put("contextId", it) }
}

private fun textParts(value: JsonObject): String = (value["parts"] as? JsonArray).orEmpty()
    .mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.content }.joinToString("\n")

private fun field(value: JsonObject, key: String): String? = value[key]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)

private fun supportedInterface(card: JsonObject): JsonObject {
    val interfaces = card["supportedInterfaces"] as? JsonArray ?: error("Agent Card 缺少 supportedInterfaces")
    return interfaces.mapNotNull { it as? JsonObject }.firstOrNull {
        field(it, "protocolBinding") == "JSONRPC" && field(it, "protocolVersion")?.startsWith("1.") == true &&
            field(it, "url")?.let { url -> runCatching { validateUrl(url) }.isSuccess } == true
    } ?: error("Agent 不支持 A2A 1.x JSONRPC")
}

private fun validateUrl(value: String) {
    val url = Url(value)
    require(url.protocol.name == "https" || url.protocol.name == "http" && url.host in setOf("localhost", "127.0.0.1")) {
        "远端 Agent 必须使用 HTTPS；本机允许 HTTP"
    }
    require(url.user.isNullOrEmpty() && url.password.isNullOrEmpty()) { "地址不能包含账号或密码" }
}
