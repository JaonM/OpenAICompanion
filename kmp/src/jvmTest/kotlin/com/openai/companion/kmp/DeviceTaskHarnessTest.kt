package com.openai.companion.kmp

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*
import uniffi.harness.appOpenStore
import uniffi.harness.appStartSession
import uniffi.harness.appSendMessage

class DeviceTaskHarnessTest {
    @Test
    fun delegatedTaskHasIndependentHistoryAndResumesWithoutRepeatingTool() = runBlocking {
        val database = Files.createTempDirectory("companion-device-task-").resolve("test.sqlite")
        val bindings = GeneratedHarnessBindingsAdapter()
        var toolCalls = 0
        val taskRequests = mutableListOf<String>()
        var visibleEvents = 0
        fun response(content: String) = buildJsonObject {
            put("choices", JsonArray(listOf(buildJsonObject {
                put("message", buildJsonObject { put("content", content) })
            })))
        }.toString()
        bindings.registerToolProvider(object : RustToolProvider {
            override suspend fun getTools() = listOf(McpTool("device_get_context", "Get context", "{\"type\":\"object\"}", ToolPolicy(origin = "device", effect = "read", dataClass = "public", requiresForeground = false, requiresApproval = false)))
            override suspend fun callTool(name: String, argumentsJson: String, executionContext: String): McpCallResult {
                assertEquals("device_get_context", name); assertEquals("foreground", executionContext)
                toolCalls++
                return McpCallResult("{\"platform\":\"macos\"}", false)
            }
        })
        bindings.registerModelServeCallback(object : AppModelServe {
            override suspend fun complete(requestJson: String, callback: ModelStreamCallback) {
                val messages = Json.parseToJsonElement(requestJson).jsonObject.getValue("messages").jsonArray
                val prompt = messages.first().jsonObject["content"]!!.jsonPrimitive.content
                if (prompt.contains("A delegated agent asks a clarification")) {
                    assertFalse(requestJson.contains("private local conversation"))
                    val payload = Json.parseToJsonElement(messages.last().jsonObject["content"]!!.jsonPrimitive.content).jsonObject
                    val answer = if (payload["question"]!!.jsonPrimitive.content == "Invent a fact") "Unprovided fact" else "Current branch"
                    callback.onChunk(response("{\"answer\":\"$answer\"}")); return
                }
                if (!prompt.contains("bounded task delegated")) {
                    callback.onChunk(response(if (prompt.contains("提取未来有用的原子记忆点")) "[]" else "private local answer")); return
                }
                taskRequests.add(requestJson)
                assertFalse(requestJson.contains("private local conversation"))
                val lastUser = messages.last { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }.jsonObject["content"]!!.jsonPrimitive.content
                if (lastUser == "Current branch") {
                    assertTrue(requestJson.contains("Which branch?"))
                    assertTrue(messages.any { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" })
                    callback.onChunk(response("{\"state\":\"completed\",\"text\":\"Passed\"}"))
                } else if (messages.any { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }) {
                    callback.onChunk(response("{\"state\":\"input_required\",\"text\":\"Which branch?\"}"))
                } else {
                    callback.onChunk("""{"choices":[{"message":{"content":"","tool_calls":[{"id":"c1","type":"function","function":{"name":"device_get_context","arguments":"{}"}}]}}]}""")
                }
            }
        })
        try {
            assertTrue(appOpenStore(database.toString()).ok)
            assertTrue(appStartSession().ok)
            assertTrue(appSendMessage("private local conversation").ok)
            bindings.registerAgentEventSink(object : AppAgentEventSink {
                override fun onReasoningDelta(text: String) { visibleEvents++ }
                override fun onTextDelta(text: String) { visibleEvents++ }
                override fun onCompleted(finalText: String) { visibleEvents++ }
                override fun onError(errorJson: String) { visibleEvents++ }
            })
            fun execute(input: String, checkpoint: JsonElement) = Json.parseToJsonElement(bindings.executeDeviceTask(buildJsonObject {
                put("input", input); put("checkpoint", checkpoint)
                put("allowed_tools", JsonArray(listOf(JsonPrimitive("device_get_context"))))
            }.toString())).jsonObject
            val first = execute("Get context then ask for the branch", JsonArray(emptyList()))
            assertEquals("TASK_STATE_INPUT_REQUIRED", first["state"]!!.jsonPrimitive.content)
            val second = execute("Current branch", first.getValue("checkpoint"))
            assertEquals("TASK_STATE_COMPLETED", second["state"]!!.jsonPrimitive.content)
            assertEquals("Passed", second["text"]!!.jsonPrimitive.content)
            assertEquals(1, toolCalls)
            assertEquals(0, visibleEvents)
            assertEquals(3, taskRequests.size)
            fun answer(question: String) = Json.parseToJsonElement(bindings.answerDeviceQuestion(buildJsonObject {
                put("task", "Run tests on Current branch"); put("question", question)
            }.toString())).jsonObject["answer"]
            assertEquals(JsonPrimitive("Current branch"), answer("Which branch?"))
            assertEquals(JsonNull, answer("Invent a fact"))
            assertEquals(1, toolCalls)
            assertEquals(0, visibleEvents)
        } finally {
            bindings.unregisterAgentEventSink(); bindings.unregisterToolProvider(); bindings.unregisterModelServeCallback()
        }
    }
}
