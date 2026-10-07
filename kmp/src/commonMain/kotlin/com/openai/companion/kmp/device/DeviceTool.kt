package com.openai.companion.kmp.device

import com.openai.companion.kmp.McpToolDescriptor
import com.openai.companion.kmp.ToolPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

interface DeviceTool {
    val descriptor: McpToolDescriptor
    val policy: ToolPolicy
    suspend fun execute(arguments: JsonObject): DeviceToolResult
}

@Serializable
data class DeviceToolResult(
    val status: String = "ok",
    val data: JsonElement = JsonNull,
    val code: String? = null,
    val message: String? = null,
    val nextCursor: String? = null,
) {
    fun json(platform: String, observedAt: String): String = buildJsonObject {
        put("version", 1)
        put("status", status)
        put("data", data)
        put("source", buildJsonObject { put("device", "current"); put("platform", platform) })
        put("observed_at", observedAt)
        put("next_cursor", nextCursor?.let(::JsonPrimitive) ?: JsonNull)
        put("truncated", nextCursor != null)
        if (code != null) put("error", buildJsonObject {
            put("code", code); put("message", message.orEmpty()); put("retryable", false)
        })
    }.toString()
}

fun objectSchema(properties: JsonObject = JsonObject(emptyMap()), required: List<String> = emptyList()): String =
    buildJsonObject {
        put("type", "object"); put("properties", properties); put("additionalProperties", false)
        if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
    }.toString()

internal fun JsonObject.only(vararg keys: String) {
    require(this.keys.all { it in keys }) { "Unknown argument" }
}
