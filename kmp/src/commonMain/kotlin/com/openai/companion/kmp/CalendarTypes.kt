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
)

data class CalendarQuery(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val query: String? = null,
    val limit: Int = 20,
    val sortOrder: CalendarSortOrder = CalendarSortOrder.ASC,
)

enum class CalendarSortOrder { ASC, DESC }

interface CalendarEventDataSource {
    suspend fun getEvents(query: CalendarQuery): List<CalendarEvent>
}
