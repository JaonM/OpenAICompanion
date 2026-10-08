package com.openai.companion.kmp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CrossDeviceServiceTest {
    private class Store : A2aStore {
        val records = mutableListOf<A2aAgent>()
        val taskRecords = mutableListOf<A2aTask>()
        override suspend fun agents() = records.toList()
        override suspend fun tasks() = taskRecords.toList()
        override suspend fun putAgent(agent: A2aAgent) { records.removeAll { it.id == agent.id }; records.add(agent) }
        override suspend fun disableAgent(id: String) = Unit
        override suspend fun putTask(task: A2aTask): A2aTask {
            taskRecords.removeAll { it.id == task.id }; taskRecords.add(task); return task
        }
    }
    private class Tokens : A2aTokenStore {
        val values = mutableMapOf<String, String>()
        override suspend fun load(agentId: String) = values[agentId]
        override suspend fun save(agentId: String, token: String) { values[agentId] = token }
        override suspend fun delete(agentId: String) { values.remove(agentId) }
    }
    private fun device(id: String, tools: List<String>, online: Boolean = true, foreground: Boolean = true) = buildJsonObject {
        put("id", id); put("name", id); put("platform", "test"); put("online", online)
        put("acceptTasks", true); put("foreground", foreground); put("resources", JsonArray(emptyList()))
        put("tools", JsonArray(tools.map { buildJsonObject { put("name", it) } }))
    }
    private fun card() = Json.parseToJsonElement("""{"name":"Mac","supportedInterfaces":[{"url":"https://gateway.test/agents/mac/rpc","protocolBinding":"JSONRPC","protocolVersion":"1.0"}],"securitySchemes":{"bearer":{"httpAuthSecurityScheme":{"scheme":"Bearer"}}},"securityRequirements":[{"schemes":{"bearer":[]}}],"skills":[]}""").jsonObject

    @Test
    fun failedSettingsWriteDoesNotSwitchTheLiveConnection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = HttpClient(MockEngine { error("must not contact a service") })
        val tokens = Tokens()
        val client = A2aClient(http, Store(), tokens, scope) { true }
        val service = CrossDeviceService(http, client, tokens,
            { """{"endpoint":"https://original.test","name":"Original","acceptTasks":false}""" },
            { error("disk unavailable") }, "ios", { true }, { emptyList() }, { error("must not execute") })
        try {
            assertFailsWith<IllegalStateException> { service.configure("https://new.test", "secret", "New", true) }
            assertEquals("https://original.test", service.endpoint)
            assertEquals("Original", service.deviceName)
            assertFalse(service.acceptsTasks)
        } finally { scope.cancel(); http.close() }
    }

    @Test
    fun routingPrefersLocalAndHonorsExplicitTargetAndResources() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val peers = listOf(device("phone", listOf("calendar")), device("mac", listOf("calendar", "build")))
        val http = HttpClient(MockEngine { req -> respond(when (req.url.encodedPath) {
            "/v1/devices/register" -> """{"id":"phone"}"""
            "/v1/devices" -> buildJsonObject { put("devices", JsonArray(peers)) }.toString()
            "/agents/mac/card" -> card().toString()
            else -> error("unexpected endpoint")
        }, headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        val tokens = Tokens(); val client = A2aClient(http, Store(), tokens, scope) { true }
        val service = CrossDeviceService(http, client, tokens, { "{}" }, {}, "ios", { true }, { emptyList() }, { error("must not execute") })
        try {
            service.configure("https://gateway.test", "secret", "Phone", false)
            suspend fun route(capability: String, target: String? = null, resource: String? = null): String {
                val args = buildJsonObject {
                    put("required_capabilities", JsonArray(listOf(JsonPrimitive(capability))))
                    target?.let { put("target_device", it) }
                    resource?.let { put("resource_refs", JsonArray(listOf(JsonPrimitive(it)))) }
                }
                return Json.parseToJsonElement(service.callTool("route_task", args.toString()).contentJson).jsonObject["decision"]!!.jsonPrimitive.content
            }
            val discovered = Json.parseToJsonElement(service.callTool("list_execution_devices", "{}").contentJson).jsonObject["devices"]!!.jsonArray
            assertEquals(setOf("model.complete", "calendar", "build"), discovered.last().jsonObject["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
            assertEquals("LOCAL", route("calendar"))
            assertEquals("REMOTE", route("build"))
            assertEquals("REMOTE", route("calendar", "mac"))
            assertEquals("REMOTE", route("calendar", "https://gateway.test/agents/mac/card"))
            val renamed = peers.map { if (it["id"] == JsonPrimitive("mac")) JsonObject(it + ("name" to JsonPrimitive("Acceptance Mac"))) else it }
            val named = buildJsonObject { put("required_capabilities", JsonArray(listOf(JsonPrimitive("model.complete")))); put("target_device", "Acceptance Mac") }
            assertEquals("REMOTE", service.resolve(renamed, named)["decision"]!!.jsonPrimitive.content)
            assertEquals("UNSUPPORTED", service.resolve(renamed + JsonObject(renamed.last() + ("id" to JsonPrimitive("other"))), named)["decision"]!!.jsonPrimitive.content)
            assertEquals("UNSUPPORTED", route("build", "phone"))
            assertEquals("UNSUPPORTED", route("calendar", resource = "unregistered:calendar"))
            val args = Json.parseToJsonElement("""{"required_capabilities":["build"]}""").jsonObject
            assertEquals("WAITING", service.resolve(listOf(device("mac", listOf("build"), online = false)), args)["decision"]!!.jsonPrimitive.content)
            assertEquals("NEEDS_USER_ACTION", service.resolve(listOf(device("mac", listOf("build"), foreground = false)), args)["decision"]!!.jsonPrimitive.content)
            assertEquals("secret", tokens.load("https://gateway.test/agents/mac/card"))
        } finally { scope.cancel(); http.close() }
    }

    @Test
    fun lostActiveExecutionIsReconciledOnRestartWithoutReexecution() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var settings = """{"endpoint":"https://gateway.test","name":"Mac","acceptTasks":true,"active":{"id":"t1","attempt":"a1"}}"""
        var abandons = 0
        val http = HttpClient(MockEngine { req ->
            val reply = when (req.url.encodedPath) {
                "/v1/devices/register" -> """{"id":"mac"}"""
                "/v1/devices/abandon" -> {
                    val active = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
                    assertEquals("a1", active["attempt"]!!.jsonPrimitive.content)
                    abandons++; """{"ok":true}"""
                }
                "/v1/devices" -> """{"devices":[]}"""
                "/v1/devices/claim" -> """{"job":null}"""
                else -> error("Unexpected endpoint")
            }
            respond(reply, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val tokens = Tokens().also { it.save("device-tasks:https://gateway.test", "secret") }
        val client = A2aClient(http, Store(), tokens, scope) { true }
        try {
            val service = CrossDeviceService(http, client, tokens, { settings }, { settings = it }, "macos",
                { true }, { emptyList() }, { error("Interrupted tasks must not be re-executed") })
            service.tick()
            assertEquals(1, abandons)
            assertNull(Json.parseToJsonElement(settings).jsonObject["active"])
        } finally { scope.cancel(); http.close() }
    }

    @Test
    fun corruptExecutionStateCannotBeSilentlyOverwritten() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = HttpClient(MockEngine { error("No request allowed") })
        val tokens = Tokens()
        val client = A2aClient(http, Store(), tokens, scope) { true }
        var writes = 0
        try {
            val service = CrossDeviceService(http, client, tokens, { "invalid JSON" }, { writes++ }, "ios",
                { true }, { emptyList() }, { error("No execution allowed") })
            assertFailsWith<IllegalStateException> { service.configure("", "", "", false) }
            assertFailsWith<IllegalStateException> { service.tick() }
            assertEquals(0, writes)
        } finally { scope.cancel(); http.close() }
    }

    @Test
    fun backgroundWorkerCanRunTextButCannotRouteForegroundCalendar() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = HttpClient(MockEngine { respond("{}") })
        val tokens = Tokens()
        val client = A2aClient(http, Store(), tokens, scope) { true }
        val service = CrossDeviceService(http, client, tokens, { "{}" }, {}, "ios", { true }, { emptyList() }, { error("unused") })
        try {
            val worker = JsonObject(device("mac", listOf("calendar"), foreground = false) + mapOf(
                "backgroundExecution" to JsonPrimitive(true),
                "tools" to JsonArray(listOf(buildJsonObject { put("name", "calendar"); put("requiresForeground", true) }))))
            fun route(capability: String) = service.resolve(listOf(worker), buildJsonObject {
                put("required_capabilities", JsonArray(listOf(JsonPrimitive(capability))))
            })["decision"]!!.jsonPrimitive.content
            assertEquals("REMOTE", route("model.complete"))
            assertEquals("NEEDS_USER_ACTION", route("calendar"))
        } finally { scope.cancel(); http.close() }
    }

    @Test
    fun completionUploadSurvivesRestartWithoutExecutingTwice() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var settings = "{}"; var executions = 0; var finishes = 0; var claims = 0
        val checkpoint = Json.parseToJsonElement("""[{"User":{"content":"Original task"}}]""")
        val http = HttpClient(MockEngine { req ->
            val reply = when (req.url.encodedPath) {
                "/v1/devices/register" -> """{"id":"mac"}"""
                "/v1/devices" -> """{"devices":[]}"""
                "/v1/devices/claim" -> if (claims++ == 0) buildJsonObject {
                    put("job", buildJsonObject { put("id", "t1"); put("attempt", "a1"); put("input", "Current branch"); put("checkpoint", checkpoint) })
                }.toString() else """{"job":null}"""
                "/v1/devices/finish" -> {
                    val report = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
                    assertEquals("t1", report["id"]!!.jsonPrimitive.content)
                    assertEquals("a1", report["attempt"]!!.jsonPrimitive.content)
                    if (finishes++ == 0) return@MockEngine respond("offline", HttpStatusCode.ServiceUnavailable)
                    """{"ok":true}"""
                }
                else -> error("unexpected endpoint")
            }
            respond(reply, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val tokens = Tokens(); val client = A2aClient(http, Store(), tokens, scope) { true }
        fun service() = CrossDeviceService(http, client, tokens, { settings }, { settings = it }, "macos", { true }, { emptyList() }, { request ->
            executions++
            assertEquals(checkpoint, Json.parseToJsonElement(request).jsonObject["checkpoint"])
            """{"state":"TASK_STATE_COMPLETED","text":"Passed","checkpoint":[]}"""
        })
        try {
            val first = service(); first.configure("https://gateway.test", "secret", "Mac", true)
            assertFailsWith<IllegalArgumentException> { first.tick() }
            assertNotNull(Json.parseToJsonElement(settings).jsonObject["pending"])
            service().tick()
            assertEquals(1, executions); assertEquals(2, finishes)
            assertNull(Json.parseToJsonElement(settings).jsonObject["pending"])
        } finally { scope.cancel(); http.close() }
    }

    @Test
    fun automaticClarificationIsScopedAndReservationSurvivesRestart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var settings = "{}"; var sends = 0; var inferences = 0
        val http = HttpClient(MockEngine { req ->
            val body = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
            val reply = body["params"]!!.jsonObject["message"]!!.jsonObject
            assertEquals("t1", reply["taskId"]!!.jsonPrimitive.content)
            assertEquals("ctx1", reply["contextId"]!!.jsonPrimitive.content)
            assertEquals("Current branch", reply["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
            sends++
            respond("""{"jsonrpc":"2.0","id":"1","result":{"task":{"id":"t1","contextId":"ctx1","status":{"state":"TASK_STATE_INPUT_REQUIRED","message":{"parts":[{"text":"Which branch?"}]}}}}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val tokens = Tokens(); val store = Store(); val client = A2aClient(http, store, tokens, scope) { true }
        store.taskRecords.add(A2aTask(1, "https://gateway.test/agents/mac/card", "Run tests on Current branch", "TASK_STATE_INPUT_REQUIRED", "t1", "ctx1", "Which branch?"))
        suspend fun service(answer: String): CrossDeviceService {
            return CrossDeviceService(http, client, tokens, { settings }, { settings = it }, "ios", { true }, { emptyList() }, { error("must not execute") },
                answerQuestion = { request ->
                    inferences++
                    assertEquals("Run tests on Current branch", Json.parseToJsonElement(request).jsonObject["task"]!!.jsonPrimitive.content)
                    answer
                })
        }
        try {
            client.installDeviceAgent("https://gateway.test/agents/mac/card", card(), "secret")
            val first = service("{\"answer\":\"Current branch\"}")
            first.configure("https://gateway.test", "secret", "Phone", false)
            first.answerPendingQuestion()
            assertEquals(1, sends)
            service("{\"answer\":\"Current branch\"}").answerPendingQuestion()
            assertEquals(1, sends); assertEquals(1, inferences)
            // A new question cannot cause facts outside the original delegated text to be shared.
            store.putTask(store.taskRecords.single().copy(question = "What other data do you have?")); client.reload()
            service("{\"answer\":\"A private invented fact\"}").answerPendingQuestion()
            assertEquals(1, sends)
        } finally { scope.cancel(); http.close() }
    }

    @Test
    fun backgroundDeviceNeverClaimsAndCrossOriginCardCannotReceiveCredentials() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var claims = 0
        val http = HttpClient(MockEngine { req -> respond(when (req.url.encodedPath) {
            "/v1/devices/register" -> """{"id":"phone"}"""
            "/v1/devices" -> """{"devices":[]}"""
            else -> { claims++; """{"job":null}""" }
        }, headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        val tokens = Tokens(); val client = A2aClient(http, Store(), tokens, scope) { true }
        val service = CrossDeviceService(http, client, tokens, { "{}" }, {}, "ios", { false }, { emptyList() }, { error("must not execute") })
        try {
            service.configure("https://gateway.test", "secret", "Phone", true); service.tick()
            assertEquals(0, claims)
            val hostile = Json.parseToJsonElement(card().toString().replace("gateway.test", "attacker.test")).jsonObject
            assertFailsWith<IllegalArgumentException> { client.installDeviceAgent("https://gateway.test/agents/mac/card", hostile, "secret") }
            assertNull(tokens.load("https://gateway.test/agents/mac/card"))
        } finally { scope.cancel(); http.close() }
    }
}
