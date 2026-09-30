package com.openai.companion.kmp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Shared A2A persistence codec; each host supplies its UniFFI calls. */
class HarnessA2aStore(
    private val listAgentsJson: () -> String,
    private val putAgentJson: (String) -> String,
    private val deleteAgentJson: (String) -> String,
    private val listTasksJson: () -> String,
    private val putTaskJson: (String) -> String,
) : A2aStore {
    override suspend fun agents(): List<A2aAgent> = withContext(Dispatchers.Default) {
        (Json.parseToJsonElement(listAgentsJson()) as JsonArray).map { entry ->
            val item = entry.jsonObject
            A2aAgent(
                id = item.string("id"), name = item.string("name"), cardUrl = item.string("cardUrl"),
                card = item.getValue("card").jsonObject,
                enabled = item.getValue("enabled").jsonPrimitive.content == "true",
            )
        }
    }

    override suspend fun putAgent(agent: A2aAgent) = withContext(Dispatchers.Default) {
        putAgentJson(buildJsonObject {
            put("id", agent.id); put("name", agent.name); put("cardUrl", agent.cardUrl)
            put("card", agent.card); put("enabled", agent.enabled)
        }.toString())
        Unit
    }

    override suspend fun disableAgent(id: String) = withContext(Dispatchers.Default) {
        deleteAgentJson(id)
        Unit
    }

    override suspend fun tasks(): List<A2aTask> = withContext(Dispatchers.Default) {
        (Json.parseToJsonElement(listTasksJson()) as JsonArray).map { entry ->
            val item = entry.jsonObject
            A2aTask(
                id = item.getValue("id").jsonPrimitive.long, agentId = item.string("agentId"),
                requestText = item.string("requestText"), state = item.string("state"),
                remoteTaskId = item.optional("remoteTaskId"), contextId = item.optional("contextId"),
                question = item.optional("question"), result = item.optional("result"),
                updatedAt = item.getValue("updatedAt").jsonPrimitive.long,
            )
        }
    }

    override suspend fun putTask(task: A2aTask): A2aTask = withContext(Dispatchers.Default) {
        val result = Json.parseToJsonElement(putTaskJson(buildJsonObject {
            if (task.id > 0) put("id", task.id)
            put("agentId", task.agentId); put("requestText", task.requestText); put("state", task.state)
            task.remoteTaskId?.let { put("remoteTaskId", it) }
            task.contextId?.let { put("contextId", it) }
            task.question?.let { put("question", it) }
            task.result?.let { put("result", it) }
        }.toString())).jsonObject
        task.copy(id = result.getValue("id").jsonPrimitive.long)
    }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
private fun JsonObject.optional(key: String): String? = get(key)?.let {
    if (it.toString() == "null") null else it.jsonPrimitive.content
}
