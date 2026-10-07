package com.openai.companion.kmp.device.calendar

import com.openai.companion.kmp.*
import com.openai.companion.kmp.device.*
import kotlinx.serialization.json.*

/** Durable claim precedes the native write; interrupted requests recover by their calendar marker. */
class CalendarCreateTool(
    source: CalendarWriteDataSource,
    journal: DeviceOperationJournal,
    isForeground: suspend () -> Boolean,
) : DeviceTool {
    override val policy = DeviceToolPolicies.PersonalWrite
    override val descriptor = McpToolDescriptor(NAME,
        "Create one calendar event after user confirmation. First list calendars and select a writable calendar_id and copy its title into calendar_title for confirmation. " +
            "Required request_id must be unique for each intended event; reuse the SAME id and arguments when retrying. " +
            "Request outcomes persist across restarts. Interrupted saves are checked by a durable calendar marker; an unresolved request is never written again automatically. " +
            "For timed events start/end are ISO 8601 timestamps with offsets; for all_day=true they are YYYY-MM-DD dates, end exclusive. " +
            "time_zone is an IANA timezone. No attendees, recurrence or reminders are added.",
        objectSchema(buildJsonObject {
            for (name in listOf("request_id", "calendar_id", "calendar_title", "title", "start", "end", "time_zone", "location", "notes"))
                put(name, buildJsonObject { put("type", "string") })
            put("all_day", buildJsonObject { put("type", "boolean") })
        }, listOf("request_id", "calendar_id", "calendar_title", "title", "start", "end", "time_zone")))

    private val service = CalendarCreateService(source, journal, isForeground)

    override suspend fun execute(arguments: JsonObject): DeviceToolResult =
        service.create(CalendarCreateRequest.parse(arguments))

    internal companion object { const val NAME = "device_calendar_create_event" }
}
