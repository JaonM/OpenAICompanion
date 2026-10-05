package com.openai.companion.kmp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Native adapters check the rendered prompt with the model's actual tokenizer before decoding.
 * Retry only that preflight failure; never replay a partially streamed generation.
 */
object LocalModelContext {
    const val EXCEEDED = "LOCAL_MODEL_CONTEXT_EXCEEDED"

    suspend fun complete(requestJson: String, generate: suspend (String) -> Unit) {
        var request = Json.parseToJsonElement(requestJson).jsonObject
        while (true) {
            try {
                generate(request.toString())
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalStateException) {
                if (error.message != EXCEEDED) throw error
                request = reduce(request) ?: throw IllegalArgumentException(
                    "当前消息或工具定义超过端侧模型容量，请缩短消息或减少启用的工具。", error)
            }
        }
    }

    internal fun reduce(request: JsonObject): JsonObject? {
        val messages = request.getValue("messages").jsonArray.toMutableList()
        val users = messages.indices.filter { messages[it].jsonObject["role"]?.jsonPrimitive?.content == "user" }
        // Remove complete oldest turns, including assistant calls and their tool results.
        if (users.size > 1) {
            repeat(users[1] - users[0]) { messages.removeAt(users[0]) }
        } else {
            val tool = messages.indices.filter {
                messages[it].jsonObject["role"]?.jsonPrimitive?.content == "tool" &&
                    messages[it].jsonObject["content"]?.jsonPrimitive?.content.orEmpty().length > 256
            }.maxByOrNull { messages[it].jsonObject["content"]!!.jsonPrimitive.content.length }
            if (tool != null) {
                val item = messages[tool].jsonObject
                val content = item.getValue("content").jsonPrimitive.content
                var end = content.length / 2
                if (content[end - 1].isHighSurrogate()) end--
                messages[tool] = JsonObject(item + ("content" to JsonPrimitive(
                    content.take(end) + "\n[工具结果因上下文容量被截断]")))
            } else {
                // Harness appends optional recalled data after the base system instructions.
                val system = messages.indexOfFirst { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }
                if (system < 0) return null
                val item = messages[system].jsonObject
                val content = item["content"]?.jsonPrimitive?.content ?: return null
                val boundary = listOf("\n===近期中期摘要", "\n===长期用户画像", "\n===当前相关事项",
                    "\n\nRecent remote A2A task results").map { content.indexOf(it) }.filter { it >= 0 }.minOrNull()
                    ?: return null
                messages[system] = JsonObject(item + ("content" to JsonPrimitive(content.take(boundary))))
            }
        }
        return JsonObject(request + ("messages" to JsonArray(messages)))
    }
}
