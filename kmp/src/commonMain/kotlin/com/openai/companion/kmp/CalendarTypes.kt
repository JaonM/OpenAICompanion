package com.openai.companion.kmp

import kotlinx.serialization.Serializable

@Serializable
data class CalendarEvent(
    val id: String,
    val title: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val calendarName: String? = null,
    val location: String? = null,
    val notes: String? = null,
    val allDay: Boolean = false,
)

data class CalendarQuery(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val query: String? = null,
    val limit: Int = 20,
    val includeLocation: Boolean = true,
    val includeNotes: Boolean = true,
)

interface CalendarEventDataSource {
    /** Recheck access when serving cached pages. Must not return cached private data after revocation. */
    suspend fun checkPermission() = Unit
    suspend fun getEvents(query: CalendarQuery): List<CalendarEvent>
}
