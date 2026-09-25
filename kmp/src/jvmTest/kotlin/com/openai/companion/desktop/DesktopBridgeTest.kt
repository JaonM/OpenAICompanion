package com.openai.companion.desktop

import com.openai.companion.kmp.AppModelServe
import com.openai.companion.kmp.AppAgentEventSink
import com.openai.companion.kmp.GeneratedHarnessBindingsAdapter
import com.openai.companion.kmp.ModelStreamCallback
import java.nio.file.Files
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uniffi.harness.appDeleteSession
import uniffi.harness.appListSessions
import uniffi.harness.appLoadSession
import uniffi.harness.appOpenStore
import uniffi.harness.appResumeSession
import uniffi.harness.appSendMessage
import uniffi.harness.appStartSession

class DesktopBridgeTest {
    @Test
    fun parsesSseDataAndDoneMarker() {
        val chunks = mutableListOf<String>()
        consumeServerSentEvents(StringReader("""
            : keepalive
            data: {"choices":[{"delta":{"reasoning_content":"先想"}}]}

            data: {"choices":[{"delta":{"content":"你好"}}]}

            data: [DONE]

            data: ignored

        """.trimIndent()), chunks::add)
        assertEquals(2, chunks.size)
        assertTrue(chunks.first().contains("先想"))
        assertTrue(chunks.last().contains("你好"))
    }

    @Test
    fun sessionRoundTripAcrossKotlinAndRust() {
        val library = System.getenv("HARNESS_LIBRARY_PATH")
            ?: error("HARNESS_LIBRARY_PATH must point to libharness.dylib")
        System.setProperty("uniffi.component.harness.libraryOverride", library)
        val bindings = GeneratedHarnessBindingsAdapter()
        bindings.registerModelServeCallback(object : AppModelServe {
            override suspend fun complete(requestJson: String, callback: ModelStreamCallback) {
                callback.onChunk("""{"choices":[{"delta":{"reasoning_content":"先分析，"}}]}""")
                callback.onChunk("""{"choices":[{"delta":{"reasoning_content":"再回答。","content":"来自 "}}]}""")
                callback.onChunk("""{"choices":[{"delta":{"content":"Rust loop 的回复"}}]}""")
            }
        })
        val events = mutableListOf<String>()
        bindings.registerAgentEventSink(object : AppAgentEventSink {
            override fun onReasoningDelta(text: String) { events += "reasoning:$text" }
            override fun onTextDelta(text: String) { events += "text:$text" }
            override fun onCompleted(finalText: String) { events += "completed:$finalText" }
            override fun onError(errorJson: String) { events += "error:$errorJson" }
        })
        val database = Files.createTempFile("companion-desktop-bridge-", ".sqlite")
        try {
            assertTrue(appOpenStore(database.toString()).ok)
            val started = appStartSession()
            assertTrue(started.ok, started.error)
            val id = Json.parseToJsonElement(started.valueJson).jsonObject
                .getValue("id").jsonPrimitive.content.toLong()

            val reply = appSendMessage("你好")
            assertTrue(reply.ok, reply.error)
            assertEquals("来自 Rust loop 的回复", Json.parseToJsonElement(reply.valueJson)
                .jsonObject.getValue("output").jsonPrimitive.content)
            assertEquals("reasoning:先分析，", events[0])
            assertEquals("reasoning:再回答。", events[1])
            assertTrue(events.contains("completed:来自 Rust loop 的回复"))
            assertTrue(appResumeSession(id).ok)
            val trace = Json.parseToJsonElement(appLoadSession(id).valueJson).jsonObject
                .getValue("messages").jsonArray
            assertEquals(3, trace.size)
            assertEquals("你好", trace[0].jsonObject.getValue("content").jsonPrimitive.content)
            assertEquals("先分析，再回答。", trace[1].jsonObject.getValue("content").jsonPrimitive.content)
            assertEquals(1, Json.parseToJsonElement(appListSessions().valueJson).jsonArray.size)
            bindings.registerModelServeCallback(object : AppModelServe {
                override suspend fun complete(requestJson: String, callback: ModelStreamCallback) {
                    error("model offline")
                }
            })
            val failedSession = appStartSession()
            assertTrue(failedSession.ok, failedSession.error)
            val failedId = Json.parseToJsonElement(failedSession.valueJson).jsonObject
                .getValue("id").jsonPrimitive.content.toLong()
            val failed = appSendMessage("再试一次")
            assertTrue(!failed.ok)
            assertTrue(failed.error.contains("model offline"), failed.error)
            val failedMessages = Json.parseToJsonElement(appLoadSession(failedId).valueJson)
                .jsonObject.getValue("messages").jsonArray
            assertEquals("user", failedMessages[0].jsonObject.getValue("role").jsonPrimitive.content)
            assertEquals("status", failedMessages[1].jsonObject.getValue("role").jsonPrimitive.content)
            assertTrue(failedMessages[1].jsonObject.getValue("content").jsonPrimitive.content
                .contains("model offline"))
            assertTrue(appDeleteSession(id).ok)
            assertEquals(1, Json.parseToJsonElement(appListSessions().valueJson).jsonArray.size)
        } finally {
            bindings.unregisterAgentEventSink()
            bindings.unregisterModelServeCallback()
            Files.deleteIfExists(database)
        }
    }
}
