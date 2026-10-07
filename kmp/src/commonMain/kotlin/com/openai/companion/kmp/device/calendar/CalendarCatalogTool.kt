package com.openai.companion.kmp.device.calendar

import com.openai.companion.kmp.CalendarWriteDataSource
import com.openai.companion.kmp.McpToolDescriptor
import com.openai.companion.kmp.device.*
import kotlinx.serialization.json.*

class CalendarCatalogTool(private val source: CalendarWriteDataSource) : DeviceTool {
    override val policy = DeviceToolPolicies.PersonalRead
    override val descriptor = McpToolDescriptor("device_calendar_list_calendars",
        "List this device's calendars (id, title, writable). Select a writable calendar_id before creating an event. Requires approval and calendar read permission.", objectSchema())
    override suspend fun execute(arguments: JsonObject): DeviceToolResult {
        arguments.only()
        val calendars = source.listCalendars()
        require(calendars.size <= 200) { "Too many calendars to list" }
        return DeviceToolResult(data = buildJsonObject {
            put("calendars", JsonArray(calendars.map { calendar -> buildJsonObject {
                put("id", calendar.id); put("title", calendar.title.take(500)); put("writable", calendar.writable)
            } }))
        })
    }
}
