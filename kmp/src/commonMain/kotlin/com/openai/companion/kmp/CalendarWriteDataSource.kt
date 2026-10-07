package com.openai.companion.kmp

import kotlinx.serialization.Serializable

@Serializable
data class DeviceCalendar(val id: String, val title: String, val writable: Boolean)

/** Validated single-event draft. For all-day events the instants delimit local calendar dates. */
@Serializable
data class CalendarEventDraft(
    val calendarId: String,
    val title: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val timeZone: String,
    val allDay: Boolean = false,
    val location: String? = null,
    val notes: String? = null,
    val operationId: String? = null,
)

fun CalendarEventDraft.operationUrl(): String {
    require(operationId?.matches(Regex("[0-9a-f]{32}")) == true) { "Missing durable operation identifier" }
    return "openai-companion://calendar/operations/$operationId"
}

interface CalendarWriteDataSource : CalendarEventDataSource {
    suspend fun listCalendars(): List<DeviceCalendar>
    /** May prompt; must finish before entering the non-cancellable save operation. */
    suspend fun ensureWritePermission()
    /** Save exactly one event. Do not prompt or retry inside this method. Return its native identifier. */
    suspend fun createEvent(draft: CalendarEventDraft): String
    /** Recover only by the durable marker, never by a fuzzy title/time match. No writes here. */
    suspend fun findCreatedEvent(draft: CalendarEventDraft): String? = error("Calendar recovery is unavailable")
}
