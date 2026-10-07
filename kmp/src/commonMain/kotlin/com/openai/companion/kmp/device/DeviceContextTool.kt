package com.openai.companion.kmp.device

import com.openai.companion.kmp.McpToolDescriptor
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.*

/** Non-identifying context. Optional hardware fields are deliberately omitted when unsupported. */
class DeviceContextTool(private val platform: String, private val language: () -> String) : DeviceTool {
    override val policy = DeviceToolPolicies.PublicRead
    override val descriptor = McpToolDescriptor("device_get_context",
        "Get current device time, timezone, language and platform. Does not return location or identifiers.", objectSchema())
    override suspend fun execute(arguments: JsonObject): DeviceToolResult {
        arguments.only()
        val now = Clock.System.now()
        val zone = TimeZone.currentSystemDefault()
        return DeviceToolResult(data = buildJsonObject {
            put("platform", platform); put("time", now.toString()); put("timezone", zone.id)
            put("local_time", now.toLocalDateTime(zone).toString()); put("language", language())
        })
    }
}
