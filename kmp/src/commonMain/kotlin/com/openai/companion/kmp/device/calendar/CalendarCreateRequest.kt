package com.openai.companion.kmp.device.calendar

import com.openai.companion.kmp.CalendarEventDraft
import com.openai.companion.kmp.device.*
import kotlinx.datetime.*
import kotlinx.serialization.json.*

/** Validated request; canonical encoding is stable for existing persisted operation keys. */
internal data class CalendarCreateRequest(
    val requestId: String,
    val calendarTitle: String,
    val draft: CalendarEventDraft,
    val start: String,
    val end: String,
) {
    val canonicalJson: String get() = buildJsonObject {
        put("version", 1)
        put("calendar_title", calendarTitle)
        put("draft", Json.encodeToJsonElement(draft))
    }.toString()

    fun success(eventId: String) = DeviceToolResult(data = buildJsonObject {
        put("event_id", eventId); put("calendar_id", draft.calendarId); put("calendar", calendarTitle)
        put("request_id", requestId); put("title", draft.title)
        put("start", if (draft.allDay) start else Instant.fromEpochMilliseconds(draft.startTimeMs).toString())
        put("end", if (draft.allDay) end else Instant.fromEpochMilliseconds(draft.endTimeMs).toString())
        put("time_zone", draft.timeZone); put("all_day", draft.allDay); put("created", true)
    })

    companion object {
        fun parse(arguments: JsonObject): CalendarCreateRequest {
            arguments.only("request_id", "calendar_id", "calendar_title", "title", "start", "end", "time_zone", "all_day", "location", "notes")
            val calendarTitle = arguments.text("calendar_title", 500)
            val requestId = arguments.text("request_id", 128)
            require(requestId.matches(Regex("[A-Za-z0-9_-]{8,128}"))) { "request_id must contain 8–128 letters, digits, hyphens or underscores" }
            val allDay = arguments["all_day"]?.let {
                require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) { "all_day must be boolean" }
                it.boolean
            } ?: false
            val zone = TimeZone.of(arguments.text("time_zone", 100))
            require(zone.id == "UTC" || zone.id in TimeZone.availableZoneIds) { "time_zone must be a named IANA timezone" }
            val start = arguments.text("start", 64)
            val end = arguments.text("end", 64)
            fun instant(value: String) = if (allDay) LocalDate.parse(value).atStartOfDayIn(zone) else Instant.parse(value)
            val startMs = instant(start).toEpochMilliseconds()
            val endMs = instant(end).toEpochMilliseconds()
            require(endMs > startMs && endMs - startMs <= 367L * 86_400_000) { "Event duration must be positive and at most 367 days" }
            val draft = CalendarEventDraft(arguments.text("calendar_id", 1024), arguments.text("title", 500),
                startMs, endMs, zone.id, allDay, arguments.optionalText("location", 1000), arguments.optionalText("notes", 2000))
            return CalendarCreateRequest(requestId, calendarTitle, draft, start, end)
        }

        private fun JsonObject.text(name: String, max: Int): String {
            val value = this[name]
            require(value is JsonPrimitive && value.isString) { "$name must be a string" }
            return value.content.also { require(it.isNotBlank() && it.length <= max) { "$name must contain 1–$max characters" } }
        }
        private fun JsonObject.optionalText(name: String, max: Int): String? = if (name in this) text(name, max) else null
    }
}
