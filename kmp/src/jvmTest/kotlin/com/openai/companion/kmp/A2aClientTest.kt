package com.openai.companion.kmp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class A2aClientTest {
    @Test
    fun uncertainSendIsPersistedAndNotRetried() = runBlocking {
        var sends = 0
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("agent-card.json")) {
                respond(
                    """{"name":"Agent","description":"Test","version":"1.0","supportedInterfaces":[{"url":"https://agent.example/rpc","protocolBinding":"JSONRPC","protocolVersion":"1.0","tenant":"team-a"}],"capabilities":{},"defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                sends++
                val params = Json.parseToJsonElement((request.body as TextContent).text).jsonObject["params"]!!.jsonObject
                assertEquals("team-a", params["tenant"]!!.jsonPrimitive.content)
                respond("server error", HttpStatusCode.InternalServerError)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = A2aClient(HttpClient(engine), TestA2aStore(), TestTokenStore(), scope) { true }
        try {
            client.start()
            client.addAgent("https://agent.example/.well-known/agent-card.json")
            val reply = client.delegate(client.agents.value.single().id, "Do work")
            assertTrue(reply.contains("发送结果未知"))
            assertEquals("SEND_UNCERTAIN", client.tasks.value.single().state)
            assertEquals(1, sends)
            client.refresh()
            assertEquals(1, sends)
        } finally { scope.cancel() }
    }

    @Test
    fun delegatesAndResumesTheSameRemoteTaskAfterUserInput() = runBlocking {
        val sent = mutableListOf<JsonObject>()
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("agent-card.json")) {
                respond(
                    """{"name":"Research Agent","description":"Research","version":"1.0","supportedInterfaces":[{"url":"https://agent.example/rpc","protocolBinding":"JSONRPC","protocolVersion":"1.0"}],"capabilities":{},"defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[{"id":"research","name":"Research","description":"Research","tags":["research"]}]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                assertEquals("1.0", request.headers["A2A-Version"])
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                sent += body
                val reply = if (sent.size == 1) {
                    """{"jsonrpc":"2.0","id":"1","result":{"task":{"id":"remote-1","contextId":"ctx-1","status":{"state":"TASK_STATE_INPUT_REQUIRED","message":{"messageId":"q1","role":"ROLE_AGENT","parts":[{"text":"Which year?"}]}}}}}"""
                } else {
                    """{"jsonrpc":"2.0","id":"2","result":{"task":{"id":"remote-1","contextId":"ctx-1","status":{"state":"TASK_STATE_COMPLETED"},"artifacts":[{"artifactId":"a1","parts":[{"text":"2026 report"}]}]}}}"""
                }
                respond(reply, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = TestA2aStore()
        val client = A2aClient(HttpClient(engine), store, TestTokenStore(), scope) { true }
        try {
            client.start()
            client.addAgent("https://agent.example/.well-known/agent-card.json")
            val agentId = client.agents.value.single().id
            val submitted = client.delegate(agentId, "Prepare a report")
            assertTrue(submitted.contains("remote-1"))
            val task = client.tasks.value.single()
            assertEquals("TASK_STATE_INPUT_REQUIRED", task.state)
            assertEquals("Which year?", task.question)
            client.reply(task.id, "2026")
            assertEquals("TASK_STATE_COMPLETED", client.tasks.value.single().state)
            assertEquals("2026 report", client.tasks.value.single().result)
            val followup = sent.last()["params"]!!.jsonObject["message"]!!.jsonObject
            assertEquals("remote-1", followup["taskId"]!!.jsonPrimitive.content)
            assertEquals("ctx-1", followup["contextId"]!!.jsonPrimitive.content)
        } finally {
            scope.cancel()
        }
    }
}

private class TestA2aStore : A2aStore {
    private val savedAgents = mutableListOf<A2aAgent>()
    private val savedTasks = mutableListOf<A2aTask>()
    override suspend fun agents(): List<A2aAgent> = savedAgents.toList()
    override suspend fun putAgent(agent: A2aAgent) { savedAgents.removeAll { it.id == agent.id }; savedAgents += agent }
    override suspend fun disableAgent(id: String) {
        savedAgents.replaceAll { if (it.id == id) it.copy(enabled = false) else it }
    }
    override suspend fun tasks(): List<A2aTask> = savedTasks.toList()
    override suspend fun putTask(task: A2aTask): A2aTask {
        val saved = if (task.id == 0L) task.copy(id = savedTasks.size.toLong() + 1) else task
        savedTasks.removeAll { it.id == saved.id }
        savedTasks += saved
        return saved
    }
}

private class TestTokenStore : A2aTokenStore {
    override suspend fun load(agentId: String): String? = null
    override suspend fun save(agentId: String, token: String) = Unit
    override suspend fun delete(agentId: String) = Unit
}
