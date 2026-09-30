package com.openai.companion.kmp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HarnessA2aStoreTest {
    @Test
    fun sharedCodecPreservesAgentCardAndRemoteTaskFields() = runBlocking {
        var agentJson = "[]"
        var taskJson = "[]"
        val store = HarnessA2aStore(
            listAgentsJson = { agentJson },
            putAgentJson = { raw -> agentJson = "[$raw]"; "{}" },
            deleteAgentJson = { id ->
                assertEquals("agent-1", id)
                agentJson = "[]"
                "{}"
            },
            listTasksJson = { taskJson },
            putTaskJson = { raw ->
                val input = Json.parseToJsonElement(raw).jsonObject
                assertEquals("remote-9", input.getValue("remoteTaskId").jsonPrimitive.content)
                taskJson = "[${buildJsonObject {
                    input.forEach { (key, value) -> put(key, value) }
                    put("id", 42); put("updatedAt", 1234)
                }}]"
                """{"id":42}"""
            },
        )
        val card = buildJsonObject { put("name", "Research") }
        store.putAgent(A2aAgent("agent-1", "Research", "https://example.test/card", card))
        assertEquals(card, store.agents().single().card)

        val saved = store.putTask(A2aTask(
            id = 0, agentId = "agent-1", requestText = "Research a topic", state = "submitted",
            remoteTaskId = "remote-9", contextId = null, question = null, result = null,
            updatedAt = 0,
        ))
        assertEquals(42, saved.id)
        val loaded = store.tasks().single()
        assertEquals("remote-9", loaded.remoteTaskId)
        assertNull(loaded.contextId)

        store.disableAgent("agent-1")
        assertEquals(emptyList(), store.agents())
    }
}
