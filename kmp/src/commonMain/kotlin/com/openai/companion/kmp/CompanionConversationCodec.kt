package com.openai.companion.kmp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Shared macOS/iOS decoding contract for the Rust Harness app API. */
data class CompanionSessionSummary(val id: Long, val preview: String)

data class CompanionChatMessage(val role: String, val content: String)

class CompanionModelDefaults {
    val modelName: String = "hf.co/unsloth/Qwen3.8-27B-GGUF:UD-Q4_K_M"
    val desktopEndpoint: String = "http://localhost:11434/v1/chat/completions"

    fun validationError(endpoint: String, model: String): String? = when {
        endpoint.isBlank() -> "请先配置模型接口地址"
        model.isBlank() -> "请先配置模型名称"
        else -> null
    }
}

/** Keeps streamed reasoning and answer text consistent across desktop and iOS. */
class CompanionStreamAccumulator {
    var reasoning: String = ""
        private set
    var text: String = ""
        private set

    fun reset() {
        reasoning = ""
        text = ""
    }

    fun addReasoning(delta: String): String {
        reasoning += delta
        return reasoning
    }

    fun addText(delta: String): String {
        text += delta
        return text
    }
}

class CompanionConversationCodec {
    @Throws(IllegalArgumentException::class)
    fun sessions(valueJson: String): List<CompanionSessionSummary> = parse(valueJson) {
        Json.parseToJsonElement(valueJson).jsonArray.map { item ->
            val data = item.jsonObject
            CompanionSessionSummary(
                id = data.getValue("id").jsonPrimitive.content.toLong(),
                preview = data.getValue("preview").jsonPrimitive.content,
            )
        }
    }

    @Throws(IllegalArgumentException::class)
    fun messages(valueJson: String): List<CompanionChatMessage> = parse(valueJson) {
        Json.parseToJsonElement(valueJson).jsonObject.getValue("messages").jsonArray.map { item ->
            val data = item.jsonObject
            CompanionChatMessage(
                role = data.getValue("role").jsonPrimitive.content,
                content = data.getValue("content").jsonPrimitive.content,
            )
        }
    }

    @Throws(IllegalArgumentException::class)
    fun sessionId(valueJson: String): Long = parse(valueJson) {
        Json.parseToJsonElement(valueJson).jsonObject.getValue("id").jsonPrimitive.content.toLong()
    }

    @Throws(IllegalArgumentException::class)
    fun output(valueJson: String): String = parse(valueJson) {
        Json.parseToJsonElement(valueJson).jsonObject.getValue("output").jsonPrimitive.content
    }

    private inline fun <T> parse(valueJson: String, decode: () -> T): T = try {
        decode()
    } catch (error: Exception) {
        throw IllegalArgumentException("Invalid Harness app response: ${valueJson.take(120)}", error)
    }
}
