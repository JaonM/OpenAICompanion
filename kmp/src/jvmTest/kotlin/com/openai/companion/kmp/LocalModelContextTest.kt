package com.openai.companion.kmp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalModelContextTest {
    private fun request(vararg messages: JsonObject) = buildJsonObject { put("messages", JsonArray(messages.toList())) }.toString()
    private fun message(role: String, content: String) = buildJsonObject { put("role", role); put("content", content) }

    @Test fun removesWholeOldTurnsBeforeRetryingNativePreflight() = runBlocking<Unit> {
        val input = request(message("system", "instructions"), message("user", "old"),
            buildJsonObject { put("role", "assistant"); put("tool_calls", buildJsonArray { add("old-call") }) },
            message("tool", "old-result"), message("assistant", "old-answer"), message("user", "current"))
        var attempts = 0
        LocalModelContext.complete(input) { fitted ->
            attempts++
            val messages = Json.parseToJsonElement(fitted).jsonObject.getValue("messages").jsonArray
            if (attempts == 1) error(LocalModelContext.EXCEEDED)
            assertEquals(listOf("system", "user"), messages.map { it.jsonObject.getValue("role").jsonPrimitive.content })
            assertEquals("current", messages.last().jsonObject.getValue("content").jsonPrimitive.content)
        }
        assertEquals(2, attempts)
    }

    @Test fun trimsToolPayloadWithoutDroppingCallIdentityOrCurrentUser() = runBlocking<Unit> {
        val input = request(message("system", "base\n===长期用户画像===\n" + "m".repeat(1000)),
            message("user", "current"), buildJsonObject { put("role", "assistant"); put("tool_calls", buildJsonArray { add("call-1") }) },
            buildJsonObject { put("role", "tool"); put("tool_call_id", "call-1"); put("content", "天气😀".repeat(1000)) })
        LocalModelContext.complete(input) { fitted ->
            if (fitted.length > 600) error(LocalModelContext.EXCEEDED)
            assertTrue(fitted.contains("call-1")); assertTrue(fitted.contains("current"))
            assertTrue(fitted.contains("被截断")); assertFalse(fitted.contains("长期用户画像"))
        }
    }

    @Test fun oversizedCurrentMessageFailsWithoutTruncationAndOtherErrorsNeverRetry() = runBlocking<Unit> {
        val input = request(message("system", "base"), message("user", "current"))
        var attempts = 0
        assertFailsWith<IllegalArgumentException> {
            LocalModelContext.complete(input) { attempts++; error(LocalModelContext.EXCEEDED) }
        }
        assertEquals(1, attempts)
        assertFailsWith<IllegalStateException> { LocalModelContext.complete(input) { error("decode failed") } }
    }
}
